package simcore;

import simcore.config.BusSystemType;
import simcore.config.SimulationConfig;
import simcore.config.SimulationConstants;
import simcore.config.SystemParameters;
import simcore.config.SystemParametersBuilder;
import simcore.engine.*;
import simcore.io.InputData;
import simcore.io.InputDataLoader;
import simcore.io.SweepResultsCsvWriter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Main {

    // 3 режима:
    //  - SINGLE: один набор параметров
    //  - SWEEP_1: варьируем 1 параметр
    //  - SWEEP_2: варьируем 2 параметра
    private enum RunMode { SINGLE, SWEEP_1, SWEEP_2 }

    public static void main(String[] args) {

        String loadFilePath = "D:/01_Load.txt";
        String windFilePath = "D:/02_Wind.txt";

        String resultsCsvPath = "D:/simulation_results_batch.csv";
        String traceCsvPath = "D:/simulation_trace.csv";

        // TODO: ==== ВЫБОР РЕЖИМА ====
//        RunMode mode = RunMode.SINGLE;
//        RunMode mode = RunMode.SWEEP_1;
        RunMode mode = RunMode.SWEEP_2;


        // MC execution-level
        int mcIterations = 500; // для trace должен быть 1
        int threads = Runtime.getRuntime().availableProcessors();
        long mcBaseSeed = 1_000_000L;

        try {
            // 1) входные данные
            InputData input = new InputDataLoader().load(loadFilePath, windFilePath);
            double[] totalLoadKw = input.getLoadKw();
            double[] windMs = input.getWindMs();

            if (totalLoadKw.length != SimulationConstants.DATA_SIZE || windMs.length != SimulationConstants.DATA_SIZE) {
                throw new IllegalStateException(
                        "Неверная длина входных данных. Ожидалось " + SimulationConstants.DATA_SIZE
                                + ", нагрузка: " + totalLoadKw.length
                                + ", ветер: " + windMs.length
                );
            }

            // 2) базовые параметры
            SystemParameters baseParams = new SystemParameters(
                    BusSystemType.SINGLE_SECTIONAL_BUS,
                    8, 330.0,
                    8, 340.0,
                    336.5,
                    1.0, 2.0, 0.5,
                    1.94, 46,
                    4.75, 50,
                    0.575, 44,
                    0.016, 12,
                    0.05, 10
            );

            // 3) конфиг логики
            SimulationConfig cfg = new SimulationConfig(
                    windMs,
                    mcIterations,
                    threads,
                    true,
                    true,
                    false,
                    false,
                    true,
                    true
            );

            SimInput baseInput = new SimInput(cfg, baseParams, totalLoadKw);

            // 4) параметры для варьирования
            // TODO: КАКИЕ ЗНАЧЕНИЯ БУДУ ЗАДАВАТЬ ИЗМЕНЯЕМЫМ ПАРАМЕТРАМ
            double[] param1 = new double[]{200.0, 336.5, 500.0, 800.0}; // BTcap
            double[] param2 = new double[]{0.8, 0.7, 0.6, 0.5, 0.4, 0.3, 0.2};             // I_dis

            // 5) строим список наборов параметров согласно mode
            List<SystemParameters> paramSets = buildParamSets(mode, baseParams, param1, param2);

            boolean allowTrace = (mcIterations == 1) && (paramSets.size() == 1);

            // 6) общий пул потоков на весь эксперимент
            ExecutorService ex = Executors.newFixedThreadPool(threads);
            try {
                SingleRunSimulator sim = new SingleRunSimulator();
                MonteCarloRunner mc = new MonteCarloRunner(ex, sim, false, 1.96, 0.10);
                SimulationEngine engine = new SimulationEngine(mc);

                // 7) прогон всех наборов
                List<MonteCarloEstimate> estimates = new ArrayList<>(paramSets.size());

                for (int k = 0; k < paramSets.size(); k++) {
                    SystemParameters p = paramSets.get(k);
                    SimInput in = baseInput.withSystemParameters(p);

                    // trace включаем только при allowTrace
                    MonteCarloEstimate est = engine.runMonteCarlo(in, mcIterations, mcBaseSeed, allowTrace);
                    estimates.add(est);

                    // экспорт trace только один раз (и только в allowTrace)
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

    // TODO: ТУТ ЗАДАВАТЬ МЕТОДЫ В ЗАВИСИМОСТИ ОТ ИЗМЕНЯЕМЫХ ПАРАМЕТРОВ
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
                        .setNonReserveDischargeLevel(p2)
                        .build();
                paramSets.add(p);
            }
        }
        return paramSets;
    }
}
