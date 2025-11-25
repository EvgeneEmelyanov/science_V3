package simcore.sobol;

import simcore.config.SystemParametersBuilder;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Каталог всех параметров, которые можно варьировать в анализе Соболя.
 *
 * Здесь задаются дефолтные диапазоны [min, max] и логика применения
 * значения к SystemParametersBuilder.
 *
 * Важная фишка:
 *  - ты один раз здесь описываешь все параметры,
 *  - потом в экспериментах просто выбираешь список TunableParamId.
 */
public class TunableParameterPool {

    private static final Map<TunableParamId, TunableParameter> PARAMS;

    static {
        Map<TunableParamId, TunableParameter> m = new EnumMap<>(TunableParamId.class);

        // ----- Частоты отказов -----
        m.put(TunableParamId.WT_FAILURE_RATE,
                new TunableParameter(
                        TunableParamId.WT_FAILURE_RATE,
                        "WT Failure Rate (1/year)",
                        0.5, 3.0,
                        (SystemParametersBuilder b, double v) -> b.setWindTurbineFailureRatePerYear(v)
                ));

        m.put(TunableParamId.DG_FAILURE_RATE,
                new TunableParameter(
                        TunableParamId.DG_FAILURE_RATE,
                        "DG Failure Rate (1/year)",
                        0.2, 2.0,
                        (SystemParametersBuilder b, double v) -> b.setDieselGeneratorFailureRatePerYear(v)
                ));

        m.put(TunableParamId.BT_FAILURE_RATE,
                new TunableParameter(
                        TunableParamId.BT_FAILURE_RATE,
                        "Battery Failure Rate (1/year)",
                        0.1, 1.0,
                        (SystemParametersBuilder b, double v) -> b.setBatteryFailureRatePerYear(v)
                ));

        m.put(TunableParamId.BUS_FAILURE_RATE,
                new TunableParameter(
                        TunableParamId.BUS_FAILURE_RATE,
                        "Bus Failure Rate (1/year)",
                        0.01, 0.5,
                        (SystemParametersBuilder b, double v) -> b.setBusFailureRatePerYear(v)
                ));

        m.put(TunableParamId.BRK_FAILURE_RATE,
                new TunableParameter(
                        TunableParamId.BRK_FAILURE_RATE,
                        "Breaker Failure Rate (1/year)",
                        0.01, 0.5,
                        (SystemParametersBuilder b, double v) -> b.setBreakerFailureRatePerYear(v)
                ));

        // ----- Времена ремонта -----
        m.put(TunableParamId.WT_REPAIR_TIME,
                new TunableParameter(
                        TunableParamId.WT_REPAIR_TIME,
                        "WT Repair Time (h)",
                        10.0, 100.0,
                        (SystemParametersBuilder b, double v) -> b.setWindTurbineRepairTimeHours((int) Math.round(v))
                ));

        m.put(TunableParamId.DG_REPAIR_TIME,
                new TunableParameter(
                        TunableParamId.DG_REPAIR_TIME,
                        "DG Repair Time (h)",
                        5.0, 72.0,
                        (SystemParametersBuilder b, double v) -> b.setDieselGeneratorRepairTimeHours((int) Math.round(v))
                ));

        m.put(TunableParamId.BT_REPAIR_TIME,
                new TunableParameter(
                        TunableParamId.BT_REPAIR_TIME,
                        "Battery Repair Time (h)",
                        10.0, 100.0,
                        (SystemParametersBuilder b, double v) -> b.setBatteryRepairTimeHours((int) Math.round(v))
                ));

        m.put(TunableParamId.BUS_REPAIR_TIME,
                new TunableParameter(
                        TunableParamId.BUS_REPAIR_TIME,
                        "Bus Repair Time (h)",
                        1.0, 48.0,
                        (SystemParametersBuilder b, double v) -> b.setBusRepairTimeHours((int) Math.round(v))
                ));

        m.put(TunableParamId.BRK_REPAIR_TIME,
                new TunableParameter(
                        TunableParamId.BRK_REPAIR_TIME,
                        "Breaker Repair Time (h)",
                        1.0, 24.0,
                        (SystemParametersBuilder b, double v) -> b.setBreakerRepairTimeHours((int) Math.round(v))
                ));

        // ----- Параметры ВЭУ -----
        m.put(TunableParamId.WT_COUNT,
                new TunableParameter(
                        TunableParamId.WT_COUNT,
                        "WT Count",
                        1.0, 20.0,
                        (SystemParametersBuilder b, double v) -> b.setTotalWindTurbineCount((int) Math.round(v))
                ));

        m.put(TunableParamId.WT_POWER,
                new TunableParameter(
                        TunableParamId.WT_POWER,
                        "WT Power (kW)",
                        100.0, 500.0,
                        (SystemParametersBuilder b, double v) -> b.setWindTurbinePowerKw(v)
                ));

        // ----- Параметры ДГУ -----
        m.put(TunableParamId.DG_COUNT,
                new TunableParameter(
                        TunableParamId.DG_COUNT,
                        "DG Count",
                        1.0, 20.0,
                        (SystemParametersBuilder b, double v) -> b.setTotalDieselGeneratorCount((int) Math.round(v))
                ));

        m.put(TunableParamId.DG_POWER,
                new TunableParameter(
                        TunableParamId.DG_POWER,
                        "DG Power (kW)",
                        100.0, 800.0,
                        (SystemParametersBuilder b, double v) -> b.setDieselGeneratorPowerKw(v)
                ));

        // ----- АКБ -----
        m.put(TunableParamId.BT_CAPACITY_PER_BUS,
                new TunableParameter(
                        TunableParamId.BT_CAPACITY_PER_BUS,
                        "Battery Capacity per Bus (kWh)",
                        0.0, 2000.0,
                        (SystemParametersBuilder b, double v) -> b.setBatteryCapacityKwhPerBus(v)
                ));

        PARAMS = Collections.unmodifiableMap(m);
    }

    /**
     * Получить описание параметра по id.
     */
    public static TunableParameter get(TunableParamId id) {
        TunableParameter p = PARAMS.get(id);
        if (p == null) {
            throw new IllegalArgumentException("Неизвестный параметр: " + id);
        }
        return p;
    }

    /**
     * Собрать список SobolParameter по списку TunableParamId.
     */
    public static List<SobolParameter> toSobolParameters(List<TunableParamId> ids) {
        return ids.stream()
                .map(TunableParameterPool::get)
                .map(TunableParameter::toSobolParameter)
                .collect(Collectors.toList());
    }

    /**
     * Получить все доступные параметры (например, для UI).
     */
    public static Collection<TunableParameter> all() {
        return PARAMS.values();
    }
}
