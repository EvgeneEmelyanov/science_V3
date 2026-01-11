package simcore;

import simcore.config.SimulationConfig;
import simcore.config.SystemParameters;
import simcore.config.SystemParametersBuilder;
import simcore.engine.*;
import simcore.io.SweepResultsExcelWriter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import simcore.config.BusSystemType;

//    TODO: 1. allowMaintenanceStart = true у Diesel --> несколько дгу в ТО можно одновременно
//          2. горячего резерва нет
//          3. considerChargeByDg работает не правильно
//          4. у меня сейчас вращ резерв и хх для 1 и 2 категории
//          5. BATTERY_DEG_Z и BATTERY_DEG_H вопросительные значения - уточнить

public class Main {

    public enum RunMode {SINGLE, SWEEP_1, SWEEP_2}

    public enum LoadType {GOK, KOMUNAL, SELHOZ, def}

    public static double MAX_LOAD;

    public static void main(String[] args) {

        String loadFilePath;
        String windFilePath = "D:/02_Wind.txt";
        String resultsXlsxPath = "D:/results.xlsx";
        String traceCsvPath = "D:/trace.csv";

        LoadType loadType = LoadType.def;
        RunMode mode = RunMode.SWEEP_2;
        BusSystemType busType = BusSystemType.DOUBLE_BUS;

        int mcIterations = 10;

        switch (loadType) {
            case GOK:
                loadFilePath = "D:/01_Load_g.txt";
                MAX_LOAD = 7740;
                break;
            case KOMUNAL:
                loadFilePath = "D:/01_Load_k.txt";
                MAX_LOAD = 40;
                break;
            case SELHOZ:
                loadFilePath = "D:/01_Load_s.txt";
                MAX_LOAD = 44;
                break;
            case def:
                loadFilePath = "D:/01_Load.txt";
                MAX_LOAD = 1346;
                break;
            default:
                loadFilePath = "D:/01_Load.txt";
                MAX_LOAD = 1346;
                break;
        }

        MAX_LOAD = 1346;

        int threads = Runtime.getRuntime().availableProcessors();
        long mcBaseSeed = 1_000_000L;

        try {
            // 1) входные данные
            ScenarioFactory.LoadedInput li = ScenarioFactory.load(loadFilePath, windFilePath);
            // 2) базовые параметры/конфиг
            SystemParameters baseParams = ScenarioFactory.defaultParams(busType);
            SimulationConfig cfg = ScenarioFactory.defaultConfig(li.windMs(), mcIterations, threads);
            SimInput baseInput = new SimInput(cfg, baseParams, li.totalLoadKw());
            // 3) сетка параметров

            // ===== Прямоугольные сетки =====
//            double[] param1 = new double[]{0.5, 1, 1.5, 2, 2.5, 3, 3.5, 4, 4.5, 5};
//            double[] param2 = new double[]{0.0, 67.3, 134.6, 201.9, 269.2, 336.5, 403.8, 471.1, 538.4, 605.7, 673.0};
            double[] param1 = new double[]{0.8, 0.7, 0.6, 0.5, 0.4, 0.3, 0.2};
//            double[] param1 = new double[]{1, 2, 3, 4, 5};
            double[] param2 = new double[]{0.0, 50, 100, 150, 200, 250};

            // ===== Треугольная сетка категорий (k1,k2,k3) =====
            // Если включено — строим param1/param2 как сетки 0..1 с шагом, а paramSets как треугольник.
            final boolean sweepCatsTriangle = false;
            final double catStep = 0.2;

            if (mode == RunMode.SWEEP_2 && sweepCatsTriangle) {
                param1 = buildGrid01(catStep); // k1
                param2 = buildGrid01(catStep); // k2
            }

            List<SystemParameters> paramSets = buildParamSets(mode, baseParams, param1, param2, sweepCatsTriangle, catStep);

            // trace: только 1 набор и mcIterations==1
            boolean allowTrace = (mcIterations == 1) && (paramSets.size() == 1);

            // 4) общий пул
            ExecutorService ex = Executors.newFixedThreadPool(threads);
            try {
                SingleRunSimulator sim = new SingleRunSimulator();
                MonteCarloRunner mc = new MonteCarloRunner(ex, sim, false, 1.96, 0.05);
                SimulationEngine engine = new SimulationEngine(mc);
                List<MonteCarloEstimate> estimates = new ArrayList<>(paramSets.size());

                for (int k = 0; k < paramSets.size(); k++) {
                    SimInput in = baseInput.withSystemParameters(paramSets.get(k));
                    MonteCarloEstimate est = engine.runMonteCarlo(in, mcIterations, mcBaseSeed, allowTrace);
                    estimates.add(est);

                    if (allowTrace
                            && est.singleRun != null
                            && est.singleRun.trace != null
                            && !est.singleRun.trace.isEmpty()) {
                        SimulationTraceExporter.exportToCsv(traceCsvPath, est.singleRun.trace);
                    }
                }

                // Передаём param1/param2 как оси: для треугольника это сетки k1,k2.
                SweepResultsExcelWriter.writeXlsx(resultsXlsxPath, mode, cfg, baseParams, paramSets, estimates, param1, param2);
                System.out.println("Saved: " + resultsXlsxPath);

            } finally {
                ex.shutdown();
            }
        } catch (Exception e) {
            System.err.println("Ошибка: " + e.getMessage());
            e.printStackTrace();
        }
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

    // TODO ТУТ ЗАДАЮ КАКИЕ ПАРАМЕТРЫ МЕНЯТЬ В SWEEP
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

        // SWEEP_2
//        for (double p1 : param1) {
//            for (double p2 : param2) {
//                SystemParameters p = SystemParametersBuilder.from(baseParams)
//                        .setMaxDischargeCurrent(p1)
//                        .setBatteryCapacityKwhPerBus(p2)
//                        .build();
//                paramSets.add(p);
//            }
//        }

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
            // Треугольник категорий: k1,k2 сетка 0..1, берём только пары k1+k2<=1.
            // k3 считаем как 1-k1-k2.
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
}
