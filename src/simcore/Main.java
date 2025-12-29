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


//    TODO: 1. СЕЙЧАС В ТО МОЖЕТ БЫТЬ СРАЗУ НЕСКОЛЬКО ДГУ ИЛИ УЖЕ НЕТ ХУЙ ЗНАЕТ
//          2. посмотреть если вообще не заряжать акб даже при прожоге
//          4. если установить интенсивность отказов комнаты, то что будет?
//          5. 2 одинаковых метода computeWindPotential и windPotentialNoSideEffects
//          6. подумать как задавать 3 категория как 1-к1-к2
//          7. либо ток разряда не правильно учитывается на пусках дгу либо он действительно не влияет на ENS
//          8. колво отказов акб равно колву замен акб

public class Main {

    public enum RunMode {SINGLE, SWEEP_1, SWEEP_2}

    public static void main(String[] args) {

        String loadFilePath = "D:/01_Load.txt";
        String windFilePath = "D:/02_Wind.txt";

        String resultsXlsxPath = "D:/results.xlsx";
        String traceCsvPath = "D:/trace.csv";

        RunMode mode = RunMode.SWEEP_2;

        int mcIterations = 500; // trace пишем только если mcIterations==1 и mode==SINGLE
        int threads = Runtime.getRuntime().availableProcessors();
        long mcBaseSeed = 1_000_000L;

        try {
            // 1) входные данные
            ScenarioFactory.LoadedInput li = ScenarioFactory.load(loadFilePath, windFilePath);
            // 2) базовые параметры/конфиг
            SystemParameters baseParams = ScenarioFactory.defaultParams();
            SimulationConfig cfg = ScenarioFactory.defaultConfig(li.windMs(), mcIterations, threads);
            SimInput baseInput = new SimInput(cfg, baseParams, li.totalLoadKw());
            // 3) сетка параметров
//            double[] param1 = new double[]{0.00, 0.05, 0.10, 0.15, 0.20, 0.25, 0.30, 0.35, 0.40, 0.45,
//                    0.50, 0.55, 0.60, 0.65, 0.70, 0.75, 0.80, 0.85, 0.90, 0.95, 1.00};
//            double[] param1 = new double[]{0.5, 1, 1.5, 2, 2.5, 3, 3.5, 4, 4.5, 5};
            double[] param1 = new double[]{1, 2, 3, 4, 5};
//            double[] param2 = new double[]{0.8, 0.7, 0.6, 0.5, 0.4, 0.3, 0.2};
            double[] param2 = new double[]{0.0, 67.3, 134.6, 201.9, 269.2, 336.5, 403.8, 471.1, 538.4, 605.7, 673.0};

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

    // TODO ТУТ ЗАДАЮ КАКИЕ ПАРАМЕТРЫ МЕНЯТЬ В SWEEP
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

//        if (mode == RunMode.SWEEP_1) {
//            for (double p1 : param1) {
//                SystemParameters p = SystemParametersBuilder.from(baseParams)
//                        .setSecondCat(p1)
//                        .setThirdCat(1 - p1)
//                        .build();
//                paramSets.add(p);
//            }
//            return paramSets;
//        }

        // SWEEP_2
        for (double p1 : param1) {
            for (double p2 : param2) {
                SystemParameters p = SystemParametersBuilder.from(baseParams)
                        .setMaxDischargeCurrent(p1)
                        .setBatteryCapacityKwhPerBus(p2)
                        .build();
                paramSets.add(p);
            }
        }

//        for (double p1 : param1) {
//            for (double p2 : param2) {
//                SystemParameters p = SystemParametersBuilder.from(baseParams)
//                        .setMaxDischargeCurrent(p1)
//                        .setNonReserveDischargeLevel(p2)
//                        .build();
//                paramSets.add(p);
//            }
//        }
        return paramSets;
    }
}
