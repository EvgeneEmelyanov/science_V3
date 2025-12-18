package simcore.engine;

import simcore.config.SimulationConfig;
import simcore.config.SimulationConstants;
import simcore.config.SystemParameters;
import simcore.model.*;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

public class SimulationEngine {

    // ===== Fuel model constants (из старого кода) =====
    private static final double K11 = 0.0185;
    private static final double K21 = -0.0361;
    private static final double K31 = 0.2745;
    private static final double K12 = 5.3978;
    private static final double K22 = -11.4831;
    private static final double K32 = 11.6284;

    private static final Locale RU = new Locale("ru", "RU");

    // Настройки анализа (как в старом ResultAnalyzer)
    private static final boolean REMOVE_OUTLIERS = false;
    private static final double T_SCORE = 1.96; // 95%
    private static final double REL_ERR = 0.10; // 10%

    private final SimulationConfig config;
    private final SystemParameters systemParameters;
    private final double[] totalLoadKw;

    public SimulationEngine(SimulationConfig config,
                            SystemParameters systemParameters,
                            double[] totalLoadKw) {
        this.config = config;
        this.systemParameters = systemParameters;
        this.totalLoadKw = totalLoadKw;
    }

    public SimulationSummary runMonteCarlo()
            throws InterruptedException, ExecutionException, IOException {

        final int iterations = config.getIterations();
        final int threads = config.getThreads();

        final String resultsCsvPath = "D:/simulation_results.csv";

        if (iterations == 1) {
            List<SimulationStepRecord> trace = new ArrayList<>();
            SimulationResult r = runSingleSimulation(0, trace);

            SimulationTraceExporter.exportToCsv("D:/simulation_trace.csv", trace);

            ResultsCsvWriter.write(resultsCsvPath, systemParameters, config, List.of(r));
            ResultsCsvWriter.appendSummary(resultsCsvPath, systemParameters, config, List.of(r));

            return new SimulationSummary(
                    r.ensKwh, r.ensKwh, 1,
                    r.wtKwh, r.dgKwh, r.btKwh,
                    r.wreKwh, r.fuelLiters, r.motoHours
            );
        }

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        List<Future<SimulationResult>> futures = new ArrayList<>(iterations);

        for (int i = 0; i < iterations; i++) {
            final int idx = i;
            futures.add(executor.submit(() -> runSingleSimulation(idx, null)));
        }

        List<SimulationResult> results = new ArrayList<>(iterations);

        double sumEns = 0.0, sumWt = 0.0, sumDg = 0.0, sumBt = 0.0, sumWre = 0.0, sumFuel = 0.0;
        long sumMoto = 0;

        for (Future<SimulationResult> f : futures) {
            SimulationResult r = f.get();
            results.add(r);

            sumEns += r.ensKwh;
            sumWt += r.wtKwh;
            sumDg += r.dgKwh;
            sumBt += r.btKwh;
            sumWre += r.wreKwh;
            sumFuel += r.fuelLiters;
            sumMoto += r.motoHours;
        }

        executor.shutdown();

        ResultsCsvWriter.write(resultsCsvPath, systemParameters, config, results);
        ResultsCsvWriter.appendSummary(resultsCsvPath, systemParameters, config, results);

        return new SimulationSummary(
                sumEns,
                sumEns / iterations,
                iterations,
                sumWt,
                sumDg,
                sumBt,
                sumWre,
                sumFuel,
                sumMoto
        );
    }

    private SimulationResult runSingleSimulation(int iterationIndex, List<SimulationStepRecord> trace) {

        final double[] wind = config.getWindMs();
        final int n = wind.length;

        final boolean considerFailures = config.isConsiderFailures();
        final boolean considerDegradation = config.isConsiderBatteryDegradation();
        final boolean considerChargeByDg = config.isConsiderSortDiesel(); // TODO rename

        PowerSystem powerSystem = new PowerSystemBuilder().build(systemParameters, totalLoadKw);

        final List<PowerBus> buses = powerSystem.getBuses();
        final int busCount = buses.size();
        final Breaker breaker = powerSystem.getTieBreaker();

        final double dgPower = systemParameters.getDieselGeneratorPowerKw();
        final double dgMaxLoad = dgPower * SimulationConstants.DG_MAX_POWER;
        final double perDgTarget = dgPower * SimulationConstants.DG_OPTIMAL_POWER;
        final double minKw = dgPower * SimulationConstants.DG_MIN_POWER;
        final double tau = SimulationConstants.DG_START_DELAY_HOURS;
        final int maxLowLoadHours = SimulationConstants.DG_MAX_IDLE_HOURS;

        double loadKwh = 0.0;
        double ensKwh = 0.0;
        double wreKwh = 0.0;

        double wtToLoadKwh = 0.0;
        double dgToLoadKwh = 0.0;
        double btToLoadKwh = 0.0;

        double fuelLiters = 0.0;

        long baseSeed = 1_000_000L + iterationIndex * 10_000L;
        Random rndWT = new Random(baseSeed + 1);
        Random rndDG = new Random(baseSeed + 2);
        Random rndBT = new Random(baseSeed + 3);
        Random rndBUS = new Random(baseSeed + 4);
        Random rndBRK = new Random(baseSeed + 5);

        if (breaker != null) breaker.initFailureModel(rndBRK, considerFailures);

        for (PowerBus bus : buses) {
            bus.initFailureModel(rndBUS, considerFailures);
            for (WindTurbine wt : bus.getWindTurbines()) wt.initFailureModel(rndWT, considerFailures);
            for (DieselGenerator dg : bus.getDieselGenerators()) dg.initFailureModel(rndDG, considerFailures);
            Battery bt = bus.getBattery();
            if (bt != null) bt.initFailureModel(rndBT, considerFailures);
        }

        boolean[] busAvailBefore = new boolean[busCount];
        boolean[] busAvailAfter = new boolean[busCount];
        boolean[] busFailedHour = new boolean[busCount];
        boolean[] busAlive = new boolean[busCount];

        for (int t = 0; t < n; t++) {

            final double v = wind[t];

            // failures bus/breaker
            for (int b = 0; b < busCount; b++) busAvailBefore[b] = buses.get(b).isAvailable();

            final boolean brAvailBefore = breaker != null && breaker.isAvailable();
            final boolean brClosedBefore = breaker != null && breaker.isClosed();

            if (breaker != null) breaker.updateFailureOneHour(considerFailures);
            for (PowerBus bus : buses) bus.updateFailureOneHour(considerFailures);

            boolean anyBusFailed = false;
            for (int b = 0; b < busCount; b++) {
                PowerBus bus = buses.get(b);
                busAvailAfter[b] = bus.isAvailable();
                busFailedHour[b] = busAvailBefore[b] && !busAvailAfter[b];
                anyBusFailed |= busFailedHour[b];
            }

            final boolean brAvailAfter = breaker != null && breaker.isAvailable();
            final boolean brFailedThisHour = breaker != null && brAvailBefore && !brAvailAfter;

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

            // per bus
            for (int b = 0; b < busCount; b++) {

                PowerBus bus = buses.get(b);
                final double loadKw = bus.getLoadKw()[t];
                loadKwh += loadKw;

                double windPotKw = 0.0;
                double windToLoadKw = 0.0;

                double dgProducedKw = 0.0;
                double dgToLoadKwLocal = 0.0;

                double btNetKw = 0.0;
                double chargedFromDgKw = 0.0;

                double wreLocal = 0.0;

                if (busAlive[b]) {
                    bus.addWorkTime(1);
                    for (WindTurbine wt : bus.getWindTurbines()) {
                        double g = wt.getPotentialGenerationKw(v);
                        windPotKw += g;
                        if (wt.isAvailable()) wt.addWorkTime(1);
                    }
                }

                Battery battery = bus.getBattery();
                boolean btAvail = battery != null && battery.isAvailable();

                if (!busAlive[b]) {
                    for (DieselGenerator dg : bus.getDieselGenerators()) {
                        dg.stopWork();
                        dg.setCurrentLoad(0.0);
                    }
                }
                else if (windPotKw >= loadKw - SimulationConstants.EPSILON) {
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

                    for (DieselGenerator dg : bus.getDieselGenerators()) {
                        dg.stopWork();
                        dg.setCurrentLoad(0.0);
                    }
                }
                else {
                    windToLoadKw = windPotKw;
                    final double deficitAfterWind = loadKw - windToLoadKw;

                    double btDisCap = 0.0;
                    if (btAvail) btDisCap = battery.getDischargeCapacity(systemParameters);

                    List<DieselGenerator> dgs = new ArrayList<>(bus.getDieselGenerators());
                    dgs.sort(DieselGenerator.DISPATCH_COMPARATOR);

                    int available = 0, ready = 0;
                    for (DieselGenerator dg : dgs) {
                        if (dg.isAvailable()) available++;
                        if (dg.isWorking()) ready++;
                    }

                    if (available == 0) {
                        double bt = btAvail ? Math.min(deficitAfterWind, btDisCap) : 0.0;
                        if (bt > SimulationConstants.EPSILON && btAvail) {
                            battery.adjustCapacity(battery, -bt, bt, false, considerDegradation);
                        }
                        btNetKw += bt;

                        dgProducedKw = Math.max(0.0, deficitAfterWind - bt);
                        dgToLoadKwLocal = dgProducedKw;
                    } else {

                        boolean canUseOptimal = (perDgTarget * available >= deficitAfterWind);

                        int needed = canUseOptimal
                                ? (int) Math.ceil(deficitAfterWind / perDgTarget)
                                : (int) Math.ceil(deficitAfterWind / dgMaxLoad);

                        int dgCount = Math.min(needed, available);
                        int dgToUse = dgCount;

                        for (int i = 0; i <= dgCount; i++) {

                            double btEnergy, btCurrent;
                            double startDef = 0.0, startEnergy = 0.0, steadyDef = 0.0;

                            if (i == 0) {
                                btEnergy = deficitAfterWind;
                                btCurrent = deficitAfterWind;
                                steadyDef = deficitAfterWind;
                            } else {
                                int readyUsed = Math.min(i, ready);

                                double dgPowerReadyStart = readyUsed * dgMaxLoad;

                                startDef = Math.max(0.0, deficitAfterWind - dgPowerReadyStart);
                                startEnergy = startDef * tau;

                                double perDgLoad = canUseOptimal
                                        ? Math.min(deficitAfterWind / i, perDgTarget)
                                        : Math.min(deficitAfterWind / i, dgMaxLoad);

                                double totalSteady = perDgLoad * i;

                                steadyDef = Math.max(0.0, deficitAfterWind - totalSteady);
                                double steadyEnergy = steadyDef * (1.0 - tau);

                                btEnergy = startEnergy + steadyEnergy;
                                btCurrent = Math.max(startDef, steadyDef);
                            }

                            boolean useBatteryBase = btAvail
                                    && btDisCap > btEnergy - SimulationConstants.EPSILON
                                    && Battery.useBattery(systemParameters, battery, btEnergy, btDisCap);

                            boolean allowStartBridge = (i > 0)
                                    && (steadyDef <= SimulationConstants.EPSILON)
                                    && btAvail
                                    && (btDisCap > startEnergy - SimulationConstants.EPSILON);

                            boolean useBattery = useBatteryBase || allowStartBridge;

                            if (useBattery) {
                                double discharge = btEnergy;
                                double dischargeCur = btCurrent;

                                if (allowStartBridge && !useBatteryBase) {
                                    discharge = startEnergy;
                                    dischargeCur = startDef;
                                }

                                battery.adjustCapacity(battery, -discharge, dischargeCur, false, considerDegradation);
                                btNetKw += discharge;
                                dgToUse = i;
                                break;
                            }

                            if (i == dgCount) {
                                double bt = btAvail ? Math.min(deficitAfterWind, btDisCap) : 0.0;
                                if (bt > SimulationConstants.EPSILON && btAvail) {
                                    battery.adjustCapacity(battery, -bt, bt, false, considerDegradation);
                                }
                                btNetKw += bt;
                                dgToUse = dgCount;
                            }
                        }

                        int R = Math.min(ready, dgToUse);

                        double readyMaxStart = R * dgMaxLoad;
                        double readyLoadStart = Math.min(deficitAfterWind, readyMaxStart);
                        double perReadyStart = (R > 0) ? (readyLoadStart / R) : 0.0;

                        double perDgSteady = 0.0;
                        if (dgToUse > 0) {
                            perDgSteady = canUseOptimal
                                    ? (deficitAfterWind / dgToUse)
                                    : Math.min(deficitAfterWind / dgToUse, dgMaxLoad);
                        }

                        double chargeCap = 0.0;
                        boolean canCharge = false;
                        if (btAvail) {
                            chargeCap = battery.getChargeCapacity(systemParameters);
                            canCharge = chargeCap > SimulationConstants.EPSILON
                                    && battery.getStateOfCharge() < SimulationConstants.BATTERY_MAX_SOC - SimulationConstants.EPSILON;
                        }

                        int used = 0;
                        double sumDiesel = 0.0;
                        boolean anyBurn = false;

                        for (DieselGenerator dg : dgs) {

                            if (!dg.isAvailable() || used >= dgToUse) {
                                dg.setCurrentLoad(0.0);
                                dg.stopWork();
                                continue;
                            }

                            boolean wasWorking = dg.isWorking();

                            double genKw = wasWorking
                                    ? (perReadyStart * tau + perDgSteady * (1.0 - tau))
                                    : (perDgSteady * (1.0 - tau));

                            if (genKw + SimulationConstants.EPSILON < minKw) {
                                if (dg.getIdleTime() >= maxLowLoadHours) {
                                    genKw = Math.max(genKw, minKw);
                                    anyBurn = true;
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

                            if (genKw > dgMaxLoad) genKw = dgMaxLoad;

                            dg.setCurrentLoad(genKw);
                            dg.addWorkTime(1, wasWorking ? 1 : 6);
                            dg.startWork();

                            sumDiesel += genKw;
                            used++;
                        }

                        dgProducedKw = sumDiesel;

                        double btDisToLoad = Math.max(0.0, btNetKw);
                        double needFromDiesel = loadKw - windToLoadKw - btDisToLoad;
                        if (needFromDiesel < 0.0) needFromDiesel = 0.0;

                        double surplusDiesel = dgProducedKw - needFromDiesel;
                        if (surplusDiesel < 0.0) surplusDiesel = 0.0;

                        boolean allowChargeNow = canCharge && (considerChargeByDg || anyBurn);

                        if (allowChargeNow && surplusDiesel > SimulationConstants.EPSILON) {
                            double ch = Math.min(surplusDiesel, chargeCap);
                            if (ch > SimulationConstants.EPSILON) {
                                battery.adjustCapacity(battery, +ch, ch, true, considerDegradation);
                                btNetKw -= ch;
                                chargedFromDgKw = ch;
                            }
                        }

                        dgToLoadKwLocal = Math.max(0.0, dgProducedKw - chargedFromDgKw);
                    }
                }

                // Fuel: abs(currentLoad)/dgPower
                if (busAlive[b]) {
                    for (DieselGenerator dg : bus.getDieselGenerators()) {
                        if (!dg.isAvailable()) continue;
                        double pSigned = dg.getCurrentLoad();
                        double loadLevel = Math.abs(pSigned) / dgPower;
                        if (loadLevel <= SimulationConstants.EPSILON) continue;
                        if (loadLevel > 1.0) loadLevel = 1.0;
                        fuelLiters += fuelLitersOneHour(loadLevel, dgPower);
                    }
                }

                // totals
                double btDisToLoad = Math.max(0.0, btNetKw);

                wtToLoadKwh += windToLoadKw;
                dgToLoadKwh += dgToLoadKwLocal;
                btToLoadKwh += btDisToLoad;

                wreKwh += wreLocal;

                double supplied = windToLoadKw + dgToLoadKwLocal + btDisToLoad;
                double def = loadKw - supplied;
                if (def < 0.0) def = 0.0;

                ensKwh += def;
            }
        }

        long motoHours = 0;
        for (PowerBus bus : buses) {
            for (DieselGenerator dg : bus.getDieselGenerators()) {
                motoHours += dg.getTotalTimeWorked();
            }
        }

        return new SimulationResult(
                iterationIndex,
                loadKwh,
                ensKwh,
                wreKwh,
                wtToLoadKwh,
                dgToLoadKwh,
                btToLoadKwh,
                fuelLiters,
                motoHours,
                considerFailures,
                considerDegradation,
                considerChargeByDg,
                dgPower
        );
    }

    private static double fuelLitersOneHour(double loadLevel, double powerKw) {
        if (loadLevel <= 0.0) return 0.0;

        double k1 = K11 + (K12 / powerKw);
        double k2 = K21 + (K22 / powerKw);
        double k3 = K31 + (K32 / powerKw);

        double unitFuel = k1 * loadLevel * loadLevel + k2 * loadLevel + k3;
        double liters = 0.84 * powerKw * loadLevel * unitFuel;

        return Math.max(0.0, liters);
    }

    // ========================= CSV RESULTS + MONTE CARLO ANALYSIS =========================

    private static final class ResultsCsvWriter {

        private static final String[] COLS = {
                "iter", "ENS_kWh", "Fuel_L", "Moto_h", "WRE_kWh", "WT_%", "DG_%", "BT_%", "TLE_%", "ENS_ppt"
        };

        static void write(String filePath,
                          SystemParameters sp,
                          SimulationConfig cfg,
                          List<SimulationResult> results) throws IOException {

            SimulationResult r0 = results.get(0);

            try (BufferedWriter w = new BufferedWriter(new FileWriter(filePath, false))) {

                // ===== "шапка параметров" как в старом коде (1-2 строки) =====
                w.write(buildConfigHeaderLine(sp, cfg, r0));
                w.newLine();

                // ===== заголовок колонок =====
                w.write(String.join(";", COLS));
                w.newLine();

                for (SimulationResult r : results) {
                    w.write(line(r));
                    w.newLine();
                }
            }
        }

        static void appendSummary(String filePath,
                                  SystemParameters sp,
                                  SimulationConfig cfg,
                                  List<SimulationResult> results) throws IOException {

            double[] ens = new double[results.size()];
            double[] fuel = new double[results.size()];
            double[] moto = new double[results.size()];
            double[] wre = new double[results.size()];
            double[] wtPct = new double[results.size()];
            double[] dgPct = new double[results.size()];
            double[] btPct = new double[results.size()];
            double[] tlePct = new double[results.size()];
            double[] ensPpt = new double[results.size()];

            for (int i = 0; i < results.size(); i++) {
                SimulationResult r = results.get(i);

                ens[i] = r.ensKwh;
                fuel[i] = r.fuelLiters;
                moto[i] = r.motoHours;
                wre[i] = r.wreKwh;

                wtPct[i] = safePct(r.wtKwh, r.loadKwh);
                dgPct[i] = safePct(r.dgKwh, r.loadKwh);
                btPct[i] = safePct(r.btKwh, r.loadKwh);
                tlePct[i] = safePct(r.wreKwh, r.loadKwh);

                ensPpt[i] = (r.loadKwh > SimulationConstants.EPSILON)
                        ? (100000.0 * r.ensKwh / r.loadKwh)
                        : 0.0;
            }

            Stats ensS = Stats.of(ens);
            Stats fuelS = Stats.of(fuel);
            Stats motoS = Stats.of(moto);
            Stats wreS = Stats.of(wre);
            Stats wtS = Stats.of(wtPct);
            Stats dgS = Stats.of(dgPct);
            Stats btS = Stats.of(btPct);
            Stats tleS = Stats.of(tlePct);
            Stats pptS = Stats.of(ensPpt);

            try (BufferedWriter w = new BufferedWriter(new FileWriter(filePath, true))) {
                w.newLine();
                w.write("#SUMMARY");
                w.newLine();

                w.write("row;" + String.join(";", Arrays.copyOfRange(COLS, 1, COLS.length)));
                w.newLine();

                w.write("MEAN;" + join(ss(ensS, fuelS, motoS, wreS, wtS, dgS, btS, tleS, pptS), s -> fmt2(s.mean)));
                w.newLine();
                w.write("CI_LO;" + join(ss(ensS, fuelS, motoS, wreS, wtS, dgS, btS, tleS, pptS), s -> fmt2(s.ciLo)));
                w.newLine();
                w.write("CI_HI;" + join(ss(ensS, fuelS, motoS, wreS, wtS, dgS, btS, tleS, pptS), s -> fmt2(s.ciHi)));
                w.newLine();
                w.write("REQ_N;" + join(ss(ensS, fuelS, motoS, wreS, wtS, dgS, btS, tleS, pptS), s -> String.valueOf(s.requiredN)));
                w.newLine();
            }
        }

        private static String buildConfigHeaderLine(SystemParameters sp, SimulationConfig cfg, SimulationResult r0) {

            // Аналог старого: busSystem; N; DG:PxN; WT:PxN; BT:cap x busCount; flags...
            // Часть параметров в новых классах может отсутствовать — оставлено то, что точно есть
            // + можно расширять по мере появления в config/systemParameters.

            int busCount = cfg.getWindMs().length;// если нет — замените на 1/2 или уберите
            int dgNum = sp.getTotalDieselGeneratorCount() / busCount; // если нет — уберите/замените
            int wtNum = sp.getTotalWindTurbineCount() / busCount;     // если нет — уберите/замените
            double wtPow = sp.getWindTurbinePowerKw();
            double dgPow = sp.getDieselGeneratorPowerKw();
            double btCap = sp.getBatteryCapacityKwhPerBus();

            return String.format(
                    RU,
                    "CFG; N:%d; thr:%d; DG:%.0fx%d; WT:%.0fx%d; BT:%.0fx%d; fail:%b; deg:%b; chDg:%b;",
                    cfg.getIterations(),
                    cfg.getThreads(),
                    dgPow, dgNum,
                    wtPow, wtNum,
                    btCap, busCount,
                    r0.considerFailures,
                    r0.considerDegradation,
                    r0.considerChargeByDg
            );
        }

        private static String line(SimulationResult r) {
            double wtPct = safePct(r.wtKwh, r.loadKwh);
            double dgPct = safePct(r.dgKwh, r.loadKwh);
            double btPct = safePct(r.btKwh, r.loadKwh);
            double tlePct = safePct(r.wreKwh, r.loadKwh);

            double ensPpt = (r.loadKwh > SimulationConstants.EPSILON)
                    ? round1(100000.0 * r.ensKwh / r.loadKwh)
                    : 0.0;

            return r.iter + ";" +
                    fmt0(r.ensKwh) + ";" +
                    fmt3(r.fuelLiters) + ";" +
                    fmt0(r.motoHours) + ";" +
                    fmt3(r.wreKwh) + ";" +
                    fmt2(wtPct) + ";" +
                    fmt2(dgPct) + ";" +
                    fmt2(btPct) + ";" +
                    fmt2(tlePct) + ";" +
                    fmt1(ensPpt);
        }

        private static Stats[] ss(Stats... s) { return s; }

        private static String join(Stats[] ss, java.util.function.Function<Stats, String> f) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < ss.length; i++) {
                if (i > 0) sb.append(';');
                sb.append(f.apply(ss[i]));
            }
            return sb.toString();
        }

        private static double safePct(double part, double total) {
            if (total <= SimulationConstants.EPSILON) return 0.0;
            return (part / total) * 100.0;
        }

        private static double round1(double v) { return Math.round(v * 10.0) / 10.0; }

        private static String fmt0(double v) { return String.format(RU, "%.0f", v); }
        private static String fmt1(double v) { return String.format(RU, "%.1f", v); }
        private static String fmt2(double v) { return String.format(RU, "%.2f", v); }
        private static String fmt3(double v) { return String.format(RU, "%.3f", v); }
    }

    private static final class Stats {
        final double mean;
        final double std;
        final double ciLo;
        final double ciHi;
        final int n;
        final int requiredN;

        private Stats(double mean, double std, double ciLo, double ciHi, int n, int requiredN) {
            this.mean = mean;
            this.std = std;
            this.ciLo = ciLo;
            this.ciHi = ciHi;
            this.n = n;
            this.requiredN = requiredN;
        }

        static Stats of(double[] raw) {
            List<Double> list = new ArrayList<>(raw.length);
            for (double v : raw) if (Double.isFinite(v)) list.add(v);

            if (list.isEmpty()) return new Stats(0, 0, 0, 0, 0, 0);

            if (REMOVE_OUTLIERS && list.size() >= 4) list = removeOutliers(list);

            int n = list.size();
            double mean = mean(list);
            double std = (n < 2) ? 0.0 : sampleStd(list, mean);

            double margin = (n < 2) ? 0.0 : (T_SCORE * std / Math.sqrt(n));
            double ciLo = mean - margin;
            double ciHi = mean + margin;

            int reqN = requiredN(mean, std);
            return new Stats(mean, std, ciLo, ciHi, n, reqN);
        }

        private static int requiredN(double mean, double std) {
            if (mean == 0.0) return 1;
            double E = REL_ERR * Math.abs(mean);
            if (E <= 0.0) return 1;
            double z = T_SCORE;
            return (std <= 0.0) ? 1 : (int) Math.ceil(Math.pow((z * std) / E, 2));
        }

        private static double mean(List<Double> data) {
            double s = 0.0;
            for (double v : data) s += v;
            return s / data.size();
        }

        private static double sampleStd(List<Double> data, double mean) {
            double sum = 0.0;
            for (double v : data) sum += (v - mean) * (v - mean);
            return Math.sqrt(sum / (data.size() - 1));
        }

        private static List<Double> removeOutliers(List<Double> data) {
            List<Double> sorted = new ArrayList<>(data);
            Collections.sort(sorted);
            int n = sorted.size();
            double q1 = sorted.get(n / 4);
            double q3 = sorted.get(3 * n / 4);
            double iqr = q3 - q1;
            double lower = q1 - 1.5 * iqr;
            double upper = q3 + 1.5 * iqr;
            List<Double> filtered = new ArrayList<>();
            for (double v : sorted) if (v >= lower && v <= upper) filtered.add(v);
            return filtered;
        }
    }

    private static final class SimulationResult {
        final int iter;
        final double loadKwh;
        final double ensKwh;
        final double wreKwh;
        final double wtKwh;
        final double dgKwh;
        final double btKwh;
        final double fuelLiters;
        final long motoHours;

        final boolean considerFailures;
        final boolean considerDegradation;
        final boolean considerChargeByDg;
        final double dgPowerKw;

        SimulationResult(int iter,
                         double loadKwh,
                         double ensKwh,
                         double wreKwh,
                         double wtKwh,
                         double dgKwh,
                         double btKwh,
                         double fuelLiters,
                         long motoHours,
                         boolean considerFailures,
                         boolean considerDegradation,
                         boolean considerChargeByDg,
                         double dgPowerKw) {
            this.iter = iter;
            this.loadKwh = loadKwh;
            this.ensKwh = ensKwh;
            this.wreKwh = wreKwh;
            this.wtKwh = wtKwh;
            this.dgKwh = dgKwh;
            this.btKwh = btKwh;
            this.fuelLiters = fuelLiters;
            this.motoHours = motoHours;
            this.considerFailures = considerFailures;
            this.considerDegradation = considerDegradation;
            this.considerChargeByDg = considerChargeByDg;
            this.dgPowerKw = dgPowerKw;
        }
    }

    public static final class SimulationSummary {
        private final double totalEnsSum;
        private final double averageEns;
        private final int iterations;
        private final double totalSupplyFromWT;
        private final double totalSupplyFromDG;
        private final double totalSupplyFromBT;
        private final double totalWreKwh;
        private final double totalFuelLiters;
        private final long totalMotoHours;

        public SimulationSummary(double totalEnsSum,
                                 double averageEns,
                                 int iterations,
                                 double totalSupplyFromWT,
                                 double totalSupplyFromDG,
                                 double totalSupplyFromBT,
                                 double totalWreKwh,
                                 double totalFuelLiters,
                                 long totalMotoHours) {
            this.totalEnsSum = totalEnsSum;
            this.averageEns = averageEns;
            this.iterations = iterations;
            this.totalSupplyFromWT = totalSupplyFromWT;
            this.totalSupplyFromDG = totalSupplyFromDG;
            this.totalSupplyFromBT = totalSupplyFromBT;
            this.totalWreKwh = totalWreKwh;
            this.totalFuelLiters = totalFuelLiters;
            this.totalMotoHours = totalMotoHours;
        }
    }
}
