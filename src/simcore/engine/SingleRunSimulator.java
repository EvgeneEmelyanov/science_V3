package simcore.engine;

import simcore.config.SimulationConfig;
import simcore.config.SimulationConstants;
import simcore.config.SystemParameters;
import simcore.model.*;

import java.util.ArrayList;
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
        boolean considerChargeByDg = config.isConsiderSortDiesel(); // TODO rename

        PowerSystem powerSystem = new PowerSystemBuilder().build(systemParameters, input.getTotalLoadKw());
        List<PowerBus> buses = powerSystem.getBuses();
        int busCount = buses.size();
        Breaker breaker = powerSystem.getTieBreaker();

        // RNG: одинаковый seed на m-итерацию для всех theta
        Random rndWT = new Random(seed + 1);
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

        // общие суммы (энергия за 1ч == мощность кВт при dt=1ч)
        double loadKwh = 0.0;
        double ensKwh = 0.0;
        double wreKwh = 0.0;

        double wtToLoadKwh = 0.0;
        double dgToLoadKwh = 0.0;
        double btToLoadKwh = 0.0;

        double fuelLiters = 0.0;

        List<SimulationStepRecord> trace = traceEnabled ? new ArrayList<>() : null;

        boolean[] busAvailBefore = new boolean[busCount];
        boolean[] busAvailAfter = new boolean[busCount];
        boolean[] busFailedThisHour = new boolean[busCount];
        boolean[] busAlive = new boolean[busCount];

        for (int t = 0; t < n; t++) {

            double v = wind[t];
// ===== trace buffers for hour t =====
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

                // ВАЖНО: замените на правильный геттер у вас (вы показывали getLoadKw() в StepRecord)
                double loadKw = bus.getLoadKw()[t];

                loadKwh += loadKw;

                double windPotKw = 0.0;
                double windToLoadKw = 0.0;

                double dgProducedKw = 0.0;     // фактическая генерация ДГУ (включая прожиг)
                double dgToLoadKwLocal = 0.0;  // сколько из ДГУ пошло в нагрузку

                double btNetKw = 0.0;          // +разряд, -заряд
                double wreLocal = 0.0;

                if (!busAlive[b]) {
                    for (DieselGenerator dg : bus.getDieselGenerators()) {
                        dg.stopWork();
                        dg.setCurrentLoad(0.0);
                    }
                    // supply=0 => весь load в ENS
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

                    // дизели не нужны
                    for (DieselGenerator dg : bus.getDieselGenerators()) {
                        dg.stopWork();
                        dg.setCurrentLoad(0.0);
                        dg.setIdle(false);
                        dg.resetIdleTime();
                    }
                }
                // ====== Дефицит ветра ======
                else {

                    windToLoadKw = windPotKw;
                    double deficitAfterWind = loadKw - windToLoadKw;

                    double btDisCap = 0.0;
                    if (btAvail) btDisCap = battery.getDischargeCapacity(systemParameters);

                    // сортировка ДГУ КАЖДЫЙ ЧАС
                    List<DieselGenerator> dgs = new ArrayList<>(bus.getDieselGenerators());
                    dgs.sort(DieselGenerator.DISPATCH_COMPARATOR);

                    int available = 0, ready = 0;
                    for (DieselGenerator dg : dgs) {
                        if (dg.isAvailable()) available++;
                        if (dg.isWorking()) ready++;
                    }

                    int dgToUse = 0;

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

                                // В ПУСКЕ готовые могут кратковременно идти до dgMaxKw (НЕ до 80%)
                                double dgPowerReadyStart = readyUsed * dgMaxKw;

                                startDef = Math.max(0.0, deficitAfterWind - dgPowerReadyStart);
                                startEnergy = startDef * tau;

                                // Установившийся режим ограничиваем perDgTarget (если возможно)
                                double perDgSteady = canUseOptimal
                                        ? Math.min(deficitAfterWind / i, perDgTarget)
                                        : Math.min(deficitAfterWind / i, dgMaxKw);

                                double totalSteady = perDgSteady * i;
                                steadyDef = Math.max(0.0, deficitAfterWind - totalSteady);

                                double steadyEnergy = steadyDef * (1.0 - tau);

                                btEnergy = startEnergy + steadyEnergy;
                                btCurrent = Math.max(startDef, steadyDef);
                            }

                            boolean useBatteryBase = btAvail
                                    && btDisCap > btEnergy - SimulationConstants.EPSILON
                                    && Battery.useBattery(systemParameters, battery, btEnergy, btDisCap);

                            // Ваше требование: allowStartBridge без условия (startEnergy > EPSILON)
                            boolean allowStartBridge = (i > 0)
                                    && btAvail
                                    && (steadyDef <= SimulationConstants.EPSILON)
                                    && (btDisCap > startEnergy - SimulationConstants.EPSILON);

                            boolean useBattery = useBatteryBase || allowStartBridge;

                            if (useBattery) {
                                double discharge = btEnergy;
                                double dischargeCur = btCurrent;

                                // если нельзя разряжать "на резерв", но старт нужно мостить:
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
                                // АКБ на максимум
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

                        // Пуск: готовые могут взять до dgMaxKw
                        double readyMaxStart = R * dgMaxKw;
                        double readyLoadStart = Math.min(deficitAfterWind, readyMaxStart);
                        double perReadyStart = (R > 0) ? (readyLoadStart / R) : 0.0;

                        // steady: ограничение 80% (если возможно)
                        double perDgSteady = 0.0;
                        if (dgToUse > 0) {
                            perDgSteady = canUseOptimal
                                    ? (deficitAfterWind / dgToUse)
                                    : Math.min(deficitAfterWind / dgToUse, dgMaxKw);
                            if (canUseOptimal && perDgSteady > perDgTarget) perDgSteady = perDgTarget;
                        }

                        // можно ли заряжать АКБ
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

                        for (DieselGenerator dg : dgs) {

                            if (!dg.isAvailable() || used >= dgToUse) {
                                dg.setCurrentLoad(0.0);
                                dg.stopWork();
                                dg.setIdle(false);
                                dg.resetIdleTime();
                                continue;
                            }

                            boolean wasWorking = dg.isWorking();

                            double genKw = wasWorking
                                    ? (perReadyStart * tau + perDgSteady * (1.0 - tau))
                                    : (perDgSteady * (1.0 - tau));

                            // --- low-load (<30%) + прожиг ---
                            // Режим низкой загрузки общий: idleTime
                            if (genKw + SimulationConstants.EPSILON < dgMinKw) {

                                if (dg.getIdleTime() >= SimulationConstants.DG_MAX_IDLE_HOURS) {
                                    // прожиг 1 час, затем сброс idle state, чтобы на след.час снова можно было быть low-load
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
                            dg.addWorkTime(1, wasWorking ? 1 : 6); // TODO: вынести 6 в константу
                            dg.startWork();

                            sumDiesel += genKw;
                            used++;
                        }

                        dgProducedKw = sumDiesel;

                        // ---- заряд от ДГУ ----
                        // 1) если considerChargeByDg=true — всегда, когда есть избыток
                        // 2) если considerChargeByDg=false — всё равно заряжаем при прожиге (anyBurnThisHour)
                        boolean allowChargeNow = canCharge && (considerChargeByDg || anyBurnThisHour);

                        // Сколько реально надо в нагрузку с учётом ветра и разряда АКБ
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

                // ---- Fuel per hour: по модулю текущей мощности ДГУ (учитывает отрицательные режимы, если появятся) ----
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
                    busGenWindAtTime[b] = windToLoadKw;      // сколько ветра реально пошло в нагрузку
                    busGenDgAtTime[b] = dgToLoadKwLocal;     // сколько ДГУ реально пошло в нагрузку
                    busGenBtAtTime[b] = btNetKw;             // +разряд, -заряд (как у вас принято)
                    busDefAtTime[b] = def;

                    totalLoadAtTime += loadKw;
                    totalDefAtTime += def;
                    totalWreAtTime += wreLocal;

                    // DG detail arrays (по всем ДГУ на шине)
                    List<DieselGenerator> dgList = bus.getDieselGenerators();
                    int dgCount = dgList.size();

                    busGenDgLoadKw[b] = new double[dgCount];
                    busGenDgHoursSinceMaintenance[b] = new double[dgCount];
                    busGenDgTimeWorked[b] = new double[dgCount];
                    busGenDgTotalTimeWorked[b] = new double[dgCount];
                    dgAvailable[b] = new boolean[dgCount];
                    dgInMaintenance[b] = new boolean[dgCount];

                    for (int i = 0; i < dgCount; i++) {
                        DieselGenerator dg = dgList.get(i);
                        busGenDgLoadKw[b][i] = dg.getCurrentLoad();
                        busGenDgHoursSinceMaintenance[b][i] = dg.getHoursSinceMaintenance();
                        busGenDgTimeWorked[b][i] = dg.getTimeWorked();
                        busGenDgTotalTimeWorked[b][i] = dg.getTotalTimeWorked();
                        dgAvailable[b][i] = dg.isAvailable();
                        dgInMaintenance[b][i] = dg.isInMaintenance();
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
                        dgInMaintenance
                ));
            }

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
                trace
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
}
