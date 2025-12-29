package simcore;

import simcore.config.*;
import simcore.io.InputData;
import simcore.io.InputDataLoader;

public final class ScenarioFactory {

    private ScenarioFactory() {}

    public static LoadedInput load(String loadPath, String windPath) throws Exception {
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

    public static SystemParameters defaultParams() {
        return new SystemParameters(
                BusSystemType.DOUBLE_BUS,
                0.1, 0.3,0.6,
                8, 330.0,
                8, 315,
                336.5,
                1.0, 2.0, 0.8,
                1.94, 46,
                4.75, 50,
                0.575, 44,
                0.015, 12,
                0.15, 10,
                0, 46,
                0.2, 0.05
        );
    }

    public static SimulationConfig defaultConfig(double[] windMs, int mcIterations, int threads) {
        return new SimulationConfig(
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
    }

    public record LoadedInput(double[] totalLoadKw, double[] windMs) {}
}
