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
import simcore.engine.bus.BusPotential;
import simcore.engine.trace.ArrayTraceSession;
import simcore.engine.trace.NoTraceSession;
import simcore.engine.trace.TraceSession;
import simcore.economy.*;


import static simcore.economy.RuCostAdjuster.effectiveRuCost;

import java.util.List;

public final class SingleRunSimulator {
    private static double computeAverageLoad(double[] profile) {
        if (profile == null || profile.length == 0) return 0.0;
        double sum = 0.0;
        for (double v : profile) sum += v;
        return sum / profile.length;
    }


    static boolean considerRotationReserve;

    /**
     * "Есть grid-forming оборудование" == на шине физически присутствует (и доступно) хотя бы одно из:
     * - любой ДГУ (available)
     * - АКБ (available)
     *
     * ВАЖНО: это НЕ зависит от того, работал ли ДГУ в прошлом часу. Если оборудование доступно,
     * то оно может быть запущено/использовано в текущем часу.
     */
    private static boolean hasAnyGridFormingEquipment(PowerBus bus) {
        if (bus == null) return false;
        for (DieselGenerator dg : bus.getDieselGenerators()) {
            if (dg != null && dg.isAvailable()) return true;
        }
        Battery bt = bus.getBattery();
        return bt != null && bt.isAvailable();
    }

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

        // If false: do NOT build per-year drivers arrays (save memory) and do NOT do per-hour moto/repl sums.
        // LCOE is still computed, but in a streaming per-year way (without arrays).
        final boolean computeEconomyDrivers = config.isComputeEconomyDrivers();

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
        double[] servedKwhByYear = null;
        double[] fuelLitersByYear = null;
        double[] motoHoursByYear = null;
        long[] btReplByYear = null;
        double[] ensCat1KwhByYear = null;
        double[] ensCat2KwhByYear = null;
        double[] ensCat3KwhByYear = null;

        // Streaming LCOE accumulators (used when computeEconomyDrivers==false)
        double pvCostRub = 0.0;
        double pvServedKwh = 0.0;
        double servedKwhThisYear = 0.0;
        double fuelLitersAtYearStart = 0.0;
        double ensKwhAtYearStart = 0.0;
        double ensCat1AtYearStart = 0.0;
        double ensCat2AtYearStart = 0.0;
        long motoAtYearStart = 0L;
        long replAtYearStart = 0L;

        // Installed amounts (needed for LCOE in both modes)
        double dgTotalKw = 0.0;
        double wtTotalKw = 0.0;
        double btTotalKwh = 0.0;
        for (PowerBus bus : buses) {
            dgTotalKw += (double) bus.getDieselGenerators().size() * sp.getDieselGeneratorPowerKw();
            wtTotalKw += (double) bus.getWindTurbines().size() * sp.getWindTurbinePowerKw();
            Battery bt = bus.getBattery();
            if (bt != null) btTotalKwh += bt.getMaxCapacityKwh();
        }

        final UnitCosts unitCostsForLcoe = new UnitCosts(
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

        if (computeEconomyDrivers) {
            servedKwhByYear = new double[YEARS];
            fuelLitersByYear = new double[YEARS];
            motoHoursByYear = new double[YEARS];
            btReplByYear = new long[YEARS];
            ensCat1KwhByYear = new double[YEARS];
            ensCat2KwhByYear = new double[YEARS];
            ensCat3KwhByYear = new double[YEARS];
        } else {
            // CAPEX at t=0
            pvCostRub =
                    unitCostsForLcoe.costRuRub
                            + unitCostsForLcoe.costDgRubPerKw * dgTotalKw
                            + unitCostsForLcoe.costWtRubPerKw * wtTotalKw
                            + unitCostsForLcoe.costBtRubPerKwh * btTotalKwh;
            pvServedKwh = 0.0;

            fuelLitersAtYearStart = 0.0;
            ensKwhAtYearStart = 0.0;
            ensCat1AtYearStart = 0.0;
            ensCat2AtYearStart = 0.0;
            motoAtYearStart = 0L;
            replAtYearStart = 0L;
            servedKwhThisYear = 0.0;
        }
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

        // "Энергизована" == физически жива и есть grid-forming оборудование (ДГУ и/или АКБ).
        final boolean[] busEnergised = new boolean[busCount];
        // Счетчик часов подряд, когда шина не энергизована (для правил "в следующий час").
        final int[] outageHours = new int[busCount];

        // DOUBLE_BUS: если МШВ не работает, то перенос II/III и генерации выполняется в следующий час.
        int pendingDoubleBusTransferFrom = -1;

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
// если на шине ТО началось в этом часу => все остальные ДГУ делаем hot-standby до snapshot
            for (PowerBus bus : buses) {
                boolean maintenanceStartedNow = false;
                for (DieselGenerator dg : bus.getDieselGenerators()) {
                    if (dg.isInMaintenance() && dg.getRepairDurationHours() == 4) { // первый час ТО
                        maintenanceStartedNow = true;
                        break;
                    }
                }
                if (maintenanceStartedNow) {
                    // выставит isWorking=true для всех available ДГУ (ТОшная сюда не попадет, она не available)
                    DieselGenerator.keepAllDieselsReadyHotStandby(bus);
                }
            }

// Snapshot DG working states at the beginning of the hour
            for (PowerBus bus : buses) {
                for (DieselGenerator dg : bus.getDieselGenerators()) {
                    dg.snapshotWorkingAtHourStart();
                }
            }
            // ===== effective energised state (physical alive + grid-forming equipment presence) =====
            for (int b = 0; b < busCount; b++) {
                busEnergised[b] = busAlive[b] && hasAnyGridFormingEquipment(buses.get(b));
                if (busEnergised[b]) outageHours[b] = 0;
                else outageHours[b] = outageHours[b] + 1;
            }

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

            // ===== Trace status (failures / repairs) =====
            boolean anyBusFailed = false;
            for (int b = 0; b < busCount; b++) {
                if (busFailedThisHour[b]) { anyBusFailed = true; break; }
            }
            if (anyBusFailed) {
                ctx.status.set(HourContext.StatusCollector.PRI_FAILURE, "BUS_FAILED");
            }

            int dgFailedNow = 0;
            int wtFailedNow = 0;
            int btFailedNow = 0;
            for (PowerBus bus : buses) {
                for (DieselGenerator dg : bus.getDieselGenerators()) {
                    if (!dg.isAvailable() && dg.getRepairDurationHours() > 0) {
                        // First hour of repair/maintenance.
                        if (dg.getRepairDurationHours() == dg.getRepairTimeHours() || dg.isInMaintenance()) {
                            dgFailedNow++;
                        }
                    }
                }
                for (WindTurbine wt : bus.getWindTurbines()) {
                    if (!wt.isAvailable() && wt.getRepairDurationHours() > 0
                            && wt.getRepairDurationHours() == wt.getRepairTimeHours()) {
                        wtFailedNow++;
                    }
                }

                Battery bt = bus.getBattery();
                if (bt != null && !bt.isAvailable() && bt.getRepairDurationHours() > 0
                        && bt.getRepairDurationHours() == bt.getRepairTimeHours()) {
                    btFailedNow++;
                }
            }

            if (dgFailedNow > 0) ctx.status.set(HourContext.StatusCollector.PRI_FAILURE, "DG_FAILED: " + dgFailedNow);
            if (wtFailedNow > 0) ctx.status.set(HourContext.StatusCollector.PRI_FAILURE, "WT_FAILED: " + wtFailedNow);
            if (btFailedNow > 0) ctx.status.set(HourContext.StatusCollector.PRI_FAILURE, "BESS_FAILED: " + btFailedNow);

            // ENS event statistics: track per-hour ENS and "start ENS" (DG start delay ENS)
            final double ensBeforeHour = totals.ensKwh;
            final double startEnsBeforeHour = totals.startEnsKwh;
            // The following snapshots are only needed when we build per-year drivers arrays.
            final double ensCat1BeforeHour = computeEconomyDrivers ? totals.ensCat1Kwh : 0.0;
            final double ensCat2BeforeHour = computeEconomyDrivers ? totals.ensCat2Kwh : 0.0;
            final double fuelBeforeHour = computeEconomyDrivers ? totals.fuelLiters : 0.0;
            final long motoBeforeHour = computeEconomyDrivers ? sumMotoHours(buses) : 0L;
            final long replBeforeHour = computeEconomyDrivers ? sumBatteryReplacements(buses) : 0L;
            // ===== Bus system logic (SINGLE_SECTIONAL_BUS / DOUBLE_BUS) =====
            final BusSystemType busType = sp.getBusSystemType();

            final double[] effectiveLoadKw = BusLoadAllocator.maybeComputeEffectiveLoadsOnOutage(
                    sp,
                    buses,
                    t,
                    cat1,
                    cat2,
                    rawLoadThisHourKw,
                    outageHours
            );


            // ===== SINGLE_BUS (SN): outage / no grid-forming => 100% ENS =====
            if (busType == BusSystemType.SINGLE_NOT_SECTIONAL_BUS && busCount == 1 && !busEnergised[0]) {
                double loadKw = rawLoadThisHourKw[0];
                totals.loadKwh += loadKw;
                totals.ensKwh += loadKw;
                EnsAllocator.addEnsByCategoryProportional(totals, loadKw, loadKw, cat1, cat2);
                ctx.status.set(HourContext.StatusCollector.PRI_FAILURE, "SN_OUTAGE_FULL_ENS");

                if (doTrace) {
                    trace.setBusDown(0, loadKw, loadKw);
                    trace.fillDgState(0, buses.get(0));
                    trace.fillBatteryState(0, buses.get(0).getBattery());
                    Boolean brkClosed = (breaker == null) ? null : breaker.isClosed();
                    trace.addHourRecord(t, loadKw, loadKw, 0.0, brkClosed, ctx.status.get());
                }

                // ENS event stats (whole system, per hour)
                double ensThisHour = totals.ensKwh - ensBeforeHour;
                double startEnsThisHour = totals.startEnsKwh - startEnsBeforeHour;
                totals.ensEventStats.updateHour(ensThisHour, startEnsThisHour);
                continue;
            }

            boolean sectionalClosedThisHour = false;
            if (busType == BusSystemType.SINGLE_SECTIONAL_BUS
                    && busCount == 2
                    && breaker != null
                    && breaker.isAvailable()
                    && busEnergised[0] && busEnergised[1]) {

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
                if (sectionalClosedThisHour && (busEnergised[0] ^ busEnergised[1])) {
                    // По новой логике: при объединенной шине, если откажет одна из шин ИЛИ пропадет grid-forming,
                    // то откажут обе.
                    busEnergised[0] = false;
                    busEnergised[1] = false;
                    ctx.status.set(HourContext.StatusCollector.PRI_FAILURE, "SS_COUPLED_OUTAGE");
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
                    trace.addHourRecord(t, totalLoadAtTime, totalDefAtTime, totalWreAtTime, brkClosed, ctx.status.get());
                }

                // ENS event stats (whole system, per hour)
                double ensThisHour = totals.ensKwh - ensBeforeHour;
                double startEnsThisHour = totals.startEnsKwh - startEnsBeforeHour;
                totals.ensEventStats.updateHour(ensThisHour, startEnsThisHour);

                double loadKwhThisHour = totalLoadAtTime; // kW over 1h
                final double consumerLoadKwhThisHour = Math.max(0.0, loadKwhThisHour - ownUseTotalKwThisHour);
                final double servedTotalKwhThisHour = Math.max(0.0, loadKwhThisHour - ensThisHour); // сколько реально отдали всем нагрузкам
                final double servedToConsumers = Math.min(consumerLoadKwhThisHour, servedTotalKwhThisHour);

                if (computeEconomyDrivers) {
                    int y = t / HOURS_PER_YEAR;
                    if (y >= YEARS) y = YEARS - 1;
                    double ensCat1ThisHour = totals.ensCat1Kwh - ensCat1BeforeHour;
                    double ensCat2ThisHour = totals.ensCat2Kwh - ensCat2BeforeHour;
                    double ensCat3ThisHour = Math.max(0.0, ensThisHour - ensCat1ThisHour - ensCat2ThisHour);
                    ensCat1KwhByYear[y] += ensCat1ThisHour;
                    ensCat2KwhByYear[y] += ensCat2ThisHour;
                    ensCat3KwhByYear[y] += ensCat3ThisHour;
                    servedKwhByYear[y] += servedToConsumers;
                    fuelLitersByYear[y] += (totals.fuelLiters - fuelBeforeHour);
                    motoHoursByYear[y] += (sumMotoHours(buses) - motoBeforeHour);
                    btReplByYear[y] += (sumBatteryReplacements(buses) - replBeforeHour);
                } else {
                    servedKwhThisYear += servedToConsumers;
                }

                if (!computeEconomyDrivers) {
                    final boolean yearEndsNow = ((t + 1) % HOURS_PER_YEAR == 0) || (t == hours - 1);
                    if (yearEndsNow) {
                        final int y = t / HOURS_PER_YEAR;
                        final double df = 1.0 / Math.pow(1.0 + sp.getDiscountRatePerYear(), (y + 1));

                        pvServedKwh += servedKwhThisYear * df;

                        final double fuelLitersYear = totals.fuelLiters - fuelLitersAtYearStart;
                        final double ensKwhYear = totals.ensKwh - ensKwhAtYearStart;
                        final double ensCat1Year = totals.ensCat1Kwh - ensCat1AtYearStart;
                        final double ensCat2Year = totals.ensCat2Kwh - ensCat2AtYearStart;
                        final double ensCat3Year = Math.max(0.0, ensKwhYear - ensCat1Year - ensCat2Year);

                        final long motoNow = sumMotoHours(buses);
                        final long replNow = sumBatteryReplacements(buses);
                        final double motoHoursYear = (double) (motoNow - motoAtYearStart);
                        final long replYear = replNow - replAtYearStart;

                        // fuel: rub/kt, conversion consistent with DiscountedLcoeCalculator: kt = liters / 1e6
                        final double fuelKt = fuelLitersYear / 1_000_000.0;
                        final double fuelRub = fuelKt * unitCostsForLcoe.costFuelRubPerKt;

                        // moto: rub per (kW * 1000 moto-hours)
                        final double motoRub = (motoHoursYear / 1000.0) * dgTotalKw * unitCostsForLcoe.costDgRubPerKwPerKmh;

                        // annual opex
                        final double wtOpexRub = wtTotalKw * unitCostsForLcoe.costWtRubPerKwPerYear;
                        final double btOpexRub = btTotalKwh * unitCostsForLcoe.costBtRubPerKwhPerYear;

                        // battery replacements: replacementCount * (full pack cost)
                        final double btReplRub = (double) replYear * (unitCostsForLcoe.costBtRubPerKwh * btTotalKwh);

                        final double damageRub =
                                ensCat1Year * unitCostsForLcoe.damageRubPerKwhCat1
                                        + ensCat2Year * unitCostsForLcoe.damageRubPerKwhCat2
                                        + ensCat3Year * unitCostsForLcoe.damageRubPerKwhCat3;

                        final double yearCostRub = fuelRub + motoRub + wtOpexRub + btOpexRub + btReplRub + damageRub;
                        pvCostRub += yearCostRub * df;

                        // reset year accumulators
                        servedKwhThisYear = 0.0;
                        fuelLitersAtYearStart = totals.fuelLiters;
                        ensKwhAtYearStart = totals.ensKwh;
                        ensCat1AtYearStart = totals.ensCat1Kwh;
                        ensCat2AtYearStart = totals.ensCat2Kwh;
                        motoAtYearStart = motoNow;
                        replAtYearStart = replNow;
                    }
                }
                continue;
            }

            // ===== Standard per-bus dispatch =====
            // ===== DOUBLE_BUS: deficit handling per new table =====
            // If the other bus has surplus:
            //  - MSHV works: transfer I/II/III and generation NOW (modeled as immediate load shift)
            //  - MSHV failed: transfer I NOW, II/III and generation NEXT hour
            if (busType == BusSystemType.DOUBLE_BUS && busCount == 2 && busEnergised[0] && busEnergised[1]) {

                // Apply pending transfer from previous hour (MSHV failed case)
                if (pendingDoubleBusTransferFrom != -1) {
                    int from = pendingDoubleBusTransferFrom;
                    int to = 1 - from;
                    double transfer = rawLoadThisHourKw[from] * (1.0 - cat1);
                    rawLoadThisHourKw[from] = Math.max(0.0, rawLoadThisHourKw[from] - transfer);
                    rawLoadThisHourKw[to] += transfer;
                    pendingDoubleBusTransferFrom = -1;
                    ctx.status.set(HourContext.StatusCollector.PRI_TRANSFER, "DOUBLEBUS_TRANSFER_II_III_AND_GEN_NEXT");
                }

                double load0 = (effectiveLoadKw != null) ? effectiveLoadKw[0] : rawLoadThisHourKw[0];
                double load1 = (effectiveLoadKw != null) ? effectiveLoadKw[1] : rawLoadThisHourKw[1];

                double pot0 = BusPotential.windPotentialNoSideEffects(buses.get(0), windV)
                        + BusPotential.dieselPotential(buses.get(0), dgMaxKw)
                        + BusPotential.batteryDischargePotential(buses.get(0), sp);
                double pot1 = BusPotential.windPotentialNoSideEffects(buses.get(1), windV)
                        + BusPotential.dieselPotential(buses.get(1), dgMaxKw)
                        + BusPotential.batteryDischargePotential(buses.get(1), sp);

                double deficit0 = Math.max(0.0, load0 - pot0);
                double deficit1 = Math.max(0.0, load1 - pot1);
                double surplus0 = Math.max(0.0, pot0 - load0);
                double surplus1 = Math.max(0.0, pot1 - load1);

                if (deficit0 > SimulationConstants.EPSILON && surplus1 > SimulationConstants.EPSILON) {
                    if (breaker != null && breaker.isAvailable()) {
                        // Transfer NOW in 10% steps (round up): if need 35% -> 40%.
                        double stepKw = SimulationConstants.UFLS_STEP * Math.max(load0, SimulationConstants.EPSILON);
                        double transferKw = (stepKw > SimulationConstants.EPSILON)
                                ? Math.ceil(deficit0 / stepKw) * stepKw
                                : deficit0;
                        transferKw = Math.min(load0, transferKw);
                        rawLoadThisHourKw[0] = Math.max(0.0, rawLoadThisHourKw[0] - transferKw);
                        rawLoadThisHourKw[1] += transferKw;
                        int pct = (int) Math.round(100.0 * (transferKw / Math.max(load0, SimulationConstants.EPSILON)));
                        ctx.status.set(HourContext.StatusCollector.PRI_TRANSFER, "DOUBLEBUS_TRANSFER_ALL_NOW_" + pct + "%");
                    } else {
                        double transferI = rawLoadThisHourKw[0] * cat1;
                        rawLoadThisHourKw[0] = Math.max(0.0, rawLoadThisHourKw[0] - transferI);
                        rawLoadThisHourKw[1] += transferI;
                        pendingDoubleBusTransferFrom = 0;
                        ctx.status.set(HourContext.StatusCollector.PRI_TRANSFER, "DOUBLEBUS_TRANSFER_I_NOW_II_III_GEN_NEXT");
                    }
                } else if (deficit1 > SimulationConstants.EPSILON && surplus0 > SimulationConstants.EPSILON) {
                    if (breaker != null && breaker.isAvailable()) {
                        double stepKw = SimulationConstants.UFLS_STEP * Math.max(load1, SimulationConstants.EPSILON);
                        double transferKw = (stepKw > SimulationConstants.EPSILON)
                                ? Math.ceil(deficit1 / stepKw) * stepKw
                                : deficit1;
                        transferKw = Math.min(load1, transferKw);
                        rawLoadThisHourKw[1] = Math.max(0.0, rawLoadThisHourKw[1] - transferKw);
                        rawLoadThisHourKw[0] += transferKw;
                        int pct = (int) Math.round(100.0 * (transferKw / Math.max(load1, SimulationConstants.EPSILON)));
                        ctx.status.set(HourContext.StatusCollector.PRI_TRANSFER, "DOUBLEBUS_TRANSFER_ALL_NOW_" + pct + "%");
                    } else {
                        double transferI = rawLoadThisHourKw[1] * cat1;
                        rawLoadThisHourKw[1] = Math.max(0.0, rawLoadThisHourKw[1] - transferI);
                        rawLoadThisHourKw[0] += transferI;
                        pendingDoubleBusTransferFrom = 1;
                        ctx.status.set(HourContext.StatusCollector.PRI_TRANSFER, "DOUBLEBUS_TRANSFER_I_NOW_II_III_GEN_NEXT");
                    }
                }
            }

            // DOUBLE_BUS: if one bus is down, from the 2nd repair hour transfer ALL DG and WT from the dead bus
            // onto the live bus (in addition to load transfer performed in BusLoadAllocator).
            int doubleBusDead = -1;
            int doubleBusLive = -1;
            boolean doubleBusTransferGen = false;
            if (busType == BusSystemType.DOUBLE_BUS && busCount == 2 && (busEnergised[0] ^ busEnergised[1])) {
                doubleBusDead = busEnergised[0] ? 1 : 0;
                doubleBusLive = 1 - doubleBusDead;
                // Transfer generation starting from the 2nd outage hour.
                doubleBusTransferGen = outageHours[doubleBusDead] >= 1;
            }

            for (int b = 0; b < busCount; b++) {
                final PowerBus bus = buses.get(b);
                final double loadKw = (effectiveLoadKw != null) ? effectiveLoadKw[b] : rawLoadThisHourKw[b];

                if (!busEnergised[b]) {
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
                    if (doubleBusTransferGen) {
                        PowerBus extra = (b == doubleBusLive) ? buses.get(doubleBusDead) : null;
                        PerBusDispatcher.dispatchOneBusOneHourWithExtraSources(ctx, bus, extra, true, b, loadKw);
                    } else {
                        PerBusDispatcher.dispatchOneBusOneHour(ctx, bus, true, b, loadKw);
                    }
                }
            }

            if (doTrace) {
                for (int b = 0; b < busCount; b++) {
                    totalLoadAtTime += rawLoadThisHourKw[b]; // consumer load + own-use
                }
                totalWreAtTime = hourWreRef[0];
                Boolean brkClosed = (breaker == null) ? null : breaker.isClosed();
                trace.addHourRecord(t, totalLoadAtTime, totalDefAtTime, totalWreAtTime, brkClosed, ctx.status.get());
            }

            // ENS event stats (whole system, per hour)
            double ensThisHour = totals.ensKwh - ensBeforeHour;
            double startEnsThisHour = totals.startEnsKwh - startEnsBeforeHour;
            totals.ensEventStats.updateHour(ensThisHour, startEnsThisHour);

            double loadKwhThisHour = 0.0;
            if (effectiveLoadKw != null) {
                for (int b = 0; b < busCount; b++) loadKwhThisHour += effectiveLoadKw[b];
            } else {
                for (int b = 0; b < busCount; b++) loadKwhThisHour += rawLoadThisHourKw[b];
            }

            final double consumerLoadKwhThisHour = Math.max(0.0, loadKwhThisHour - ownUseTotalKwThisHour);
            final double servedTotalKwhThisHour = Math.max(0.0, loadKwhThisHour - ensThisHour); // сколько реально отдали всем нагрузкам
            final double servedToConsumers = Math.min(consumerLoadKwhThisHour, servedTotalKwhThisHour);

            if (computeEconomyDrivers) {
                int y = t / HOURS_PER_YEAR;
                if (y >= YEARS) y = YEARS - 1;
                double ensCat1ThisHour = totals.ensCat1Kwh - ensCat1BeforeHour;
                double ensCat2ThisHour = totals.ensCat2Kwh - ensCat2BeforeHour;
                double ensCat3ThisHour = Math.max(0.0, ensThisHour - ensCat1ThisHour - ensCat2ThisHour);
                ensCat1KwhByYear[y] += ensCat1ThisHour;
                ensCat2KwhByYear[y] += ensCat2ThisHour;
                ensCat3KwhByYear[y] += ensCat3ThisHour;

                servedKwhByYear[y] += servedToConsumers;
                fuelLitersByYear[y] += (totals.fuelLiters - fuelBeforeHour);
                motoHoursByYear[y] += (sumMotoHours(buses) - motoBeforeHour);
                btReplByYear[y] += (sumBatteryReplacements(buses) - replBeforeHour);
            } else {
                servedKwhThisYear += servedToConsumers;

                final boolean yearEndsNow = ((t + 1) % HOURS_PER_YEAR == 0) || (t == hours - 1);
                if (yearEndsNow) {
                    final int y = t / HOURS_PER_YEAR;
                    final double df = 1.0 / Math.pow(1.0 + sp.getDiscountRatePerYear(), (y + 1));

                    pvServedKwh += servedKwhThisYear * df;

                    final double fuelLitersYear = totals.fuelLiters - fuelLitersAtYearStart;
                    final double ensKwhYear = totals.ensKwh - ensKwhAtYearStart;
                    final double ensCat1Year = totals.ensCat1Kwh - ensCat1AtYearStart;
                    final double ensCat2Year = totals.ensCat2Kwh - ensCat2AtYearStart;
                    final double ensCat3Year = Math.max(0.0, ensKwhYear - ensCat1Year - ensCat2Year);

                    final long motoNow = sumMotoHours(buses);
                    final long replNow = sumBatteryReplacements(buses);
                    final double motoHoursYear = (double) (motoNow - motoAtYearStart);
                    final long replYear = replNow - replAtYearStart;

                    final double fuelKt = fuelLitersYear / 1_000_000.0;
                    final double fuelRub = fuelKt * unitCostsForLcoe.costFuelRubPerKt;
                    final double motoRub = (motoHoursYear / 1000.0) * dgTotalKw * unitCostsForLcoe.costDgRubPerKwPerKmh;
                    final double wtOpexRub = wtTotalKw * unitCostsForLcoe.costWtRubPerKwPerYear;
                    final double btOpexRub = btTotalKwh * unitCostsForLcoe.costBtRubPerKwhPerYear;
                    final double btReplRub = (double) replYear * (unitCostsForLcoe.costBtRubPerKwh * btTotalKwh);
                    final double damageRub =
                            ensCat1Year * unitCostsForLcoe.damageRubPerKwhCat1
                                    + ensCat2Year * unitCostsForLcoe.damageRubPerKwhCat2
                                    + ensCat3Year * unitCostsForLcoe.damageRubPerKwhCat3;

                    final double yearCostRub = fuelRub + motoRub + wtOpexRub + btOpexRub + btReplRub + damageRub;
                    pvCostRub += yearCostRub * df;

                    servedKwhThisYear = 0.0;
                    fuelLitersAtYearStart = totals.fuelLiters;
                    ensKwhAtYearStart = totals.ensKwh;
                    ensCat1AtYearStart = totals.ensCat1Kwh;
                    ensCat2AtYearStart = totals.ensCat2Kwh;
                    motoAtYearStart = motoNow;
                    replAtYearStart = replNow;
                }
            }

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
        EconomyDrivers econDrivers = null;
        double lcoeRubPerKwh;

        if (computeEconomyDrivers) {
            econDrivers = buildEconomyDrivers(
                    sp,
                    buses,
                    servedKwhByYear,
                    fuelLitersByYear,
                    motoHoursByYear,
                    btReplByYear,
                    ensCat1KwhByYear,
                    ensCat2KwhByYear,
                    ensCat3KwhByYear
            );
            lcoeRubPerKwh = DiscountedLcoeCalculator.computeRubPerKwh(econDrivers, unitCostsForLcoe);
        } else {
            final double eps = 1e-12;
            lcoeRubPerKwh = (pvServedKwh <= eps) ? 0.0 : (pvCostRub / pvServedKwh);
        }
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
            double btDisCap = battery.getDischargePowerCapKw(sp);
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
            double btDisCap = battery.getDischargePowerCapKw(sp);
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
            double btDisCap = battery.getDischargePowerCapKw(sp);
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
            double btDisCap = battery.getDischargePowerCapKw(sp);
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