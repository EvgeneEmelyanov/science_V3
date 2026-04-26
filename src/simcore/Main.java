package simcore;

import simcore.config.*;
import simcore.config.ModelDefaults;
import simcore.economy.*;
import simcore.engine.*;
import simcore.io.InputData;
import simcore.io.InputDataLoader;
import simcore.io.SweepResultsExcelWriter;
import simcore.sobol.*;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Main {
    private static final Locale OUT_LOCALE = Locale.forLanguageTag("ru-RU");

    public enum Task {RUN, SOBOL_HARD, SOBOL_ECON, ADAPTIVE_TUNE}

    public enum RunMode {SINGLE, SWEEP_1, SWEEP_2}

    public enum LoadType {GOK, KOMUNAL, SELHOZ, DEF}

    public enum TuneObjective {LCOE, LOLH}

    public static double MAX_LOAD;

    private static final class Cli {

        Task task = Task.RUN;
        RunMode runMode = RunMode.SINGLE;
        int mcIterations = 1;

        BusSystemType busType = BusSystemType.DOUBLE_BUS;

        SobolConfig.SeedMode sobolSeedMode = SobolConfig.SeedMode.HYBRID_BY_TYPE;
        int sobolN = 256;

        String exportDriversPath = null;

        String econDriversPath = "D:/econ_drivers.csv";
        String econCaseId = "case_0";
        Integer econN = null;

        LoadType loadType = LoadType.DEF;
        Integer maxLoadOverride = null;
        int threads = Runtime.getRuntime().availableProcessors();
        long mcBaseSeed = 1_000_000L;
        String loadFilePath = null;
        String windFilePath = Defaults.WIND_PATH;
        String resultsXlsxPath = Defaults.RESULTS_XLSX;
        String traceXlsxPath = Defaults.TRACE_XLSX;

        // Adaptive tuning
        TuneObjective tuneOptimizeBy = TuneObjective.LOLH;

        int tuneSamples = 256;
        int tuneStage1Mc = 50;
        int tuneStage2Mc = 50;
        int tuneBaselineMc = 50;

        int tuneTopPrimary = 10;        // топ по выбранной цели с учетом ограничения
        int tuneTopCompromise = 0;      // мягкий компромисс LCOE+LOLH
        int tuneTopPareto = 30;         // сколько Pareto-точек печатать / отбирать
        double tuneConstraintTolRel = 0.0;

        String tuneCsvPath = "D:/adaptive_tune.csv";

        int tuneSobolSkip = 16;

        // Stage 2 local search
        int tuneStage2Samples = 64;
        int tuneStage2SobolSkip = 0;
        double tuneStage2RadiusFracWE = 0.10;
        double tuneStage2RadiusFracWT = 0.10;
        double tuneStage2RadiusFracWA = 0.10;
        double tuneStage2RadiusFracWH = 0.10;
        double tuneStage2RadiusFracWD = 0.10;
        double tuneStage2RadiusFracWR = 0.10;

//        double tuneWEMin = 0.0, tuneWEMax = 0.4;
//        double tuneWTMin = 0.0, tuneWTMax = 0.4;
//        double tuneWAMin = 0.0, tuneWAMax = 0.7;
//        double tuneWHMin = 0.0, tuneWHMax = 4.0;
//        double tuneWDMin = 0.0, tuneWDMax = 0.4;
//        double tuneWRMin = 0.0, tuneWRMax = 2.0;

        double tuneWEMin = 0.0, tuneWEMax = 1;
        double tuneWTMin = 0.0, tuneWTMax = 2;
        double tuneWAMin = 0.0, tuneWAMax = 1;
        double tuneWHMin = 0.0, tuneWHMax = 4.0;
        double tuneWDMin = 0.0, tuneWDMax = 1;
        double tuneWRMin = 1.0, tuneWRMax = 4.0;

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

                if (a.startsWith("--maxLoad=")) {
                    c.maxLoadOverride = Integer.parseInt(a.substring("--maxLoad=".length()).trim());
                }

                if (a.startsWith("--sobolN=")) c.sobolN = Integer.parseInt(a.substring("--sobolN=".length()).trim());

                if (a.startsWith("--sobolSeedMode=")) {
                    c.sobolSeedMode = SobolConfig.SeedMode.valueOf(a.substring("--sobolSeedMode=".length()).trim());
                }

                if (a.startsWith("--exportDrivers=")) {
                    String p = a.substring("--exportDrivers=".length()).trim();
                    c.exportDriversPath = p.isEmpty() ? null : p;
                }

                if (a.startsWith("--econDrivers=")) c.econDriversPath = a.substring("--econDrivers=".length()).trim();
                if (a.startsWith("--econCase=")) c.econCaseId = a.substring("--econCase=".length()).trim();
                if (a.startsWith("--econN=")) c.econN = Integer.parseInt(a.substring("--econN=".length()).trim());

                if (a.startsWith("--tuneOptimizeBy="))
                    c.tuneOptimizeBy = TuneObjective.valueOf(a.substring("--tuneOptimizeBy=".length()).trim());

                if (a.startsWith("--tuneSamples="))
                    c.tuneSamples = Integer.parseInt(a.substring("--tuneSamples=".length()).trim());
                if (a.startsWith("--tuneStage1Mc="))
                    c.tuneStage1Mc = Integer.parseInt(a.substring("--tuneStage1Mc=".length()).trim());
                if (a.startsWith("--tuneStage2Mc="))
                    c.tuneStage2Mc = Integer.parseInt(a.substring("--tuneStage2Mc=".length()).trim());
                if (a.startsWith("--tuneBaselineMc="))
                    c.tuneBaselineMc = Integer.parseInt(a.substring("--tuneBaselineMc=".length()).trim());

                if (a.startsWith("--tuneTopPrimary="))
                    c.tuneTopPrimary = Integer.parseInt(a.substring("--tuneTopPrimary=".length()).trim());
                if (a.startsWith("--tuneTopCompromise="))
                    c.tuneTopCompromise = Integer.parseInt(a.substring("--tuneTopCompromise=".length()).trim());
                if (a.startsWith("--tuneTopPareto="))
                    c.tuneTopPareto = Integer.parseInt(a.substring("--tuneTopPareto=".length()).trim());
                if (a.startsWith("--tuneConstraintTolRel="))
                    c.tuneConstraintTolRel = Double.parseDouble(a.substring("--tuneConstraintTolRel=".length()).trim());

                if (a.startsWith("--tuneCsv=")) c.tuneCsvPath = a.substring("--tuneCsv=".length()).trim();
                if (a.startsWith("--tuneSobolSkip="))
                    c.tuneSobolSkip = Integer.parseInt(a.substring("--tuneSobolSkip=".length()).trim());

                if (a.startsWith("--tuneStage2Samples="))
                    c.tuneStage2Samples = Integer.parseInt(a.substring("--tuneStage2Samples=".length()).trim());
                if (a.startsWith("--tuneStage2SobolSkip="))
                    c.tuneStage2SobolSkip = Integer.parseInt(a.substring("--tuneStage2SobolSkip=".length()).trim());

                if (a.startsWith("--tuneStage2RadiusFracWE="))
                    c.tuneStage2RadiusFracWE = Double.parseDouble(a.substring("--tuneStage2RadiusFracWE=".length()).trim());
                if (a.startsWith("--tuneStage2RadiusFracWT="))
                    c.tuneStage2RadiusFracWT = Double.parseDouble(a.substring("--tuneStage2RadiusFracWT=".length()).trim());
                if (a.startsWith("--tuneStage2RadiusFracWA="))
                    c.tuneStage2RadiusFracWA = Double.parseDouble(a.substring("--tuneStage2RadiusFracWA=".length()).trim());
                if (a.startsWith("--tuneStage2RadiusFracWH="))
                    c.tuneStage2RadiusFracWH = Double.parseDouble(a.substring("--tuneStage2RadiusFracWH=".length()).trim());
                if (a.startsWith("--tuneStage2RadiusFracWD="))
                    c.tuneStage2RadiusFracWD = Double.parseDouble(a.substring("--tuneStage2RadiusFracWD=".length()).trim());
                if (a.startsWith("--tuneStage2RadiusFracWR="))
                    c.tuneStage2RadiusFracWR = Double.parseDouble(a.substring("--tuneStage2RadiusFracWR=".length()).trim());

                if (a.startsWith("--tuneWEMin="))
                    c.tuneWEMin = Double.parseDouble(a.substring("--tuneWEMin=".length()).trim());
                if (a.startsWith("--tuneWEMax="))
                    c.tuneWEMax = Double.parseDouble(a.substring("--tuneWEMax=".length()).trim());
                if (a.startsWith("--tuneWTMin="))
                    c.tuneWTMin = Double.parseDouble(a.substring("--tuneWTMin=".length()).trim());
                if (a.startsWith("--tuneWTMax="))
                    c.tuneWTMax = Double.parseDouble(a.substring("--tuneWTMax=".length()).trim());
                if (a.startsWith("--tuneWAMin="))
                    c.tuneWAMin = Double.parseDouble(a.substring("--tuneWAMin=".length()).trim());
                if (a.startsWith("--tuneWAMax="))
                    c.tuneWAMax = Double.parseDouble(a.substring("--tuneWAMax=".length()).trim());
                if (a.startsWith("--tuneWHMin="))
                    c.tuneWHMin = Double.parseDouble(a.substring("--tuneWHMin=".length()).trim());
                if (a.startsWith("--tuneWHMax="))
                    c.tuneWHMax = Double.parseDouble(a.substring("--tuneWHMax=".length()).trim());
                if (a.startsWith("--tuneWDMin="))
                    c.tuneWDMin = Double.parseDouble(a.substring("--tuneWDMin=".length()).trim());
                if (a.startsWith("--tuneWDMax="))
                    c.tuneWDMax = Double.parseDouble(a.substring("--tuneWDMax=".length()).trim());
                if (a.startsWith("--tuneWRMin="))
                    c.tuneWRMin = Double.parseDouble(a.substring("--tuneWRMin=".length()).trim());
                if (a.startsWith("--tuneWRMax="))
                    c.tuneWRMax = Double.parseDouble(a.substring("--tuneWRMax=".length()).trim());
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
                                .setDieselGeneratorPowerKw(p2)
                                .setWindTurbinePowerKw(p1)
//                                .setBatteryCapacityKwhPerBus(p2 * 1346 / 2)
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

        final boolean sweepCatsTriangle = true;
        final double catStep = 0.1;

//        double[] param1 = new double[]{6, 8, 10};

        double[] param2 = new double[]{
                150,
                160, 170, 180, 190, 200,
                210, 220, 230, 240, 250,
                260, 270, 280, 290, 300,
////                310, 320, 330, 340, 350,
////                360, 370, 380, 390, 400,
////                410, 420, 430, 440, 450,
////                460, 470, 480, 490, 500,
////                510, 520, 530, 540, 550,
////                560, 570, 580, 590, 600
        };

//        double[] param2 = new double[]{
//                1346, 1480, 1615, 1749, 1884, 2019,
//                2153, 2288, 2422, 2557, 2692
//        };

        double[] param1 = new double[] {
                0.0,
//                168.25,
//                336.5,
//                504.75,
                673.0,
//                841.25,
//                1009.5,
//                1177.75,
                1346.0,
//                1514.25,
//                1682.5,
//                1850.75,
                2019.0
        };
//        double[] param2 = new double[]{
//                0.0,
//                0.1,
//                0.2,
//                0.3,
//                0.4,
//                0.5,
//                0.6,
//                0.7,
//                0.8,
//                0.9,
//                1.0,
//        };

//        double[] param1 = new double[]{
//                0.2, 0.225, 0.25, 0.275,
//                0.3, 0.325, 0.35, 0.375,
//                0.4, 0.425, 0.45, 0.475,
//                0.5, 0.525, 0.55, 0.575,
//                0.6, 0.625, 0.65, 0.675,
//                0.7, 0.725, 0.75, 0.775,
//                0.8, 0.825, 0.85, 0.875,
//                0.9, 0.925, 0.95, 0.975,
//                1.0
//        };
//
//        double[] param2 = new double[]{
//                0.4, 0.425, 0.45, 0.475,
//                0.5, 0.525, 0.55, 0.575,
//                0.6, 0.625, 0.65, 0.675,
//                0.7, 0.725, 0.75, 0.775,
//                0.8, 0.825, 0.85, 0.875,
//        };

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

                if (allowTrace && est.singleRun != null && est.singleRun.trace != null && !est.singleRun.trace.isEmpty()) {
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
                TunableParamId.DG_POWER,
                TunableParamId.DG_COUNT,
                TunableParamId.WT_POWER,
                TunableParamId.BT_CAPACITY_PER_BUS,
                TunableParamId.BT_MAX_DISCHARGE_CURRENT,
                TunableParamId.BT_MAX_CHARGE_CURRENT,
                TunableParamId.BT_NON_RESERVE_DISCHARGE_LVL


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

    private record TuneWeights(double wE, double wT, double wA, double wH, double wD, double wR) {
        String key() {
            return String.format(OUT_LOCALE, "%.6f|%.6f|%.6f|%.6f|%.6f|%.6f", wE, wT, wA, wH, wD, wR);
        }
    }

    private record TuneResult(TuneWeights weights, MonteCarloEstimate estimate, double compromise) {
    }

    private record WeightBounds(
            double weMin, double weMax,
            double wtMin, double wtMax,
            double waMin, double waMax,
            double whMin, double whMax,
            double wdMin, double wdMax,
            double wrMin, double wrMax
    ) {
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
        System.out.printf(OUT_LOCALE, "metric(A∪B)  LCOE: var=%.6g std=%.6g range=[%.6g..%.6g]%n",
                varY, Math.sqrt(varY), minY, maxY);
        for (int j = 0; j < d; j++) {
            System.out.printf(OUT_LOCALE, "%-28s  S=%.6f  ST=%.6f%n", TunableParameterPool.get(econIds.get(j)).getName(), S[j], ST[j]);
        }
    }

    private static void runTaskAdaptiveTune(ScenarioFactory.LoadedInput li, SystemParameters baseParams, Cli cli) throws Exception {
        validateAdaptiveTuneCli(cli);

        ExecutorService ex = Executors.newFixedThreadPool(cli.threads);
        try {
            SingleRunSimulator sim = new SingleRunSimulator();
            MonteCarloRunner mc = new MonteCarloRunner(ex, sim, false, 1.96, 0.1);
            SimulationEngine engine = new SimulationEngine(mc);

            SimulationConfig cfgBaseline = ScenarioFactory.defaultConfig(li.windMs(), cli.tuneBaselineMc, cli.threads, false);
            SimulationConfig cfgStage1 = ScenarioFactory.defaultConfig(li.windMs(), cli.tuneStage1Mc, cli.threads, false);
            SimulationConfig cfgStage2 = (cli.tuneStage2Mc > 0)
                    ? ScenarioFactory.defaultConfig(li.windMs(), cli.tuneStage2Mc, cli.threads, false)
                    : null;

            SystemParameters staticBaselineParams = SystemParametersBuilder.from(baseParams)
                    .setBtUseAdaptiveNonReserveDischargeLevel(false)
                    .build();

            System.out.println("=== ADAPTIVE_TUNE static baseline ===");
            MonteCarloEstimate baseline = engine.runMonteCarlo(
                    new SimInput(cfgBaseline, staticBaselineParams, li.totalLoadKw()),
                    cli.tuneBaselineMc,
                    cli.mcBaseSeed,
                    false
            );

            System.out.printf(OUT_LOCALE,
                    "baseline(static NRL): LCOE=%.6f ENS=%.6f LOLH=%.6f LOLP=%.6e LPSP=%.6e ENS_evtN=%.6f Fuel=%.6f Moto=%.6f%n",
                    baseline.meanLcoeRubPerKwh,
                    baseline.ensStats.getMean(),
                    baseline.meanLoleHours,
                    baseline.meanLolp,
                    baseline.meanLpsp,
                    baseline.meanEnsEventsTotal,
                    baseline.meanFuelLiters,
                    baseline.meanMotoHours
            );

            WeightBounds globalBounds = globalBounds(cli);

            System.out.println("=== ADAPTIVE_TUNE objective = " + cli.tuneOptimizeBy + " ===");
            System.out.println("secondary constraint: " + secondaryConstraintName(cli));

            System.out.println("=== ADAPTIVE_TUNE stage 1: global Sobol ===");
            List<TuneWeights> stage1Candidates = generateSobolTuneWeights(globalBounds, cli.tuneSamples, cli.tuneSobolSkip);
            List<TuneResult> stage1 = evaluateCandidates(
                    engine, li, cfgStage1, baseParams, cli.mcBaseSeed, cli.tuneStage1Mc, baseline, stage1Candidates
            );

            List<TuneResult> finalResults;
            if (cli.tuneStage2Mc > 0 && cli.tuneStage2Samples > 0) {
                System.out.println("=== ADAPTIVE_TUNE stage 2: local Sobol around elite points ===");
                finalResults = runStage2LocalSearch(
                        stage1, cli, engine, li, cfgStage2, baseParams, cli.mcBaseSeed, cli.tuneStage2Mc, baseline, globalBounds
                );
            } else if (cli.tuneStage2Mc > 0) {
                finalResults = reevaluateTopUnion(
                        stage1, cli, engine, li, cfgStage2, baseParams, cli.mcBaseSeed, cli.tuneStage2Mc, baseline
                );
            } else {
                finalResults = selectTopUnionFromStage1(stage1, cli, baseline);
            }

            List<TuneResult> feasiblePrimary = filterPrimaryFeasible(finalResults, baseline, cli);
            List<TuneResult> pareto = paretoFront(finalResults);

            writeTuneCsv(cli.tuneCsvPath, finalResults, baseline, cli);

            System.out.println("=== ADAPTIVE_TUNE final top by primary objective with secondary constraint ===");
            printTop(feasiblePrimary, primaryComparator(cli), cli.tuneTopPrimary);

            if (cli.tuneTopCompromise > 0) {
                System.out.println("=== ADAPTIVE_TUNE final top by compromise(LCOE+LOLH) ===");
                printTop(finalResults, Comparator.comparingDouble(TuneResult::compromise), cli.tuneTopCompromise);
            }

            if (cli.tuneTopPareto > 0) {
                System.out.println("=== ADAPTIVE_TUNE Pareto front (LCOE vs LOLH) ===");
                printTop(pareto, paretoComparator(), cli.tuneTopPareto);
            }

            System.out.println("Saved: " + cli.tuneCsvPath);

        } finally {
            ex.shutdown();
        }
    }

    private static void validateAdaptiveTuneCli(Cli cli) {
        if (cli.tuneSamples <= 0) throw new IllegalArgumentException("tuneSamples must be > 0");
        if (cli.tuneStage1Mc <= 0) throw new IllegalArgumentException("tuneStage1Mc must be > 0");
        if (cli.tuneBaselineMc <= 0) throw new IllegalArgumentException("tuneBaselineMc must be > 0");
        if (cli.tuneSobolSkip < 0) throw new IllegalArgumentException("tuneSobolSkip must be >= 0");
        if (cli.tuneStage2Mc < 0) throw new IllegalArgumentException("tuneStage2Mc must be >= 0");
        if (cli.tuneStage2Samples < 0) throw new IllegalArgumentException("tuneStage2Samples must be >= 0");
        if (cli.tuneStage2SobolSkip < 0) throw new IllegalArgumentException("tuneStage2SobolSkip must be >= 0");

        if (cli.tuneTopPrimary < 0) throw new IllegalArgumentException("tuneTopPrimary must be >= 0");
        if (cli.tuneTopCompromise < 0) throw new IllegalArgumentException("tuneTopCompromise must be >= 0");
        if (cli.tuneTopPareto < 0) throw new IllegalArgumentException("tuneTopPareto must be >= 0");
        if (cli.tuneConstraintTolRel < 0.0) throw new IllegalArgumentException("tuneConstraintTolRel must be >= 0");

        validateFrac(cli.tuneStage2RadiusFracWE, "tuneStage2RadiusFracWE");
        validateFrac(cli.tuneStage2RadiusFracWT, "tuneStage2RadiusFracWT");
        validateFrac(cli.tuneStage2RadiusFracWA, "tuneStage2RadiusFracWA");
        validateFrac(cli.tuneStage2RadiusFracWH, "tuneStage2RadiusFracWH");
        validateFrac(cli.tuneStage2RadiusFracWD, "tuneStage2RadiusFracWD");
        validateFrac(cli.tuneStage2RadiusFracWR, "tuneStage2RadiusFracWR");

        validateRange(cli.tuneWEMin, cli.tuneWEMax, "tuneWE");
        validateRange(cli.tuneWTMin, cli.tuneWTMax, "tuneWT");
        validateRange(cli.tuneWAMin, cli.tuneWAMax, "tuneWA");
        validateRange(cli.tuneWHMin, cli.tuneWHMax, "tuneWH");
        validateRange(cli.tuneWDMin, cli.tuneWDMax, "tuneWD");
        validateRange(cli.tuneWRMin, cli.tuneWRMax, "tuneWR");
    }

    private static void validateRange(double min, double max, String name) {
        if (!(max >= min)) {
            throw new IllegalArgumentException(name + " max must be >= min");
        }
    }

    private static void validateFrac(double v, String name) {
        if (v < 0.0 || v > 0.5) {
            throw new IllegalArgumentException(name + " must be in [0.0, 0.5]");
        }
    }

    private static WeightBounds globalBounds(Cli cli) {
        return new WeightBounds(
                cli.tuneWEMin, cli.tuneWEMax,
                cli.tuneWTMin, cli.tuneWTMax,
                cli.tuneWAMin, cli.tuneWAMax,
                cli.tuneWHMin, cli.tuneWHMax,
                cli.tuneWDMin, cli.tuneWDMax,
                cli.tuneWRMin, cli.tuneWRMax
        );
    }

    private static List<TuneWeights> generateSobolTuneWeights(WeightBounds bounds, int samples, int sobolSkip) {
        double[][] unitPoints = SobolMath.generateSequence(samples, 6, sobolSkip);
        List<TuneWeights> out = new ArrayList<>(samples);

        for (double[] u : unitPoints) {
            out.add(new TuneWeights(
                    scaleToRange(u[0], bounds.weMin, bounds.weMax),
                    scaleToRange(u[1], bounds.wtMin, bounds.wtMax),
                    scaleToRange(u[2], bounds.waMin, bounds.waMax),
                    scaleToRange(u[3], bounds.whMin, bounds.whMax),
                    scaleToRange(u[4], bounds.wdMin, bounds.wdMax),
                    scaleToRange(u[5], bounds.wrMin, bounds.wrMax)
            ));
        }

        return out;
    }

    private static double scaleToRange(double u01, double min, double max) {
        return min + u01 * (max - min);
    }

    private static List<TuneResult> evaluateCandidates(SimulationEngine engine,
                                                       ScenarioFactory.LoadedInput li,
                                                       SimulationConfig cfg,
                                                       SystemParameters baseParams,
                                                       long mcBaseSeed,
                                                       int mcIterations,
                                                       MonteCarloEstimate baseline,
                                                       List<TuneWeights> candidates) throws Exception {
        List<TuneResult> out = new ArrayList<>(candidates.size());

        for (TuneWeights w : candidates) {
            MonteCarloEstimate est = evaluateWeights(engine, li, cfg, baseParams, mcBaseSeed, w, mcIterations);
            double compromise = compromiseMetric(est, baseline);
            out.add(new TuneResult(w, est, compromise));
        }

        return out;
    }

    private static boolean isPrimaryFeasible(TuneResult tr,
                                             MonteCarloEstimate baseline,
                                             Cli cli) {
        if (baseline == null) return true;

        double tol = Math.max(0.0, cli.tuneConstraintTolRel);

        return switch (cli.tuneOptimizeBy) {
            case LCOE -> tr.estimate().meanLoleHours <= baseline.meanLoleHours * (1.0 + tol);
            case LOLH -> tr.estimate().meanLcoeRubPerKwh <= baseline.meanLcoeRubPerKwh * (1.0 + tol);
        };
    }

    private static List<TuneResult> filterPrimaryFeasible(List<TuneResult> src,
                                                          MonteCarloEstimate baseline,
                                                          Cli cli) {
        List<TuneResult> out = new ArrayList<>();
        for (TuneResult tr : src) {
            if (isPrimaryFeasible(tr, baseline, cli)) {
                out.add(tr);
            }
        }
        return out;
    }

    private static Comparator<TuneResult> primaryComparator(Cli cli) {
        return switch (cli.tuneOptimizeBy) {
            case LCOE -> Comparator.comparingDouble((TuneResult tr) -> tr.estimate().meanLcoeRubPerKwh)
                    .thenComparingDouble(tr -> tr.estimate().meanLoleHours);
            case LOLH -> Comparator.comparingDouble((TuneResult tr) -> tr.estimate().meanLoleHours)
                    .thenComparingDouble(tr -> tr.estimate().meanLcoeRubPerKwh);
        };
    }

    private static Comparator<TuneResult> paretoComparator() {
        return Comparator.comparingDouble((TuneResult tr) -> tr.estimate().meanLcoeRubPerKwh)
                .thenComparingDouble(tr -> tr.estimate().meanLoleHours);
    }

    private static String primaryObjectiveName(Cli cli) {
        return cli.tuneOptimizeBy == TuneObjective.LCOE ? "LCOE" : "LOLH";
    }

    private static String secondaryConstraintName(Cli cli) {
        return cli.tuneOptimizeBy == TuneObjective.LCOE
                ? "LOLH <= baseline * (1 + tol)"
                : "LCOE <= baseline * (1 + tol)";
    }

    private static boolean dominates(TuneResult a, TuneResult b) {
        boolean noWorseLcoe = a.estimate().meanLcoeRubPerKwh <= b.estimate().meanLcoeRubPerKwh;
        boolean noWorseLole = a.estimate().meanLoleHours <= b.estimate().meanLoleHours;

        boolean strictlyBetterAtLeastOne =
                a.estimate().meanLcoeRubPerKwh < b.estimate().meanLcoeRubPerKwh
                        || a.estimate().meanLoleHours < b.estimate().meanLoleHours;

        return noWorseLcoe && noWorseLole && strictlyBetterAtLeastOne;
    }

    private static List<TuneResult> paretoFront(List<TuneResult> src) {
        List<TuneResult> out = new ArrayList<>();

        for (int i = 0; i < src.size(); i++) {
            TuneResult candidate = src.get(i);
            boolean dominated = false;

            for (int j = 0; j < src.size(); j++) {
                if (i == j) continue;
                if (dominates(src.get(j), candidate)) {
                    dominated = true;
                    break;
                }
            }

            if (!dominated) {
                out.add(candidate);
            }
        }

        out.sort(paretoComparator());
        return out;
    }

    private static void addPareto(List<TuneResult> src, int topK, Map<String, TuneWeights> dst) {
        List<TuneResult> pareto = paretoFront(src);
        for (int i = 0; i < Math.min(topK, pareto.size()); i++) {
            TuneWeights w = pareto.get(i).weights();
            dst.putIfAbsent(w.key(), w);
        }
    }

    private static void addParetoResults(List<TuneResult> src, int topK, Map<String, TuneResult> dst) {
        List<TuneResult> pareto = paretoFront(src);
        for (int i = 0; i < Math.min(topK, pareto.size()); i++) {
            TuneResult tr = pareto.get(i);
            dst.putIfAbsent(tr.weights().key(), tr);
        }
    }

    private static TuneResult minOrNull(List<TuneResult> src, Comparator<TuneResult> cmp) {
        if (src == null || src.isEmpty()) return null;
        return Collections.min(src, cmp);
    }

    private static TuneResult selectRepresentativeForZone(List<TuneResult> localResults,
                                                          MonteCarloEstimate baseline,
                                                          Cli cli) {
        List<TuneResult> feasiblePrimary = filterPrimaryFeasible(localResults, baseline, cli);
        List<TuneResult> pareto = paretoFront(localResults);

        List<TuneResult> pool = new ArrayList<>();

        TuneResult bestPrimary = minOrNull(feasiblePrimary, primaryComparator(cli));
        TuneResult bestComp = minOrNull(localResults, Comparator.comparingDouble(TuneResult::compromise));
        TuneResult bestParetoComp = minOrNull(pareto, Comparator.comparingDouble(TuneResult::compromise));

        if (bestPrimary != null) pool.add(bestPrimary);
        if (bestComp != null) pool.add(bestComp);
        if (bestParetoComp != null) pool.add(bestParetoComp);

        return minOrNull(pool, Comparator.comparingDouble(TuneResult::compromise));
    }

    private static List<TuneResult> runStage2LocalSearch(List<TuneResult> stage1,
                                                         Cli cli,
                                                         SimulationEngine engine,
                                                         ScenarioFactory.LoadedInput li,
                                                         SimulationConfig cfgStage2,
                                                         SystemParameters baseParams,
                                                         long mcBaseSeed,
                                                         int mcIterations,
                                                         MonteCarloEstimate baseline,
                                                         WeightBounds globalBounds) throws Exception {
        if (cfgStage2 == null) {
            throw new IllegalArgumentException("cfgStage2 is null");
        }
        if (mcIterations <= 0) {
            throw new IllegalArgumentException("mcIterations must be > 0 for runStage2LocalSearch");
        }

        Map<String, TuneWeights> elite = new LinkedHashMap<>();

        addTop(
                filterPrimaryFeasible(stage1, baseline, cli),
                primaryComparator(cli),
                cli.tuneTopPrimary,
                elite
        );

        addTop(
                stage1,
                Comparator.comparingDouble(TuneResult::compromise),
                cli.tuneTopCompromise,
                elite
        );

        addPareto(stage1, cli.tuneTopPareto, elite);

        Map<String, TuneResult> bestLocal = new LinkedHashMap<>();

        int eliteIdx = 0;
        for (TuneWeights center : elite.values()) {
            eliteIdx++;

            WeightBounds localBounds = localBoundsAround(center, globalBounds, cli);
            List<TuneWeights> localCandidates = generateSobolTuneWeights(
                    localBounds,
                    cli.tuneStage2Samples,
                    cli.tuneStage2SobolSkip
            );

            localCandidates = addCenterCandidateFirst(center, localCandidates);

            List<TuneResult> localResults = evaluateCandidates(
                    engine, li, cfgStage2, baseParams, mcBaseSeed, mcIterations, baseline, localCandidates
            );

            TuneResult representative = selectRepresentativeForZone(localResults, baseline, cli);
            if (representative == null) continue;

            bestLocal.put(representative.weights().key(), representative);

            System.out.printf(OUT_LOCALE,
                    "stage2 local zone %d: center=(%.4f, %.4f, %.4f, %.4f, %.4f, %.4f) representative: LCOE=%.6f LOLH=%.6f comp=%.6f%n",
                    eliteIdx,
                    center.wE(), center.wT(), center.wA(), center.wH(), center.wD(), center.wR(),
                    representative.estimate().meanLcoeRubPerKwh,
                    representative.estimate().meanLoleHours,
                    representative.compromise()
            );
        }

        return new ArrayList<>(bestLocal.values());
    }

    private static List<TuneWeights> addCenterCandidateFirst(TuneWeights center, List<TuneWeights> localCandidates) {
        LinkedHashMap<String, TuneWeights> uniq = new LinkedHashMap<>();
        uniq.put(center.key(), center);
        for (TuneWeights w : localCandidates) {
            uniq.putIfAbsent(w.key(), w);
        }
        return new ArrayList<>(uniq.values());
    }

    private static WeightBounds localBoundsAround(TuneWeights center, WeightBounds globalBounds, Cli cli) {
        return new WeightBounds(
                localMin(center.wE(), globalBounds.weMin, globalBounds.weMax, cli.tuneStage2RadiusFracWE),
                localMax(center.wE(), globalBounds.weMin, globalBounds.weMax, cli.tuneStage2RadiusFracWE),

                localMin(center.wT(), globalBounds.wtMin, globalBounds.wtMax, cli.tuneStage2RadiusFracWT),
                localMax(center.wT(), globalBounds.wtMin, globalBounds.wtMax, cli.tuneStage2RadiusFracWT),

                localMin(center.wA(), globalBounds.waMin, globalBounds.waMax, cli.tuneStage2RadiusFracWA),
                localMax(center.wA(), globalBounds.waMin, globalBounds.waMax, cli.tuneStage2RadiusFracWA),

                localMin(center.wH(), globalBounds.whMin, globalBounds.whMax, cli.tuneStage2RadiusFracWH),
                localMax(center.wH(), globalBounds.whMin, globalBounds.whMax, cli.tuneStage2RadiusFracWH),

                localMin(center.wD(), globalBounds.wdMin, globalBounds.wdMax, cli.tuneStage2RadiusFracWD),
                localMax(center.wD(), globalBounds.wdMin, globalBounds.wdMax, cli.tuneStage2RadiusFracWD),

                localMin(center.wR(), globalBounds.wrMin, globalBounds.wrMax, cli.tuneStage2RadiusFracWR),
                localMax(center.wR(), globalBounds.wrMin, globalBounds.wrMax, cli.tuneStage2RadiusFracWR)
        );
    }

    private static double localMin(double center, double globalMin, double globalMax, double radiusFrac) {
        double radius = radiusFrac * (globalMax - globalMin);
        return Math.max(globalMin, center - radius);
    }

    private static double localMax(double center, double globalMin, double globalMax, double radiusFrac) {
        double radius = radiusFrac * (globalMax - globalMin);
        return Math.min(globalMax, center + radius);
    }

    private static MonteCarloEstimate evaluateWeights(SimulationEngine engine,
                                                      ScenarioFactory.LoadedInput li,
                                                      SimulationConfig cfg,
                                                      SystemParameters baseParams,
                                                      long mcBaseSeed,
                                                      TuneWeights w,
                                                      int mcIterations) throws Exception {
        SystemParameters params = SystemParametersBuilder.from(baseParams)
                .setBtUseAdaptiveNonReserveDischargeLevel(true)
                .setBtAdaptiveDeficitWeight(w.wE())
                .setBtAdaptiveTrendWeight(w.wT())
                .setBtAdaptiveAccelerationWeight(w.wA())
                .setBtAdaptiveNoDgPrevHourWeight(w.wH())
                .setBtAdaptiveDgAvailabilityWeight(w.wD())
                .setBtAdaptiveReplacementWeight(w.wR())
                .build();

        return engine.runMonteCarlo(
                new SimInput(cfg, params, li.totalLoadKw()),
                mcIterations,
                mcBaseSeed,
                false
        );
    }

    private static double compromiseMetric(MonteCarloEstimate est, MonteCarloEstimate baseline) {
        double lcoeNorm = est.meanLcoeRubPerKwh / Math.max(1e-9, baseline.meanLcoeRubPerKwh);
        double loleNorm = est.meanLoleHours / Math.max(1e-9, baseline.meanLoleHours);
        return 0.5 * lcoeNorm + 0.5 * loleNorm;
    }

    private static List<TuneResult> reevaluateTopUnion(
            List<TuneResult> stage1,
            Cli cli,
            SimulationEngine engine,
            ScenarioFactory.LoadedInput li,
            SimulationConfig cfgStage2,
            SystemParameters baseParams,
            long mcBaseSeed,
            int mcIterations,
            MonteCarloEstimate baseline
    ) throws Exception {

        if (cfgStage2 == null) {
            throw new IllegalArgumentException("cfgStage2 is null");
        }
        if (mcIterations <= 0) {
            throw new IllegalArgumentException("mcIterations must be > 0 for reevaluateTopUnion");
        }

        Map<String, TuneWeights> selected = new LinkedHashMap<>();

        addTop(
                filterPrimaryFeasible(stage1, baseline, cli),
                primaryComparator(cli),
                cli.tuneTopPrimary,
                selected
        );

        addTop(
                stage1,
                Comparator.comparingDouble(TuneResult::compromise),
                cli.tuneTopCompromise,
                selected
        );

        addPareto(stage1, cli.tuneTopPareto, selected);

        List<TuneResult> out = new ArrayList<>();

        for (TuneWeights w : selected.values()) {
            MonteCarloEstimate est = evaluateWeights(engine, li, cfgStage2, baseParams, mcBaseSeed, w, mcIterations);
            double comp = compromiseMetric(est, baseline);
            out.add(new TuneResult(w, est, comp));
        }

        return out;
    }

    private static List<TuneResult> selectTopUnionFromStage1(List<TuneResult> stage1,
                                                             Cli cli,
                                                             MonteCarloEstimate baseline) {
        Map<String, TuneResult> selected = new LinkedHashMap<>();

        addTopResults(
                filterPrimaryFeasible(stage1, baseline, cli),
                primaryComparator(cli),
                cli.tuneTopPrimary,
                selected
        );

        addTopResults(
                stage1,
                Comparator.comparingDouble(TuneResult::compromise),
                cli.tuneTopCompromise,
                selected
        );

        addParetoResults(stage1, cli.tuneTopPareto, selected);

        return new ArrayList<>(selected.values());
    }

    private static void addTop(List<TuneResult> src, Comparator<TuneResult> cmp, int topK, Map<String, TuneWeights> dst) {
        if (topK <= 0 || src == null || src.isEmpty()) return;

        List<TuneResult> copy = new ArrayList<>(src);
        copy.sort(cmp);
        for (int i = 0; i < Math.min(topK, copy.size()); i++) {
            TuneWeights w = copy.get(i).weights();
            dst.putIfAbsent(w.key(), w);
        }
    }

    private static void addTopResults(List<TuneResult> src,
                                      Comparator<TuneResult> cmp,
                                      int topK,
                                      Map<String, TuneResult> dst) {
        if (topK <= 0 || src == null || src.isEmpty()) return;

        List<TuneResult> copy = new ArrayList<>(src);
        copy.sort(cmp);
        for (int i = 0; i < Math.min(topK, copy.size()); i++) {
            TuneResult tr = copy.get(i);
            dst.putIfAbsent(tr.weights().key(), tr);
        }
    }

    private static void printTop(List<TuneResult> results, Comparator<TuneResult> cmp, int n) {
        List<TuneResult> copy = new ArrayList<>(results);
        copy.sort(cmp);

        System.out.println("rank\tcomp\twE\twT\twA\twH\twD\twR\tLCOE\tENS\tLOLH\tLOLP\tLPSP\tENS_evtN\tFuel\tMoto\tavgNRL\tmedNRL");

        for (int i = 0; i < Math.min(n, copy.size()); i++) {
            TuneResult tr = copy.get(i);
            MonteCarloEstimate est = tr.estimate();
            TuneWeights w = tr.weights();

            System.out.printf(OUT_LOCALE,
                    "#%d\t%.6f\t%.4f\t%.4f\t%.4f\t%.4f\t%.4f\t%.4f\t%.6f\t%.6f\t%.6f\t%.6e\t%.6e\t%.6f\t%.6f\t%.6f\t%.4f\t%.4f%n",
                    i + 1,
                    tr.compromise(),
                    w.wE(), w.wT(), w.wA(), w.wH(), w.wD(), w.wR(),
                    est.meanLcoeRubPerKwh,
                    est.ensStats.getMean(),
                    est.meanLoleHours,
                    est.meanLolp,
                    est.meanLpsp,
                    est.meanEnsEventsTotal,
                    est.meanFuelLiters,
                    est.meanMotoHours,
                    est.meanAdaptiveNonReserveLevel,
                    est.medianAdaptiveNonReserveLevel
            );
        }
    }

    private static void writeTuneCsv(String path,
                                     List<TuneResult> rows,
                                     MonteCarloEstimate baseline,
                                     Cli cli) throws IOException {
        List<TuneResult> pareto = paretoFront(rows);
        Set<String> paretoKeys = new HashSet<>();
        for (TuneResult tr : pareto) {
            paretoKeys.add(tr.weights().key());
        }

        try (BufferedWriter bw = new BufferedWriter(new FileWriter(path))) {
            bw.write("wE;wT;wA;wH;wD;wR;LCOE;ENS;LOLH;LOLP;LPSP;ENS_evtN;Fuel;Moto;avgNRL;medNRL;compromise;lcoeNorm;loleNorm;isPareto;isFeasibleForPrimaryObjective;primaryObjective;secondaryConstraint");
            bw.newLine();

            for (TuneResult tr : rows) {
                MonteCarloEstimate est = tr.estimate();
                TuneWeights w = tr.weights();

                double lcoeNorm = est.meanLcoeRubPerKwh / Math.max(1e-9, baseline.meanLcoeRubPerKwh);
                double loleNorm = est.meanLoleHours / Math.max(1e-9, baseline.meanLoleHours);

                boolean isPareto = paretoKeys.contains(w.key());
                boolean isFeasibleForPrimaryObjective = isPrimaryFeasible(tr, baseline, cli);

                bw.write(String.format(OUT_LOCALE,
                        "%.8f;%.8f;%.8f;%.8f;%.8f;%.8f;%.8f;%.8f;%.8f;%.8f;%.8f;%.8f;%.8f;%.8f;%.8f;%.8f;%.8f;%.8f;%.8f;%b;%b;%s;%s",
                        w.wE(), w.wT(), w.wA(), w.wH(), w.wD(), w.wR(),
                        est.meanLcoeRubPerKwh,
                        est.ensStats.getMean(),
                        est.meanLoleHours,
                        est.meanLolp,
                        est.meanLpsp,
                        est.meanEnsEventsTotal,
                        est.meanFuelLiters,
                        est.meanMotoHours,
                        est.meanAdaptiveNonReserveLevel,
                        est.medianAdaptiveNonReserveLevel,
                        tr.compromise(),
                        lcoeNorm,
                        loleNorm,
                        isPareto,
                        isFeasibleForPrimaryObjective,
                        primaryObjectiveName(cli),
                        secondaryConstraintName(cli)
                ));
                bw.newLine();
            }
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

    private static final class Defaults {
        private Defaults() {
        }

        static final String WIND_PATH = "D:/08_ModelingData/02_Wind.txt";
        static final String RESULTS_XLSX = "D:/results.xlsx";
        static final String TRACE_XLSX = "D:/trace.xlsx";

        static final String LOAD_GOK = "D:/08_ModelingData/01_Load_g.txt";
        static final String LOAD_KOMUNAL = "D:/08_ModelingData/02_Load_k.txt";
        static final String LOAD_SELHOZ = "D:/08_ModelingData/01_Load_s.txt";
        static final String LOAD_DEF = "D:/08_ModelingData/01_Load.txt";

        static final int MAX_LOAD_GOK = 1346;
        static final int MAX_LOAD_KOMUNAL = 1346;
        static final int MAX_LOAD_SELHOZ = 1346;
        static final int MAX_LOAD_DEF = 1346;

        static final double DEFAULT_FIRST_CAT = ModelDefaults.DEFAULT_FIRST_CAT;
        static final double DEFAULT_SECOND_CAT = ModelDefaults.DEFAULT_SECOND_CAT;

        static final int DEFAULT_WT_COUNT_TOTAL = ModelDefaults.DEFAULT_WT_COUNT_TOTAL;
        static final double DEFAULT_WT_POWER_KW = ModelDefaults.DEFAULT_WT_POWER_KW;

        static final int DEFAULT_DG_COUNT_TOTAL = ModelDefaults.DEFAULT_DG_COUNT_TOTAL;
        static final double DEFAULT_DG_POWER_KW = ModelDefaults.DEFAULT_DG_POWER_KW;

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

        static final boolean CFG_CONSIDER_FAILURES = ModelDefaults.CFG_CONSIDER_FAILURES;
        static final boolean CFG_CONSIDER_MAINTENANCE = ModelDefaults.CFG_CONSIDER_MAINTENANCE;
        static final boolean CFG_CONSIDER_HOT_RESERVE = ModelDefaults.CFG_CONSIDER_HOT_RESERVE;
        static final boolean CFG_CONSIDER_BATTERY_DEGRADATION = ModelDefaults.CFG_CONSIDER_BATTERY_DEGRADATION;
        static final boolean CFG_RESERVE_THIRD_CATEGORY = ModelDefaults.CFG_RESERVE_THIRD_CATEGORY;
        static final boolean CFG_CONSIDER_ROTATION_RESERVE = ModelDefaults.CFG_CONSIDER_ROTATION_RESERVE;
        static final boolean CFG_KEEP_ONE_DG_INSTANT_START_READY_AFTER_WT_BESS_GRID_FORMING =
                ModelDefaults.CFG_KEEP_ONE_DG_INSTANT_START_READY_AFTER_WT_BESS_GRID_FORMING;
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