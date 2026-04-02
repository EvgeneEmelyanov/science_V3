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

    public static double MAX_LOAD;

    private static final class Cli {

        Task task = Task.RUN;
        RunMode runMode = RunMode.SINGLE;
        int mcIterations = 1;

        BusSystemType busType = BusSystemType.SINGLE_SECTIONAL_BUS;

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
        int tuneSamples = 100;
        int tuneStage1Mc = 25;
        int tuneStage2Mc = 0;
        int tuneBaselineMc = 25;

        int tuneTopByLcoe = 3;
        int tuneTopByEns = 3;
        int tuneTopByCompromise = 0;

        String tuneCsvPath = "adaptive_tune.csv";

        double tuneWEMin = 0.0, tuneWEMax = 0.1;//0,3
        double tuneWTMin = 0.0, tuneWTMax = 0.75;
        double tuneWAMin = 0.8, tuneWAMax = 1.5;
        double tuneWHMin = 0.0, tuneWHMax = 1;
        double tuneWDMin = 0.0, tuneWDMax = 0.1;
        double tuneWRMin = 0.5, tuneWRMax = 2;

//        double tuneWEMin = 0.0, tuneWEMax = 0.3;
//        double tuneWTMin = 0.0, tuneWTMax = 1;
//        double tuneWAMin = 0.8, tuneWAMax = 3;
//        double tuneWHMin = 1.0, tuneWHMax = 3.5;
//        double tuneWDMin = 0.0, tuneWDMax = 1.5;
//        double tuneWRMin = 1.0, tuneWRMax = 2.0;

        static Cli parse(String[] args) {
            Cli c = new Cli();
            for (String a : args) {
                if (a == null) continue;

                if (a.startsWith("--task=")) c.task = Task.valueOf(a.substring("--task=".length()).trim());

                if (a.startsWith("--runMode=")) c.runMode = RunMode.valueOf(a.substring("--runMode=".length()).trim());
                if (a.startsWith("--loadType=")) c.loadType = LoadType.valueOf(a.substring("--loadType=".length()).trim());
                if (a.startsWith("--busType=")) c.busType = BusSystemType.valueOf(a.substring("--busType=".length()).trim());

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

                if (a.startsWith("--tuneSamples=")) c.tuneSamples = Integer.parseInt(a.substring("--tuneSamples=".length()).trim());
                if (a.startsWith("--tuneStage1Mc=")) c.tuneStage1Mc = Integer.parseInt(a.substring("--tuneStage1Mc=".length()).trim());
                if (a.startsWith("--tuneStage2Mc=")) c.tuneStage2Mc = Integer.parseInt(a.substring("--tuneStage2Mc=".length()).trim());
                if (a.startsWith("--tuneBaselineMc=")) c.tuneBaselineMc = Integer.parseInt(a.substring("--tuneBaselineMc=".length()).trim());

                if (a.startsWith("--tuneTopByLcoe=")) c.tuneTopByLcoe = Integer.parseInt(a.substring("--tuneTopByLcoe=".length()).trim());
                if (a.startsWith("--tuneTopByEns=")) c.tuneTopByEns = Integer.parseInt(a.substring("--tuneTopByEns=".length()).trim());
                if (a.startsWith("--tuneTopByCompromise=")) c.tuneTopByCompromise = Integer.parseInt(a.substring("--tuneTopByCompromise=".length()).trim());

                if (a.startsWith("--tuneCsv=")) c.tuneCsvPath = a.substring("--tuneCsv=".length()).trim();

                if (a.startsWith("--tuneWEMin=")) c.tuneWEMin = Double.parseDouble(a.substring("--tuneWEMin=".length()).trim());
                if (a.startsWith("--tuneWEMax=")) c.tuneWEMax = Double.parseDouble(a.substring("--tuneWEMax=".length()).trim());
                if (a.startsWith("--tuneWTMin=")) c.tuneWTMin = Double.parseDouble(a.substring("--tuneWTMin=".length()).trim());
                if (a.startsWith("--tuneWTMax=")) c.tuneWTMax = Double.parseDouble(a.substring("--tuneWTMax=".length()).trim());
                if (a.startsWith("--tuneWAMin=")) c.tuneWAMin = Double.parseDouble(a.substring("--tuneWAMin=".length()).trim());
                if (a.startsWith("--tuneWAMax=")) c.tuneWAMax = Double.parseDouble(a.substring("--tuneWAMax=".length()).trim());
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

    private record TuneWeights(double wE, double wT, double wA, double wH, double wD, double wR) {
        String key() {
            return String.format(OUT_LOCALE, "%.6f|%.6f|%.6f|%.6f|%.6f|%.6f", wE, wT, wA, wH, wD, wR);
        }
    }

    private record TuneResult(TuneWeights weights, MonteCarloEstimate estimate, double compromise) {}

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
//                                .setDieselGeneratorPowerKw(p2)
//                                .setWindTurbinePowerKw(p1)

//                                .setBatteryCapacityKwhPerBus(p1 * 1346 / 2)
                                .setMaxChargeCurrent(p1)
                                .setNonReserveDischargeLevel(p2)
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

//        double[] param2 = new double[]{
//                180, 190,
//                200, 210, 220, 230, 240,
//                250, 260, 270, 280, 290,
//                300
//        };
//
//        double[] param1 = new double[] {
//                0.0,
//                168.25,
//                336.5,
//                504.75,
//                673.0,
//                841.25,
//                1009.5,
//                1177.75,
//                1346.0,
//                1514.25,
//                1682.5,
//                1850.75,
//                2019.0
//        };

//        double[] param2 = new double[]{0, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1};
        double[] param1 = new double[]{0, 0.05, 0.1, 0.15, 0.2, 0.25, 0.3, 0.35, 0.4, 0.45, 0.5, 0.55, 0.6, 0.65, 0.7, 0.75, 0.8, 0.85, 0.9, 0.95, 1};
//        double[] param1 = new double[]{0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1};
        double[] param2 = new double[]{0.2, 0.25, 0.3, 0.35, 0.4, 0.45, 0.5, 0.55, 0.6, 0.65, 0.7, 0.75, 0.8, 0.85, 0.9, 0.95, 1};

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
//                TunableParamId.DG_POWER,
////                TunableParamId.DG_COUNT,
////                TunableParamId.WT_POWER,
////                TunableParamId.BT_CAPACITY_PER_BUS,
////                TunableParamId.BT_MAX_DISCHARGE_CURRENT,
////                TunableParamId.BT_MAX_CHARGE_CURRENT,
////                TunableParamId.BT_NON_RESERVE_DISCHARGE_LVL


                // Группа по надежности:
                TunableParamId.FIRST_CAT,
                TunableParamId.SECOND_CAT,

                TunableParamId.WT_FAILURE_RATE,
                TunableParamId.DG_FAILURE_RATE,
                TunableParamId.BT_FAILURE_RATE,
                TunableParamId.BUS_FAILURE_RATE,
                TunableParamId.BRK_FAILURE_RATE,

                TunableParamId.WT_REPAIR_TIME,
                TunableParamId.DG_REPAIR_TIME,
                TunableParamId.BT_REPAIR_TIME,
                TunableParamId.BUS_REPAIR_TIME,
                TunableParamId.BRK_REPAIR_TIME

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
        System.out.printf(OUT_LOCALE, "metric(A∪B)  LCOE: var=%.6g std=%.6g range=[%.6g..%.6g]%n",
                varY, Math.sqrt(varY), minY, maxY);
        for (int j = 0; j < d; j++) {
            System.out.printf(OUT_LOCALE, "%-28s  S=%.6f  ST=%.6f%n", TunableParameterPool.get(econIds.get(j)).getName(), S[j], ST[j]);
        }
    }

    private static void runTaskAdaptiveTune(ScenarioFactory.LoadedInput li, SystemParameters baseParams, Cli cli) throws Exception {
        if (cli.tuneSamples <= 0) {
            throw new IllegalArgumentException("tuneSamples must be > 0");
        }
        if (cli.tuneStage1Mc <= 0) {
            throw new IllegalArgumentException("tuneStage1Mc must be > 0");
        }
        if (cli.tuneBaselineMc <= 0) {
            throw new IllegalArgumentException("tuneBaselineMc must be > 0");
        }

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
                    "baseline(static NRL): LCOE=%.6f ENS=%.6f LOLH=%.6f ENS_evtN=%.6f Fuel=%.6f Moto=%.6f%n",
                    baseline.meanLcoeRubPerKwh,
                    baseline.ensStats.getMean(),
                    baseline.meanLoleHours,
                    baseline.meanEnsEventsTotal,
                    baseline.meanFuelLiters,
                    baseline.meanMotoHours
            );

            Random rnd = new Random(cli.mcBaseSeed ^ 0x9E3779B97F4A7C15L);
            List<TuneResult> stage1 = new ArrayList<>(cli.tuneSamples);

            for (int i = 0; i < cli.tuneSamples; i++) {
                TuneWeights w = randomWeights(rnd, cli);
                MonteCarloEstimate est = evaluateWeights(engine, li, cfgStage1, baseParams, cli.mcBaseSeed, w, cli.tuneStage1Mc);
                double compromise = compromiseMetric(est, baseline);
                stage1.add(new TuneResult(w, est, compromise));

                System.out.printf(OUT_LOCALE,
                        "global %3d/%d comp=%.2f wE=%.4f wT=%.4f wA=%.4f wH=%.4f wD=%.4f wR=%.4f | LCOE=%.6f ENS=%.6f LOLH=%.6f ENS_evtN=%.6f avgNRL=%.4f%n",
                        i + 1, cli.tuneSamples, compromise,
                        w.wE(), w.wT(), w.wA(), w.wH(), w.wD(), w.wR(),
                        est.meanLcoeRubPerKwh,
                        est.ensStats.getMean(),
                        est.meanLoleHours,
                        est.meanEnsEventsTotal,
                        est.meanAdaptiveNonReserveLevel
                );
            }

//            writeTuneCsv(cli.tuneCsvPath, stage1, baseline);

            List<TuneResult> finalResults;

            if (cli.tuneStage2Mc > 0) {
                finalResults = reevaluateTopUnion(
                        stage1,
                        cli,
                        engine,
                        li,
                        cfgStage2,
                        baseParams,
                        cli.mcBaseSeed,
                        cli.tuneStage2Mc,
                        baseline
                );
            } else {
                finalResults = selectTopUnionFromStage1(stage1, cli);
            }

            System.out.println("=== ADAPTIVE_TUNE final top by LCOE ===");
            printTop(finalResults, Comparator.comparingDouble(tr -> tr.estimate().meanLcoeRubPerKwh), cli.tuneTopByLcoe);

            System.out.println("=== ADAPTIVE_TUNE final top by ENS ===");
            printTop(finalResults, Comparator.comparingDouble(tr -> tr.estimate().ensStats.getMean()), cli.tuneTopByEns);

            if (cli.tuneTopByCompromise > 0) {
                System.out.println("=== ADAPTIVE_TUNE final top by compromise(LCOE+ENS) ===");
                printTop(finalResults, Comparator.comparingDouble(TuneResult::compromise), cli.tuneTopByCompromise);
            }

//            System.out.println("Saved tune table: " + cli.tuneCsvPath);

        } finally {
            ex.shutdown();
        }
    }

    private static TuneWeights randomWeights(Random rnd, Cli cli) {
        return new TuneWeights(
                uniform(rnd, cli.tuneWEMin, cli.tuneWEMax),
                uniform(rnd, cli.tuneWTMin, cli.tuneWTMax),
                uniform(rnd, cli.tuneWAMin, cli.tuneWAMax),
                uniform(rnd, cli.tuneWHMin, cli.tuneWHMax),
                uniform(rnd, cli.tuneWDMin, cli.tuneWDMax),
                uniform(rnd, cli.tuneWRMin, cli.tuneWRMax)
        );
    }

    private static double uniform(Random rnd, double min, double max) {
        return min + rnd.nextDouble() * (max - min);
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
        double ensNorm = est.ensStats.getMean() / Math.max(1e-9, baseline.ensStats.getMean());
        return 0.5 * lcoeNorm + 0.5 * ensNorm;
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

        addTop(stage1,
                Comparator.comparingDouble(tr -> tr.estimate().meanLcoeRubPerKwh),
                cli.tuneTopByLcoe,
                selected);

        addTop(stage1,
                Comparator.comparingDouble(tr -> tr.estimate().ensStats.getMean()),
                cli.tuneTopByEns,
                selected);

        addTop(stage1,
                Comparator.comparingDouble(TuneResult::compromise),
                cli.tuneTopByCompromise,
                selected);

        List<TuneResult> out = new ArrayList<>();

        for (TuneWeights w : selected.values()) {
            MonteCarloEstimate est = evaluateWeights(engine, li, cfgStage2, baseParams, mcBaseSeed, w, mcIterations);
            double comp = compromiseMetric(est, baseline);
            out.add(new TuneResult(w, est, comp));

//            System.out.printf(OUT_LOCALE,
//                    "final %3d/%d comp=%.6f wE=%.4f wT=%.4f wA=%.4f wH=%.4f wD=%.4f wR=%.4f | LCOE=%.6f ENS=%.6f LOLH=%.6f ENS_evtN=%.6f avgNRL=%.4f%n",
//                    idx++, selected.size(), comp,
//                    w.wE(), w.wT(), w.wA(), w.wH(), w.wD(), w.wR(),
//                    est.meanLcoeRubPerKwh,
//                    est.ensStats.getMean(),
//                    est.meanLoleHours,
//                    est.meanEnsEventsTotal,
//                    est.meanAdaptiveNonReserveLevel
//            );
        }

        return out;
    }

    private static List<TuneResult> selectTopUnionFromStage1(List<TuneResult> stage1, Cli cli) {
        Map<String, TuneResult> selected = new LinkedHashMap<>();

        addTopResults(
                stage1,
                Comparator.comparingDouble(tr -> tr.estimate().meanLcoeRubPerKwh),
                cli.tuneTopByLcoe,
                selected
        );

        addTopResults(
                stage1,
                Comparator.comparingDouble(tr -> tr.estimate().ensStats.getMean()),
                cli.tuneTopByEns,
                selected
        );

        addTopResults(
                stage1,
                Comparator.comparingDouble(TuneResult::compromise),
                cli.tuneTopByCompromise,
                selected
        );

        return new ArrayList<>(selected.values());
    }

    private static void addTop(List<TuneResult> src, Comparator<TuneResult> cmp, int topK, Map<String, TuneWeights> dst) {
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

        System.out.println("rank\tcomp\twE\twT\twA\twH\twD\twR\tLCOE\tENS\tLOLH\tENS_evtN\tFuel\tMoto\tavgNRL\tmedNRL");

        for (int i = 0; i < Math.min(n, copy.size()); i++) {
            TuneResult tr = copy.get(i);
            MonteCarloEstimate est = tr.estimate();
            TuneWeights w = tr.weights();

            System.out.printf(OUT_LOCALE,
                    "#%d\t%.6f\t%.4f\t%.4f\t%.4f\t%.4f\t%.4f\t%.4f\t%.6f\t%.6f\t%.6f\t%.6f\t%.6f\t%.6f\t%.4f\t%.4f%n",
                    i + 1,
                    tr.compromise(),
                    w.wE(), w.wT(), w.wA(), w.wH(), w.wD(), w.wR(),
                    est.meanLcoeRubPerKwh,
                    est.ensStats.getMean(),
                    est.meanLoleHours,
                    est.meanEnsEventsTotal,
                    est.meanFuelLiters,
                    est.meanMotoHours,
                    est.meanAdaptiveNonReserveLevel,
                    est.medianAdaptiveNonReserveLevel
            );
        }
    }

    private static void writeTuneCsv(String path, List<TuneResult> rows, MonteCarloEstimate baseline) throws IOException {
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(path))) {
            bw.write("wE;wT;wA;wH;wD;wR;LCOE;ENS;LOLH;ENS_evtN;Fuel;Moto;avgNRL;medNRL;compromise;lcoeNorm;ensNorm");
            bw.newLine();

            for (TuneResult tr : rows) {
                MonteCarloEstimate est = tr.estimate();
                TuneWeights w = tr.weights();
                double lcoeNorm = est.meanLcoeRubPerKwh / Math.max(1e-9, baseline.meanLcoeRubPerKwh);
                double ensNorm = est.ensStats.getMean() / Math.max(1e-9, baseline.ensStats.getMean());

                bw.write(String.format(OUT_LOCALE,
                        "%.8f;%.8f;%.8f;%.8f;%.8f;%.8f;%.8f;%.8f;%.8f;%.8f;%.8f;%.8f;%.8f;%.8f;%.8f;%.8f;%.8f",
                        w.wE(), w.wT(), w.wA(), w.wH(), w.wD(), w.wR(),
                        est.meanLcoeRubPerKwh,
                        est.ensStats.getMean(),
                        est.meanLoleHours,
                        est.meanEnsEventsTotal,
                        est.meanFuelLiters,
                        est.meanMotoHours,
                        est.meanAdaptiveNonReserveLevel,
                        est.medianAdaptiveNonReserveLevel,
                        tr.compromise(),
                        lcoeNorm,
                        ensNorm
                ));
                bw.newLine();
            }
        }
    }

    private static final class ScenarioFactory {
        private ScenarioFactory() {}

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

        record LoadedInput(double[] totalLoadKw, double[] windMs) {}
    }

    private static final class Defaults {
        private Defaults() {}

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

    private record EconInputs(UnitCosts costs, double discountRatePerYear) {}

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