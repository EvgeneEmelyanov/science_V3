package simcore.engine;

import simcore.config.SimulationConfig;
import simcore.config.SimulationConstants;
import simcore.config.SystemParameters;
import simcore.config.BusSystemType;
import simcore.model.*;
import simcore.engine.failures.FailureStepper;
import simcore.engine.diesel.DieselFleetController;
import simcore.engine.metrics.EnsAllocator;
import simcore.engine.step.NetworkFailureStep;
import simcore.engine.bus.BusLoadAllocator;
import simcore.engine.bus.TieBreakerController;
import simcore.engine.trace.ArrayTraceSession;
import simcore.engine.trace.NoTraceSession;
import simcore.engine.trace.TraceSession;

import java.util.List;

public final class SingleRunSimulator {

    static final boolean ENABLE_ZERO_LOAD_ALL_DG_READY = true;
    static final boolean ENABLE_MAINTENANCE_ONLY_AT_ZERO_LOAD = false;
    static boolean considerRotationReserve;

    public SimulationMetrics simulate(SimInput input, long seed, boolean traceEnabled) {

        final SimulationConfig config = input.getConfig();
        final SystemParameters sp = input.getSystemParameters();

        final double[] windMs = config.getWindMs();
        final int hours = windMs.length;

        final boolean considerFailures = config.isConsiderFailures();
        final boolean considerDegradation = config.isConsiderBatteryDegradation();
        final boolean considerChargeByDg = config.isConsiderChargeByDg();
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
        final TraceSession trace = traceEnabled ? new ArrayTraceSession() : new NoTraceSession();

        // For ENABLE_ZERO_LOAD_ALL_DG_READY: remember which buses had 0 load in previous hour.
        final boolean[] prevZeroLoadByBus = new boolean[busCount];
        final boolean[] zeroLoadThisHourByBus = new boolean[busCount];

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
            final double[] hourWreRef = doTrace ? new double[]{0.0} : null;

            // Raw (pre-transfer) loads for maintenance deferral decision.
            final double[] rawLoadThisHourKw = new double[busCount];
            for (int b = 0; b < busCount; b++) {
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
                    rawLoadThisHourKw,
                    ENABLE_MAINTENANCE_ONLY_AT_ZERO_LOAD
            );

            // Snapshot DG "working" states at the beginning of the hour (after failures, before dispatch).
            java.util.IdentityHashMap<DieselGenerator, Boolean> wasWorkingAtHourStart = new java.util.IdentityHashMap<>();
            for (PowerBus bus : buses) {
                for (DieselGenerator dg : bus.getDieselGenerators()) {
                    wasWorkingAtHourStart.put(dg, dg.isWorking());
                }
            }

            final HourContext ctx = new HourContext(
                    sp,
                    windV,
                    considerDegradation,
                    considerChargeByDg,
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
                    trace,
                    prevZeroLoadByBus,
                    ENABLE_MAINTENANCE_ONLY_AT_ZERO_LOAD,
                    wasWorkingAtHourStart
            );
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
                    dgMaxKw
            );

            boolean sectionalClosedThisHour = false;
            if (busType == BusSystemType.SINGLE_SECTIONAL_BUS
                    && busCount == 2
                    && breaker != null
                    && breaker.isAvailable()
                    && busAlive[0] && busAlive[1]) {

                double[] loadsForDecision = (effectiveLoadKw != null)
                        ? effectiveLoadKw
                        : new double[]{buses.get(0).getLoadKw()[t], buses.get(1).getLoadKw()[t]};

                sectionalClosedThisHour = TieBreakerController.shouldCloseTieBreakerThisHour(
                        sp, buses, loadsForDecision, windV, dgMaxKw
                );

                breaker.setClosed(sectionalClosedThisHour);
            } else {
                if (breaker != null) breaker.setClosed(false);
            }
            // ===== Sectional-closed dispatch (если секционник закрыт) =====

            if (sectionalClosedThisHour) {

                final double[] loads = (effectiveLoadKw != null)
                        ? effectiveLoadKw
                        : new double[]{buses.get(0).getLoadKw()[t], buses.get(1).getLoadKw()[t]};

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

                // Update "previous hour zero-load" markers for the next hour.
                for (int b = 0; b < busCount; b++) {
                    prevZeroLoadByBus[b] = loads[b] <= SimulationConstants.EPSILON;
                }
                continue;
            }

            // ===== Standard per-bus dispatch =====
            for (int b = 0; b < busCount; b++) {
                final PowerBus bus = buses.get(b);
                final double loadKw = (effectiveLoadKw != null) ? effectiveLoadKw[b] : bus.getLoadKw()[t];

                PerBusDispatcher.dispatchOneBusOneHour(
                        ctx,
                        bus,
                        busAlive[b],
                        b,
                        loadKw
                );

                // Update "previous hour zero-load" markers for the next hour.
                prevZeroLoadByBus[b] = loadKw <= SimulationConstants.EPSILON;
            }

            if (doTrace) {
                for (int b = 0; b < busCount; b++) {
                    totalLoadAtTime += buses.get(b).getLoadKw()[t]; // или effectiveLoadKw[b] если он не null — ниже см.
                }
                totalWreAtTime = hourWreRef[0];
                Boolean brkClosed = (breaker == null) ? null : breaker.isClosed();
                trace.addHourRecord(t, totalLoadAtTime, totalDefAtTime, totalWreAtTime, brkClosed);
            }
        }

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

        return new SimulationMetrics(
                totals.loadKwh,
                totals.ensKwh,
                totals.ensCat1Kwh,
                totals.ensCat2Kwh,
                totals.wreKwh,
                totals.wtToLoadKwh,
                totals.dgToLoadKwh,
                totals.btToLoadKwh,
                totals.fuelLiters,
                moto,
                trace.records(),
                failBus,
                failDg,
                failWt,
                failBt,
                failBrk,
                failRoom,
                repBt
        );
    }

    // ======================================================================
    // Helpers
    // ======================================================================

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

                boolean wasWorkingAtHourStart = false;
                if (ctx != null && ctx.wasWorkingAtHourStart != null) {
                    Boolean v = ctx.wasWorkingAtHourStart.get(dg);
                    wasWorkingAtHourStart = (v != null && v);
                }
                boolean startedThisHour = !wasWorkingAtHourStart;

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
        DieselGenerator[] dgs = DieselFleetController.getSortedDgs(bus);
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
                DieselFleetController.hardStopDg(dg);
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
                DieselFleetController.hardStopDg(dg);
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
                DieselFleetController.hardStopDg(dg);
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

        double pCrit = loadKw * (cat1 + SimulationConstants.DG_IDLE_K2 * cat2);
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
        double reserveNeed = loadKw * (cat1 + SimulationConstants.DG_IDLE_K2 * cat2);
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
            double cat1, double cat2
    ) {
        final int n = dgs.length;

        // 0) Реальная потребность дизеля для покрытия нагрузки (энергетика), а не резерв
        final double btToLoadKw = Math.max(0.0, btNetKw);
        double needDieselNowKw = loadKw - windToLoadKw - btToLoadKw;
        if (needDieselNowKw < 0.0) needDieselNowKw = 0.0;

        // 1) Уставка резерва N−1 (только 1+2 категория)
        double reserveTargetKw = loadKw * (cat1 + SimulationConstants.DG_IDLE_K2 * cat2);
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

            // если per меньше dgMinKw — это уже твоя бизнес-логика:
            // либо разрешаем "малую нагрузку" (тогда idleTime будет расти),
            // либо поднимаем часть ДГУ до dgMinKw и остальных разгружаем/останавливаем.
            // Здесь оставляю как есть: ставим per, а холостой ход/прожиг решает finalizeIdleAndBurn().
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

}
