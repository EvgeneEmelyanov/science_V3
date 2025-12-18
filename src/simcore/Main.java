package simcore;

import simcore.config.BusSystemType;
import simcore.config.SimulationConfig;
import simcore.config.SimulationConstants;
import simcore.config.SystemParameters;
import simcore.engine.SimulationEngine;
import simcore.io.InputData;
import simcore.io.InputDataLoader;

public class Main {

    public static void main(String[] args) {

        String loadFilePath = "D:/01_Load.txt";
        String windFilePath = "D:/02_Wind.txt";

        try {
            // 1. Загрузка входных данных
            InputDataLoader loader = new InputDataLoader();
            InputData input = loader.load(loadFilePath, windFilePath);

            double[] totalLoadKw = input.getLoadKw();
            double[] windMs = input.getWindMs();

            // Проверка длины временных рядов
            if (totalLoadKw.length != SimulationConstants.DATA_SIZE ||
                    windMs.length != SimulationConstants.DATA_SIZE) {
                throw new IllegalStateException(
                        "Неверная длина входных данных. Ожидалось " + SimulationConstants.DATA_SIZE +
                                ", нагрузка: " + totalLoadKw.length +
                                ", ветер: " + windMs.length
                );
            }

            // 2. Параметры системы (тип шин, количество и мощность оборудования, ёмкость АКБ)
            SystemParameters params = new SystemParameters(
                    BusSystemType.SINGLE_SECTIONAL_BUS,
                    8,        // всего ВЭУ
                    330.0,    // мощность одной ВЭУ, кВт
                    8,       // всего ДГУ
                    340.0,    // мощность одного ДГУ, кВт
                    336.5,    // ёмкость АКБ на шину, кВт·ч

                    1.0,      // максимальный ток заряда АКБ, С
                    2.0,      // максимальный ток разряда АКБ, С
                    0.8,      // допустимый уровень разряда АКБ не для резерва


                    1.94,     // WT: частота отказов, 1/год
                    46,       // WT: время ремонта, ч

                    4.75,      // DG: частота отказов, 1/год (пример)
                    50,       // DG: время ремонта, ч (пример)

                    0.575,    // BT: частота отказов, 1/год (пример)
                    44,       // BT: время ремонта/замены, ч

                    0.016,      // BUS: частота отказов, 1/год (пример)
                    12,       // BUS: время ремонта, ч (пример)

                    0.05,      // BRK: частота отказов, 1/год (пример)
                    10         // BRK: время ремонта, ч (пример)
            );

            // 3. Конфиг симуляции
            SimulationConfig config = new SimulationConfig(
                    windMs,
                    1,   // Monte Carlo итераций; 1 → будет CSV-трейс на D:/simulation_trace.csv
                    Runtime.getRuntime().availableProcessors(),
                    true,
                    true,
                    true,
                    false,
                    true,
                    true
            );

            // 4. Создаём движок
            SimulationEngine engine = new SimulationEngine(config, params, totalLoadKw);

            // 5. Запускаем
            SimulationEngine.SimulationSummary summary = engine.runMonteCarlo();

            // 6. Вывод результатов


        } catch (Exception e) {
            System.err.println("Ошибка: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
