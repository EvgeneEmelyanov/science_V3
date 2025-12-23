package simcore.engine;

import simcore.config.SimulationConfig;
import simcore.config.SimulationConstants;
import simcore.config.SystemParameters;
import simcore.model.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

public final class SingleRunSimulator {

    // ===== Fuel model constants (из старого кода) =====
    private static final double K11 = 0.0185;
    private static final double K21 = -0.0361;
    private static final double K31 = 0.2745;
    private static final double K12 = 5.3978;
    private static final double K22 = -11.4831;
    private static final double K32 = 11.6284;

    private static final ThreadLocal<DieselGenerator[]> DG_SORT_BUF = new ThreadLocal<>();

    /**
     * Один прогон по всему временному ряду.
     * seed задаёт идентичные RNG последовательности для всех наборов параметров (common random numbers).
     */
    public SimulationMetrics simulate(SimInput input, long seed, boolean traceEnabled) {

        SimulationConfig config = input.getConfig();
        SystemParameters systemParameters = input.getSystemParameters();

        double[] wind = config.getWindMs();
        int n = wind.length;

        boolean considerFailures = config.isConsiderFailures();
        boolean considerDegradation = config.isConsiderBatteryDegradation();
        boolean considerChargeByDg = config.isConsiderChargeByDg();
        boolean considerRotationReserve = config.isConsiderRotationReserve();

        double cat1 = systemParameters.getFirstCat();
        double cat2 = systemParameters.getSecondCat();
        double cat3 = systemParameters.getThirdCat();

        PowerSystem powerSystem = new PowerSystemBuilder().build(systemParameters, input.getTotalLoadKw());
        List<PowerBus> buses = powerSystem.getBuses();
        int busCount = buses.size();
        Breaker breaker = powerSystem.getTieBreaker();

        // RNG: одинаковый seed на m-итерацию для всех theta
        Random rndWT = new Random(seed + 10);
        Random rndDG = new Random(seed + 2);
        Random rndBT = new Random(seed + 3);
        Random rndBUS = new Random(seed + 4);
        Random rndBRK = new Random(seed + 5);

        if (breaker != null) breaker.initFailureModel(rndBRK, considerFailures);

        for (PowerBus bus : buses) {
            bus.initFailureModel(rndBUS, considerFailures);
            for (WindTurbine wt : bus.getWindTurbines()) wt.initFailureModel(rndWT, considerFailures);
            for (DieselGenerator dg : bus.getDieselGenerators()) dg.initFailureModel(rndDG, considerFailures);
            Battery bt = bus.getBattery();
            if (bt != null) bt.initFailureModel(rndBT, considerFailures);
        }

        double loadKwh = 0.0;
        double ensKwh = 0.0;
        double wreKwh = 0.0;

        double wtToLoadKwh = 0.0;
        double dgToLoadKwh = 0.0;
        double btToLoadKwh = 0.0;

        double fuelLiters = 0.0;

        long failBus = 0;
        long failDg = 0;
        long failWt = 0;
        long failBt = 0;
        long failBrk = 0;

        List<SimulationStepRecord> trace = traceEnabled ? new ArrayList<>() : null;

        boolean[] busAvailBefore = new boolean[busCount];
        boolean[] busAvailAfter = new boolean[busCount];
        boolean[] busFailedThisHour = new boolean[busCount];
        boolean[] busAlive = new boolean[busCount];

        for (int t = 0; t < n; t++) {

            double v = wind[t];
            final boolean doTrace = (trace != null);

            double totalLoadAtTime = 0.0;
            double totalDefAtTime = 0.0;
            double totalWreAtTime = 0.0;

            boolean[] busStatus = null;
            double[] busLoadAtTime = null;
            double[] busGenWindAtTime = null;
            double[] busGenDgAtTime = null;
            double[] busGenBtAtTime = null;
            double[] busDefAtTime = null;

            double[][] busGenDgLoadKw = null;
            double[][] busGenDgHoursSinceMaintenance = null;
            double[][] busGenDgTimeWorked = null;
            double[][] busGenDgTotalTimeWorked = null;
            boolean[][] dgAvailable = null;
            boolean[][] dgInMaintenance = null;
            double[] btActualCapacity = null;
            double[] btActualSOC = null;

            if (doTrace) {
                busStatus = new boolean[busCount];
                busLoadAtTime = new double[busCount];
                busGenWindAtTime = new double[busCount];
                busGenDgAtTime = new double[busCount];
                busGenBtAtTime = new double[busCount];
                busDefAtTime = new double[busCount];

                busGenDgLoadKw = new double[busCount][];
                busGenDgHoursSinceMaintenance = new double[busCount][];
                busGenDgTimeWorked = new double[busCount][];
                busGenDgTotalTimeWorked = new double[busCount][];
                dgAvailable = new boolean[busCount][];
                dgInMaintenance = new boolean[busCount][];
                btActualCapacity = new double[busCount];
                btActualSOC = new double[busCount];
            }

            // --- failures bus/breaker ---
            for (int b = 0; b < busCount; b++) busAvailBefore[b] = buses.get(b).isAvailable();

            boolean brAvailBefore = breaker != null && breaker.isAvailable();
            boolean brClosedBefore = breaker != null && breaker.isClosed();

            if (breaker != null) breaker.updateFailureOneHour(considerFailures);
            for (PowerBus bus : buses) bus.updateFailureOneHour(considerFailures);

            boolean anyBusFailed = false;
            for (int b = 0; b < busCount; b++) {
                PowerBus bus = buses.get(b);
                busAvailAfter[b] = bus.isAvailable();
                busFailedThisHour[b] = busAvailBefore[b] && !busAvailAfter[b];
                anyBusFailed |= busFailedThisHour[b];
            }

            boolean brAvailAfter = breaker != null && breaker.isAvailable();
            boolean brFailedThisHour = breaker != null && brAvailBefore && !brAvailAfter;

            if (breaker != null && brClosedBefore && brFailedThisHour && anyBusFailed) {
                for (PowerBus bus : buses) if (bus.isAvailable()) bus.forceFailNow();
            } else if (breaker != null && brClosedBefore && anyBusFailed && !brFailedThisHour) {
                breaker.setClosed(false);
            }

            for (int b = 0; b < busCount; b++) busAlive[b] = buses.get(b).isAvailable();

            // equipment failures
            for (int b = 0; b < busCount; b++) {
                if (!busAlive[b]) continue;
                PowerBus bus = buses.get(b);
                for (WindTurbine wt : bus.getWindTurbines()) wt.updateFailureOneHour(considerFailures);
                for (DieselGenerator dg : bus.getDieselGenerators()) dg.updateFailureOneHour(considerFailures);
                Battery bt = bus.getBattery();
                if (bt != null) bt.updateFailureOneHour(considerFailures);
            }

            if (breaker != null && breaker.isAvailable()) breaker.addWorkTime(1);

            // --- per bus ---
            for (int b = 0; b < busCount; b++) {

                PowerBus bus = buses.get(b);

                double loadKw = bus.getLoadKw()[t];
                loadKwh += loadKw;

                double windPotKw = 0.0;
                double windToLoadKw;

                double dgProducedKw = 0.0;
                double dgToLoadKwLocal = 0.0;

                double btNetKw = 0.0;
                double wreLocal = 0.0;

                if (!busAlive[b]) {
                    for (DieselGenerator dg : bus.getDieselGenerators()) {
                        dg.stopWork();
                        dg.setCurrentLoad(0.0);
                    }

                    // трасса для "мертвой" шины
                    if (doTrace) {
                        busStatus[b] = false;
                        busLoadAtTime[b] = loadKw;
                        busGenWindAtTime[b] = 0.0;
                        busGenDgAtTime[b] = 0.0;
                        busGenBtAtTime[b] = 0.0;
                        busDefAtTime[b] = loadKw; // всё в дефицит

                        totalLoadAtTime += loadKw;
                        totalDefAtTime += loadKw;

                        List<DieselGenerator> dgListTrace = bus.getDieselGenerators();
                        int dgCount = dgListTrace.size();

                        busGenDgLoadKw[b] = new double[dgCount];
                        busGenDgHoursSinceMaintenance[b] = new double[dgCount];
                        busGenDgTimeWorked[b] = new double[dgCount];
                        busGenDgTotalTimeWorked[b] = new double[dgCount];
                        dgAvailable[b] = new boolean[dgCount];
                        dgInMaintenance[b] = new boolean[dgCount];

                        for (int i = 0; i < dgCount; i++) {
                            DieselGenerator dg = dgListTrace.get(i);
                            busGenDgLoadKw[b][i] = 0.0;
                            busGenDgHoursSinceMaintenance[b][i] = dg.getHoursSinceMaintenance();
                            busGenDgTimeWorked[b][i] = dg.getTimeWorked();
                            busGenDgTotalTimeWorked[b][i] = dg.getTotalTimeWorked();
                            dgAvailable[b][i] = dg.isAvailable();
                            dgInMaintenance[b][i] = dg.isInMaintenance();
                        }

                        Battery bt = bus.getBattery();
                        if (bt != null) {
                            btActualCapacity[b] = bt.getMaxCapacityKwh();
                            btActualSOC[b] = bt.getStateOfCharge();
                        } else {
                            btActualCapacity[b] = Double.NaN;
                            btActualSOC[b] = Double.NaN;
                        }
                    }

                    ensKwh += loadKw;
                    continue;
                }

                bus.addWorkTime(1);

                for (WindTurbine wt : bus.getWindTurbines()) {
                    double g = wt.getPotentialGenerationKw(v);
                    windPotKw += g;
                    if (wt.isAvailable()) wt.addWorkTime(1);
                }

                Battery battery = bus.getBattery();
                boolean btAvail = battery != null && battery.isAvailable();

                double dgPower = systemParameters.getDieselGeneratorPowerKw();
                double dgMaxKw = dgPower * SimulationConstants.DG_MAX_POWER;
                double dgMinKw = dgPower * SimulationConstants.DG_MIN_POWER;
                double perDgTarget = dgPower * SimulationConstants.DG_OPTIMAL_POWER;
                double tau = SimulationConstants.DG_START_DELAY_HOURS;

                // ====== Профицит ветра ======
                if (windPotKw >= loadKw - SimulationConstants.EPSILON) {

                    windToLoadKw = loadKw;

                    double surplus = Math.max(0.0, windPotKw - loadKw);

                    if (btAvail && battery.getStateOfCharge() < SimulationConstants.BATTERY_MAX_SOC) {
                        double cap = battery.getChargeCapacity(systemParameters);
                        double ch = Math.min(surplus, cap);
                        if (ch > SimulationConstants.EPSILON) {
                            battery.adjustCapacity(battery, ch, ch, false, considerDegradation);
                            btNetKw -= ch;
                            surplus -= ch;
                        }
                    }

                    wreLocal = Math.max(0.0, surplus);

                    // ===== IDLE (холостой ход) в профиците ветра =====
                    // ДГУ не несут нагрузку, но могут держаться в ХХ как резерв под потерю ветра (равно windToLoadKw=loadKw).
                    {
                        // сортируем ДГУ
                        List<DieselGenerator> dgList = bus.getDieselGenerators();
                        int dgCountAll = dgList.size();

                        DieselGenerator[] dgs = DG_SORT_BUF.get();
                        if (dgs == null || dgs.length != dgCountAll) {
                            dgs = new DieselGenerator[dgCountAll];
                            DG_SORT_BUF.set(dgs);
                        }
                        for (int k = 0; k < dgCountAll; k++) dgs[k] = dgList.get(k);
                        Arrays.sort(dgs, DieselGenerator.DISPATCH_COMPARATOR);

                        // сколько доступно
                        int available = 0;
                        for (int k = 0; k < dgCountAll; k++) {
                            if (dgs[k].isAvailable()) available++;
                        }

                        boolean[] keepOn = new boolean[dgCountAll];

                        // pCrit и требуемый резерв на "потерю ветра"
                        double pCrit = loadKw * (cat1 + SimulationConstants.DG_IDLE_K2 * cat2);
                        double windLoss = Math.min(windToLoadKw, pCrit);

                        double btFirm = 0.0;
                        if (btAvail) {
                            double btDisCap = battery.getDischargeCapacity(systemParameters);
                            // Если АКБ может удержать потерю ветра на время пуска ДГУ — ХХ не нужен
                            if (canBatteryBridge(battery, systemParameters, windLoss, tau, btDisCap)) {
                                btFirm = windLoss;
                            } else {
                                btFirm = btDisCap;
                            }
                        }

                        double reserveNeed = windLoss - btFirm;
                        reserveNeed += windLoss * SimulationConstants.DG_IDLE_MARGIN_PCT;
                        if (reserveNeed < 0.0) reserveNeed = 0.0;


                        int idleNeed = (reserveNeed > SimulationConstants.EPSILON)
                                ? (int) Math.ceil(reserveNeed / dgPower)
                                : 0; //todo нужна +1 если вращающийся резерв?)
                        if (idleNeed > available) idleNeed = available;

                        // 1) сначала используем уже working (без пуска)
                        for (int k = 0; k < dgCountAll && idleNeed > 0; k++) {
                            DieselGenerator dg = dgs[k];
                            if (!dg.isAvailable()) {
                                dg.stopWork();
                                dg.setCurrentLoad(0.0);
                                dg.setIdle(false);
                                dg.resetIdleTime();
                                continue;
                            }
                            if (!dg.isWorking()) continue;

                            double genKw = -0.15 * dgPower;

                            if (dg.getIdleTime() >= SimulationConstants.DG_MAX_IDLE_HOURS) {
                                genKw = Math.max(dgMinKw, 0.0);
                                dg.setIdle(false);
                                dg.resetIdleTime();
                            } else {
                                dg.incrementIdleTime();
                                dg.setIdle(true);
                            }

                            dg.setCurrentLoad(genKw);
                            dg.addWorkTime(1, 1);
                            dg.startWork();
                            keepOn[k] = true;

                            idleNeed--;
                        }

                        // 2) если нужно — запускаем новые
                        for (int k = 0; k < dgCountAll && idleNeed > 0; k++) {
                            DieselGenerator dg = dgs[k];
                            if (!dg.isAvailable()) {
                                dg.stopWork();
                                dg.setCurrentLoad(0.0);
                                dg.setIdle(false);
                                dg.resetIdleTime();
                                continue;
                            }
                            if (dg.isWorking()) continue;

                            dg.startWork(); // запуск

                            double genKw = -0.15 * dgPower;

                            if (dg.getIdleTime() >= SimulationConstants.DG_MAX_IDLE_HOURS) {
                                genKw = Math.max(dgMinKw, 0.0);
                                dg.setIdle(false);
                                dg.resetIdleTime();
                            } else {
                                dg.incrementIdleTime();
                                dg.setIdle(true);
                            }

                            dg.setCurrentLoad(genKw);
                            dg.addWorkTime(1, 6);
                            keepOn[k] = true;

                            idleNeed--;
                        }

                        // финализация: все, кто НЕ был выбран для ХХ/прожига, выключаем и обнуляем нагрузку
                        for (int k = 0; k < dgCountAll; k++) {
                            DieselGenerator dg = dgs[k];

                            if (!dg.isAvailable()) {
                                // если недоступна — гарантированно не должна "светиться" нагрузкой
                                dg.stopWork();
                                dg.setIdle(false);
                                dg.setCurrentLoad(0.0);
                                dg.resetIdleTime();
                                continue;
                            }

                            if (keepOn[k]) {
                                // оставляем включённой (у неё уже выставлен genKw < 0 или genKw >= dgMinKw)
                                continue;
                            }

                            // иначе: должна быть полностью выключена и p=0, чтобы не тянуть прошлочасовой currentLoad
                            dg.setCurrentLoad(0.0);
                            dg.stopWork();
                            dg.setIdle(false);
                            dg.resetIdleTime();
                        }

                    }
                }
                // ====== Дефицит ветра ======
                else {
                    windToLoadKw = windPotKw;
                    double deficitAfterWind = loadKw - windToLoadKw;

                    double btDisCap = 0.0;
                    if (btAvail) btDisCap = battery.getDischargeCapacity(systemParameters);

                    // ===== СОРТИРОВКА ДГУ БЕЗ new ArrayList КАЖДЫЙ ЧАС =====
                    List<DieselGenerator> dgList = bus.getDieselGenerators();
                    int dgCountAll = dgList.size();

                    DieselGenerator[] dgs = DG_SORT_BUF.get();
                    if (dgs == null || dgs.length != dgCountAll) {
                        dgs = new DieselGenerator[dgCountAll];
                        DG_SORT_BUF.set(dgs);
                    }
                    for (int k = 0; k < dgCountAll; k++) {
                        dgs[k] = dgList.get(k);
                    }
                    Arrays.sort(dgs, DieselGenerator.DISPATCH_COMPARATOR);
                    // ======================================================
                    // ===== tauEff: если в этом часу какая-то ДГУ только что ушла в ТО,
                    // то пуск других ДГУ считаем без задержки (tau=0)
                    boolean maintenanceStartedThisHour = false;
                    for (int k = 0; k < dgCountAll; k++) {
                        DieselGenerator dg = dgs[k];
                        if (!dg.isAvailable()) continue;

                        if (dg.isInMaintenance() && dg.getRepairTimeHours()==4) {
                            maintenanceStartedThisHour = true;
                            break;
                        }
                    }

                    double tauEff = maintenanceStartedThisHour ? 0.0 : tau;

                    int available = 0, ready = 0;
                    for (int k = 0; k < dgCountAll; k++) {
                        DieselGenerator dg = dgs[k];
                        if (dg.isAvailable()) available++;
                        if (dg.isWorking()) ready++;
                    }

                    int dgToUse;

                    if (available == 0) {
                        // только АКБ
                        double bt = btAvail ? Math.min(deficitAfterWind, btDisCap) : 0.0;
                        if (bt > SimulationConstants.EPSILON && btAvail) {
                            battery.adjustCapacity(battery, -bt, bt, false, considerDegradation);
                            btNetKw += bt;
                        }
                    } else {

                        boolean canUseOptimal = (perDgTarget * available >= deficitAfterWind);

                        int needed = canUseOptimal
                                ? (int) Math.ceil(deficitAfterWind / perDgTarget)
                                : (int) Math.ceil(deficitAfterWind / dgMaxKw);

                        int dgCount = Math.min(needed, available);
                        dgToUse = dgCount;

                        // подобрать минимальное i, где АКБ может покрыть старт/дефицит
                        for (int i = 0; i <= dgCount; i++) {

                            double btEnergy, btCurrent;
                            double startDef = 0.0, startEnergy = 0.0, steadyDef;

                            if (i == 0) {
                                btEnergy = deficitAfterWind;
                                btCurrent = deficitAfterWind;
                                steadyDef = deficitAfterWind;
                            } else {

                                int readyUsed = Math.min(i, ready);

                                double dgPowerReadyStart = readyUsed * dgMaxKw;

                                startDef = Math.max(0.0, deficitAfterWind - dgPowerReadyStart);
                                startEnergy = startDef * tauEff;

                                double perDgSteady = canUseOptimal
                                        ? Math.min(deficitAfterWind / i, perDgTarget)
                                        : Math.min(deficitAfterWind / i, dgMaxKw);

                                double totalSteady = perDgSteady * i;
                                steadyDef = Math.max(0.0, deficitAfterWind - totalSteady);

                                double steadyEnergy = steadyDef * (1.0 - tauEff);

                                btEnergy = startEnergy + steadyEnergy;
                                btCurrent = Math.max(startDef, steadyDef);
                            }

                            boolean useBatteryBase = btAvail
                                    && btDisCap > btEnergy - SimulationConstants.EPSILON
                                    && Battery.useBattery(systemParameters, battery, btEnergy, btDisCap);

                            boolean allowStartBridge = (i > 0)
                                    && btAvail
                                    && (steadyDef <= SimulationConstants.EPSILON)
                                    && (btDisCap > startEnergy - SimulationConstants.EPSILON);

                            boolean useBattery = useBatteryBase || allowStartBridge;

                            if (useBattery) {
                                double discharge = btEnergy;
                                double dischargeCur = btCurrent;

                                if (allowStartBridge && !useBatteryBase) {
                                    discharge = startEnergy;
                                    dischargeCur = startDef;
                                }

                                if (discharge > SimulationConstants.EPSILON) {
                                    battery.adjustCapacity(battery, -discharge, dischargeCur, false, considerDegradation);
                                    btNetKw += discharge;
                                }
                                dgToUse = i;
                                break;
                            }

                            if (i == dgCount) {
                                double bt = btAvail ? Math.min(deficitAfterWind, btDisCap) : 0.0;
                                if (bt > SimulationConstants.EPSILON && btAvail) {
                                    battery.adjustCapacity(battery, -bt, bt, false, considerDegradation);
                                    btNetKw += bt;
                                }
                                dgToUse = dgCount;
                            }
                        }

                        // ---- распределение по ДГУ (пуск + steady) + low-load + прожиг ----
                        int R = Math.min(ready, dgToUse);

                        double readyMaxStart = R * dgMaxKw;
                        double readyLoadStart = Math.min(deficitAfterWind, readyMaxStart);
                        double perReadyStart = (R > 0) ? (readyLoadStart / R) : 0.0;

                        double perDgSteady = 0.0;
                        if (dgToUse > 0) {
                            perDgSteady = canUseOptimal
                                    ? (deficitAfterWind / dgToUse)
                                    : Math.min(deficitAfterWind / dgToUse, dgMaxKw);
                            if (canUseOptimal && perDgSteady > perDgTarget) perDgSteady = perDgTarget;
                        }

                        boolean canCharge = btAvail
                                && battery.getStateOfCharge() < SimulationConstants.BATTERY_MAX_SOC - SimulationConstants.EPSILON;

                        double chargeCap = 0.0;
                        if (canCharge) {
                            chargeCap = battery.getChargeCapacity(systemParameters);
                            canCharge = chargeCap > SimulationConstants.EPSILON;
                        }


                        int used = 0;
                        double sumDiesel = 0.0;
                        double extraForCharge = 0.0;

                        boolean anyBurnThisHour = false;

                        for (int k = 0; k < dgCountAll; k++) {
                            DieselGenerator dg = dgs[k];

                            // ВАЖНО: здесь НЕ гасим неиспользованные (stopWork/resetIdleTime),
                            // иначе ломаем логику ХХ/wasWorking/idleTime.
                            if (!dg.isAvailable() || used >= dgToUse) {
                                dg.setCurrentLoad(0.0);
                                dg.setIdle(false);
                                continue;
                            }

                            boolean wasWorking = dg.isWorking();

                            double genKw = wasWorking
                                    ? (perReadyStart * tauEff + perDgSteady * (1.0 - tauEff))
                                    : (perDgSteady * (1.0 - tauEff));

                            if (genKw + SimulationConstants.EPSILON < dgMinKw) {

                                if (dg.getIdleTime() >= SimulationConstants.DG_MAX_IDLE_HOURS) {
                                    genKw = Math.max(genKw, dgMinKw);
                                    anyBurnThisHour = true;

                                    dg.setIdle(false);
                                    dg.resetIdleTime();

                                } else {
                                    dg.incrementIdleTime();
                                    dg.setIdle(true);
                                }

                            } else {
                                dg.setIdle(false);
                                dg.resetIdleTime();
                            }

                            if (genKw > dgMaxKw) genKw = dgMaxKw;

                            dg.setCurrentLoad(genKw);
                            dg.addWorkTime(1, wasWorking ? 1 : 6);
                            dg.startWork();

                            sumDiesel += genKw;
                            used++;
                        }

                        // ===== IDLE в дефиците ветра =====
                        // ХХ делаем только если ВЭУ реально участвует (есть что "терять")
                        if (windToLoadKw > SimulationConstants.EPSILON) {

                            double pCrit = loadKw * (cat1 + SimulationConstants.DG_IDLE_K2 * cat2);
                            double windLoss = Math.min(windToLoadKw, pCrit);

                            double btFirm = 0.0;
                            if (btAvail) {
                                // Если АКБ держит windLoss на время пуска ДГУ — ХХ не нужен
                                if (canBatteryBridge(battery, systemParameters, windLoss, tauEff, btDisCap)) {
                                    btFirm = windLoss;
                                } else {
                                    btFirm = btDisCap;
                                }
                            }


                            double dgFirm = 0.0;
                            for (int k = 0; k < dgCountAll; k++) {
                                DieselGenerator dg = dgs[k];
                                if (!dg.isAvailable()) continue;
                                if (!dg.isWorking()) continue;
                                if (dg.getCurrentLoad() < -SimulationConstants.EPSILON) continue;
                                dgFirm += dgMaxKw;
                            }

                            double reserveNeed = windLoss - (btFirm + dgFirm);
                            reserveNeed += windLoss * SimulationConstants.DG_IDLE_MARGIN_PCT;
                            if (reserveNeed < 0.0) reserveNeed = 0.0;

                            int idleNeed = (reserveNeed > SimulationConstants.EPSILON)
                                    ? (int) Math.ceil(reserveNeed / dgPower)
                                    : 0;

                            int idleCapable = 0;
                            for (int k = 0; k < dgCountAll; k++) {
                                DieselGenerator dg = dgs[k];
                                if (!dg.isAvailable()) continue;
                                if (dg.getCurrentLoad() > SimulationConstants.EPSILON) continue;
                                idleCapable++;
                            }
                            if (idleNeed > idleCapable) idleNeed = idleCapable;

                            // 1) сначала те, кто уже working
                            for (int k = 0; k < dgCountAll && idleNeed > 0; k++) {
                                DieselGenerator dg = dgs[k];
                                if (!dg.isAvailable()) continue;
                                if (dg.getCurrentLoad() > SimulationConstants.EPSILON) continue;
                                if (!dg.isWorking()) continue;

                                double genKw = -0.15 * dgPower;

                                if (dg.getIdleTime() >= SimulationConstants.DG_MAX_IDLE_HOURS) {
                                    genKw = Math.max(dgMinKw, 0.0);
                                    dg.setIdle(false);
                                    dg.resetIdleTime();
                                } else {
                                    dg.incrementIdleTime();
                                    dg.setIdle(true);
                                }

                                dg.setCurrentLoad(genKw);
                                dg.addWorkTime(1, 1);
                                dg.startWork();

                                idleNeed--;
                            }

                            // 2) затем допускаем пуск новых
                            for (int k = 0; k < dgCountAll && idleNeed > 0; k++) {
                                DieselGenerator dg = dgs[k];
                                if (!dg.isAvailable()) continue;
                                if (dg.getCurrentLoad() > SimulationConstants.EPSILON) continue;
                                if (dg.isWorking()) continue;

                                dg.startWork(); // запуск

                                double genKw = -0.15 * dgPower;

                                if (dg.getIdleTime() >= SimulationConstants.DG_MAX_IDLE_HOURS) {
                                    genKw = Math.max(dgMinKw, 0.0);
                                    dg.setIdle(false);
                                    dg.resetIdleTime();
                                } else {
                                    dg.incrementIdleTime();
                                    dg.setIdle(true);
                                }

                                dg.setCurrentLoad(genKw);
                                dg.addWorkTime(1, 6);

                                idleNeed--;
                            }
                        }

                        // ===== ROTATING RESERVE (N-1 по отказу 1 ДГУ) =====
                        if (considerRotationReserve) {

                            double btDisToLoadRR = Math.max(0.0, btNetKw);
                            double needFromDieselNow = loadKw - windToLoadKw - btDisToLoadRR;
                            if (needFromDieselNow < 0.0) needFromDieselNow = 0.0;

                            // online = ДГУ под нагрузкой (p>0) + ДГУ в ХХ (p<0), т.к. ХХ может мгновенно перейти в генерацию
                            int onlineCount = 0;
                            for (int k = 0; k < dgCountAll; k++) {
                                DieselGenerator dg = dgs[k];
                                if (!dg.isAvailable()) continue;
                                if (Math.abs(dg.getCurrentLoad()) > SimulationConstants.EPSILON) onlineCount++;
                            }

                            // Дефицит при отказе одного онлайн-ДГУ (остальные могут дать (onlineCount-1)*dgMaxKw)
                            double deficitAfterOneTrip = needFromDieselNow - Math.max(0, (onlineCount - 1)) * dgMaxKw;
                            if (deficitAfterOneTrip < 0.0) deficitAfterOneTrip = 0.0;

                            // Если АКБ может закрыть этот дефицит на время пуска (tauEff), то доп. ДГУ не нужна
                            boolean batteryCoversNminus1 = false;
                            if (btAvail && deficitAfterOneTrip > SimulationConstants.EPSILON) {
                                double btDisCapRR = battery.getDischargeCapacity(systemParameters);
                                batteryCoversNminus1 = canBatteryBridge(battery, systemParameters, deficitAfterOneTrip, tauEff, btDisCapRR);
                            }

                            boolean nMinusOneOk = (deficitAfterOneTrip <= SimulationConstants.EPSILON) || batteryCoversNminus1;

                            if (!nMinusOneOk && needFromDieselNow > SimulationConstants.EPSILON) {

                                int needOnline = (int) Math.ceil(needFromDieselNow / dgMaxKw) + 1;

                                int avail = 0;
                                for (int k = 0; k < dgCountAll; k++) if (dgs[k].isAvailable()) avail++;
                                if (needOnline > avail) needOnline = avail;

                                int add = needOnline - onlineCount;

                                if (add > 0) {
                                    // 1) сначала "горячие" (isWorking==true), но p==0
                                    for (int k = 0; k < dgCountAll && add > 0; k++) {
                                        DieselGenerator dg = dgs[k];
                                        if (!dg.isAvailable()) continue;
                                        if (!dg.isWorking()) continue;
                                        if (Math.abs(dg.getCurrentLoad()) > SimulationConstants.EPSILON) continue;

                                        dg.setCurrentLoad(dgMinKw);
                                        dg.setIdle(false);
                                        dg.resetIdleTime();

                                        // ВАЖНО: здесь начисляем время, потому что этот DG реально включили дополнительно
                                        dg.addWorkTime(1, 1);
                                        dg.startWork();

                                        add--;
                                        onlineCount++;
                                    }

                                    // 2) затем запускаем новые (isWorking==false, p==0)
                                    for (int k = 0; k < dgCountAll && add > 0; k++) {
                                        DieselGenerator dg = dgs[k];
                                        if (!dg.isAvailable()) continue;
                                        if (dg.isWorking()) continue;
                                        if (Math.abs(dg.getCurrentLoad()) > SimulationConstants.EPSILON) continue;

                                        dg.startWork(); // запуск

                                        dg.setCurrentLoad(dgMinKw);
                                        dg.setIdle(false);
                                        dg.resetIdleTime();

                                        // ВАЖНО: начисляем время запуска/час работы для реально добавленного DG
                                        dg.addWorkTime(1, 6);

                                        add--;
                                        onlineCount++;
                                    }
                                }

                                // Перераспределяем нагрузку по всем online ДГУ (включая тех, кто был в ХХ),
                                // НО НЕ начисляем addWorkTime второй раз (он уже был в основном распределении).
                                if (onlineCount > 0) {
                                    double per = needFromDieselNow / onlineCount;
                                    if (per > dgMaxKw) per = dgMaxKw;

                                    double sum = 0.0;
                                    int usedRR = 0;

                                    for (int k = 0; k < dgCountAll; k++) {
                                        DieselGenerator dg = dgs[k];
                                        if (!dg.isAvailable()) continue;

                                        if (Math.abs(dg.getCurrentLoad()) <= SimulationConstants.EPSILON) continue; // не online
                                        if (usedRR >= onlineCount) break;

                                        double genKw = per;

                                        // если из ХХ/прожига переводим в нагрузку — сбрасываем idle-флаги
                                        if (genKw + SimulationConstants.EPSILON >= dgMinKw) {
                                            dg.setIdle(false);
                                            dg.resetIdleTime();
                                        }

                                        if (genKw > dgMaxKw) genKw = dgMaxKw;

                                        dg.setCurrentLoad(genKw);
                                        // НЕТ dg.addWorkTime(...) здесь специально
                                        dg.startWork();

                                        sum += genKw;
                                        usedRR++;
                                    }

                                    // обновляем суммарную мощность ДГУ после перераспределения
                                    sumDiesel = sum;
                                }
                            }
                        }


                        // ===== Финализация статусов ДГУ за час =====
                        // Всё, что не под нагрузкой (p==0) и не в ХХ/прожиге (p!=0), выключаем.
                        for (int k = 0; k < dgCountAll; k++) {
                            DieselGenerator dg = dgs[k];
                            if (!dg.isAvailable()) continue;

                            double p = dg.getCurrentLoad();
                            if (Math.abs(p) > SimulationConstants.EPSILON) continue;

                            dg.stopWork();
                            dg.setIdle(false);
                            dg.resetIdleTime();
                        }

                        dgProducedKw = sumDiesel;

                        // ---- заряд от ДГУ ----
                        boolean allowChargeNow = canCharge && (considerChargeByDg || anyBurnThisHour);

                        double btDisToLoad = Math.max(0.0, btNetKw);
                        double needFromDieselToLoad = loadKw - windToLoadKw - btDisToLoad;
                        if (needFromDieselToLoad < 0.0) needFromDieselToLoad = 0.0;

                        double dieselSurplus = dgProducedKw - needFromDieselToLoad;
                        if (dieselSurplus < 0.0) dieselSurplus = 0.0;

                        if (allowChargeNow && dieselSurplus > SimulationConstants.EPSILON) {
                            double ch = Math.min(dieselSurplus, chargeCap);
                            if (ch > SimulationConstants.EPSILON) {
                                battery.adjustCapacity(battery, +ch, ch, true, considerDegradation);
                                btNetKw -= ch;
                                extraForCharge = ch;
                            }
                        }

                        dgToLoadKwLocal = Math.max(0.0, dgProducedKw - extraForCharge);
                    }
                }

                // ---- Fuel per hour ----
                for (DieselGenerator dg : bus.getDieselGenerators()) {
                    if (!dg.isAvailable()) continue;
                    double pSigned = dg.getCurrentLoad();
                    double loadLevel = Math.abs(pSigned) / dgPower;
                    if (loadLevel <= SimulationConstants.EPSILON) continue;
                    if (loadLevel > 1.0) loadLevel = 1.0;
                    fuelLiters += fuelLitersOneHour(loadLevel, dgPower);
                }

                // ---- totals ----
                double btDisToLoad = Math.max(0.0, btNetKw);

                wtToLoadKwh += windToLoadKw;
                dgToLoadKwh += dgToLoadKwLocal;
                btToLoadKwh += btDisToLoad;

                wreKwh += wreLocal;

                double supplied = windToLoadKw + dgToLoadKwLocal + btDisToLoad;
                double def = loadKw - supplied;
                if (def < 0.0) def = 0.0;
                ensKwh += def;

                // ===== fill trace arrays for bus b =====
                if (doTrace) {
                    busStatus[b] = busAlive[b];
                    busLoadAtTime[b] = loadKw;
                    busGenWindAtTime[b] = windToLoadKw;
                    busGenDgAtTime[b] = dgToLoadKwLocal;
                    busGenBtAtTime[b] = btNetKw;
                    busDefAtTime[b] = def;

                    totalLoadAtTime += loadKw;
                    totalDefAtTime += def;
                    totalWreAtTime += wreLocal;

                    List<DieselGenerator> dgListTrace = bus.getDieselGenerators();
                    int dgCount = dgListTrace.size();

                    busGenDgLoadKw[b] = new double[dgCount];
                    busGenDgHoursSinceMaintenance[b] = new double[dgCount];
                    busGenDgTimeWorked[b] = new double[dgCount];
                    busGenDgTotalTimeWorked[b] = new double[dgCount];
                    dgAvailable[b] = new boolean[dgCount];
                    dgInMaintenance[b] = new boolean[dgCount];

                    for (int i = 0; i < dgCount; i++) {
                        DieselGenerator dg = dgListTrace.get(i);
                        busGenDgLoadKw[b][i] = dg.getCurrentLoad();
                        busGenDgHoursSinceMaintenance[b][i] = dg.getHoursSinceMaintenance();
                        busGenDgTimeWorked[b][i] = dg.getTimeWorked();
                        busGenDgTotalTimeWorked[b][i] = dg.getTotalTimeWorked();
                        dgAvailable[b][i] = dg.isAvailable();
                        dgInMaintenance[b][i] = dg.isInMaintenance();
                    }

                    Battery bt = bus.getBattery();
                    if (bt != null) {
                        btActualCapacity[b] = bt.getMaxCapacityKwh();
                        btActualSOC[b] = bt.getStateOfCharge();
                    } else {
                        btActualCapacity[b] = Double.NaN;
                        btActualSOC[b] = Double.NaN;
                    }
                }
            }

            if (doTrace) {
                trace.add(new SimulationStepRecord(
                        t,
                        totalLoadAtTime,
                        totalDefAtTime,
                        totalWreAtTime,
                        busStatus,
                        busLoadAtTime,
                        busGenWindAtTime,
                        busGenDgAtTime,
                        busGenBtAtTime,
                        busDefAtTime,
                        busGenDgLoadKw,
                        busGenDgHoursSinceMaintenance,
                        busGenDgTimeWorked,
                        busGenDgTotalTimeWorked,
                        dgAvailable,
                        dgInMaintenance,
                        btActualCapacity,
                        btActualSOC
                ));
            }
        }

        // --- total failures by internal counters ---
        for (PowerBus bus : buses) {
            // шины
            failBus += bus.getFailureCount();

            // ВЭУ
            for (WindTurbine wt : bus.getWindTurbines()) {
                failWt += wt.getFailureCount();
            }

            // ДГУ
            for (DieselGenerator dg : bus.getDieselGenerators()) {
                failDg += dg.getFailureCount();
            }

            // АКБ (если есть)
            Battery bt = bus.getBattery();
            if (bt != null) {
                failBt += bt.getFailureCount();
            }
        }

        // автомат (если есть)
        if (breaker != null) {
            failBrk += breaker.getFailureCount();
        }

        long moto = 0;
        for (PowerBus bus : buses) {
            for (DieselGenerator dg : bus.getDieselGenerators()) {
                moto += dg.getTotalTimeWorked();
            }
        }

        return new SimulationMetrics(
                loadKwh,
                ensKwh,
                wreKwh,
                wtToLoadKwh,
                dgToLoadKwh,
                btToLoadKwh,
                fuelLiters,
                moto,
                trace,
                failBus,
                failDg,
                failWt,
                failBt,
                failBrk
        );

    }

    private static double fuelLitersOneHour(double loadLevel, double powerKw) {
        double k1 = K11 + (K12 / powerKw);
        double k2 = K21 + (K22 / powerKw);
        double k3 = K31 + (K32 / powerKw);

        double unitFuel = k1 * loadLevel * loadLevel + k2 * loadLevel + k3;
        double liters = 0.84 * powerKw * loadLevel * unitFuel;

        return Math.max(0.0, liters);
    }

    private static boolean canBatteryBridge(
            Battery battery,
            SystemParameters sp,
            double requiredPowerKw,
            double durationHours,
            double btDisCap // то, что у тебя уже считается через getDischargeCapacity(...)
    ) {
        if (battery == null || !battery.isAvailable()) return false;
        if (requiredPowerKw <= SimulationConstants.EPSILON) return true;
        if (durationHours <= 0.0) return true;

        double requiredEnergyKwh = requiredPowerKw * durationHours;

        // Ограничения по "току" (мощности) и по энергии (SOC) //TODO ПРОВЕРИТЬ НА ПРЕДМЕТ ХУЙНИ
        boolean powerOk = btDisCap + SimulationConstants.EPSILON >= requiredPowerKw;
        boolean energyOk = btDisCap + SimulationConstants.EPSILON >= requiredEnergyKwh;

        if (!powerOk || !energyOk) {
            return false;
        } else {
            return true;
        }

        // Доп. проверка вашей логикой useBattery (SOC/ограничения модели АКБ)
//        return Battery.useBattery(sp, battery, requiredEnergyKwh, btDisCap);
    }

}
