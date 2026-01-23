package simcore;

import org.apache.commons.math3.random.SobolSequenceGenerator;
import simcore.config.*;
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

    // ======================================================================
    // Public knobs
    // ======================================================================

    public enum Task { RUN, SOBOL_HARD, SOBOL_ECON }
    public enum RunMode { SINGLE, SWEEP_1, SWEEP_2 }
    public enum LoadType { GOK, KOMUNAL, SELHOZ, DEF }

    // Used by some dispatch formulas
    public static double MAX_LOAD;

    // ======================================================================
    // CLI
    // ======================================================================

    private static final class Cli {
        Task task = Task.RUN; // тип запуска: прогон / тяжелый или легкий соболь
        LoadType loadType = LoadType.GOK; // тип нагрузки

        int mcIterations = 100;
        int sobolN = 500;
        RunMode runMode = RunMode.SINGLE;
        BusSystemType busType = BusSystemType.SINGLE_SECTIONAL_BUS;

        String loadFilePath = null;
        String windFilePath = Defaults.WIND_PATH;
        String resultsXlsxPath = Defaults.RESULTS_XLSX;
        String traceCsvPath = Defaults.TRACE_CSV;

        int threads = Runtime.getRuntime().availableProcessors();
        long mcBaseSeed = 1_000_000L;

        Integer maxLoadOverride = 1000;

        // Export drivers from RUN
        String exportDriversPath = "D:/econ_drivers.csv";

        // Econ sobol
        String econDriversPath = "D:/econ_drivers.csv";;
        String econCaseId = "case_0";
        Integer econN = 1024;

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
                if (a.startsWith("--trace=")) c.traceCsvPath = a.substring("--trace=".length()).trim();

                if (a.startsWith("--threads=")) c.threads = Integer.parseInt(a.substring("--threads=".length()).trim());
                if (a.startsWith("--mc=")) c.mcIterations = Integer.parseInt(a.substring("--mc=".length()).trim());
                if (a.startsWith("--mcSeed=")) c.mcBaseSeed = Long.parseLong(a.substring("--mcSeed=".length()).trim());

                if (a.startsWith("--maxLoad=")) c.maxLoadOverride = Integer.parseInt(a.substring("--maxLoad=".length()).trim());

                if (a.startsWith("--sobolN=")) c.sobolN = Integer.parseInt(a.substring("--sobolN=".length()).trim());

                if (a.startsWith("--exportDrivers=")) c.exportDriversPath = a.substring("--exportDrivers=".length()).trim();

                if (a.startsWith("--econDrivers=")) c.econDriversPath = a.substring("--econDrivers=".length()).trim();
                if (a.startsWith("--econCase=")) c.econCaseId = a.substring("--econCase=".length()).trim();
                if (a.startsWith("--econN=")) c.econN = Integer.parseInt(a.substring("--econN=".length()).trim());
            }
            return c;
        }
    }

    // ======================================================================
    // Defaults: paths + ALL SystemParameters defaults as named constants
    // ======================================================================

    private static final class Defaults {
        private Defaults() {}

        // Paths
        static final String WIND_PATH = "D:/08_ModelingData/02_Wind.txt";
        static final String RESULTS_XLSX = "D:/results.xlsx";
        static final String TRACE_CSV = "D:/trace.csv";

        // Load paths (can be overridden by --load=)
        static final String LOAD_GOK = "D:/08_ModelingData/01_Load_g.txt";
        static final String LOAD_KOMUNAL = "D:/08_ModelingData/02_Load_k.txt";
        static final String LOAD_SELHOZ = "D:/08_ModelingData/01_Load_s.txt";
        static final String LOAD_DEF = "D:/08_ModelingData/01_Load.txt";

        // Max load by profile
        static final int MAX_LOAD_GOK = 7740;
        static final int MAX_LOAD_KOMUNAL = 40;
        static final int MAX_LOAD_SELHOZ = 44;
        static final int MAX_LOAD_DEF = 1346;

        // ---- SystemParameters defaults (match SystemParameters constructor types/order) ----

        // Categories share (k1, k2); k3 implied = 1 - k1 - k2
        static final double DEFAULT_FIRST_CAT = 0.65;
        static final double DEFAULT_SECOND_CAT = 0.25;

        // WT
        static final int DEFAULT_WT_COUNT_TOTAL = 4;
        static final double DEFAULT_WT_POWER_KW = 500.0;

        // DG
        static final int DEFAULT_DG_COUNT_TOTAL = 6;
        static final double DEFAULT_DG_POWER_KW = 250.0;

        // Battery
        static final double DEFAULT_BT_CAPACITY_KWH_PER_BUS = 300.0;

        // Battery current / discharge policy
        static final double DEFAULT_BT_MAX_CHARGE_CURRENT = 1.0;
        static final double DEFAULT_BT_MAX_DISCHARGE_CURRENT = 2.0;
        static final double DEFAULT_BT_NON_RESERVE_DISCHARGE_LEVEL = 0.8;

        // Reliability (rates are double, repair times are int)
        static final double DEFAULT_WT_FAILURE_RATE_PER_YEAR = 1.94;
        static final int    DEFAULT_WT_REPAIR_TIME_HOURS = 46;

        static final double DEFAULT_DG_FAILURE_RATE_PER_YEAR = 4.75;
        static final int    DEFAULT_DG_REPAIR_TIME_HOURS = 50;

        static final double DEFAULT_BT_FAILURE_RATE_PER_YEAR = 0.575;
        static final int    DEFAULT_BT_REPAIR_TIME_HOURS = 44;

        static final double DEFAULT_BUS_FAILURE_RATE_PER_YEAR = 0.02;
        static final int    DEFAULT_BUS_REPAIR_TIME_HOURS = 12;

        static final double DEFAULT_BRK_FAILURE_RATE_PER_YEAR = 0.1;
        static final int    DEFAULT_BRK_REPAIR_TIME_HOURS = 10;

        static final double DEFAULT_SWITCHGEAR_ROOM_FAILURE_RATE_PER_YEAR = 0.0;
        static final int    DEFAULT_SWITCHGEAR_ROOM_REPAIR_TIME_HOURS = 24;

        static final double DEFAULT_BUS_CCF_BETA_SECTIONAL = 0.0;
        static final double DEFAULT_BUS_CCF_BETA_DOUBLE = 0.0;

        // ---- Economics defaults ----
        static final double DEFAULT_DISCOUNT_RATE = 0.08;
        static final double DEFAULT_COST_RU_RUB = 4_000_000;
        static final double DEFAULT_COST_DG_RUB_PER_KW = 40_000;
        static final double DEFAULT_COST_DG_RUB_PER_KW_PER_KMH = 1600.0;
        static final double DEFAULT_COST_FUEL_RUB_PER_KT = 90_000_000.0;
        static final double DEFAULT_COST_WT_RUB_PER_KW = 150_000;
        static final double DEFAULT_COST_WT_RUB_PER_KW_PER_YEAR = 3_000;
        static final double DEFAULT_COST_BT_RUB_PER_KWH = 88_000.0;
        static final double DEFAULT_COST_BT_RUB_PER_KWH_PER_YEAR = 2_200.0;
        static final double DEFAULT_DAMAGE_RUB_PER_KWH_CAT1 = 7_000.0;
        static final double DEFAULT_DAMAGE_RUB_PER_KWH_CAT2 = 2_100.0;
        static final double DEFAULT_DAMAGE_RUB_PER_KWH_CAT3 = 700.0;

        // ---- SimulationConfig defaults (match SimulationConfig constructor) ----
        static final boolean CFG_CONSIDER_FAILURES = true;
        static final boolean CFG_CONSIDER_MAINTENANCE = true;
        static final boolean CFG_CONSIDER_CHARGE_BY_DG = false;
        static final boolean CFG_CONSIDER_HOT_RESERVE = false;
        static final boolean CFG_CONSIDER_BATTERY_DEGRADATION = true;
        static final boolean CFG_CONSIDER_ROTATION_RESERVE = true;
    }

    // ======================================================================
    // Sweep builders
    // ======================================================================

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
                        .setBatteryCapacityKwhPerBus(p1)
                        .build();
                paramSets.add(p);
            }
            return paramSets;
        }

        // SWEEP_2 (your current active variant)
        for (double p1 : param1) {
            for (double p2 : param2) {
                SystemParameters p = SystemParametersBuilder.from(baseParams)
                        .setNonReserveDischargeLevel(p1)
                        .setBatteryCapacityKwhPerBus(p2)
                        .build();
                paramSets.add(p);
            }
        }

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
        }

        return paramSets;
    }

    // ======================================================================
    // Task: RUN (SINGLE / SWEEP) + Excel + optional drivers export
    // ======================================================================

    private static void runTaskRun(ScenarioFactory.LoadedInput li, SystemParameters baseParams, Cli cli) throws Exception {
        SimulationConfig cfg = ScenarioFactory.defaultConfig(li.windMs(), cli.mcIterations, cli.threads);
        SimInput baseInput = new SimInput(cfg, baseParams, li.totalLoadKw());

        // ===== Axes (edit here) =====
        double[] param1 = new double[]{0.8, 0.7, 0.6, 0.5, 0.4, 0.3, 0.2};
        double[] param2 = new double[]{0.0, 50, 100, 150, 200, 250, 300, 350, 400, 450, 500};

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
                    SimulationTraceExporter.exportToCsv(cli.traceCsvPath, est.singleRun.trace);
                }
            }

            SweepResultsExcelWriter.writeXlsx(cli.resultsXlsxPath, cli.runMode, cfg, baseParams, paramSets, estimates, param1, param2);
            System.out.println("Saved: " + cli.resultsXlsxPath);

        } finally {
            ex.shutdown();
        }
    }

    // ======================================================================
    // Task: SOBOL_HARD (tech Sobol via simulator+MC)
    // ======================================================================

    private static void runTaskSobolHard(ScenarioFactory.LoadedInput li, SystemParameters baseParams, Cli cli) throws Exception {
        List<TunableParamId> ids = List.of(
                TunableParamId.WT_FAILURE_RATE,
                TunableParamId.DG_FAILURE_RATE,
                TunableParamId.BT_FAILURE_RATE,
                TunableParamId.BUS_FAILURE_RATE,
                TunableParamId.BRK_FAILURE_RATE
        );

        SobolConfig sobolCfg = SobolConfig.fromIds(
                cli.sobolN,
                cli.mcIterations,
                cli.mcBaseSeed,
                cli.threads,
                ids
        );

        SimulationConfig cfg = ScenarioFactory.defaultConfig(li.windMs(), sobolCfg.getMcIterations(), sobolCfg.getThreads());
        SimInput baseInput = new SimInput(cfg, baseParams, li.totalLoadKw());

        ExecutorService ex = Executors.newFixedThreadPool(sobolCfg.getThreads());
        try {
            SingleRunSimulator sim = new SingleRunSimulator();
            MonteCarloRunner mc = new MonteCarloRunner(ex, sim, false, 1.96, 0.10);
            SobolAnalyzer analyzer = new SobolAnalyzer(mc);

            SobolResult res = analyzer.run(baseInput, sobolCfg);

            System.out.println("Sobol done. dim=" + sobolCfg.dim());
            SobolResultPrinter.printTable(sobolCfg.getFactors(), res);
        } finally {
            ex.shutdown();
        }
    }

    // ======================================================================
    // Task: SOBOL_ECON (fast Sobol for LCOE vs UnitCosts using saved drivers)
    // ======================================================================

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

        UnitCosts base = new UnitCosts(
                baseParams.getCostRuRub(),
                baseParams.getCostDgRubPerKw(),
                baseParams.getCostWtRubPerKw(),
                baseParams.getCostBtRubPerKwh(),
                baseParams.getCostFuelRubPerKt(),
                baseParams.getCostDgRubPerKwPerKmh(),
                baseParams.getCostWtRubPerKwPerYear(),
                baseParams.getCostBtRubPerKwhPerYear()
        );

        List<CostFactor> factors = List.of(
                new CostFactor("COST_RU_RUB", base.costRuRub),
                new CostFactor("COST_DG_RUB_PER_KW", base.costDgRubPerKw),
                new CostFactor("COST_WT_RUB_PER_KW", base.costWtRubPerKw),
                new CostFactor("COST_BT_RUB_PER_KWH", base.costBtRubPerKwh),
                new CostFactor("COST_FUEL_RUB_PER_KT", base.costFuelRubPerKt),
                new CostFactor("COST_DG_RUB_PER_KW_PER_KMH", base.costDgRubPerKwPerKmh),
                new CostFactor("COST_WT_RUB_PER_KW_PER_YEAR", base.costWtRubPerKwPerYear),
                new CostFactor("COST_BT_RUB_PER_KWH_PER_YEAR", base.costBtRubPerKwhPerYear)
        );

        int d = factors.size();
        int N = (cli.econN != null) ? cli.econN : 8192;

        double[][][] ab = generateABBySobolSequence(N, d, 1024);
        double[][] A = ab[0];
        double[][] B = ab[1];

        double[] yA = new double[N];
        double[] yB = new double[N];
        double[][] yAB = new double[d][N];

        for (int i = 0; i < N; i++) {
            UnitCosts cA = costsFromUnitRow(A[i], factors);
            UnitCosts cB = costsFromUnitRow(B[i], factors);
            yA[i] = DiscountedLcoeCalculator.computeRubPerKwh(drivers, cA);
            yB[i] = DiscountedLcoeCalculator.computeRubPerKwh(drivers, cB);
        }

        for (int j = 0; j < d; j++) {
            for (int i = 0; i < N; i++) {
                double[] row = Arrays.copyOf(A[i], d);
                row[j] = B[i][j];
                UnitCosts c = costsFromUnitRow(row, factors);
                yAB[j][i] = DiscountedLcoeCalculator.computeRubPerKwh(drivers, c);
            }
        }

        double[] S = new double[d];
        double[] ST = new double[d];
        computeSobolIndicesSaltelliJansen(yA, yB, yAB, S, ST);

        System.out.println("=== ECON Sobol (LCOE vs unit costs) ===");
        System.out.println("drivers=" + cli.econDriversPath + " case=" + useCase + " N=" + N + " years=" + drivers.years());
        for (int j = 0; j < d; j++) {
            System.out.printf(Locale.US, "%-28s  S=%.6f  ST=%.6f%n", factors.get(j).name, S[j], ST[j]);
        }
    }

    // ======================================================================
    // Main entry
    // ======================================================================

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

    // ======================================================================
    // Inlined ScenarioFactory
    // ======================================================================

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

        static SimulationConfig defaultConfig(double[] windMs, int mcIterations, int threads) {
            return new SimulationConfig(
                    windMs,
                    mcIterations,
                    threads,
                    Defaults.CFG_CONSIDER_FAILURES,
                    Defaults.CFG_CONSIDER_MAINTENANCE,
                    Defaults.CFG_CONSIDER_CHARGE_BY_DG,
                    Defaults.CFG_CONSIDER_HOT_RESERVE,
                    Defaults.CFG_CONSIDER_BATTERY_DEGRADATION,
                    Defaults.CFG_CONSIDER_ROTATION_RESERVE
            );
        }

        record LoadedInput(double[] totalLoadKw, double[] windMs) {}
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

    private static final class CostFactor {
        final String name;
        final double base;
        final double min;
        final double max;

        CostFactor(String name, double base) {
            this.name = name;
            this.base = base;
            this.min = base * 0.5;
            this.max = base * 1.5;
        }

        double scaleFromUnit(double u01) {
            return min + u01 * (max - min);
        }
    }

    private static UnitCosts costsFromUnitRow(double[] u01, List<CostFactor> factors) {
        double ru = factors.get(0).scaleFromUnit(u01[0]);
        double dg = factors.get(1).scaleFromUnit(u01[1]);
        double wt = factors.get(2).scaleFromUnit(u01[2]);
        double bt = factors.get(3).scaleFromUnit(u01[3]);
        double fuel = factors.get(4).scaleFromUnit(u01[4]);
        double moto = factors.get(5).scaleFromUnit(u01[5]);
        double wtOpex = factors.get(6).scaleFromUnit(u01[6]);
        double btOpex = factors.get(7).scaleFromUnit(u01[7]);
        return new UnitCosts(ru, dg, wt, bt, fuel, moto, wtOpex, btOpex);
    }

    private static void computeSobolIndicesSaltelliJansen(double[] a, double[] b, double[][] ab, double[] S, double[] ST) {
        int N = a.length;
        double[] yAll = new double[2 * N];
        System.arraycopy(a, 0, yAll, 0, N);
        System.arraycopy(b, 0, yAll, N, N);

        double meanY = mean(yAll);
        double varY = variancePopulation(yAll, meanY);
        if (!(varY > 0.0) || Double.isNaN(varY) || Double.isInfinite(varY)) {
            Arrays.fill(S, Double.NaN);
            Arrays.fill(ST, Double.NaN);
            return;
        }

        int d = ab.length;
        for (int j = 0; j < d; j++) {
            double sumProd = 0.0;
            double sumSt = 0.0;
            for (int i = 0; i < N; i++) {
                double yAB = ab[j][i];
                sumProd += b[i] * yAB; // Saltelli 2010 first-order (covariance form)
                double diff = a[i] - yAB;
                sumSt += diff * diff;  // Jansen total-order
            }
            double meanProd = sumProd / N;
            S[j] = (meanProd - meanY * meanY) / varY;
            ST[j] = (sumSt / (2.0 * N)) / varY;
        }
    }

    private static double mean(double[] x) {
        double s = 0.0;
        for (double v : x) s += v;
        return s / x.length;
    }

    private static double variancePopulation(double[] x, double mean) {
        double s = 0.0;
        for (double v : x) {
            double d = v - mean;
            s += d * d;
        }
        return s / x.length;
    }

    private static double[][][] generateABBySobolSequence(int N, int d, int skip) {
        SobolSequenceGenerator sobol = new SobolSequenceGenerator(2 * d);
        for (int i = 0; i < skip; i++) sobol.nextVector();

        double[][] A = new double[N][d];
        double[][] B = new double[N][d];

        for (int i = 0; i < N; i++) {
            double[] v = sobol.nextVector();
            System.arraycopy(v, 0, A[i], 0, d);
            System.arraycopy(v, d, B[i], 0, d);
        }
        return new double[][][] { A, B };
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
