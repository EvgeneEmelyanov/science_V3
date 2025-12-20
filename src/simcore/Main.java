package simcore;

import simcore.config.SimulationConfig;
import simcore.config.SystemParameters;
import simcore.config.SystemParametersBuilder;
import simcore.engine.*;
import simcore.io.SweepResultsCsvWriter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Main {

    private enum RunMode { SINGLE, SWEEP_1, SWEEP_2 }

    public static void main(String[] args) {

        String loadFilePath = "D:/01_Load.txt";
        String windFilePath = "D:/02_Wind.txt";

        String resultsCsvPath = "D:/simulation_results_batch.csv";
        String traceCsvPath = "D:/simulation_trace.csv";

        RunMode mode = RunMode.SINGLE;

        int mcIterations = 10; // trace пишем только если mcIterations==1 и mode==SINGLE
        int threads = Runtime.getRuntime().availableProcessors();
        long mcBaseSeed = 1_000_000L;

        try {
            // 1) входные данные
            ScenarioFactory.LoadedInput li = ScenarioFactory.load(loadFilePath, windFilePath);

            // 2) базовые параметры/конфиг — единый источник
            SystemParameters baseParams = ScenarioFactory.defaultParams();
            SimulationConfig cfg = ScenarioFactory.defaultConfig(li.windMs(), mcIterations, threads);

            SimInput baseInput = new SimInput(cfg, baseParams, li.totalLoadKw());

            // 3) сетка параметров
            double[] param1 = new double[]{200.0, 336.5, 500.0, 800.0}; // BTcap
            double[] param2 = new double[]{1.0, 1.5, 2.0};             // I_dis

            List<SystemParameters> paramSets = buildParamSets(mode, baseParams, param1, param2);

            // trace: только 1 набор и mcIterations==1
            boolean allowTrace = (mcIterations == 1) && (paramSets.size() == 1);

            // 4) общий пул
            ExecutorService ex = Executors.newFixedThreadPool(threads);
            try {
                SingleRunSimulator sim = new SingleRunSimulator();
                MonteCarloRunner mc = new MonteCarloRunner(ex, sim, false, 1.96, 0.10);
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

                SweepResultsCsvWriter.write(resultsCsvPath, cfg, baseParams, paramSets, estimates);
                System.out.println("Saved: " + resultsCsvPath);

            } finally {
                ex.shutdown();
            }

        } catch (Exception e) {
            System.err.println("Ошибка: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static List<SystemParameters> buildParamSets(RunMode mode,
                                                         SystemParameters baseParams,
                                                         double[] param1,
                                                         double[] param2) {

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
        for (double p1 : param1) {
            for (double p2 : param2) {
                SystemParameters p = SystemParametersBuilder.from(baseParams)
                        .setBatteryCapacityKwhPerBus(p1)
                        .setMaxDischargeCurrent(p2)
                        .build();
                paramSets.add(p);
            }
        }
        return paramSets;
    }
}
