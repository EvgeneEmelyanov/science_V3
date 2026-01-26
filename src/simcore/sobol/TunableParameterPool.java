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

        // ----- Частоты отказов (интенсивности), 1/год -----

        m.put(TunableParamId.WT_FAILURE_RATE,
                new TunableParameter(
                        TunableParamId.WT_FAILURE_RATE,
                        "WT_FAILURE_RATE",
                        0.97, 3.88, // base=1.94
                        (b, v) -> b.setWindTurbineFailureRatePerYear(v)
                ));

        m.put(TunableParamId.DG_FAILURE_RATE,
                new TunableParameter(
                        TunableParamId.DG_FAILURE_RATE,
                        "DG_FAILURE_RATE",
                        2.375, 9.5, // base=4.75
                        (b, v) -> b.setDieselGeneratorFailureRatePerYear(v)
                ));

        m.put(TunableParamId.BT_FAILURE_RATE,
                new TunableParameter(
                        TunableParamId.BT_FAILURE_RATE,
                        "BT_FAILURE_RATE",
                        0.2875, 1.15, // base=0.575
                        (b, v) -> b.setBatteryFailureRatePerYear(v)
                ));

        m.put(TunableParamId.BUS_FAILURE_RATE,
                new TunableParameter(
                        TunableParamId.BUS_FAILURE_RATE,
                        "BUS_FAILURE_RATE",
                        0.008, 0.032, // base=0.016
                        (b, v) -> b.setBusFailureRatePerYear(v)
                ));

        m.put(TunableParamId.BRK_FAILURE_RATE,
                new TunableParameter(
                        TunableParamId.BRK_FAILURE_RATE,
                        "BRK_FAILURE_RATE",
                        0.025, 0.1, // base=0.05
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

        m.put(TunableParamId.ROOM_REPAIR_TIME,
                new TunableParameter(
                        TunableParamId.ROOM_REPAIR_TIME,
                        "ROOM_REPAIR_TIME",
                        12, 48,
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
                        2.0, 16,
                        (b, v) -> b.setTotalWindTurbineCount((int) Math.round(v))
                ));

        m.put(TunableParamId.WT_POWER,
                new TunableParameter(
                        TunableParamId.WT_POWER,
                        "WT_POWER",
                        250, 1000,
                        (b, v) -> b.setWindTurbinePowerKw(v)
                ));

        // ----- Параметры ДГУ -----
        m.put(TunableParamId.DG_COUNT,
                new TunableParameter(
                        TunableParamId.DG_COUNT,
                        "DG_COUNT",
                        4, 8,
                        (b, v) -> b.setTotalDieselGeneratorCount((int) Math.round(v))
                ));

        m.put(TunableParamId.DG_POWER,
                new TunableParameter(
                        TunableParamId.DG_POWER,
                        "DG_POWER",
                        250, 500,
                        (b, v) -> b.setDieselGeneratorPowerKw(v)
                ));

        // ----- АКБ -----
        m.put(TunableParamId.BT_CAPACITY_PER_BUS,
                new TunableParameter(
                        TunableParamId.BT_CAPACITY_PER_BUS,
                        "BT_CAPACITY_PER_BUS",
                        200, 450,
                        (b, v) -> b.setBatteryCapacityKwhPerBus(v)
                ));
        m.put(TunableParamId.BT_MAX_CHARGE_CURRENT,
                new TunableParameter(
                        TunableParamId.BT_MAX_CHARGE_CURRENT,
                        "BT_MAX_CHARGE_CURRENT",
                        0.6, 1,
                        (b, v) -> b.setMaxChargeCurrent(v)
                ));
        m.put(TunableParamId.BT_MAX_DISCHARGE_CURRENT,
                new TunableParameter(
                        TunableParamId.BT_MAX_DISCHARGE_CURRENT,
                        "BT_MAX_DISCHARGE_CURRENT",
                        1, 3,
                        (b, v) -> b.setMaxDischargeCurrent(v)
                ));
        m.put(TunableParamId.BT_NON_RESERVE_DISCHARGE_LVL,
                new TunableParameter(
                        TunableParamId.BT_NON_RESERVE_DISCHARGE_LVL,
                        "BT_NON_RESERVE_DISCHARGE_LVL",
                        0.6, 0.8,
                        (b, v) -> b.setNonReserveDischargeLevel(v)
                ));

        // ----- Economics / prices -----

        m.put(TunableParamId.DISCOUNT_RATE,
                new TunableParameter(
                        TunableParamId.DISCOUNT_RATE,
                        "DISCOUNT_RATE",
                        0.04, 0.16,
                        (b, v) -> b.setDiscountRatePerYear(v)
                ));

        m.put(TunableParamId.COST_RU_RUB,
                new TunableParameter(
                        TunableParamId.COST_RU_RUB,
                        "COST_RU_RUB",
                        2_000_000, 8_000_000,
                        (b, v) -> b.setCostRuRub(v)
                ));

        m.put(TunableParamId.COST_DG_RUB_PER_KW,
                new TunableParameter(
                        TunableParamId.COST_DG_RUB_PER_KW,
                        "COST_DG_RUB_PER_KW",
                        20_000.0, 80_000.0,
                        (b, v) -> b.setCostDgRubPerKw(v)
                ));

        m.put(TunableParamId.COST_DG_RUB_PER_KW_PER_KMH,
                new TunableParameter(
                        TunableParamId.COST_DG_RUB_PER_KW_PER_KMH,
                        "COST_DG_RUB_PER_KW_PER_KMH",
                        800, 3200,
                        (b, v) -> b.setCostDgRubPerKwPerKmh(v)
                ));

        m.put(TunableParamId.COST_FUEL_RUB_PER_KT,
                new TunableParameter(
                        TunableParamId.COST_FUEL_RUB_PER_KT,
                        "COST_FUEL_RUB_PER_KT",
                        45_000_000.0, 180_000_000,
                        (b, v) -> b.setCostFuelRubPerKt(v)
                ));

        m.put(TunableParamId.COST_WT_RUB_PER_KW,
                new TunableParameter(
                        TunableParamId.COST_WT_RUB_PER_KW,
                        "COST_WT_RUB_PER_KW",
                        75000, 300000,
                        (b, v) -> b.setCostWtRubPerKw(v)
                ));

        m.put(TunableParamId.COST_WT_RUB_PER_KW_PER_YEAR,
                new TunableParameter(
                        TunableParamId.COST_WT_RUB_PER_KW_PER_YEAR,
                        "COST_WT_RUB_PER_KW_PER_YEAR",
                        1500, 6000,
                        (b, v) -> b.setCostWtRubPerKwPerYear(v)
                ));

        m.put(TunableParamId.COST_BT_RUB_PER_KWH,
                new TunableParameter(
                        TunableParamId.COST_BT_RUB_PER_KWH,
                        "COST_BT_RUB_PER_KWH",
                        44000, 176000,
                        (b, v) -> b.setCostBtRubPerKwh(v)
                ));

        m.put(TunableParamId.COST_BT_RUB_PER_KWH_PER_YEAR,
                new TunableParameter(
                        TunableParamId.COST_BT_RUB_PER_KWH_PER_YEAR,
                        "COST_BT_RUB_PER_KWH_PER_YEAR",
                        1100, 4400,
                        (b, v) -> b.setCostBtRubPerKwhPerYear(v)
                ));

        m.put(TunableParamId.DAMAGE_RUB_PER_KWH_CAT1,
                new TunableParameter(
                        TunableParamId.DAMAGE_RUB_PER_KWH_CAT1,
                        "DAMAGE_RUB_PER_KWH_CAT1",
                        3500, 14000,
                        (b, v) -> b.setDamageRubPerKwhCat1(v)
                ));

        m.put(TunableParamId.DAMAGE_RUB_PER_KWH_CAT2,
                new TunableParameter(
                        TunableParamId.DAMAGE_RUB_PER_KWH_CAT2,
                        "DAMAGE_RUB_PER_KWH_CAT2",
                        1050, 4200,
                        (b, v) -> b.setDamageRubPerKwhCat2(v)
                ));

        m.put(TunableParamId.DAMAGE_RUB_PER_KWH_CAT3,
                new TunableParameter(
                        TunableParamId.DAMAGE_RUB_PER_KWH_CAT3,
                        "DAMAGE_RUB_PER_KWH_CAT3",
                        350, 1400,
                        (b, v) -> b.setDamageRubPerKwhCat3(v)
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
