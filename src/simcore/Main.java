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
                    4,        // всего ВЭУ
                    250.0,    // мощность одной ВЭУ, кВт
                    10,       // всего ДГУ
                    500.0,    // мощность одного ДГУ, кВт
                    500.0,    // ёмкость АКБ на шину, кВт·ч

                    1.94,     // WT: частота отказов, 1/год
                    46,       // WT: время ремонта, ч

                    1.0,      // DG: частота отказов, 1/год (пример)
                    24,       // DG: время ремонта, ч (пример)

                    0.575,    // BT: частота отказов, 1/год (пример)
                    44,       // BT: время ремонта/замены, ч

                    0.1,      // BUS: частота отказов, 1/год (пример)
                    10,       // BUS: время ремонта, ч (пример)

                    0.2,      // BRK: частота отказов, 1/год (пример)
                    8         // BRK: время ремонта, ч (пример)
            );

            // 3. Конфиг симуляции
            SimulationConfig config = new SimulationConfig(
                    windMs,
                    1,   // Monte Carlo итераций; 1 → будет CSV-трейс на D:/simulation_trace.csv
                    Runtime.getRuntime().availableProcessors(),
                    true // учитывать отказы
            );

            // 4. Создаём движок
            SimulationEngine engine = new SimulationEngine(config, params, totalLoadKw);

            // 5. Запускаем
            SimulationEngine.SimulationSummary summary = engine.runMonteCarlo();

            // 6. Вывод результатов
            System.out.println("Прогонов:              " + summary.getIterations());
            System.out.println("Суммарный дефицит, кВт·ч:  " + summary.getTotalDeficitSum());
            System.out.println("Средний дефицит, кВт·ч:    " + summary.getAverageDeficit());
            System.out.println("Нагрузка от ВЭУ, кВт·ч:    " + summary.getTotalSupplyFromWT());
            System.out.println("Нагрузка от ДГУ, кВт·ч:    " + summary.getTotalSupplyFromDG());

        } catch (Exception e) {
            System.err.println("Ошибка: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
