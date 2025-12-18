package simcore;

import simcore.config.BusSystemType;
import simcore.config.SimulationConfig;
import simcore.config.SimulationConstants;
import simcore.config.SystemParameters;
import simcore.engine.*;
import simcore.io.InputData;
import simcore.io.InputDataLoader;
import simcore.sobol.*;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class SimcoreRunner {

    private SimcoreRunner() {}

    public static LoadedScenario loadScenario(String loadFilePath, String windFilePath) throws Exception {

        InputDataLoader loader = new InputDataLoader();
        InputData input = loader.load(loadFilePath, windFilePath);

        double[] totalLoadKw = input.getLoadKw();
        double[] windMs = input.getWindMs();

        if (totalLoadKw.length != SimulationConstants.DATA_SIZE ||
                windMs.length != SimulationConstants.DATA_SIZE) {
            throw new IllegalStateException(
                    "Неверная длина входных данных. Ожидалось " + SimulationConstants.DATA_SIZE +
                            ", нагрузка: " + totalLoadKw.length +
                            ", ветер: " + windMs.length
            );
        }

        return new LoadedScenario(totalLoadKw, windMs);
    }

    public static SystemParameters buildDefaultParams(double[] ignoredWind) {
        // Здесь держите “базовую точку”, как в вашем старом Main.
        return new SystemParameters(
                BusSystemType.SINGLE_SECTIONAL_BUS,
                8,
                330.0,
                8,
                340.0,
                336.5,

                1.0,
                2.0,
                0.8,

                1.94,
                46,

                4.75,
                50,

                0.575,
                44,

                0.016,
                12,

                0.05,
                10
        );
    }

    public static SimulationConfig buildDefaultConfig(double[] windMs, int iterations, int threads) {
        // Важно: iterations/threads в новой архитектуре не обязательны, но пусть остаются для совместимости.
        return new SimulationConfig(
                windMs,
                iterations,
                threads,
                true,   // considerFailures
                true,   // considerBatteryDegradation
                false,
                false,
                true,
                true
        );
    }

    public static EngineContext buildEngineContext(SimulationConfig cfg,
                                                   SystemParameters params,
                                                   double[] totalLoadKw,
                                                   int threads) {

        ExecutorService executor = Executors.newFixedThreadPool(threads);

        SingleRunSimulator simulator = new SingleRunSimulator();

        MonteCarloRunner mcRunner = new MonteCarloRunner(
                executor,
                simulator,
                false,   // removeOutliers
                1.96,    // tScore
                0.10     // relativeError
        );

        SimulationEngine engine = new SimulationEngine(mcRunner);

        SimInput simInput = new SimInput(cfg, params, totalLoadKw);

        return new EngineContext(simInput, engine, mcRunner, executor);
    }

    /**
     * Собираем SobolConfig и список факторов.
     * Факторы здесь примерные — поменяйте min/max под ваши смыслы.
     */
    public static SobolConfig buildSobolConfig(int sobolN,
                                               int mcIterations,
                                               long mcBaseSeed,
                                               int threads,
                                               List<SobolFactor> factors) {

        return new SobolConfig(
                sobolN,
                mcIterations,
                mcBaseSeed,
                threads,
                factors,
                SystemParameters::copy // <-- теперь copy() есть
        );
    }

    public record LoadedScenario(double[] totalLoadKw, double[] windMs) {}

    public record EngineContext(SimInput input,
                                SimulationEngine engine,
                                MonteCarloRunner mcRunner,
                                ExecutorService executor) {}
}
