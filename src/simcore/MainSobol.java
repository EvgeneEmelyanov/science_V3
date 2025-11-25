package simcore;

import simcore.config.*;
import simcore.io.InputData;
import simcore.io.InputDataLoader;
import simcore.sobol.*;

import java.util.List;

public class MainSobol {

    public static void main(String[] args) {

        String loadFilePath = "D:/01_Load.txt";
        String windFilePath = "D:/02_Wind.txt";

        try {
            // 1. Загрузка входных данных
            InputDataLoader loader = new InputDataLoader();
            InputData input = loader.load(loadFilePath, windFilePath);

            double[] totalLoadKw = input.getLoadKw();
            double[] windMs = input.getWindMs();

            // 2. Базовые параметры системы (та же точка, что и в Main)
            SystemParameters baseParams = new SystemParameters(
                    BusSystemType.SINGLE_SECTIONAL_BUS,
                    8,        // всего ВЭУ
                    250.0,    // мощность одной ВЭУ, кВт
                    10,       // всего ДГУ
                    500.0,    // мощность одного ДГУ, кВт
                    500.0,    // ёмкость АКБ на шину, кВт·ч

                    1.94,     // WT: частота отказов, 1/год
                    46,       // WT: время ремонта, ч

                    1.0,      // DG: частота отказов, 1/год
                    24,       // DG: время ремонта, ч

                    0.575,    // BT: частота отказов, 1/год
                    44,       // BT: время ремонта, ч

                    0.1,      // BUS: частота отказов, 1/год
                    10,       // BUS: время ремонта, ч

                    0.2,      // BRK: частота отказов, 1/год
                    8         // BRK: время ремонта, ч
            );

            // 3. Базовый конфиг симуляции
            SimulationConfig baseConfig = new SimulationConfig(
                    windMs,
                    50,   // число Monte Carlo прогонов на одну точку Соболя (пример)
                    Runtime.getRuntime().availableProcessors(),
                    true
            );

            // 4. Выбираем, какие параметры хотим варьировать.
            //    Здесь: все частоты отказов оборудования.
            List<TunableParamId> ids = List.of(
                    TunableParamId.WT_FAILURE_RATE,
                    TunableParamId.DG_FAILURE_RATE,
                    TunableParamId.BT_FAILURE_RATE,
                    TunableParamId.BUS_FAILURE_RATE,
                    TunableParamId.BRK_FAILURE_RATE
            );

            // 5. Преобразуем их в список SobolParameter через пул
            List<SobolParameter> sobolParams =
                    TunableParameterPool.toSobolParameters(ids);

            // 6. Определяем метрику: по чему считаем чувствительность.
            //    Например, по среднему дефициту.
            SimulationMetric metric = summary -> summary.getAverageDeficit();

            // 7. Создаём анализатор Соболя
            SobolAnalyzer analyzer = new SobolAnalyzer(
                    baseConfig,
                    baseParams,
                    totalLoadKw,
                    sobolParams,
                    metric
            );

            int N = 100; // размер матриц A и B (число базовых точек)
            SobolResult result = analyzer.analyze(N);

            double[] S = result.getFirstOrderIndices();
            double[] ST = result.getTotalOrderIndices();

            System.out.println("Результаты анализа Соболя (метрика: средний дефицит):");
            for (int i = 0; i < sobolParams.size(); i++) {
                String name = sobolParams.get(i).getName();
                System.out.printf("%-30s  S = %8.4f   ST = %8.4f%n",
                        name, S[i], ST[i]);
            }

        } catch (Exception e) {
            System.err.println("Ошибка в MainSobol: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
