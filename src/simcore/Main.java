package simcore;

import simcore.config.*;
import simcore.config.ModelDefaults;
import simcore.economy.*;
import simcore.engine.*;
import simcore.io.InputData;
import simcore.io.InputDataLoader;
import simcore.io.SweepResultsExcelWriter;
import simcore.sobol.*;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Main {

    public enum Task {RUN, SOBOL_HARD, SOBOL_ECON, ADAPTIVE_TUNE}
    public enum RunMode {SINGLE, SWEEP_1, SWEEP_2}
    public enum LoadType {GOK, KOMUNAL, SELHOZ, DEF}

    public static double MAX_LOAD;

    private static final class Cli {

        Task task = Task.ADAPTIVE_TUNE;
        RunMode runMode = RunMode.SINGLE;
        BusSystemType busType = BusSystemType.DOUBLE_BUS;
        SobolConfig.SeedMode sobolSeedMode = SobolConfig.SeedMode.HYBRID_BY_TYPE;

        int mcIterations = 500;

        int sobolN = 128;

        // Adaptive weight tuning
        int tuneSamples = 256;
        int tuneTopK = 16;
        int tuneStage1Mc = 50;
        int tuneStage2Mc = 250;
        String tuneCsvPath = Defaults.TUNE_CSV;
        Double tuneEnsMax = null;
        Double tuneLolpMax = null;
        Double tuneLolehMax = null;
        double tuneLambdaFuel = 0.0;
        double tuneLambdaMoto = 0.0;
        double tuneFixedWA = 0.0;

        double tuneWEMin = 0.0, tuneWEMax = 1.2;
        double tuneWTMin = 0.0, tuneWTMax = 0.5;
        double tuneWHMin = 0.0, tuneWHMax = 0.3;
        double tuneWDMin = 0.0, tuneWDMax = 0.8;
        double tuneWRMin = 0.0, tuneWRMax = 0.8;

        //        String exportDriversPath = "D:/econ_drivers.csv";
        String exportDriversPath = null;
        // Econ sobol
        String econDriversPath = "D:/econ_drivers.csv";
        String econCaseId = "case_0";
        Integer econN = null;

        LoadType loadType = LoadType.DEF; // тип нагрузки
        Integer maxLoadOverride = null;
        int threads = Runtime.getRuntime().availableProcessors();
        long mcBaseSeed = 1_000_000L;
        String loadFilePath = null;
        String windFilePath = Defaults.WIND_PATH;
        String resultsXlsxPath = Defaults.RESULTS_XLSX;
        String traceXlsxPath = Defaults.TRACE_XLSX;

        static Cli parse(String[] args) {
            Cli c = new Cli();
            for (String a : args) {
                if (a == null) continue;

                if (a.startsWith("--task=")) c.task = Task.valueOf(a.substring("--task=".length()).trim());

                if (a.startsWith("--runMode=")) c.runMode = RunMode.valueOf(a.substring("--runMode=".length()).trim());
                if (a.startsWith("--loadType="))
                    c.loadType = LoadType.valueOf(a.substring("--loadType=".length()).trim());
                if (a.startsWith("--busType="))
                    c.busType = BusSystemType.valueOf(a.substring("--busType=".length()).trim());

                if (a.startsWith("--load=")) c.loadFilePath = a.substring("--load=".length()).trim();
                if (a.startsWith("--wind=")) c.windFilePath = a.substring("--wind=".length()).trim();
                if (a.startsWith("--results=")) c.resultsXlsxPath = a.substring("--results=".length()).trim();
                if (a.startsWith("--trace=")) c.traceXlsxPath = a.substring("--trace=".length()).trim();

                if (a.startsWith("--threads=")) c.threads = Integer.parseInt(a.substring("--threads=".length()).trim());
                if (a.startsWith("--mc=")) c.mcIterations = Integer.parseInt(a.substring("--mc=".length()).trim());
                if (a.startsWith("--mcSeed=")) c.mcBaseSeed = Long.parseLong(a.substring("--mcSeed=".length()).trim());

                if (a.startsWith("--maxLoad="))
                    c.maxLoadOverride = Integer.parseInt(a.substring("--maxLoad=".length()).trim());

                if (a.startsWith("--sobolN=")) c.sobolN = Integer.parseInt(a.substring("--sobolN=".length()).trim());

                if (a.startsWith("--sobolSeedMode="))
                    c.sobolSeedMode = SobolConfig.SeedMode.valueOf(a.substring("--sobolSeedMode=".length()).trim());

                if (a.startsWith("--exportDrivers=")) {
                    String p = a.substring("--exportDrivers=".length()).trim();
                    c.exportDriversPath = p.isEmpty() ? null : p;
                }

                if (a.startsWith("--econDrivers=")) c.econDriversPath = a.substring("--econDrivers=".length()).trim();
                if (a.startsWith("--econCase=")) c.econCaseId = a.substring("--econCase=".length()).trim();
                if (a.startsWith("--econN=")) c.econN = Integer.parseInt(a.substring("--econN=".length()).trim());

                if (a.startsWith("--tuneSamples=")) c.tuneSamples = Integer.parseInt(a.substring("--tuneSamples=".length()).trim());
                if (a.startsWith("--tuneTopK=")) c.tuneTopK = Integer.parseInt(a.substring("--tuneTopK=".length()).trim());
                if (a.startsWith("--tuneStage1Mc=")) c.tuneStage1Mc = Integer.parseInt(a.substring("--tuneStage1Mc=".length()).trim());
                if (a.startsWith("--tuneStage2Mc=")) c.tuneStage2Mc = Integer.parseInt(a.substring("--tuneStage2Mc=".length()).trim());
                if (a.startsWith("--tuneCsv=")) c.tuneCsvPath = a.substring("--tuneCsv=".length()).trim();
                if (a.startsWith("--tuneEnsMax=")) c.tuneEnsMax = Double.parseDouble(a.substring("--tuneEnsMax=".length()).trim());
                if (a.startsWith("--tuneLolpMax=")) c.tuneLolpMax = Double.parseDouble(a.substring("--tuneLolpMax=".length()).trim());
                if (a.startsWith("--tuneLolehMax=")) c.tuneLolehMax = Double.parseDouble(a.substring("--tuneLolehMax=".length()).trim());
                if (a.startsWith("--tuneLambdaFuel=")) c.tuneLambdaFuel = Double.parseDouble(a.substring("--tuneLambdaFuel=".length()).trim());
                if (a.startsWith("--tuneLambdaMoto=")) c.tuneLambdaMoto = Double.parseDouble(a.substring("--tuneLambdaMoto=".length()).trim());
                if (a.startsWith("--tuneFixedWA=")) c.tuneFixedWA = Double.parseDouble(a.substring("--tuneFixedWA=".length()).trim());

                if (a.startsWith("--tuneWEMin=")) c.tuneWEMin = Double.parseDouble(a.substring("--tuneWEMin=".length()).trim());
                if (a.startsWith("--tuneWEMax=")) c.tuneWEMax = Double.parseDouble(a.substring("--tuneWEMax=".length()).trim());
                if (a.startsWith("--tuneWTMin=")) c.tuneWTMin = Double.parseDouble(a.substring("--tuneWTMin=".length()).trim());
                if (a.startsWith("--tuneWTMax=")) c.tuneWTMax = Double.parseDouble(a.substring("--tuneWTMax=".length()).trim());
                if (a.startsWith("--tuneWHMin=")) c.tuneWHMin = Double.parseDouble(a.substring("--tuneWHMin=".length()).trim());
                if (a.startsWith("--tuneWHMax=")) c.tuneWHMax = Double.parseDouble(a.substring("--tuneWHMax=".length()).trim());
                if (a.startsWith("--tuneWDMin=")) c.tuneWDMin = Double.parseDouble(a.substring("--tuneWDMin=".length()).trim());
                if (a.startsWith("--tuneWDMax=")) c.tuneWDMax = Double.parseDouble(a.substring("--tuneWDMax=".length()).trim());
                if (a.startsWith("--tuneWRMin=")) c.tuneWRMin = Double.parseDouble(a.substring("--tuneWRMin=".length()).trim());
                if (a.startsWith("--tuneWRMax=")) c.tuneWRMax = Double.parseDouble(a.substring("--tuneWRMax=".length()).trim());
            }
            return c;
        }
    }

    private static List<SystemParameters> buildParamSets(RunMode mode,
                                                         SystemParameters baseParams,
                                                         double[] param1,
                                                         double[] param2,
                                                         boolean sweepCatsTriangle,
                                                         double catStep) {

        List<SystemParameters> paramSets = new ArrayList<>();

        if (mode == RunMode.SINGLE) {
            paramSets.add(baseParams);
            return paramSets;
        }

        if (mode == RunMode.SWEEP_1) {
            for (double p1 : param1) {
                SystemParameters p = SystemParametersBuilder.from(baseParams)
//                        .setBatteryCapacityKwhPerBus(p1 * 1346 / 2)
//                        .setDieselGeneratorPowerKw(p1)
//                        .setRotationReserveCoeff(p1)
//                        .setBtGridFormingReserveShare(p1)
                        .setNonReserveDischargeLevel(p1)
                        .build();
                paramSets.add(p);
            }
            return paramSets;
        }

        if (mode == RunMode.SWEEP_2) {
            if (sweepCatsTriangle) {
                int n = (int) Math.round(1.0 / catStep);
                for (int i = 0; i <= n; i++) {
                    double k1 = i * catStep;
                    for (int j = 0; j <= n - i; j++) {
                        double k2 = j * catStep;
                        SystemParameters p = SystemParametersBuilder.from(baseParams)
                                .setFirstCat(k1)
                                .setSecondCat(k2)
                                .build();
                        paramSets.add(p);
                    }
                }
            } else {
                for (double p1 : param1) {
                    for (double p2 : param2) {
                        SystemParameters p = SystemParametersBuilder.from(baseParams)
//                                .setTotalDieselGeneratorCount((int) p1)
//                                .setDieselGeneratorPowerKw(p2)

//                                .setTotalWindTurbineCount((int) p1)
//                                .setWindTurbinePowerKw(p1)
//
                                .setBatteryCapacityKwhPerBus(p2 * 1346 / 2)

//                                .setMaxDischargeCurrent(p2)
                                .setNonReserveDischargeLevel(p1)

//                                .setBtAdaptiveDeficitWeight(p1) // wE
//                                .setBtAdaptiveTrendWeight(p1) // wT
//                                .setBtAdaptiveAccelerationWeight(p1) // wA
//                                .setBtAdaptiveNoDgPrevHourWeight(p2) // wH
//                                .setBtAdaptiveReplacementWeight(p1) // wR
//                                .setBtAdaptiveDgAvailabilityWeight(p1) // wD



                                .build();
                        paramSets.add(p);
                    }
                }
            }
        }

        return paramSets;
    }

    private static void runTaskRun(ScenarioFactory.LoadedInput li, SystemParameters baseParams, Cli cli) throws Exception {
        SimulationConfig cfg = ScenarioFactory.defaultConfig(
                li.windMs(),
                cli.mcIterations,
                cli.threads,
                cli.exportDriversPath != null
        );
        SimInput baseInput = new SimInput(cfg, baseParams, li.totalLoadKw());

        final boolean sweepCatsTriangle = false;
        final double catStep = 0.1;

        // ===== Axes (edit here) =====
//        double[] param1 = new double[]{6, 8, 10};
////        double[] param1 = new double[]{3, 4, 5, 6, 7, 8};
//        double[] param2 = new double[]{
//                150, 160, 170, 180, 190,
//                200, 210, 220, 230, 240,
//                250, 260, 270, 280, 290,
//                300, 310, 320, 330, 340,
//                350, 360, 370, 380, 390,
//                400, 410, 420, 430, 440,
//                450, 460, 470, 480, 490,
//                500,
////                510, 520, 530, 540,
////                550, 560, 570, 580, 590,
////                600, 610, 620, 630, 640,
////                650, 660, 670, 680, 690,
//
//        };
//
//        double[] param1 = new double[]{
//                0,
//                168.25, 336.5, 504.75, 673,
//                841.25, 1009.5, 1177.75, 1346,
//                1514.25, 1682.5, 1850.75, 2019,
//////                2187.25, 2355.5, 2523.75, 2692,
//////                2860.25, 3028.5, 3196.75, 3365
//        };
//        double[] param2 = new double[]{0, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1};

        double[] param2 = new double[]{0, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1};
        double[] param1 = new double[]{0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1};
//                double[] param2 = new double[]{1, 1.5, 2, 2.5, 3, 3.5, 4, 4.5, 5};



        if (cli.runMode == RunMode.SWEEP_2 && sweepCatsTriangle) {
            param1 = buildGrid01(catStep);
            param2 = buildGrid01(catStep);
        }

        List<SystemParameters> paramSets = buildParamSets(cli.runMode, baseParams, param1, param2, sweepCatsTriangle, catStep);
        boolean allowTrace = (cli.mcIterations == 1) && (paramSets.size() == 1);

        ExecutorService ex = Executors.newFixedThreadPool(cli.threads);
        try {
            SingleRunSimulator sim = new SingleRunSimulator();
            MonteCarloRunner mc = new MonteCarloRunner(ex, sim, false, 1.96, 0.1);
            SimulationEngine engine = new SimulationEngine(mc);

            List<MonteCarloEstimate> estimates = new ArrayList<>(paramSets.size());

            for (int k = 0; k < paramSets.size(); k++) {
                SimInput in = baseInput.withSystemParameters(paramSets.get(k));
                MonteCarloEstimate est = engine.runMonteCarlo(in, cli.mcIterations, cli.mcBaseSeed, allowTrace);
                estimates.add(est);

                if (cli.exportDriversPath != null && est.economyDrivers != null) {
                    EconomyDriversCsvIO.append(cli.exportDriversPath, "case_" + k, est.economyDrivers);
                }

                if (allowTrace
                        && est.singleRun != null
                        && est.singleRun.trace != null
                        && !est.singleRun.trace.isEmpty()) {
                    SimulationTraceExporter.exportToXlsx(cli.traceXlsxPath, est.singleRun.trace);
                }
            }

            SweepResultsExcelWriter.writeXlsx(cli.resultsXlsxPath, cli.runMode, cfg, baseParams, paramSets, estimates, param1, param2);
            System.out.println("Saved: " + cli.resultsXlsxPath);

        } finally {
            ex.shutdown();
        }
    }

    private static void runTaskSobolHard(ScenarioFactory.LoadedInput li, SystemParameters baseParams, Cli cli) throws Exception {
        List<TunableParamId> ids = List.of(

                // Группа по параметрам:
                TunableParamId.DG_POWER,
                TunableParamId.DG_COUNT,
                TunableParamId.WT_POWER,
                TunableParamId.BT_CAPACITY_PER_BUS,
                TunableParamId.BT_MAX_DISCHARGE_CURRENT,
                TunableParamId.BT_MAX_CHARGE_CURRENT,
                TunableParamId.BT_NON_RESERVE_DISCHARGE_LVL

//                TunableParamId.WT_COUNT,

                // Группа по надежности:
//                TunableParamId.FIRST_CAT,
//                TunableParamId.SECOND_CAT,
//
//                TunableParamId.WT_FAILURE_RATE,
//                TunableParamId.DG_FAILURE_RATE,
//                TunableParamId.BT_FAILURE_RATE,
//                TunableParamId.BUS_FAILURE_RATE,
//                TunableParamId.BRK_FAILURE_RATE,
//
//                TunableParamId.WT_FAILURE_RATE,
//                TunableParamId.DG_REPAIR_TIME,
//                TunableParamId.BT_REPAIR_TIME,
//                TunableParamId.BUS_REPAIR_TIME,
//                TunableParamId.BRK_REPAIR_TIME

        );

        SobolConfig sobolCfg = SobolConfig.fromIds(
                cli.sobolN,
                cli.mcIterations,
                cli.mcBaseSeed,
                cli.threads,
                ids,
                cli.sobolSeedMode
        );

        TunableParameterPool.setMinTotalDgPowerKw(MAX_LOAD);

        SimulationConfig cfg = ScenarioFactory.defaultConfig(
                li.windMs(),
                sobolCfg.getMcIterations(),
                sobolCfg.getThreads(),
                cli.exportDriversPath != null
        );
        SimInput baseInput = new SimInput(cfg, baseParams, li.totalLoadKw());

        ExecutorService ex = Executors.newFixedThreadPool(sobolCfg.getThreads());
        try {
            SingleRunSimulator sim = new SingleRunSimulator();
            MonteCarloRunner mc = new MonteCarloRunner(ex, sim, false, 1.96, 0.10);
            SobolAnalyzer analyzer = new SobolAnalyzer(mc, ex);

            SobolResult res = analyzer.run(baseInput, sobolCfg);

            System.out.println("Sobol done. dim=" + sobolCfg.dim());
        } finally {
            ex.shutdown();
        }
    }

    private static void runTaskSobolEcon(SystemParameters baseParams, Cli cli) throws Exception {
        if (cli.econDriversPath == null) {
            throw new IllegalArgumentException("For --task=SOBOL_ECON you must set --econDrivers=PATH");
        }

        Map<String, EconomyDrivers> all = EconomyDriversCsvIO.readAll(cli.econDriversPath);
        if (all.isEmpty()) {
            System.err.println("No drivers found in: " + cli.econDriversPath);
            return;
        }

        String useCase = (cli.econCaseId != null) ? cli.econCaseId : all.keySet().iterator().next();
        EconomyDrivers drivers = all.get(useCase);
        if (drivers == null) {
            System.err.println("Case not found: " + useCase + ". Available: " + all.keySet());
            return;
        }

        List<TunableParamId> econIds = List.of(
                TunableParamId.DISCOUNT_RATE,
                TunableParamId.COST_RU_RUB,
                TunableParamId.COST_DG_RUB_PER_KW,
                TunableParamId.COST_WT_RUB_PER_KW,
                TunableParamId.COST_BT_RUB_PER_KWH,
                TunableParamId.COST_FUEL_RUB_PER_KT,
                TunableParamId.COST_DG_RUB_PER_KW_PER_KMH,
                TunableParamId.COST_WT_RUB_PER_KW_PER_YEAR,
                TunableParamId.COST_BT_RUB_PER_KWH_PER_YEAR,
                TunableParamId.DAMAGE_RUB_PER_KWH_CAT3
        );

        int d = econIds.size();
        int N = (cli.econN != null) ? cli.econN : 8192;

        double[][][] ab = simcore.sobol.SobolMath.generateABBySobolSequence(N, d, 1024);
        double[][] A = ab[0];
        double[][] B = ab[1];

        double[] yA = new double[N];
        double[] yB = new double[N];
        double[][] yAB = new double[d][N];

        for (int i = 0; i < N; i++) {
            EconInputs inA = econInputsFromUnitRow(A[i], econIds, baseParams.getBusSystemType());
            EconInputs inB = econInputsFromUnitRow(B[i], econIds, baseParams.getBusSystemType());
            yA[i] = DiscountedLcoeCalculator.computeRubPerKwh(withDiscountRate(drivers, inA.discountRatePerYear), inA.costs);
            yB[i] = DiscountedLcoeCalculator.computeRubPerKwh(withDiscountRate(drivers, inB.discountRatePerYear), inB.costs);
        }

        for (int j = 0; j < d; j++) {
            for (int i = 0; i < N; i++) {
                double[] row = Arrays.copyOf(A[i], d);
                row[j] = B[i][j];
                EconInputs in = econInputsFromUnitRow(row, econIds, baseParams.getBusSystemType());
                yAB[j][i] = DiscountedLcoeCalculator.computeRubPerKwh(withDiscountRate(drivers, in.discountRatePerYear), in.costs);
            }
        }

        double[] S = new double[d];
        double[] ST = new double[d];
        simcore.sobol.SobolMath.computeIndicesSaltelli2002Jansen(yA, yB, yAB, S, ST);

        double varY = simcore.sobol.SobolMath.variancePooledPopulation(yA, yB);
        double minY = simcore.sobol.SobolMath.minPooled(yA, yB);
        double maxY = simcore.sobol.SobolMath.maxPooled(yA, yB);

        System.out.println("=== ECON Sobol (LCOE vs unit costs) ===");
        System.out.println("drivers=" + cli.econDriversPath + " case=" + useCase + " N=" + N + " years=" + drivers.years());
        System.out.printf(Locale.US, "metric(A∪B)  LCOE: var=%.6g std=%.6g range=[%.6g..%.6g]%n",
                varY, Math.sqrt(varY), minY, maxY);
        for (int j = 0; j < d; j++) {
            System.out.printf(Locale.US, "%-28s  S=%.6f  ST=%.6f%n", TunableParameterPool.get(econIds.get(j)).getName(), S[j], ST[j]);
        }
    }

    private static void runTaskAdaptiveTune(ScenarioFactory.LoadedInput li, SystemParameters baseParams, Cli cli) throws Exception {
        int samples = Math.max(8, cli.tuneSamples);
        int topK = Math.max(1, Math.min(cli.tuneTopK, samples));
        int stage1Mc = Math.max(1, cli.tuneStage1Mc);
        int stage2Mc = Math.max(1, cli.tuneStage2Mc);

        SystemParameters adaptiveBase = SystemParametersBuilder.from(baseParams)
                .setBtUseAdaptiveNonReserveDischargeLevel(true)
                .setBtAdaptiveAccelerationWeight(cli.tuneFixedWA)
                .build();

        SimulationConfig cfg1 = ScenarioFactory.defaultConfig(li.windMs(), stage1Mc, cli.threads, false);
        SimulationConfig cfg2 = ScenarioFactory.defaultConfig(li.windMs(), stage2Mc, cli.threads, false);

        SimInput baseInput1 = new SimInput(cfg1, adaptiveBase, li.totalLoadKw());
        SimInput baseInput2 = new SimInput(cfg2, adaptiveBase, li.totalLoadKw());

        ExecutorService ex = Executors.newFixedThreadPool(cli.threads);
        try {
            SingleRunSimulator sim = new SingleRunSimulator();
            MonteCarloRunner mc = new MonteCarloRunner(ex, sim, false, 1.96, 0.1);

            MonteCarloEstimate baseline = mc.evaluateForTheta(baseInput2, null, null, stage2Mc, cli.mcBaseSeed, false);
            double ensMax = (cli.tuneEnsMax != null) ? cli.tuneEnsMax : baseline.ensStats.getMean();
            double lolpMax = (cli.tuneLolpMax != null) ? cli.tuneLolpMax : baseline.meanLolp;
            double lolehMax = (cli.tuneLolehMax != null) ? cli.tuneLolehMax : baseline.meanLoleHours;

            System.out.println("=== ADAPTIVE_TUNE baseline ===");
            System.out.printf(Locale.US,
                    "baseline: LCOE=%.6f ENS=%.6f LOLP=%.6f LOLH=%.6f Fuel=%.6f Moto=%.6f%n",
                    baseline.meanLcoeRubPerKwh, baseline.ensStats.getMean(), baseline.meanLolp, baseline.meanLoleHours,
                    baseline.meanFuelLiters, baseline.meanMotoHours);
            System.out.printf(Locale.US,
                    "constraints: ENS<=%.6f LOLP<=%.6f LOLH<=%.6f%n", ensMax, lolpMax, lolehMax);

            List<TuneResult> global = new ArrayList<>();
            double[][] points = simcore.sobol.SobolMath.generateABBySobolSequence(samples, 5, 1024)[0];
            for (int i = 0; i < samples; i++) {
                WeightPoint w = weightFromUnit(cli, points[i]);
                MonteCarloEstimate est = evaluateWeights(mc, baseInput1, cli, w);
                double score = score(est, baseline, ensMax, lolpMax, lolehMax, cli.tuneLambdaFuel, cli.tuneLambdaMoto);
                TuneResult tr = new TuneResult("global", i + 1, w, est, score);
                global.add(tr);
                System.out.printf(Locale.US,
                        "global %3d/%3d score=%.6f wE=%.4f wT=%.4f wH=%.4f wD=%.4f wR=%.4f | LCOE=%.6f ENS=%.6f LOLP=%.6f LOLH=%.6f avgNRL=%.4f%n",
                        i + 1, samples, score, w.wE, w.wT, w.wH, w.wD, w.wR,
                        est.meanLcoeRubPerKwh, est.ensStats.getMean(), est.meanLolp, est.meanLoleHours, est.meanAdaptiveNonReserveLevel);
            }
            global.sort(Comparator.comparingDouble(a -> a.score));

            List<TuneResult> local = new ArrayList<>();
            int localSamplesPerTop = 12;
            for (int i = 0; i < Math.min(topK, global.size()); i++) {
                WeightPoint center = global.get(i).weights;
                for (int j = 0; j < localSamplesPerTop; j++) {
                    double[] u = new double[]{(j + 0.5) / localSamplesPerTop, Math.abs(Math.sin((i + 1) * 17.0 + j)), Math.abs(Math.sin((i + 1) * 31.0 + j)), Math.abs(Math.sin((i + 1) * 47.0 + j)), Math.abs(Math.sin((i + 1) * 59.0 + j))};
                    WeightPoint w = localPerturb(cli, center, u);
                    MonteCarloEstimate est = evaluateWeights(mc, baseInput1, cli, w);
                    double score = score(est, baseline, ensMax, lolpMax, lolehMax, cli.tuneLambdaFuel, cli.tuneLambdaMoto);
                    local.add(new TuneResult("local", i * localSamplesPerTop + j + 1, w, est, score));
                }
            }
            local.sort(Comparator.comparingDouble(a -> a.score));

            List<TuneResult> finalists = new ArrayList<>();
            LinkedHashMap<String, WeightPoint> unique = new LinkedHashMap<>();
            for (TuneResult tr : global) {
                String key = tr.weights.key();
                if (!unique.containsKey(key)) unique.put(key, tr.weights);
                if (unique.size() >= topK) break;
            }
            for (TuneResult tr : local) {
                String key = tr.weights.key();
                if (!unique.containsKey(key)) unique.put(key, tr.weights);
                if (unique.size() >= topK * 2) break;
            }

            int idx = 1;
            for (WeightPoint w : unique.values()) {
                MonteCarloEstimate est = evaluateWeights(mc, baseInput2, cli, w);
                double score = score(est, baseline, ensMax, lolpMax, lolehMax, cli.tuneLambdaFuel, cli.tuneLambdaMoto);
                finalists.add(new TuneResult("final", idx++, w, est, score));
            }
            finalists.sort(Comparator.comparingDouble(a -> a.score));

            writeTuneCsv(cli.tuneCsvPath, baseline, ensMax, lolpMax, lolehMax, global, local, finalists);

            System.out.println("=== ADAPTIVE_TUNE final top ===");
            for (int i = 0; i < Math.min(10, finalists.size()); i++) {
                TuneResult tr = finalists.get(i);
                System.out.printf(Locale.US,
                        "#%d score=%.6f wE=%.4f wT=%.4f wH=%.4f wD=%.4f wR=%.4f | LCOE=%.6f ENS=%.6f LOLP=%.6f LOLH=%.6f Fuel=%.6f Moto=%.6f avgNRL=%.4f medNRL=%.4f%n",
                        i + 1, tr.score, tr.weights.wE, tr.weights.wT, tr.weights.wH, tr.weights.wD, tr.weights.wR,
                        tr.estimate.meanLcoeRubPerKwh, tr.estimate.ensStats.getMean(), tr.estimate.meanLolp, tr.estimate.meanLoleHours,
                        tr.estimate.meanFuelLiters, tr.estimate.meanMotoHours,
                        tr.estimate.meanAdaptiveNonReserveLevel, tr.estimate.medianAdaptiveNonReserveLevel);
            }
            System.out.println("Saved tune table: " + cli.tuneCsvPath);

        } finally {
            ex.shutdown();
        }
    }

    private static MonteCarloEstimate evaluateWeights(MonteCarloRunner mc, SimInput baseInput, Cli cli, WeightPoint w)
            throws InterruptedException, java.util.concurrent.ExecutionException {
        SystemParameters tuned = SystemParametersBuilder.from(baseInput.getSystemParameters())
                .setBtUseAdaptiveNonReserveDischargeLevel(true)
                .setBtAdaptiveDeficitWeight(w.wE)
                .setBtAdaptiveTrendWeight(w.wT)
                .setBtAdaptiveAccelerationWeight(cli.tuneFixedWA)
                .setBtAdaptiveNoDgPrevHourWeight(w.wH)
                .setBtAdaptiveDgAvailabilityWeight(w.wD)
                .setBtAdaptiveReplacementWeight(w.wR)
                .build();
        SimInput in = baseInput.withSystemParameters(tuned);
        return mc.evaluateForTheta(in, null, null, in.getConfig().getIterations(), cli.mcBaseSeed, false);
    }

    private static WeightPoint weightFromUnit(Cli cli, double[] u) {
        return new WeightPoint(
                lerp(cli.tuneWEMin, cli.tuneWEMax, u[0]),
                lerp(cli.tuneWTMin, cli.tuneWTMax, u[1]),
                lerp(cli.tuneWHMin, cli.tuneWHMax, u[2]),
                lerp(cli.tuneWDMin, cli.tuneWDMax, u[3]),
                lerp(cli.tuneWRMin, cli.tuneWRMax, u[4])
        );
    }

    private static WeightPoint localPerturb(Cli cli, WeightPoint c, double[] u) {
        double spanE = (cli.tuneWEMax - cli.tuneWEMin) * 0.15;
        double spanT = (cli.tuneWTMax - cli.tuneWTMin) * 0.15;
        double spanH = (cli.tuneWHMax - cli.tuneWHMin) * 0.15;
        double spanD = (cli.tuneWDMax - cli.tuneWDMin) * 0.15;
        double spanR = (cli.tuneWRMax - cli.tuneWRMin) * 0.15;
        return new WeightPoint(
                clip(c.wE + (u[0] * 2.0 - 1.0) * spanE, cli.tuneWEMin, cli.tuneWEMax),
                clip(c.wT + (u[1] * 2.0 - 1.0) * spanT, cli.tuneWTMin, cli.tuneWTMax),
                clip(c.wH + (u[2] * 2.0 - 1.0) * spanH, cli.tuneWHMin, cli.tuneWHMax),
                clip(c.wD + (u[3] * 2.0 - 1.0) * spanD, cli.tuneWDMin, cli.tuneWDMax),
                clip(c.wR + (u[4] * 2.0 - 1.0) * spanR, cli.tuneWRMin, cli.tuneWRMax)
        );
    }

    private static double score(MonteCarloEstimate est,
                                MonteCarloEstimate baseline,
                                double ensMax,
                                double lolpMax,
                                double lolehMax,
                                double lambdaFuel,
                                double lambdaMoto) {
        double s = est.meanLcoeRubPerKwh / safePositive(baseline.meanLcoeRubPerKwh);
        if (lambdaFuel != 0.0) s += lambdaFuel * est.meanFuelLiters / safePositive(baseline.meanFuelLiters);
        if (lambdaMoto != 0.0) s += lambdaMoto * est.meanMotoHours / safePositive(baseline.meanMotoHours);
        s += penaltyOver(est.ensStats.getMean(), ensMax, 1000.0);
        s += penaltyOver(est.meanLolp, lolpMax, 1000.0);
        s += penaltyOver(est.meanLoleHours, lolehMax, 1000.0);
        return s;
    }

    private static double penaltyOver(double value, double limit, double scale) {
        if (!(limit > 0.0)) return 0.0;
        if (value <= limit) return 0.0;
        double over = (value - limit) / limit;
        return scale * over * over;
    }

    private static double safePositive(double x) {
        return (x > 1e-12) ? x : 1e-12;
    }

    private static double lerp(double a, double b, double u) { return a + (b - a) * u; }
    private static double clip(double x, double lo, double hi) { return Math.max(lo, Math.min(hi, x)); }

    private static void writeTuneCsv(String path,
                                     MonteCarloEstimate baseline,
                                     double ensMax,
                                     double lolpMax,
                                     double lolehMax,
                                     List<TuneResult> global,
                                     List<TuneResult> local,
                                     List<TuneResult> finals) throws java.io.IOException {
        try (java.io.PrintWriter pw = new java.io.PrintWriter(new java.io.OutputStreamWriter(new java.io.FileOutputStream(path), java.nio.charset.StandardCharsets.UTF_8))) {
            pw.println("kind,seq,score,wE,wT,wH,wD,wR,LCOE,ENS,LOLP,LOLH,FuelLiters,MotoHours,AvgNRL,MedNRL,ENS_limit,LOLP_limit,LOLH_limit,baseline_LCOE,baseline_ENS,baseline_LOLP,baseline_LOLH");
            for (TuneResult tr : global) writeTuneRow(pw, tr, baseline, ensMax, lolpMax, lolehMax);
            for (TuneResult tr : local) writeTuneRow(pw, tr, baseline, ensMax, lolpMax, lolehMax);
            for (TuneResult tr : finals) writeTuneRow(pw, tr, baseline, ensMax, lolpMax, lolehMax);
        }
    }

    private static void writeTuneRow(java.io.PrintWriter pw, TuneResult tr, MonteCarloEstimate baseline,
                                     double ensMax, double lolpMax, double lolehMax) {
        pw.printf(Locale.US,
                "%s,%d,%.10f,%.6f,%.6f,%.6f,%.6f,%.6f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f%n",
                tr.kind, tr.seq, tr.score, tr.weights.wE, tr.weights.wT, tr.weights.wH, tr.weights.wD, tr.weights.wR,
                tr.estimate.meanLcoeRubPerKwh, tr.estimate.ensStats.getMean(), tr.estimate.meanLolp, tr.estimate.meanLoleHours,
                tr.estimate.meanFuelLiters, tr.estimate.meanMotoHours,
                tr.estimate.meanAdaptiveNonReserveLevel, tr.estimate.medianAdaptiveNonReserveLevel,
                ensMax, lolpMax, lolehMax,
                baseline.meanLcoeRubPerKwh, baseline.ensStats.getMean(), baseline.meanLolp, baseline.meanLoleHours);
    }

    private record WeightPoint(double wE, double wT, double wH, double wD, double wR) {
        String key() {
            return String.format(Locale.US, "%.6f|%.6f|%.6f|%.6f|%.6f", wE, wT, wH, wD, wR);
        }
    }

    private record TuneResult(String kind, int seq, WeightPoint weights, MonteCarloEstimate estimate, double score) {}

    private static final class Defaults {
        private Defaults() {
        }

        // Paths
        static final String WIND_PATH = "D:/08_ModelingData/02_Wind.txt";
        static final String RESULTS_XLSX = "D:/results.xlsx";
        static final String TRACE_XLSX = "D:/trace.xlsx";
        static final String TUNE_CSV = "D:/adaptive_tune.csv";

        // Load paths (can be overridden by --load=)
        static final String LOAD_GOK = "D:/08_ModelingData/01_Load_g.txt";
        static final String LOAD_KOMUNAL = "D:/08_ModelingData/02_Load_k.txt";
        static final String LOAD_SELHOZ = "D:/08_ModelingData/01_Load_s.txt";
        static final String LOAD_DEF = "D:/08_ModelingData/01_Load.txt";

        // Max load by profile
        static final int MAX_LOAD_GOK = 1346;
        static final int MAX_LOAD_KOMUNAL = 1346;
        static final int MAX_LOAD_SELHOZ = 1346;
        static final int MAX_LOAD_DEF = 1346;

        // Categories share (k1, k2); k3 implied = 1 - k1 - k2
        static final double DEFAULT_FIRST_CAT = ModelDefaults.DEFAULT_FIRST_CAT;
        static final double DEFAULT_SECOND_CAT = ModelDefaults.DEFAULT_SECOND_CAT;

        // WT
        static final int DEFAULT_WT_COUNT_TOTAL = ModelDefaults.DEFAULT_WT_COUNT_TOTAL;
        static final double DEFAULT_WT_POWER_KW = ModelDefaults.DEFAULT_WT_POWER_KW;

        // DG
        static final int DEFAULT_DG_COUNT_TOTAL = ModelDefaults.DEFAULT_DG_COUNT_TOTAL;
        static final double DEFAULT_DG_POWER_KW = ModelDefaults.DEFAULT_DG_POWER_KW;

        // Battery
        static final double DEFAULT_BT_CAPACITY_KWH_PER_BUS = ModelDefaults.DEFAULT_BT_CAPACITY_KWH_PER_BUS;
        static final double DEFAULT_BT_MAX_CHARGE_CURRENT = ModelDefaults.DEFAULT_BT_MAX_CHARGE_CURRENT;
        static final double DEFAULT_BT_MAX_DISCHARGE_CURRENT = ModelDefaults.DEFAULT_BT_MAX_DISCHARGE_CURRENT;
        static final double DEFAULT_BT_NON_RESERVE_DISCHARGE_LEVEL = ModelDefaults.DEFAULT_BT_NON_RESERVE_DISCHARGE_LEVEL;
        static final boolean DEFAULT_BT_USE_ADAPTIVE_NON_RESERVE_DISCHARGE_LEVEL = ModelDefaults.DEFAULT_BT_USE_ADAPTIVE_NON_RESERVE_DISCHARGE_LEVEL;
        static final double DEFAULT_BT_ADAPTIVE_DEFICIT_WEIGHT = ModelDefaults.DEFAULT_BT_ADAPTIVE_DEFICIT_WEIGHT;
        static final double DEFAULT_BT_ADAPTIVE_TREND_WEIGHT = ModelDefaults.DEFAULT_BT_ADAPTIVE_TREND_WEIGHT;
        static final double DEFAULT_BT_ADAPTIVE_ACCELERATION_WEIGHT = ModelDefaults.DEFAULT_BT_ADAPTIVE_ACCELERATION_WEIGHT;
        static final double DEFAULT_BT_ADAPTIVE_NO_DG_PREV_HOUR_WEIGHT = ModelDefaults.DEFAULT_BT_ADAPTIVE_NO_DG_PREV_HOUR_WEIGHT;
        static final double DEFAULT_BT_ADAPTIVE_REPLACEMENT_WEIGHT = ModelDefaults.DEFAULT_BT_ADAPTIVE_REPLACEMENT_WEIGHT;
        static final double DEFAULT_BT_ADAPTIVE_DG_AVAILABILITY_WEIGHT = ModelDefaults.DEFAULT_BT_ADAPTIVE_DG_AVAILABILITY_WEIGHT;
        static final double DEFAULT_BT_GRID_FORMING_RESERVE_SHARE = ModelDefaults.DEFAULT_BT_GRID_FORMING_RESERVE_SHARE;

        // Reliability (rates are double, repair times are int)
        static final double DEFAULT_WT_FAILURE_RATE_PER_YEAR = ModelDefaults.DEFAULT_WT_FAILURE_RATE_PER_YEAR;
        static final int DEFAULT_WT_REPAIR_TIME_HOURS = ModelDefaults.DEFAULT_WT_REPAIR_TIME_HOURS;

        static final double DEFAULT_DG_FAILURE_RATE_PER_YEAR = ModelDefaults.DEFAULT_DG_FAILURE_RATE_PER_YEAR;
        static final int DEFAULT_DG_REPAIR_TIME_HOURS = ModelDefaults.DEFAULT_DG_REPAIR_TIME_HOURS;

        static final double DEFAULT_BT_FAILURE_RATE_PER_YEAR = ModelDefaults.DEFAULT_BT_FAILURE_RATE_PER_YEAR;
        static final int DEFAULT_BT_REPAIR_TIME_HOURS = ModelDefaults.DEFAULT_BT_REPAIR_TIME_HOURS;

        static final double DEFAULT_BUS_FAILURE_RATE_PER_YEAR = ModelDefaults.DEFAULT_BUS_FAILURE_RATE_PER_YEAR;
        static final int DEFAULT_BUS_REPAIR_TIME_HOURS = ModelDefaults.DEFAULT_BUS_REPAIR_TIME_HOURS;

        static final double DEFAULT_BRK_FAILURE_RATE_PER_YEAR = ModelDefaults.DEFAULT_BRK_FAILURE_RATE_PER_YEAR;
        static final int DEFAULT_BRK_REPAIR_TIME_HOURS = ModelDefaults.DEFAULT_BRK_REPAIR_TIME_HOURS;

        static final double DEFAULT_SWITCHGEAR_ROOM_FAILURE_RATE_PER_YEAR = ModelDefaults.DEFAULT_SWITCHGEAR_ROOM_FAILURE_RATE_PER_YEAR;
        static final int DEFAULT_SWITCHGEAR_ROOM_REPAIR_TIME_HOURS = ModelDefaults.DEFAULT_SWITCHGEAR_ROOM_REPAIR_TIME_HOURS;
        static final double DEFAULT_BUS_CCF_BETA_SECTIONAL = ModelDefaults.DEFAULT_BUS_CCF_BETA_SECTIONAL;
        static final double DEFAULT_BUS_CCF_BETA_DOUBLE = ModelDefaults.DEFAULT_BUS_CCF_BETA_DOUBLE;

        static final double DEFAULT_IDLE_RESERVE_COEFF = ModelDefaults.CFG_IDLE_RESERVE_COEFF;
        static final double DEFAULT_ROTATION_RESERVE_COEFF = ModelDefaults.CFG_ROTATION_RESERVE_COEFF;

        // ---- Economics defaults ----
        static final double DEFAULT_DISCOUNT_RATE = ModelDefaults.DEFAULT_DISCOUNT_RATE;
        static final double DEFAULT_COST_RU_RUB = ModelDefaults.DEFAULT_COST_RU_RUB;
        static final double DEFAULT_COST_DG_RUB_PER_KW = ModelDefaults.DEFAULT_COST_DG_RUB_PER_KW;
        static final double DEFAULT_COST_DG_RUB_PER_KW_PER_KMH = ModelDefaults.DEFAULT_COST_DG_RUB_PER_KW_PER_KMH;
        static final double DEFAULT_COST_FUEL_RUB_PER_KT = ModelDefaults.DEFAULT_COST_FUEL_RUB_PER_KT;
        static final double DEFAULT_COST_WT_RUB_PER_KW = ModelDefaults.DEFAULT_COST_WT_RUB_PER_KW;
        static final double DEFAULT_COST_WT_RUB_PER_KW_PER_YEAR = ModelDefaults.DEFAULT_COST_WT_RUB_PER_KW_PER_YEAR;
        static final double DEFAULT_COST_BT_RUB_PER_KWH = ModelDefaults.DEFAULT_COST_BT_RUB_PER_KWH;
        static final double DEFAULT_COST_BT_RUB_PER_KWH_PER_YEAR = ModelDefaults.DEFAULT_COST_BT_RUB_PER_KWH_PER_YEAR;
        static final double DEFAULT_DAMAGE_RUB_PER_KWH_CAT3 = ModelDefaults.DEFAULT_DAMAGE_RUB_PER_KWH_CAT3;
        static final double DEFAULT_DAMAGE_RUB_PER_KWH_CAT2 = ModelDefaults.DEFAULT_DAMAGE_RUB_PER_KWH_CAT2;
        static final double DEFAULT_DAMAGE_RUB_PER_KWH_CAT1 = ModelDefaults.DEFAULT_DAMAGE_RUB_PER_KWH_CAT1;

        // ---- SimulationConfig defaults (match SimulationConfig constructor) ----
        static final boolean CFG_CONSIDER_FAILURES = ModelDefaults.CFG_CONSIDER_FAILURES;
        static final boolean CFG_CONSIDER_MAINTENANCE = ModelDefaults.CFG_CONSIDER_MAINTENANCE;
        static final boolean CFG_CONSIDER_HOT_RESERVE = ModelDefaults.CFG_CONSIDER_HOT_RESERVE;
        static final boolean CFG_CONSIDER_BATTERY_DEGRADATION = ModelDefaults.CFG_CONSIDER_BATTERY_DEGRADATION;
        static final boolean CFG_RESERVE_THIRD_CATEGORY = ModelDefaults.CFG_RESERVE_THIRD_CATEGORY;
        static final boolean CFG_CONSIDER_ROTATION_RESERVE = ModelDefaults.CFG_CONSIDER_ROTATION_RESERVE;
        static final boolean CFG_KEEP_ONE_DG_INSTANT_START_READY_AFTER_WT_BESS_GRID_FORMING =
                ModelDefaults.CFG_KEEP_ONE_DG_INSTANT_START_READY_AFTER_WT_BESS_GRID_FORMING;
    }

    public static void main(String[] args) {
        Cli cli = Cli.parse(args);

        String loadFilePath = (cli.loadFilePath != null) ? cli.loadFilePath : resolveLoadPath(cli.loadType);
        resolveAndSetMaxLoad(cli.loadType);
        if (cli.maxLoadOverride != null) MAX_LOAD = cli.maxLoadOverride;

        try {
            ScenarioFactory.LoadedInput li = ScenarioFactory.load(loadFilePath, cli.windFilePath);
            SystemParameters baseParams = ScenarioFactory.defaultParams(cli.busType);

            switch (cli.task) {
                case RUN -> runTaskRun(li, baseParams, cli);
                case SOBOL_HARD -> runTaskSobolHard(li, baseParams, cli);
                case SOBOL_ECON -> runTaskSobolEcon(baseParams, cli);
                case ADAPTIVE_TUNE -> runTaskAdaptiveTune(li, baseParams, cli);
            }

        } catch (Exception e) {
            System.err.println("Ошибка: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static final class ScenarioFactory {
        private ScenarioFactory() {
        }

        static LoadedInput load(String loadPath, String windPath) throws Exception {
            InputData input = new InputDataLoader().load(loadPath, windPath);

            double[] load = input.getLoadKw();
            double[] wind = input.getWindMs();

            if (load.length != SimulationConstants.DATA_SIZE || wind.length != SimulationConstants.DATA_SIZE) {
                throw new IllegalStateException(
                        "Неверная длина входных данных. Ожидалось " + SimulationConstants.DATA_SIZE
                                + ", нагрузка: " + load.length
                                + ", ветер: " + wind.length
                );
            }
            return new LoadedInput(load, wind);
        }

        static SystemParameters defaultParams(BusSystemType busSystemType) {
            return new SystemParameters(
                    busSystemType,

                    Defaults.DEFAULT_FIRST_CAT,
                    Defaults.DEFAULT_SECOND_CAT,

                    Defaults.DEFAULT_WT_COUNT_TOTAL,
                    Defaults.DEFAULT_WT_POWER_KW,

                    Defaults.DEFAULT_DG_COUNT_TOTAL,
                    Defaults.DEFAULT_DG_POWER_KW,

                    Defaults.DEFAULT_BT_CAPACITY_KWH_PER_BUS,

                    Defaults.DEFAULT_BT_MAX_CHARGE_CURRENT,
                    Defaults.DEFAULT_BT_MAX_DISCHARGE_CURRENT,
                    Defaults.DEFAULT_BT_NON_RESERVE_DISCHARGE_LEVEL,
                    Defaults.DEFAULT_BT_USE_ADAPTIVE_NON_RESERVE_DISCHARGE_LEVEL,
                    Defaults.DEFAULT_BT_ADAPTIVE_DEFICIT_WEIGHT,
                    Defaults.DEFAULT_BT_ADAPTIVE_TREND_WEIGHT,
                    Defaults.DEFAULT_BT_ADAPTIVE_ACCELERATION_WEIGHT,
                    Defaults.DEFAULT_BT_ADAPTIVE_NO_DG_PREV_HOUR_WEIGHT,
                    Defaults.DEFAULT_BT_ADAPTIVE_REPLACEMENT_WEIGHT,
                    Defaults.DEFAULT_BT_ADAPTIVE_DG_AVAILABILITY_WEIGHT,
                    Defaults.DEFAULT_BT_GRID_FORMING_RESERVE_SHARE,

                    Defaults.DEFAULT_WT_FAILURE_RATE_PER_YEAR,
                    Defaults.DEFAULT_WT_REPAIR_TIME_HOURS,

                    Defaults.DEFAULT_DG_FAILURE_RATE_PER_YEAR,
                    Defaults.DEFAULT_DG_REPAIR_TIME_HOURS,

                    Defaults.DEFAULT_BT_FAILURE_RATE_PER_YEAR,
                    Defaults.DEFAULT_BT_REPAIR_TIME_HOURS,

                    Defaults.DEFAULT_BUS_FAILURE_RATE_PER_YEAR,
                    Defaults.DEFAULT_BUS_REPAIR_TIME_HOURS,

                    Defaults.DEFAULT_BRK_FAILURE_RATE_PER_YEAR,
                    Defaults.DEFAULT_BRK_REPAIR_TIME_HOURS,

                    Defaults.DEFAULT_SWITCHGEAR_ROOM_FAILURE_RATE_PER_YEAR,
                    Defaults.DEFAULT_SWITCHGEAR_ROOM_REPAIR_TIME_HOURS,

                    Defaults.DEFAULT_BUS_CCF_BETA_SECTIONAL,
                    Defaults.DEFAULT_BUS_CCF_BETA_DOUBLE,

                    Defaults.DEFAULT_IDLE_RESERVE_COEFF,
                    Defaults.DEFAULT_ROTATION_RESERVE_COEFF,
                    Defaults.CFG_KEEP_ONE_DG_INSTANT_START_READY_AFTER_WT_BESS_GRID_FORMING,

                    Defaults.DEFAULT_DISCOUNT_RATE,
                    Defaults.DEFAULT_COST_RU_RUB,
                    Defaults.DEFAULT_COST_DG_RUB_PER_KW,
                    Defaults.DEFAULT_COST_DG_RUB_PER_KW_PER_KMH,
                    Defaults.DEFAULT_COST_FUEL_RUB_PER_KT,
                    Defaults.DEFAULT_COST_WT_RUB_PER_KW,
                    Defaults.DEFAULT_COST_WT_RUB_PER_KW_PER_YEAR,
                    Defaults.DEFAULT_COST_BT_RUB_PER_KWH,
                    Defaults.DEFAULT_COST_BT_RUB_PER_KWH_PER_YEAR,
                    Defaults.DEFAULT_DAMAGE_RUB_PER_KWH_CAT1,
                    Defaults.DEFAULT_DAMAGE_RUB_PER_KWH_CAT2,
                    Defaults.DEFAULT_DAMAGE_RUB_PER_KWH_CAT3
            );
        }

        static SimulationConfig defaultConfig(double[] windMs, int mcIterations, int threads, boolean computeEconomyDrivers) {
            return new SimulationConfig(
                    windMs,
                    mcIterations,
                    threads,
                    Defaults.CFG_CONSIDER_FAILURES,
                    Defaults.CFG_CONSIDER_MAINTENANCE,
                    Defaults.CFG_RESERVE_THIRD_CATEGORY,
                    Defaults.CFG_CONSIDER_HOT_RESERVE,
                    Defaults.CFG_CONSIDER_BATTERY_DEGRADATION,
                    Defaults.CFG_CONSIDER_ROTATION_RESERVE,
                    computeEconomyDrivers
            );
        }

        record LoadedInput(double[] totalLoadKw, double[] windMs) {
        }
    }

    private static String resolveLoadPath(LoadType lt) {
        return switch (lt) {
            case GOK -> Defaults.LOAD_GOK;
            case KOMUNAL -> Defaults.LOAD_KOMUNAL;
            case SELHOZ -> Defaults.LOAD_SELHOZ;
            case DEF -> Defaults.LOAD_DEF;
        };
    }

    private static void resolveAndSetMaxLoad(LoadType lt) {
        MAX_LOAD = switch (lt) {
            case GOK -> Defaults.MAX_LOAD_GOK;
            case KOMUNAL -> Defaults.MAX_LOAD_KOMUNAL;
            case SELHOZ -> Defaults.MAX_LOAD_SELHOZ;
            case DEF -> Defaults.MAX_LOAD_DEF;
        };
    }

    private static double scaleByPool(TunableParamId id, double u01) {
        return TunableParameterPool.get(id).scaleFromUnit(u01);
    }

    private static EconomyDrivers withDiscountRate(EconomyDrivers d, double discountRatePerYear) {
        return new EconomyDrivers(
                d.servedKwhByYear,
                d.fuelLitersByYear,
                d.motoHoursByYear,
                d.btReplByYear,
                d.ensCat1KwhByYear,
                d.ensCat2KwhByYear,
                d.ensCat3KwhByYear,
                d.dgTotalKw,
                d.dgUnitKw,
                d.wtTotalKw,
                d.btTotalKwh,
                discountRatePerYear
        );
    }

    private record EconInputs(UnitCosts costs, double discountRatePerYear) {
    }

    private static EconInputs econInputsFromUnitRow(double[] u01, List<TunableParamId> ids, BusSystemType busType) {
        // Порядок ids должен совпадать с заполнением строки u01
        double discountRate = Double.NaN;
        double ruRaw = 0, dg = 0, wt = 0, bt = 0, fuel = 0, moto = 0, wtOpex = 0, btOpex = 0, dmg3 = 0;

        for (int k = 0; k < ids.size(); k++) {
            TunableParamId id = ids.get(k);
            double v = scaleByPool(id, u01[k]);
            switch (id) {
                case DISCOUNT_RATE -> discountRate = v;
                case COST_RU_RUB -> ruRaw = v;
                case COST_DG_RUB_PER_KW -> dg = v;
                case COST_WT_RUB_PER_KW -> wt = v;
                case COST_BT_RUB_PER_KWH -> bt = v;
                case COST_FUEL_RUB_PER_KT -> fuel = v;
                case COST_DG_RUB_PER_KW_PER_KMH -> moto = v;
                case COST_WT_RUB_PER_KW_PER_YEAR -> wtOpex = v;
                case COST_BT_RUB_PER_KWH_PER_YEAR -> btOpex = v;
                case DAMAGE_RUB_PER_KWH_CAT3 -> dmg3 = v;
                default -> throw new IllegalArgumentException("Unsupported econ factor in SOBOL_ECON: " + id);
            }
        }

        if (Double.isNaN(discountRate)) discountRate = ModelDefaults.DEFAULT_DISCOUNT_RATE;

        double ruEff = RuCostAdjuster.effectiveRuCost(busType, ruRaw);
        double dmg2 = 5.0 * dmg3;
        double dmg1 = 7.0 * dmg3;

        UnitCosts costs = new UnitCosts(ruEff, dg, wt, bt, fuel, moto, wtOpex, btOpex, dmg1, dmg2, dmg3);
        return new EconInputs(costs, discountRate);
    }

    private static double[] buildGrid01(double step) {
        if (step <= 0) throw new IllegalArgumentException("step must be > 0");
        int n = (int) Math.round(1.0 / step);
        double check = n * step;
        if (Math.abs(check - 1.0) > 1e-9) {
            throw new IllegalArgumentException("step must divide 1.0 exactly (e.g. 0.05, 0.025). step=" + step);
        }
        double[] grid = new double[n + 1];
        for (int i = 0; i <= n; i++) grid[i] = i * step;
        return grid;
    }
}