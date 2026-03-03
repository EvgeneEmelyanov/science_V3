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

    // мб вернуть деградацию по току акб
    public enum Task {RUN, SOBOL_HARD, SOBOL_ECON}

    public enum RunMode {SINGLE, SWEEP_1, SWEEP_2}

    public enum LoadType {GOK, KOMUNAL, SELHOZ, DEF}

    public static double MAX_LOAD;

    private static final class Cli {

        Task task = Task.RUN;
        RunMode runMode = RunMode.SINGLE;
        int mcIterations = 50;

        BusSystemType busType = BusSystemType.DOUBLE_BUS;

        SobolConfig.SeedMode sobolSeedMode = SobolConfig.SeedMode.HYBRID_BY_TYPE;
        int sobolN = 128;

        //String exportDriversPath = "D:/econ_drivers.csv";
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
                        .setBatteryCapacityKwhPerBus(p1 * 1346 / 2)
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
//                                .setTotalWindTurbineCount((int) p1)
//                                .setWindTurbinePowerKw(p1)
//                                .setDieselGeneratorPowerKw(p2)
//                                .setFirstCat(p1)
                                .setBatteryCapacityKwhPerBus(p1 * 1346 / 2)
//                                 .setNonReserveDischargeLevel(p2)
                                .setMaxDischargeCurrent(p2)
//                                .setDieselGeneratorFailureRatePerYear(p2)
//                                .setDieselGeneratorRepairTimeHours(p2)
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

        // ===== Axes (edit here) =====

//        double[] param2 = new double[]{0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1};
//        double[] param1 = new double[]{0, 2, 4, 6, 8, 10};
        double[] param1 = new double[]{0, 0.2, 0.4, 0.6, 0.8, 1};

//        double[] param2 = new double[]{0.8, 0.7, 0.6, 0.5, 0.4, 0.3, 0.2, 0.1, 0};
        double[] param2 = new double[]{1, 1.5, 2, 2.5, 3, 3.5, 4, 4.5, 5};
//        double[] param2 = new double[]{0.0, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1};

//        double[] param2 = new double[]{2.37, 3.16, 4.75, 5.93, 7.125};

        final boolean sweepCatsTriangle = false;
        final double catStep = 0.1;

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
//                TunableParamId.DG_POWER,
//                TunableParamId.WT_POWER,
//                TunableParamId.BT_CAPACITY_PER_BUS,
//                TunableParamId.BT_MAX_DISCHARGE_CURRENT,
//                TunableParamId.BT_MAX_CHARGE_CURRENT,
//                TunableParamId.BT_NON_RESERVE_DISCHARGE_LVL
//                TunableParamId.DG_COUNT,
//                TunableParamId.WT_COUNT,

                // Группа по надежности:
                TunableParamId.FIRST_CAT,
                TunableParamId.SECOND_CAT,

                TunableParamId.WT_FAILURE_RATE,
                TunableParamId.DG_FAILURE_RATE,
                TunableParamId.BT_FAILURE_RATE,
                TunableParamId.BUS_FAILURE_RATE,
                TunableParamId.BRK_FAILURE_RATE,

                TunableParamId.WT_FAILURE_RATE,
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

        // Coupled constraint for DG: do not allow total installed DG power to drop below max load.
        // (Can be overridden by --maxLoad=...)
        TunableParameterPool.setMinTotalDgPowerKw(MAX_LOAD);

        // Для SOBOL_* драйверы по годам не нужны по умолчанию.
        // Если вдруг нужен экспорт драйверов при Sobol, можно передать --exportDrivers=...
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
            // Tables are printed by SobolAnalyzer (RAW + LOG1P) to avoid duplication.
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

    private static final class Defaults {
        private Defaults() {
        }

        // Paths
        static final String WIND_PATH = "D:/08_ModelingData/02_Wind.txt";
        static final String RESULTS_XLSX = "D:/results.xlsx";
        static final String TRACE_XLSX = "D:/trace.xlsx";

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
        // EconomyDrivers arrays are not mutated by DiscountedLcoeCalculator,
        // so it's safe to reuse references and only override the rate.
        return new EconomyDrivers(
                d.servedKwhByYear,
                d.fuelLitersByYear,
                d.motoHoursByYear,
                d.btReplByYear,
                d.ensCat1KwhByYear,
                d.ensCat2KwhByYear,
                d.ensCat3KwhByYear,
                d.dgTotalKw,
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