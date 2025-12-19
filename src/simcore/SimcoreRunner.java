package simcore;

import simcore.config.BusSystemType;
import simcore.config.SimulationConfig;
import simcore.config.SimulationConstants;
import simcore.config.SystemParameters;
import simcore.engine.*;
import simcore.io.InputData;
import simcore.io.InputDataLoader;

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
        return new SystemParameters(
                BusSystemType.SINGLE_SECTIONAL_BUS,
                8, 330.0,
                8, 340.0,
                336.5,
                1.0, 2.0, 0.8,
                1.94, 46,
                4.75, 50,
                0.575, 44,
                0.016, 12,
                0.05, 10
        );
    }

    public static SimulationConfig buildDefaultConfig(double[] windMs, int iterations, int threads) {
        return new SimulationConfig(
                windMs,
                iterations,
                threads,
                true,
                true,
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
                false,
                1.96,
                0.10
        );

        SimulationEngine engine = new SimulationEngine(mcRunner);

        SimInput simInput = new SimInput(cfg, params, totalLoadKw);

        return new EngineContext(simInput, engine, mcRunner, executor);
    }

    public record LoadedScenario(double[] totalLoadKw, double[] windMs) {}

    public record EngineContext(SimInput input,
                                SimulationEngine engine,
                                MonteCarloRunner mcRunner,
                                ExecutorService executor) {}
}
