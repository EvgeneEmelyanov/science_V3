package simcore.sobol;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Каталог всех параметров, которые можно варьировать в анализе Соболя.
 * Диапазоны и логика применения задаются один раз здесь.
 */
public final class TunableParameterPool {

    private static final Map<TunableParamId, TunableParameter> PARAMS;

    static {
        Map<TunableParamId, TunableParameter> m = new EnumMap<>(TunableParamId.class);

        // ----- Частоты отказов -----
        m.put(TunableParamId.WT_FAILURE_RATE,
                new TunableParameter(
                        TunableParamId.WT_FAILURE_RATE,
                        "WT_FAILURE_RATE",
                        0.485, 3.88,
                        (b, v) -> b.setWindTurbineFailureRatePerYear(v)
                ));

        m.put(TunableParamId.DG_FAILURE_RATE,
                new TunableParameter(
                        TunableParamId.DG_FAILURE_RATE,
                        "DG_FAILURE_RATE",
                        1.1875, 7,
                        (b, v) -> b.setDieselGeneratorFailureRatePerYear(v)
                ));

        m.put(TunableParamId.BT_FAILURE_RATE,
                new TunableParameter(
                        TunableParamId.BT_FAILURE_RATE,
                        "BT_FAILURE_RATE",
                        0.2, 3,
                        (b, v) -> b.setBatteryFailureRatePerYear(v)
                ));

        m.put(TunableParamId.BUS_FAILURE_RATE,
                new TunableParameter(
                        TunableParamId.BUS_FAILURE_RATE,
                        "BUS_FAILURE_RATE",
                        0.005, 0.5,
                        (b, v) -> b.setBusFailureRatePerYear(v)
                ));

        m.put(TunableParamId.BRK_FAILURE_RATE,
                new TunableParameter(
                        TunableParamId.BRK_FAILURE_RATE,
                        "BRK_FAILURE_RATE",
                        0.005, 0.3,
                        (b, v) -> b.setBreakerFailureRatePerYear(v)
                ));

        // ----- Времена ремонта -----
        m.put(TunableParamId.WT_REPAIR_TIME,
                new TunableParameter(
                        TunableParamId.WT_REPAIR_TIME,
                        "WT_REPAIR_TIME",
                        25, 100.0,
                        (b, v) -> b.setWindTurbineRepairTimeHours((int) Math.round(v))
                ));

        m.put(TunableParamId.DG_REPAIR_TIME,
                new TunableParameter(
                        TunableParamId.DG_REPAIR_TIME,
                        "DG_REPAIR_TIME",
                        25, 100,
                        (b, v) -> b.setDieselGeneratorRepairTimeHours((int) Math.round(v))
                ));

        m.put(TunableParamId.BT_REPAIR_TIME,
                new TunableParameter(
                        TunableParamId.BT_REPAIR_TIME,
                        "BT_REPAIR_TIME",
                        25, 100.0,
                        (b, v) -> b.setBatteryRepairTimeHours((int) Math.round(v))
                ));

        m.put(TunableParamId.BUS_REPAIR_TIME,
                new TunableParameter(
                        TunableParamId.BUS_REPAIR_TIME,
                        "BUS_REPAIR_TIME",
                        5, 20,
                        (b, v) -> b.setBusRepairTimeHours((int) Math.round(v))
                ));

        m.put(TunableParamId.BRK_REPAIR_TIME,
                new TunableParameter(
                        TunableParamId.BRK_REPAIR_TIME,
                        "BRK_REPAIR_TIME",
                        5, 20,
                        (b, v) -> b.setBreakerRepairTimeHours((int) Math.round(v))
                ));

        // ----- Параметры ВЭУ -----
        m.put(TunableParamId.WT_COUNT,
                new TunableParameter(
                        TunableParamId.WT_COUNT,
                        "WT_COUNT",
                        1.0, 20.0,
                        (b, v) -> b.setTotalWindTurbineCount((int) Math.round(v))
                ));

        m.put(TunableParamId.WT_POWER,
                new TunableParameter(
                        TunableParamId.WT_POWER,
                        "WT_POWER",
                        100.0, 500.0,
                        (b, v) -> b.setWindTurbinePowerKw(v)
                ));

        // ----- Параметры ДГУ -----
        m.put(TunableParamId.DG_COUNT,
                new TunableParameter(
                        TunableParamId.DG_COUNT,
                        "DG_COUNT",
                        1.0, 20.0,
                        (b, v) -> b.setTotalDieselGeneratorCount((int) Math.round(v))
                ));

        m.put(TunableParamId.DG_POWER,
                new TunableParameter(
                        TunableParamId.DG_POWER,
                        "DG_POWER",
                        100.0, 800.0,
                        (b, v) -> b.setDieselGeneratorPowerKw(v)
                ));

        // ----- АКБ -----
        m.put(TunableParamId.BT_CAPACITY_PER_BUS,
                new TunableParameter(
                        TunableParamId.BT_CAPACITY_PER_BUS,
                        "BT_CAPACITY_PER_BUS",
                        0.0, 2000.0,
                        (b, v) -> b.setBatteryCapacityKwhPerBus(v)
                ));
        m.put(TunableParamId.BT_MAX_CHARGE_CURRENT,
                new TunableParameter(
                        TunableParamId.BT_MAX_CHARGE_CURRENT,
                        "BT_MAX_CHARGE_CURRENT",
                        0.2, 1,
                        (b, v) -> b.setMaxChargeCurrent(v)
                ));
        m.put(TunableParamId.BT_MAX_DISCHARGE_CURRENT,
                new TunableParameter(
                        TunableParamId.BT_MAX_DISCHARGE_CURRENT,
                        "BT_MAX_DISCHARGE_CURRENT",
                        0.5, 5,
                        (b, v) -> b.setMaxDischargeCurrent(v)
                ));
        m.put(TunableParamId.BT_NON_RESERVE_DISCHARGE_LVL,
                new TunableParameter(
                        TunableParamId.BT_NON_RESERVE_DISCHARGE_LVL,
                        "BT_NON_RESERVE_DISCHARGE_LVL",
                        0, 0.8,
                        (b, v) -> b.setNonReserveDischargeLevel(v)
                ));

        PARAMS = Collections.unmodifiableMap(m);
    }

    private TunableParameterPool() {}

    public static TunableParameter get(TunableParamId id) {
        TunableParameter p = PARAMS.get(id);
        if (p == null) throw new IllegalArgumentException("Unknown parameter: " + id);
        return p;
    }

    /** Ключевое: превращаем ids -> SobolFactor (для вашей текущей архитектуры). */
    public static List<SobolFactor> toSobolFactors(List<TunableParamId> ids) {
        return ids.stream()
                .map(TunableParameterPool::get)
                .map(TunableParameter::toSobolFactor)
                .collect(Collectors.toList());
    }

    public static Collection<TunableParameter> all() {
        return PARAMS.values();
    }
}
