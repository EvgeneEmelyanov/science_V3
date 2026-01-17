package simcore.engine;

import simcore.config.SimulationConfig;
import simcore.config.SimulationConstants;
import simcore.config.SystemParameters;
import simcore.config.BusSystemType;
import simcore.model.*;
import simcore.engine.failures.FailureStepper;
import simcore.engine.diesel.DieselFleetController;
import simcore.engine.fuel.FuelModel;
import simcore.engine.fuel.LegacyFuelModel;
import simcore.engine.metrics.EnsAllocator;
import simcore.engine.step.NetworkFailureStep;
import simcore.engine.bus.BusLoadAllocator;
import simcore.engine.bus.TieBreakerController;
import simcore.engine.trace.ArrayTraceSession;
import simcore.engine.trace.NoTraceSession;
import simcore.engine.trace.TraceSession;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;


public final class SingleRunSimulator {

    static final FuelModel FUEL_MODEL = new LegacyFuelModel();

    static final boolean ENABLE_ZERO_LOAD_ALL_DG_READY = true;
    public SimulationMetrics simulate(SimInput input, long seed, boolean traceEnabled) {

        final SimulationConfig config = input.getConfig();
        final SystemParameters sp = input.getSystemParameters();

        final double[] windMs = config.getWindMs();
        final int hours = windMs.length;

        final boolean considerFailures = config.isConsiderFailures();
        final boolean considerDegradation = config.isConsiderBatteryDegradation();
        final boolean considerChargeByDg = config.isConsiderChargeByDg();
        final boolean considerRotationReserve = config.isConsiderRotationReserve();

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
        final TraceSession trace = traceEnabled ? new ArrayTraceSession() : new NoTraceSession();final boolean[] busAvailBefore = new boolean[busCount];
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
            if (t == 332) {
                System.out.println();
            }
            final double windV = windMs[t];
            final boolean doTrace = trace.enabled();
            trace.startHour(busCount);

            double totalLoadAtTime = 0.0;
            double totalDefAtTime = 0.0;
            double totalWreAtTime;
            final double[] hourWreRef = doTrace ? new double[]{0.0} : null;

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
                    trace
            );



            NetworkFailureStep.updateOneHour(
                    considerFailures,
                    buses,
                    breaker,
                    rooms,
                    roomIndexByBus,
                    busAvailBefore,
                    busAvailAfter,
                    busFailedThisHour,
                    busAlive
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

                // ENS по категориям: учитываем нюанс задержки пуска ДГУ.
                // - часть ENS из-за задержки пуска (кратковременный провал) распределяем пропорционально категориям
                // - остаток ENS (сброс нагрузки при дефиците) распределяем приоритетно: III -> II -> I
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

        for(SwitchgearRoom room : rooms) {
            failRoom += room.getFailureCount();
        }

        for (PowerBus bus : buses) {
            failBus += bus.getFailureCount();
            for (WindTurbine wt : bus.getWindTurbines()) failWt += wt.getFailureCount();
            for (DieselGenerator dg : bus.getDieselGenerators()) failDg += dg.getFailureCount();
            Battery bt = bus.getBattery();
            if (bt != null){
                failBt += bt.getFailureCount();
                repBt  += bt.getReplacementCount();
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
    // ======================================================================
// FIXED: dispatchSectionalClosedOneHour
//  - same fix: apply low-load/idle/burn AFTER rotating reserve,
//    then recompute dgProducedKw and surplus-based charging decisions.
// ======================================================================


    static boolean finalizeIdleAndBurn(DieselGenerator[] dgs, double dgMinKw) {
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
                dg.incrementIdleTime();
                dg.setIdle(true);

                if (dg.getIdleTime() >= SimulationConstants.DG_MAX_IDLE_HOURS) {
                    dg.setCurrentLoad(Math.max(dgMinKw, 0.0)); // прожиг
                    dg.resetIdleTime();
                    dg.setIdle(false);
                    anyBurnThisHour = true;
                }
            } else {
                // Нормальная нагрузка -> сбрасываем idleTime
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

        // Тут поменял уставку, старая уставка:
//        double pCrit = loadKw * (cat1 + SimulationConstants.DG_IDLE_K2 * cat2);
        // Новая уставка:
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

        double reserveNeed = windLoss - btFirm;
        reserveNeed += windLoss * SimulationConstants.DG_IDLE_MARGIN_PCT;
        if (reserveNeed < 0.0) reserveNeed = 0.0;

        int idleNeed = (reserveNeed > SimulationConstants.EPSILON)
                ? (int) Math.ceil(reserveNeed / dgRatedKw)
                : 0;
        if (idleNeed > available) idleNeed = available;

        // 1) сначала уже working
        for (int k = 0; k < dgCountAll && idleNeed > 0; k++) {
            DieselGenerator dg = dgs[k];

            if (!dg.isAvailable()) {
                DieselFleetController.hardStopDg(dg);
                continue;
            }
            if (!dg.isWorking()) continue;

            double genKw = idleOrBurnGenKw(dg, dgRatedKw);
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

            double genKw = idleOrBurnGenKw(dg, dgRatedKw);
            dg.setCurrentLoad(genKw);
            dg.addWorkTime(1, 1+ SimulationConstants.DG_MAX_START_FACTOR);

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

        // Тут поменял уставку, старая уставка:
//        double pCrit = loadKw * (cat1 + SimulationConstants.DG_IDLE_K2 * cat2);
        // Новая уставка:
        double pCrit = SimulationConstants.MAX_LOAD * (cat1 + SimulationConstants.DG_IDLE_K2 * cat2);
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

        double reserveNeed = windLoss - (btFirm + dgFirm);
        reserveNeed += windLoss * SimulationConstants.DG_IDLE_MARGIN_PCT;
        if (reserveNeed < 0.0) reserveNeed = 0.0;

        int idleNeed = (reserveNeed > SimulationConstants.EPSILON)
                ? (int) Math.ceil(reserveNeed / dgRatedKw)
                : 0;

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

            double genKw = idleOrBurnGenKw(dg, dgRatedKw);
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

            double genKw = idleOrBurnGenKw(dg, dgRatedKw);
            dg.setCurrentLoad(genKw);
            dg.addWorkTime(1, 1+ SimulationConstants.DG_MAX_START_FACTOR);

            idleNeed--;
        }
    }

    static double applyRotationReserveNminus1(
            DieselGenerator[] dgs,
            double loadKw,
            double windToLoadKw,
            double btNetKw,
            boolean btAvail,
            Battery battery,
            SystemParameters sp,
            double tauEff,
            double dgMaxKw,
            double dgMinKw,
            double currentSumDieselKw
    ) {
        int dgCountAll = dgs.length;

        double btDisToLoadRR = Math.max(0.0, btNetKw);
        //Старая уставка:
//        double needFromDieselNowKw = loadKw - windToLoadKw - btDisToLoadRR;
        //Новая уставка:
        double needFromDieselNowKw = loadKw - windToLoadKw;
        if (needFromDieselNowKw < 0.0) needFromDieselNowKw = 0.0;

        int onlineCount = 0;
        for (int k = 0; k < dgCountAll; k++) {
            DieselGenerator dg = dgs[k];
            if (!dg.isAvailable()) continue;
            if (Math.abs(dg.getCurrentLoad()) > SimulationConstants.EPSILON) onlineCount++;
        }

        double deficitAfterOneTripKw = needFromDieselNowKw - Math.max(0, (onlineCount - 1)) * dgMaxKw;
        if (deficitAfterOneTripKw < 0.0) deficitAfterOneTripKw = 0.0;

        boolean batteryCoversNminus1 = false;
        if (btAvail && deficitAfterOneTripKw > SimulationConstants.EPSILON) {
            double btDisCapRR = battery.getDischargeCapacity(sp);
            batteryCoversNminus1 = canBatteryBridge(battery, sp, deficitAfterOneTripKw, tauEff, btDisCapRR);
        }

        boolean nMinusOneOk = (deficitAfterOneTripKw <= SimulationConstants.EPSILON) || batteryCoversNminus1;

        if (!nMinusOneOk && needFromDieselNowKw > SimulationConstants.EPSILON) {

            int needOnline = (int) Math.ceil(needFromDieselNowKw / dgMaxKw) + 1;

            int avail = 0;
            for (int k = 0; k < dgCountAll; k++) if (dgs[k].isAvailable()) avail++;
            if (needOnline > avail) needOnline = avail;

            int add = needOnline - onlineCount;

            if (add > 0) {
                // 1) "горячие" (isWorking==true), p==0
                for (int k = 0; k < dgCountAll && add > 0; k++) {
                    DieselGenerator dg = dgs[k];
                    if (!dg.isAvailable()) continue;
                    if (!dg.isWorking()) continue;
                    if (Math.abs(dg.getCurrentLoad()) > SimulationConstants.EPSILON) continue;

                    dg.setCurrentLoad(dgMinKw);
                    dg.setIdle(false);

                    dg.addWorkTime(1, 1);
                    dg.startWork();

                    add--;
                    onlineCount++;
                }

                // 2) запуск новых (isWorking==false), p==0
                for (int k = 0; k < dgCountAll && add > 0; k++) {
                    DieselGenerator dg = dgs[k];
                    if (!dg.isAvailable()) continue;
                    if (dg.isWorking()) continue;
                    if (Math.abs(dg.getCurrentLoad()) > SimulationConstants.EPSILON) continue;

                    dg.startWork();

                    dg.setCurrentLoad(dgMinKw);
                    dg.setIdle(false);
                    dg.resetIdleTime();

                    dg.addWorkTime(1, 1+ SimulationConstants.DG_MAX_START_FACTOR);

                    add--;
                    onlineCount++;
                }
            }

            // перераспределяем нагрузку по всем online ДГУ (включая тех, кто был в ХХ)
            if (onlineCount > 0) {
                double per = needFromDieselNowKw / onlineCount;
                if (per > dgMaxKw) per = dgMaxKw;

                double sum = 0.0;
                int usedRR = 0;

                for (int k = 0; k < dgCountAll; k++) {
                    DieselGenerator dg = dgs[k];
                    if (!dg.isAvailable()) continue;

                    if (Math.abs(dg.getCurrentLoad()) <= SimulationConstants.EPSILON) continue; // не online
                    if (usedRR >= onlineCount) break;

                    double genKw = per;

                    if (genKw + SimulationConstants.EPSILON >= dgMinKw) {
                        dg.setIdle(false);
                        dg.resetIdleTime();
                    }

                    if (genKw > dgMaxKw) genKw = dgMaxKw;

                    dg.setCurrentLoad(genKw);
                    dg.startWork();

                    sum += genKw;
                    usedRR++;
                }

                return sum; // как в исходнике: после перераспределения sumDiesel = sum
            }
        }

        return currentSumDieselKw;
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

    static double idleOrBurnGenKw(DieselGenerator dg, double dgRatedKw) {
        // Холостой ход — это только режим мощности.
        // Учёт времени холостого хода и решение о прожиге выполняются ОДИН РАЗ
        // в финальном блоке "FINAL low-load/idle/burn" в dispatcher.
        return -0.15 * dgRatedKw;
    }

}
