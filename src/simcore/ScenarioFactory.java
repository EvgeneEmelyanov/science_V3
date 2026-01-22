package simcore;

import simcore.config.*;
import simcore.io.InputData;
import simcore.io.InputDataLoader;

public final class ScenarioFactory {

    private ScenarioFactory() {}

    // ===== Default economics (used for code-side LCOE; Econ in Excel remains formula-based) =====
    public static final double DEFAULT_DISCOUNT_RATE = 0.08;
    public static final double DEFAULT_COST_RU_RUB = 4_000_000;
    public static final double DEFAULT_COST_DG_RUB_PER_KW = 40_000;
    public static final double DEFAULT_COST_DG_RUB_PER_KW_PER_KMH = 1600.0;
    public static final double DEFAULT_COST_FUEL_RUB_PER_KT = 90_000_000.0;
    public static final double DEFAULT_COST_WT_RUB_PER_KW = 150_000;
    public static final double DEFAULT_COST_WT_RUB_PER_KW_PER_YEAR = 3_000;
    public static final double DEFAULT_COST_BT_RUB_PER_KWH = 88_000.0;
    public static final double DEFAULT_COST_BT_RUB_PER_KWH_PER_YEAR = 2_200.0;
    public static final double DEFAULT_DAMAGE_RUB_PER_KWH_CAT1 = 7_000.0;
    public static final double DEFAULT_DAMAGE_RUB_PER_KWH_CAT2 = 2_100.0;
    public static final double DEFAULT_DAMAGE_RUB_PER_KWH_CAT3 = 700.0;

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

    public static SystemParameters defaultParams(BusSystemType busSystemType) {
        return new SystemParameters(
                busSystemType,
                0.65, 0.25,
                4, 500,
                6, 250,
                300,
                1.0, 2.0, 0.8,
                1.94, 46,
                4.75, 50,
                0.575, 44,
                0.02, 12,
                0.1, 10,
                0.0, 24,
                0.0, 0.0,

                DEFAULT_DISCOUNT_RATE,
                DEFAULT_COST_RU_RUB,
                DEFAULT_COST_DG_RUB_PER_KW,
                DEFAULT_COST_DG_RUB_PER_KW_PER_KMH,
                DEFAULT_COST_FUEL_RUB_PER_KT,
                DEFAULT_COST_WT_RUB_PER_KW,
                DEFAULT_COST_WT_RUB_PER_KW_PER_YEAR,
                DEFAULT_COST_BT_RUB_PER_KWH,
                DEFAULT_COST_BT_RUB_PER_KWH_PER_YEAR,
                DEFAULT_DAMAGE_RUB_PER_KWH_CAT1,
                DEFAULT_DAMAGE_RUB_PER_KWH_CAT2,
                DEFAULT_DAMAGE_RUB_PER_KWH_CAT3
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
