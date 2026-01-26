package simcore.engine;

import simcore.config.SimulationConfig;
import simcore.config.SimulationConstants;
import simcore.config.SystemParameters;
import simcore.config.BusSystemType;
import simcore.model.*;
import simcore.engine.failures.FailureStepper;
import simcore.engine.metrics.EnsAllocator;
import simcore.engine.NetworkFailureStep;
import simcore.engine.bus.BusLoadAllocator;
import simcore.engine.bus.TieBreakerController;
import simcore.engine.trace.ArrayTraceSession;
import simcore.engine.trace.NoTraceSession;
import simcore.engine.trace.TraceSession;
import simcore.economy.*;

import static simcore.economy.RuCostAdjuster.effectiveRuCost;

import java.util.List;

public final class SingleRunSimulator {

    static boolean considerRotationReserve;

    public SimulationMetrics simulate(SimInput input, long seed, boolean traceEnabled) {

        final SimulationConfig config = input.getConfig();
        final SystemParameters sp = input.getSystemParameters();

        final double[] windMs = config.getWindMs();
        final int hours = windMs.length;
        final boolean considerFailures = config.isConsiderFailures();
        final boolean considerDegradation = config.isConsiderBatteryDegradation();
        final boolean reserveThirdCategory = config.isReserveThirdCategory();
//        final boolean considerRotationReserve = config.isConsiderRotationReserve();
        considerRotationReserve = config.isConsiderRotationReserve();

        final double cat1 = sp.getFirstCat();
        final double cat2 = sp.getSecondCat();

        final PowerSystem system = new PowerSystemBuilder().build(sp, input.getTotalLoadKw());
        final List<PowerBus> buses = system.getBuses();
        final int busCount = buses.size();
        final Breaker breaker = system.getTieBreaker();
        final List<SwitchgearRoom> rooms = system.getRooms();
        final int[] roomIndexByBus = system.getRoomIndexByBus();

        FailureStepper.initFailureModels(seed, considerFailures, buses, breaker, rooms);

        final Totals totals = new Totals();

        final int HOURS_PER_YEAR = 8760;
        final int YEARS = (hours + HOURS_PER_YEAR - 1) / HOURS_PER_YEAR; // ceil
        double[] servedKwhByYear = new double[YEARS];
        double[] fuelLitersByYear = new double[YEARS];
        double[] motoHoursByYear = new double[YEARS];
        long[] btReplByYear = new long[YEARS];
        double[] ensCat1KwhByYear = new double[YEARS];
        double[] ensCat2KwhByYear = new double[YEARS];
        double[] ensCat3KwhByYear = new double[YEARS];
        final TraceSession trace = traceEnabled ? new ArrayTraceSession() : new NoTraceSession();

        // Reusable per-hour buffers (avoid allocations inside the main hourly loop).
        final double[] ownUseKwByBus = new double[busCount];
        final double[] rawLoadThisHourKw = new double[busCount];
        final double[] loadsBuf = (busCount == 2) ? new double[2] : null; // used for sectional-closed fast paths
        final double[] hourWreRef = traceEnabled ? new double[1] : null;

        final boolean[] busAvailBefore = new boolean[busCount];
        final boolean[] busAvailAfter = new boolean[busCount];
        final boolean[] busFailedThisHour = new boolean[busCount];
        final boolean[] busAlive = new boolean[busCount];

        // часто используемые параметры ДГУ
        final double dgRatedKw = sp.getDieselGeneratorPowerKw();
        final double dgMaxKw = dgRatedKw * SimulationConstants.DG_MAX_POWER;
        final double dgMinKw = dgRatedKw * SimulationConstants.DG_MIN_POWER;
        final double perDgOptimalKw = dgRatedKw * SimulationConstants.DG_OPTIMAL_POWER;
        final double dgStartDelayHours = SimulationConstants.DG_START_DELAY_HOURS;

        for (int t = 0; t < hours; t++) {

            final double windV = windMs[t];
            final boolean doTrace = trace.enabled();
            trace.startHour(busCount);

            double totalLoadAtTime = 0.0;
            double totalDefAtTime = 0.0;
            double totalWreAtTime;
            if (doTrace) hourWreRef[0] = 0.0;

            // === Extra own-use load (does not count as delivered energy for LCOE) ===
            // Hot reserve own-use is modeled as an extra load on the bus (NOT as negative wind generation).
            // Each DG that is available and has zero electrical load contributes 1% of rated power as own-use.
            // Raw (pre-transfer) loads for maintenance deferral decision (consumer load only for now).
            for (int b = 0; b < busCount; b++) {
                ownUseKwByBus[b] = 0.0;
                rawLoadThisHourKw[b] = buses.get(b).getLoadKw()[t];
            }

            NetworkFailureStep.updateOneHour(
                    considerFailures,
                    buses,
                    breaker,
                    rooms,
                    roomIndexByBus,
                    busAvailBefore,
                    busAvailAfter,
                    busFailedThisHour,
                    busAlive,
                    rawLoadThisHourKw
            );

            if (config.isConsiderHotReserve()) {
                for (int b = 0; b < busCount; b++) {
                    double add = 0.0;
                    for (DieselGenerator dg : buses.get(b).getDieselGenerators()) {
                        if (!dg.isAvailable()) continue;
                        if (Math.abs(dg.getCurrentLoad()) <= SimulationConstants.EPSILON) {
                            add += 0.01 * dgRatedKw;
                        }
                    }
                    ownUseKwByBus[b] = add;
                    rawLoadThisHourKw[b] += add;
                }
            }

            double ownUseTotalKwThisHour = 0.0;
            for (int b = 0; b < busCount; b++) ownUseTotalKwThisHour += ownUseKwByBus[b];

            // Snapshot DG "working" states at the beginning of the hour (after failures, before dispatch).
            for (PowerBus bus : buses) {
                for (DieselGenerator dg : bus.getDieselGenerators()) {
                    dg.snapshotWorkingAtHourStart();
                }
            }

            final HourContext ctx = new HourContext(
                    sp,
                    windV,
                    considerDegradation,
                    reserveThirdCategory,
                    considerRotationReserve,
                    cat1,
                    cat2,
                    dgRatedKw,
                    dgMaxKw,
                    dgMinKw,
                    perDgOptimalKw,
                    dgStartDelayHours,
                    totals,
                    hourWreRef,
                    trace
            );

            // ENS event statistics: track per-hour ENS and "start ENS" (DG start delay ENS)
            final double ensBeforeHour = totals.ensKwh;
            final double startEnsBeforeHour = totals.startEnsKwh;
            final double ensCat1BeforeHour = totals.ensCat1Kwh;
            final double ensCat2BeforeHour = totals.ensCat2Kwh;
            final double fuelBeforeHour = totals.fuelLiters;
            final long motoBeforeHour = sumMotoHours(buses);
            final long replBeforeHour = sumBatteryReplacements(buses);
            // ===== Bus system logic (SINGLE_SECTIONAL_BUS / DOUBLE_BUS) =====
            final BusSystemType busType = sp.getBusSystemType();

            final double[] effectiveLoadKw = BusLoadAllocator.maybeComputeEffectiveLoads(
                    sp,
                    buses,
                    busAlive,
                    t,
                    cat1,
                    cat2,
                    windV,
                    dgMaxKw,
                    rawLoadThisHourKw
            );

            boolean sectionalClosedThisHour = false;
            if (busType == BusSystemType.SINGLE_SECTIONAL_BUS
                    && busCount == 2
                    && breaker != null
                    && breaker.isAvailable()
                    && busAlive[0] && busAlive[1]) {

                double[] loadsForDecision;
                if (effectiveLoadKw != null) {
                    loadsForDecision = effectiveLoadKw;
                } else {
                    loadsBuf[0] = rawLoadThisHourKw[0];
                    loadsBuf[1] = rawLoadThisHourKw[1];
                    loadsForDecision = loadsBuf;
                }

                sectionalClosedThisHour = TieBreakerController.shouldCloseTieBreakerThisHour(
                        sp, buses, loadsForDecision, windV, dgMaxKw
                );

                breaker.setClosed(sectionalClosedThisHour);

                // If the tie-breaker is closed due to deficit balancing and one bus fails this hour,
                // treat it as a coupled failure: both buses go down (no separate breaker failure needed).
                if (sectionalClosedThisHour && (busFailedThisHour[0] ^ busFailedThisHour[1])) {
                    int failed = busFailedThisHour[0] ? 0 : 1;
                    int other = 1 - failed;
                    // Force the other bus into failure now to couple the outage.
                    buses.get(other).forceFailNow();
                    busFailedThisHour[other] = true;
                    busAlive[0] = false;
                    busAlive[1] = false;
                    busAvailAfter[0] = false;
                    busAvailAfter[1] = false;
                }
            } else {
                if (breaker != null) breaker.setClosed(false);
            }
            // ===== Sectional-closed dispatch (если секционник закрыт) =====

            if (sectionalClosedThisHour) {

                final double[] loads;
                if (effectiveLoadKw != null) {
                    loads = effectiveLoadKw;
                } else {
                    loadsBuf[0] = rawLoadThisHourKw[0];
                    loadsBuf[1] = rawLoadThisHourKw[1];
                    loads = loadsBuf;
                }

                SectionalClosedResult r = SectionalClosedDispatcher.dispatchSectionalClosedOneHour(
                        ctx,
                        buses,
                        loads
                );

                // totals: полностью за час (в этом режиме per-bus dispatch ниже НЕ выполняем)
                totals.loadKwh += r.loadKwh;
                totals.ensKwh += r.ensKwh;
                totals.wreKwh += r.wreKwh;
                totals.wtToLoadKwh += r.wtToLoadKwh;
                totals.dgToLoadKwh += r.dgToLoadKwh;
                totals.btToLoadKwh += r.btToLoadKwh;
                totals.fuelLiters += r.fuelLiters;

                for (int b = 0; b < busCount; b++) {
                    double startEns = (r.startEnsByBus != null) ? r.startEnsByBus[b] : 0.0;
                    double totalEnsBus = r.defByBus[b];
                    if (startEns > SimulationConstants.EPSILON) {
                        EnsAllocator.addEnsByCategoryProportional(totals, loads[b], startEns, cat1, cat2);
                    }
                    double restEns = Math.max(0.0, totalEnsBus - startEns);
                    if (restEns > SimulationConstants.EPSILON) {
                        EnsAllocator.addEnsByCategory(totals, loads[b], restEns, cat1, cat2);
                    }
                }

                if (doTrace) {
                    totalLoadAtTime = r.loadKwh;
                    totalDefAtTime = r.ensKwh;
                    totalWreAtTime = r.wreKwh;

                    for (int b = 0; b < busCount; b++) {
                        trace.setBusValues(
                                b,
                                true,
                                loads[b],
                                r.windToLoadByBus[b],
                                r.dgToLoadByBus[b],
                                r.btNetByBus[b],
                                r.defByBus[b]
                        );
                        trace.fillDgState(b, buses.get(b));
                        trace.fillBatteryState(b, buses.get(b).getBattery());
                    }

                    Boolean brkClosed = (breaker == null) ? null : breaker.isClosed();
                    trace.addHourRecord(t, totalLoadAtTime, totalDefAtTime, totalWreAtTime, brkClosed);
                }

                // ENS event stats (whole system, per hour)
                double ensThisHour = totals.ensKwh - ensBeforeHour;
                double startEnsThisHour = totals.startEnsKwh - startEnsBeforeHour;
                totals.ensEventStats.updateHour(ensThisHour, startEnsThisHour);

                int y = t / HOURS_PER_YEAR;
                if (y >= YEARS) y = YEARS - 1;
                double ensCat1ThisHour = totals.ensCat1Kwh - ensCat1BeforeHour;
                double ensCat2ThisHour = totals.ensCat2Kwh - ensCat2BeforeHour;
                double ensCat3ThisHour = Math.max(0.0, ensThisHour - ensCat1ThisHour - ensCat2ThisHour);
                ensCat1KwhByYear[y] += ensCat1ThisHour;
                ensCat2KwhByYear[y] += ensCat2ThisHour;
                ensCat3KwhByYear[y] += ensCat3ThisHour;
                double loadKwhThisHour = totalLoadAtTime; // kW over 1h
                final double consumerLoadKwhThisHour = Math.max(0.0, loadKwhThisHour - ownUseTotalKwThisHour);
                final double servedTotalKwhThisHour = Math.max(0.0, loadKwhThisHour - ensThisHour); // сколько реально отдали всем нагрузкам
                final double servedToConsumers = Math.min(consumerLoadKwhThisHour, servedTotalKwhThisHour);
                servedKwhByYear[y] += servedToConsumers;
                fuelLitersByYear[y] += (totals.fuelLiters - fuelBeforeHour);
                motoHoursByYear[y] += (sumMotoHours(buses) - motoBeforeHour);
                btReplByYear[y] += (sumBatteryReplacements(buses) - replBeforeHour);
                continue;
            }

            // ===== Standard per-bus dispatch =====
            // DOUBLE_BUS: if one bus is down, from the 2nd repair hour transfer ALL DG and WT from the dead bus
            // onto the live bus (in addition to load transfer performed in BusLoadAllocator).
            int doubleBusDead = -1;
            int doubleBusLive = -1;
            boolean doubleBusTransferGen = false;
            if (busType == BusSystemType.DOUBLE_BUS && busCount == 2 && (busAlive[0] ^ busAlive[1])) {
                doubleBusDead = busAlive[0] ? 1 : 0;
                doubleBusLive = 1 - doubleBusDead;
                PowerBus deadBus = buses.get(doubleBusDead);
                boolean firstRepairHour = (deadBus.getRepairDurationHours() == sp.getBusRepairTimeHours());
                doubleBusTransferGen = !firstRepairHour;
            }

            for (int b = 0; b < busCount; b++) {
                final PowerBus bus = buses.get(b);
                final double loadKw = (effectiveLoadKw != null) ? effectiveLoadKw[b] : rawLoadThisHourKw[b];

                if (!busAlive[b]) {
                    // If gen transfer is active, do not stop diesels on the dead bus:
                    // these diesels are being used on the live bus.
                    if (doubleBusTransferGen && b == doubleBusDead) {
                        ctx.totals.loadKwh += loadKw;

                        final double defKw = loadKw;
                        ctx.totals.ensKwh += defKw;
                        EnsAllocator.addEnsByCategoryProportional(ctx.totals, loadKw, defKw, cat1, cat2);

                        if (trace.enabled()) {
                            trace.setBusDown(b, loadKw, defKw);
                            // Note: DG/WT states belong to the physical bus objects; do not override here.
                            trace.fillDgState(b, bus);
                            trace.fillBatteryState(b, bus.getBattery());
                        }
                    } else {
                        PerBusDispatcher.dispatchOneBusOneHour(ctx, bus, false, b, loadKw);
                    }
                } else {
                    PowerBus extra = (doubleBusTransferGen && b == doubleBusLive) ? buses.get(doubleBusDead) : null;
                    PerBusDispatcher.dispatchOneBusOneHourWithExtraSources(ctx, bus, extra, true, b, loadKw);
                }
            }

            if (doTrace) {
                for (int b = 0; b < busCount; b++) {
                    totalLoadAtTime += rawLoadThisHourKw[b]; // consumer load + own-use
                }
                totalWreAtTime = hourWreRef[0];
                Boolean brkClosed = (breaker == null) ? null : breaker.isClosed();
                trace.addHourRecord(t, totalLoadAtTime, totalDefAtTime, totalWreAtTime, brkClosed);
            }

            // ENS event stats (whole system, per hour)
            double ensThisHour = totals.ensKwh - ensBeforeHour;
            double startEnsThisHour = totals.startEnsKwh - startEnsBeforeHour;
            totals.ensEventStats.updateHour(ensThisHour, startEnsThisHour);

            int y = t / HOURS_PER_YEAR;
            if (y >= YEARS) y = YEARS - 1;
            double ensCat1ThisHour = totals.ensCat1Kwh - ensCat1BeforeHour;
            double ensCat2ThisHour = totals.ensCat2Kwh - ensCat2BeforeHour;
            double ensCat3ThisHour = Math.max(0.0, ensThisHour - ensCat1ThisHour - ensCat2ThisHour);
            ensCat1KwhByYear[y] += ensCat1ThisHour;
            ensCat2KwhByYear[y] += ensCat2ThisHour;
            ensCat3KwhByYear[y] += ensCat3ThisHour;

            double loadKwhThisHour = 0.0;
            if (effectiveLoadKw != null) {
                for (int b = 0; b < busCount; b++) loadKwhThisHour += effectiveLoadKw[b];
            } else {
                for (int b = 0; b < busCount; b++) loadKwhThisHour += rawLoadThisHourKw[b];
            }

            final double consumerLoadKwhThisHour = Math.max(0.0, loadKwhThisHour - ownUseTotalKwThisHour);
            final double servedTotalKwhThisHour = Math.max(0.0, loadKwhThisHour - ensThisHour); // сколько реально отдали всем нагрузкам
            final double servedToConsumers = Math.min(consumerLoadKwhThisHour, servedTotalKwhThisHour);

            servedKwhByYear[y] += servedToConsumers;
            fuelLitersByYear[y] += (totals.fuelLiters - fuelBeforeHour);
            motoHoursByYear[y] += (sumMotoHours(buses) - motoBeforeHour);
            btReplByYear[y] += (sumBatteryReplacements(buses) - replBeforeHour);
            ;

        }

        // Close a trailing ENS event at the end of horizon, if any.
        totals.ensEventStats.finish();

        // ===== total failures by internal counters =====
        long failRoom = 0;
        long failBus = 0;
        long failDg = 0;
        long failWt = 0;
        long failBt = 0;
        long failBrk = 0;
        long repBt = 0;

        for (SwitchgearRoom room : rooms) {
            failRoom += room.getFailureCount();
        }

        for (PowerBus bus : buses) {
            failBus += bus.getFailureCount();
            for (WindTurbine wt : bus.getWindTurbines()) failWt += wt.getFailureCount();
            for (DieselGenerator dg : bus.getDieselGenerators()) failDg += dg.getFailureCount();
            Battery bt = bus.getBattery();
            if (bt != null) {
                failBt += bt.getFailureCount();
                repBt += bt.getReplacementCount();
            }
        }
        if (breaker != null) failBrk += breaker.getFailureCount();

        long moto = 0;
        for (PowerBus bus : buses) {
            for (DieselGenerator dg : bus.getDieselGenerators()) moto += dg.getTotalTimeWorked();
        }

        // ENS event stats
        long[] bc = totals.ensEventStats.getBucketCounts();
        long ensEventsTotal = totals.ensEventStats.getEventsTotal();
        long ensEventsStartOnly = totals.ensEventStats.getEventsStartOnly();
        long ensEventsMaxHours = totals.ensEventStats.getMaxRunHours();

        // ===== LCOE (discounted), based on delivered energy (served = load - ENS) =====
        // ===== LCOE (discounted), based on delivered energy (served = load - ENS) =====
        EconomyDrivers econDrivers = buildEconomyDrivers(sp, buses, servedKwhByYear, fuelLitersByYear, motoHoursByYear, btReplByYear,
                ensCat1KwhByYear, ensCat2KwhByYear, ensCat3KwhByYear);
        UnitCosts unitCosts = new UnitCosts(
                effectiveRuCost(sp.getBusSystemType(), sp.getCostRuRub()),
                sp.getCostDgRubPerKw(),
                sp.getCostWtRubPerKw(),
                sp.getCostBtRubPerKwh(),
                sp.getCostFuelRubPerKt(),
                sp.getCostDgRubPerKwPerKmh(),
                sp.getCostWtRubPerKwPerYear(),
                sp.getCostBtRubPerKwhPerYear(),
                sp.getDamageRubPerKwhCat1(),
                sp.getDamageRubPerKwhCat2(),
                sp.getDamageRubPerKwhCat3()
        );
        double lcoeRubPerKwh = DiscountedLcoeCalculator.computeRubPerKwh(econDrivers, unitCosts);
        return new SimulationMetrics(
                totals.loadKwh,
                totals.ensKwh,
                totals.ensCat1Kwh,
                totals.ensCat2Kwh,
                totals.wreKwh,
                totals.wtToLoadKwh,
                totals.dgToLoadKwh,
                totals.btToLoadKwh,
                lcoeRubPerKwh,
                totals.fuelLiters,
                moto,
                trace.records(),
                failBus,
                failDg,
                failWt,
                failBt,
                failBrk,
                failRoom,
                repBt,
                ensEventsTotal,
                ensEventsStartOnly,
                bc[1],
                bc[2],
                bc[3],
                bc[4],
                bc[5],
                bc[6],
                bc[7],
                bc[8],
                ensEventsMaxHours
                ,
                econDrivers
        );
    }

    // ======================================================================
    // Helpers
    // ======================================================================

    private static long sumMotoHours(java.util.List<PowerBus> buses) {
        long s = 0;
        for (PowerBus bus : buses) {
            for (DieselGenerator dg : bus.getDieselGenerators()) {
                s += dg.getTotalTimeWorked();
            }
        }
        return s;
    }

    private static long sumBatteryReplacements(java.util.List<PowerBus> buses) {
        long s = 0;
        for (PowerBus bus : buses) {
            Battery bt = bus.getBattery();
            if (bt != null) s += bt.getReplacementCount();
        }
        return s;
    }


    static double computeWindPotential(PowerBus bus, double windV) {
        double pot = 0.0;
        for (WindTurbine wt : bus.getWindTurbines()) {
            if (!wt.isAvailable()) continue;
            pot += wt.getPotentialGenerationKw(windV);
            wt.addWorkTime(1);
        }
        return pot;
    }

    static boolean finalizeIdleAndBurn(HourContext ctx, DieselGenerator[] dgs, double dgMinKw) {
        boolean anyBurnThisHour = false;

        for (DieselGenerator dg : dgs) {
            if (!dg.isAvailable()) continue;

            double pAbs = Math.abs(dg.getCurrentLoad());

            // ДГУ не онлайн -> idleTime не трогаем
            if (pAbs <= SimulationConstants.EPSILON) {
                dg.setIdle(false);
                continue;
            }

            // Холостой ход / малая нагрузка
            if (pAbs + SimulationConstants.EPSILON < dgMinKw) {

                // For idle/burn logic we need to know if the DG was started during the current hour.
                // Snapshot is captured once per hour (after failures, before dispatch).
                boolean startedThisHour = !dg.wasWorkingAtHourStart();

                int nextIdle = dg.getIdleTime() + 1;
                boolean reachesBurnThresholdNow = nextIdle >= SimulationConstants.DG_MAX_IDLE_HOURS;

                if (reachesBurnThresholdNow && startedThisHour) {
                    // If the DG was started in the current hour (wasWorking=false at hour start),
                    // do not force burn in the same hour even if the idle counter reaches the limit.
                    dg.incrementIdleTime();
                    dg.setIdle(true);
                } else {
                    dg.incrementIdleTime();
                    dg.setIdle(true);

                    if (dg.getIdleTime() >= SimulationConstants.DG_MAX_IDLE_HOURS) {
                        dg.setCurrentLoad(Math.max(dgMinKw, 0.0)); // прожиг
                        dg.resetIdleTime();
                        dg.setIdle(false);
                        anyBurnThisHour = true;
                    }
                }
            } else {
                dg.resetIdleTime();
                dg.setIdle(false);
            }
        }
        return anyBurnThisHour;
    }

    static boolean canBatteryBridge(
            Battery battery,
            SystemParameters sp,
            double requiredPowerKw,
            double durationHours,
            double btDisCap
    ) {
        if (battery == null || !battery.isAvailable()) return false;
        if (requiredPowerKw <= SimulationConstants.EPSILON) return true;
        if (durationHours <= 0.0) return true;

        double requiredEnergyKwh = requiredPowerKw * durationHours;

        double maxByCurrent = battery.getMaxCapacityKwh() * sp.getMaxDischargeCurrent();
        double maxByCapacity = Math.max(
                0.0,
                (battery.getStateOfCharge() - SimulationConstants.BATTERY_MIN_SOC) * battery.getMaxCapacityKwh()
                        * SimulationConstants.BATTERY_EFFICIENCY
        );

        boolean powerOk = maxByCurrent >= requiredPowerKw;
        boolean energyOk = maxByCapacity >= requiredEnergyKwh;

        return powerOk && energyOk;
    }

    static void applyIdleReserveInWindSurplus(
            PowerBus bus,
            SystemParameters sp,
            double loadKw,
            double windToLoadKw,
            double cat1,
            double cat2,
            boolean btAvail,
            Battery battery,
            double dgRatedKw,
            double dgMinKw,
            double tau
    ) {
        DieselGenerator[] dgs = DieselGenerator.getSortedDgs(bus);
        int dgCountAll = dgs.length;

        int available = 0;
        for (DieselGenerator dg : dgs) if (dg.isAvailable()) available++;

        boolean[] keepOn = new boolean[dgCountAll];

        double pCrit = SimulationConstants.MAX_LOAD * (cat1 + SimulationConstants.DG_IDLE_K2 * cat2);
        double windLoss = Math.min(windToLoadKw, pCrit);

        double btFirm = 0.0;
        if (btAvail) {
            double btDisCap = battery.getDischargeCapacity(sp);
            if (canBatteryBridge(battery, sp, windLoss, tau, btDisCap)) {
                btFirm = windLoss;
            } else {
                btFirm = btDisCap;
            }
        }
        // РЕЗЕРВ
        double reserveNeed = loadKw * (cat1 + SimulationConstants.DG_IDLE_K2 * cat2);
//        double reserveNeed = windLoss;
        reserveNeed += windLoss * SimulationConstants.DG_IDLE_MARGIN_PCT;
        if (reserveNeed < 0.0) reserveNeed = 0.0;

        int idleNeed = (loadKw > SimulationConstants.EPSILON)
                ? (int) Math.ceil(reserveNeed / dgRatedKw)
                : 0;
        if (idleNeed > available) idleNeed = available;

        if (btAvail) {
            double btDisCap = battery.getDischargeCapacity(sp);
            idleNeed = canBatteryBridge(battery, sp, dgRatedKw * idleNeed, SimulationConstants.DG_START_DELAY_HOURS, btDisCap) ? 0 : idleNeed;
        }

        idleNeed = considerRotationReserve ? idleNeed + 1 : idleNeed;

        // 1) сначала уже working
        for (int k = 0; k < dgCountAll && idleNeed > 0; k++) {
            DieselGenerator dg = dgs[k];

            if (!dg.isAvailable()) {
                DieselGenerator.hardStopDg(dg);
                continue;
            }
            if (!dg.isWorking()) continue;

            double genKw = SimulationConstants.DG_IDLE_FUEL_LOAD * dgRatedKw;
            dg.setCurrentLoad(genKw);
            dg.addWorkTime(1, 1);
            dg.startWork();

            keepOn[k] = true;
            idleNeed--;
        }

        // 2) запуск новых
        for (int k = 0; k < dgCountAll && idleNeed > 0; k++) {
            DieselGenerator dg = dgs[k];

            if (!dg.isAvailable()) {
                DieselGenerator.hardStopDg(dg);
                continue;
            }
            if (dg.isWorking()) continue;

            dg.startWork();

            double genKw = SimulationConstants.DG_IDLE_FUEL_LOAD * dgRatedKw;
            dg.setCurrentLoad(genKw);
            dg.addWorkTime(1, 1 + SimulationConstants.DG_MAX_START_FACTOR);

            keepOn[k] = true;
            idleNeed--;
        }

        // финализация
        for (int k = 0; k < dgCountAll; k++) {
            DieselGenerator dg = dgs[k];

            if (!dg.isAvailable()) {
                DieselGenerator.hardStopDg(dg);
                continue;
            }
            if (keepOn[k]) continue;

            dg.setCurrentLoad(0.0);
            dg.stopWork();
            dg.setIdle(false);
        }
    }

    static void applyIdleReserveInWindDeficit(
            DieselGenerator[] dgs,
            double loadKw,
            double windToLoadKw,
            double cat1,
            double cat2,
            boolean reserveThirdCategory,
            boolean btAvail,
            Battery battery,
            SystemParameters sp,
            double tauEff,
            double btDisCapKw,
            double dgRatedKw,
            double dgMinKw,
            double dgMaxKw
    ) {
        int dgCountAll = dgs.length;

        double cat3 = Math.max(0.0, 1.0 - cat1 - cat2);
        double reserveShare = cat1 + SimulationConstants.DG_IDLE_K2 * cat2 + (reserveThirdCategory ? cat3 : 0.0);

        double pCrit = loadKw * reserveShare;
        double windLoss = Math.min(windToLoadKw, pCrit);

        double btFirm = 0.0;
        if (btAvail) {
            if (canBatteryBridge(battery, sp, windLoss, tauEff, btDisCapKw)) {
                btFirm = windLoss;
            } else {
                btFirm = btDisCapKw;
            }
        }

        double dgFirm = 0.0;
        for (DieselGenerator dg : dgs) {
            if (!dg.isAvailable()) continue;
            if (!dg.isWorking()) continue;
            if (dg.getCurrentLoad() < -SimulationConstants.EPSILON) continue;
            dgFirm += dgMaxKw;
        }
        // РЕЗЕРВ
//        double reserveNeed = windLoss - (btFirm + dgFirm);
        double reserveNeed = loadKw * reserveShare;
//        double reserveNeed = windLoss;
        reserveNeed += windLoss * SimulationConstants.DG_IDLE_MARGIN_PCT;
        if (reserveNeed < 0.0) reserveNeed = 0.0;

        int idleNeed = (reserveNeed > SimulationConstants.EPSILON)
                ? (int) Math.ceil(reserveNeed / dgRatedKw)
                : 0;

        if (btAvail) {
            double btDisCap = battery.getDischargeCapacity(sp);
            idleNeed = canBatteryBridge(battery, sp, dgRatedKw * idleNeed, SimulationConstants.DG_START_DELAY_HOURS, btDisCap) ? 0 : idleNeed;
        }
        idleNeed = considerRotationReserve ? idleNeed + 1 : idleNeed;

        int idleCapable = 0;
        for (DieselGenerator dg : dgs) {
            if (!dg.isAvailable()) continue;
            if (dg.getCurrentLoad() > SimulationConstants.EPSILON) continue;
            idleCapable++;
        }
        if (idleNeed > idleCapable) idleNeed = idleCapable;

        // 1) working
        for (int k = 0; k < dgCountAll && idleNeed > 0; k++) {
            DieselGenerator dg = dgs[k];
            if (!dg.isAvailable()) continue;
            if (dg.getCurrentLoad() > SimulationConstants.EPSILON) continue;
            if (!dg.isWorking()) continue;

            double genKw = SimulationConstants.DG_IDLE_FUEL_LOAD * dgRatedKw;
            dg.setCurrentLoad(genKw);
            dg.addWorkTime(1, 1);
            dg.startWork();

            idleNeed--;
        }

        // 2) start new
        for (int k = 0; k < dgCountAll && idleNeed > 0; k++) {
            DieselGenerator dg = dgs[k];
            if (!dg.isAvailable()) continue;
            if (dg.getCurrentLoad() > SimulationConstants.EPSILON) continue;
            if (dg.isWorking()) continue;

            dg.startWork();

            double genKw = SimulationConstants.DG_IDLE_FUEL_LOAD * dgRatedKw;
            dg.setCurrentLoad(genKw);
            dg.addWorkTime(1, 1 + SimulationConstants.DG_MAX_START_FACTOR);

            idleNeed--;
        }
    }

    static double applyRotationReserveNminus1(
            DieselGenerator[] dgs,
            double loadKw,
            double windToLoadKw,
            double btNetKw,          // >0 разряд в нагрузку, <0 заряд
            boolean btAvail,
            Battery battery,
            SystemParameters sp,
            double tauEff,
            double dgMaxKw,
            double dgMinKw,
            double currentSumDieselKw,
            double cat1, double cat2,
            boolean reserveThirdCategory
    ) {
        final int n = dgs.length;

        // 0) Реальная потребность дизеля для покрытия нагрузки (энергетика), а не резерв
        final double btToLoadKw = Math.max(0.0, btNetKw);
        double needDieselNowKw = loadKw - windToLoadKw - btToLoadKw;
        if (needDieselNowKw < 0.0) needDieselNowKw = 0.0;

        // 1) Уставка резерва N−1 (I/II; optionally III)
        double cat3 = Math.max(0.0, 1.0 - cat1 - cat2);
        double reserveShare = cat1 + SimulationConstants.DG_IDLE_K2 * cat2 + (reserveThirdCategory ? cat3 : 0.0);
        double reserveTargetKw = loadKw * reserveShare;
        if (reserveTargetKw < 0.0) reserveTargetKw = 0.0;
        if (reserveTargetKw > loadKw) reserveTargetKw = loadKw;

        // 2) Сколько ДГУ онлайн (по isWorking)
        int onlineCount = 0;
        int availCount = 0;
        for (DieselGenerator dg : dgs) {
            if (!dg.isAvailable()) continue;
            availCount++;
            if (dg.isWorking()) onlineCount++;
        }
        if (onlineCount <= 0) {
            // если дизеля вообще не онлайн, этот метод не должен "чинить" диспетчеризацию с нуля
            // (обычно их поднимает основной диспетчер). Возвращаем как есть.
            return currentSumDieselKw;
        }

        // 3) Проверка N−1: после потери 1 ДГУ остается (onlineCount-1)*dgMax + (возможно) АКБ на tauEff
        double deficitAfterTripKw = reserveTargetKw - Math.max(0, onlineCount - 1) * dgMaxKw;
        if (deficitAfterTripKw < 0.0) deficitAfterTripKw = 0.0;

        boolean batteryCovers = false;
        if (btAvail && deficitAfterTripKw > SimulationConstants.EPSILON) {
            double btDisCap = battery.getDischargeCapacity(sp);
            batteryCovers = canBatteryBridge(battery, sp, deficitAfterTripKw, tauEff, btDisCap);
        }

        boolean nMinusOneOk = (deficitAfterTripKw <= SimulationConstants.EPSILON) || batteryCovers;

        // 4) Если N−1 не ок -> добираем onlineCount до ceil(reserveTarget/dgMax)+1
        if (!nMinusOneOk && reserveTargetKw > SimulationConstants.EPSILON) {
            int needOnline = (int) Math.ceil(reserveTargetKw / dgMaxKw) + 1;
            if (needOnline > availCount) needOnline = availCount;

            int add = needOnline - onlineCount;
            if (add > 0) {
                // 4.1) сначала "горячие" (уже working==true) тут по идее нечего добирать,
                // потому что onlineCount уже по isWorking. Поэтому добор только среди isWorking==false.
                for (int k = 0; k < n && add > 0; k++) {
                    DieselGenerator dg = dgs[k];
                    if (!dg.isAvailable()) continue;
                    if (dg.isWorking()) continue;

                    dg.startWork();
//                    dg.setIdle(false);
//                    dg.resetIdleTime();
                    dg.setCurrentLoad(0.0); // НЕ dgMinKw

                    dg.addWorkTime(1, 1 + SimulationConstants.DG_MAX_START_FACTOR);

                    add--;
                    onlineCount++;
                }
            }
        }

        // 5) Теперь задаем нагрузку по needDieselNowKw равномерно по всем online
        if (onlineCount <= 0) return currentSumDieselKw;

        double per = needDieselNowKw / onlineCount;
        if (per > dgMaxKw) per = dgMaxKw;

        double sum = 0.0;
        int used = 0;

        for (int k = 0; k < n && used < onlineCount; k++) {
            DieselGenerator dg = dgs[k];
            if (!dg.isAvailable()) continue;
            if (!dg.isWorking()) continue;

            double genKw = per;

            if (genKw > dgMaxKw) genKw = dgMaxKw;

            dg.setCurrentLoad(genKw);
            dg.startWork();

            if (genKw + SimulationConstants.EPSILON >= dgMinKw) {
                dg.setIdle(false);
                dg.resetIdleTime();
            }

            sum += genKw;
            used++;
        }

        return sum;
    }


    static void finalizeStoppedDgs(DieselGenerator[] dgs) {
        for (DieselGenerator dg : dgs) {
            if (!dg.isAvailable()) continue;

            double p = dg.getCurrentLoad();
            if (Math.abs(p) > SimulationConstants.EPSILON) continue;

            dg.stopWork();
            dg.setIdle(false);
        }
    }

    static double computeFuelLitersOneHour(List<DieselGenerator> dgs, double ratedKw) {
        double sum = 0.0;
        for (DieselGenerator dg : dgs) {
            sum += dg.fuelLitersOneHour(ratedKw);
        }
        return sum;
    }

// ======================================================================
// LCOE (discounted): PV(cost) / PV(served energy)
// served = load - ENS (ENS не входит в знаменатель)
// ======================================================================

    // ======================================================================
// LCOE (discounted): PV(cost) / PV(served energy)
// served = load - ENS (ENS не входит в знаменатель)
// ======================================================================
    private static double computeLcoeRubPerKwh(
            SystemParameters sp,
            java.util.List<PowerBus> buses,
            double[] servedKwhByYear,
            double[] fuelLitersByYear,
            double[] motoHoursByYear,
            long[] btReplByYear,
            double[] ensCat1KwhByYear,
            double[] ensCat2KwhByYear,
            double[] ensCat3KwhByYear
    ) {
        EconomyDrivers d = buildEconomyDrivers(sp, buses, servedKwhByYear, fuelLitersByYear, motoHoursByYear, btReplByYear,
                ensCat1KwhByYear, ensCat2KwhByYear, ensCat3KwhByYear);
        UnitCosts c = new UnitCosts(
                effectiveRuCost(sp.getBusSystemType(), sp.getCostRuRub()),
                sp.getCostDgRubPerKw(),
                sp.getCostWtRubPerKw(),
                sp.getCostBtRubPerKwh(),
                sp.getCostFuelRubPerKt(),
                sp.getCostDgRubPerKwPerKmh(),
                sp.getCostWtRubPerKwPerYear(),
                sp.getCostBtRubPerKwhPerYear(),
                sp.getDamageRubPerKwhCat1(),
                sp.getDamageRubPerKwhCat2(),
                sp.getDamageRubPerKwhCat3()
        );
        return DiscountedLcoeCalculator.computeRubPerKwh(d, c);

    }

    private static EconomyDrivers buildEconomyDrivers(
            SystemParameters sp,
            java.util.List<PowerBus> buses,
            double[] servedKwhByYear,
            double[] fuelLitersByYear,
            double[] motoHoursByYear,
            long[] btReplByYear,
            double[] ensCat1KwhByYear,
            double[] ensCat2KwhByYear,
            double[] ensCat3KwhByYear
    ) {
        // installed amounts (from actual built system)
        double dgTotalKw = 0.0;
        double wtTotalKw = 0.0;
        double btTotalKwh = 0.0;

        for (PowerBus bus : buses) {
            dgTotalKw += (double) bus.getDieselGenerators().size() * sp.getDieselGeneratorPowerKw();
            wtTotalKw += (double) bus.getWindTurbines().size() * sp.getWindTurbinePowerKw();
            Battery bt = bus.getBattery();
            if (bt != null) btTotalKwh += bt.getMaxCapacityKwh();
        }

        // Важно: массивы по годам передаём как есть (это и есть "драйверы")
        return new EconomyDrivers(
                servedKwhByYear,
                fuelLitersByYear,
                motoHoursByYear,
                btReplByYear,
                ensCat1KwhByYear,
                ensCat2KwhByYear,
                ensCat3KwhByYear,
                dgTotalKw,
                wtTotalKw,
                btTotalKwh,
                sp.getDiscountRatePerYear()
        );
    }



}