package simcore.sobol;

import java.util.*;
import java.util.stream.Collectors;

import simcore.config.ModelDefaults;
import simcore.config.SystemParametersBuilder;

/**
 * Каталог всех параметров, которые можно варьировать в анализе Соболя.
 * Диапазоны и логика применения задаются один раз здесь.
 */
public final class TunableParameterPool {

    private static final Map<TunableParamId, TunableParameter> PARAMS;

    static {
        Map<TunableParamId, TunableParameter> m = new EnumMap<>(TunableParamId.class);

        // ----- Доли категорий надежности (k1, k2), k3 = 1 - k1 - k2 -----

        m.put(TunableParamId.FIRST_CAT,
                new TunableParameter(
                        TunableParamId.FIRST_CAT,
                        "FIRST_CAT",
                        0,
                        1,
                        (b, v) -> {
                            double k1 = clamp(v, 0.0, 1.0);
                            double k2 = clamp01(b.getSecondCat());
                            if (k1 + k2 > 1.0) k2 = 1.0 - k1;
                            b.setFirstCat(k1);
                            b.setSecondCat(Math.max(0.0, k2));
                        }
                )
        );

        m.put(TunableParamId.SECOND_CAT,
                new TunableParameter(
                        TunableParamId.SECOND_CAT,
                        "SECOND_CAT",
                        0,
                        1,
                        (b, v) -> {
                            double k1 = clamp01(b.getFirstCat());
                            double k2 = clamp(v, 0.0, 1.0);

                            if (k1 + k2 > 1.0) k2 = 1.0 - k1; // сохраняем место под 3 категорию
                            b.setSecondCat(Math.max(0.0, k2));
                        }
                )
        );


        // ----- Частоты отказов (интенсивности), 1/год -----

        m.put(TunableParamId.WT_FAILURE_RATE,
                new TunableParameter(
                        TunableParamId.WT_FAILURE_RATE,
                        "WT_FAILURE_RATE",
                        minFromBase(ModelDefaults.DEFAULT_WT_FAILURE_RATE_PER_YEAR, 0.5, 0.97),
                        maxFromBase(ModelDefaults.DEFAULT_WT_FAILURE_RATE_PER_YEAR, 1.5, 3.88), // base=1.94
                        SystemParametersBuilder::setWindTurbineFailureRatePerYear
                ));

        m.put(TunableParamId.DG_FAILURE_RATE,
                new TunableParameter(
                        TunableParamId.DG_FAILURE_RATE,
                        "DG_FAILURE_RATE",
                        minFromBase(ModelDefaults.DEFAULT_DG_FAILURE_RATE_PER_YEAR, 0.5, 2.375),
                        maxFromBase(ModelDefaults.DEFAULT_DG_FAILURE_RATE_PER_YEAR, 1.5, 9.5), // base=4.75
                        SystemParametersBuilder::setDieselGeneratorFailureRatePerYear
                ));

        m.put(TunableParamId.BT_FAILURE_RATE,
                new TunableParameter(
                        TunableParamId.BT_FAILURE_RATE,
                        "BT_FAILURE_RATE",
                        minFromBase(ModelDefaults.DEFAULT_BT_FAILURE_RATE_PER_YEAR, 0.5, 0.2875),
                        maxFromBase(ModelDefaults.DEFAULT_BT_FAILURE_RATE_PER_YEAR, 1.5, 1.15), // base=0.575
                        SystemParametersBuilder::setBatteryFailureRatePerYear
                ));

        m.put(TunableParamId.BUS_FAILURE_RATE,
                new TunableParameter(
                        TunableParamId.BUS_FAILURE_RATE,
                        "BUS_FAILURE_RATE",
                        minFromBase(ModelDefaults.DEFAULT_BUS_FAILURE_RATE_PER_YEAR, 0.5, 0.008),
                        maxFromBase(ModelDefaults.DEFAULT_BUS_FAILURE_RATE_PER_YEAR, 1.5, 0.032), // base=0.016
                        SystemParametersBuilder::setBusFailureRatePerYear
                ));

        m.put(TunableParamId.BRK_FAILURE_RATE,
                new TunableParameter(
                        TunableParamId.BRK_FAILURE_RATE,
                        "BRK_FAILURE_RATE",
                        minFromBase(ModelDefaults.DEFAULT_BRK_FAILURE_RATE_PER_YEAR, 0.5, 0.025),
                        maxFromBase(ModelDefaults.DEFAULT_BRK_FAILURE_RATE_PER_YEAR, 1.5, 0.1), // base=0.05
                        SystemParametersBuilder::setBreakerFailureRatePerYear
                ));

        // ----- Времена ремонта -----
        m.put(TunableParamId.WT_REPAIR_TIME,
                new TunableParameter(
                        TunableParamId.WT_REPAIR_TIME,
                        "WT_REPAIR_TIME",
                        minFromBase(ModelDefaults.DEFAULT_WT_REPAIR_TIME_HOURS, 0.5, 25),
                        maxFromBase(ModelDefaults.DEFAULT_WT_REPAIR_TIME_HOURS, 1.5, 100),
                        (b, v) -> b.setWindTurbineRepairTimeHours((int) Math.round(v))
                ));

        m.put(TunableParamId.DG_REPAIR_TIME,
                new TunableParameter(
                        TunableParamId.DG_REPAIR_TIME,
                        "DG_REPAIR_TIME",
                        minFromBase(ModelDefaults.DEFAULT_DG_REPAIR_TIME_HOURS, 0.5, 25),
                        maxFromBase(ModelDefaults.DEFAULT_DG_REPAIR_TIME_HOURS, 1.5, 100),
                        (b, v) -> b.setDieselGeneratorRepairTimeHours((int) Math.round(v))
                ));

        m.put(TunableParamId.BT_REPAIR_TIME,
                new TunableParameter(
                        TunableParamId.BT_REPAIR_TIME,
                        "BT_REPAIR_TIME",
                        minFromBase(ModelDefaults.DEFAULT_BT_REPAIR_TIME_HOURS, 0.5, 25),
                        maxFromBase(ModelDefaults.DEFAULT_BT_REPAIR_TIME_HOURS, 1.5, 100),
                        (b, v) -> b.setBatteryRepairTimeHours((int) Math.round(v))
                ));

        m.put(TunableParamId.BUS_REPAIR_TIME,
                new TunableParameter(
                        TunableParamId.BUS_REPAIR_TIME,
                        "BUS_REPAIR_TIME",
                        minFromBase(ModelDefaults.DEFAULT_BUS_REPAIR_TIME_HOURS, 0.5, 5),
                        maxFromBase(ModelDefaults.DEFAULT_BUS_REPAIR_TIME_HOURS, 1.5, 20),
                        (b, v) -> b.setSwitchgearRoomRepairTimeHours((int) Math.round(v))
                ));

        m.put(TunableParamId.ROOM_REPAIR_TIME,
                new TunableParameter(
                        TunableParamId.ROOM_REPAIR_TIME,
                        "ROOM_REPAIR_TIME",
                        minFromBase(ModelDefaults.DEFAULT_SWITCHGEAR_ROOM_REPAIR_TIME_HOURS, 0.5, 12),
                        maxFromBase(ModelDefaults.DEFAULT_SWITCHGEAR_ROOM_REPAIR_TIME_HOURS, 1.5, 48),
                        (b, v) -> b.setSwitchgearRoomRepairTimeHours((int) Math.round(v))
                ));

        m.put(TunableParamId.BRK_REPAIR_TIME,
                new TunableParameter(
                        TunableParamId.BRK_REPAIR_TIME,
                        "BRK_REPAIR_TIME",
                        minFromBase(ModelDefaults.DEFAULT_BRK_REPAIR_TIME_HOURS, 0.5, 5),
                        maxFromBase(ModelDefaults.DEFAULT_BRK_REPAIR_TIME_HOURS, 1.5, 20),
                        (b, v) -> b.setBreakerRepairTimeHours((int) Math.round(v))
                ));

        // ----- Параметры ВЭУ -----
        m.put(TunableParamId.WT_COUNT,
                new TunableParameter(
                        TunableParamId.WT_COUNT,
                        "WT_COUNT",
                        minFromBase(ModelDefaults.DEFAULT_WT_COUNT_TOTAL, 0.5, 2),
                        maxFromBase(ModelDefaults.DEFAULT_WT_COUNT_TOTAL, 2, 8),
                        (b, v) -> b.setTotalWindTurbineCount((int) Math.round(v))
                ));

        m.put(TunableParamId.WT_POWER,
                new TunableParameter(
                        TunableParamId.WT_POWER,
                        "WT_POWER",
                        minFromBase(ModelDefaults.DEFAULT_WT_POWER_KW, 1, 1250),
                        maxFromBase(ModelDefaults.DEFAULT_WT_POWER_KW, 3, 3750),
                        SystemParametersBuilder::setWindTurbinePowerKw
                ));

        // ----- Параметры ДГУ -----
        m.put(TunableParamId.DG_COUNT,
                new TunableParameter(
                        TunableParamId.DG_COUNT,
                        "DG_COUNT",
                        minFromBase(ModelDefaults.DEFAULT_DG_COUNT_TOTAL, 0.666666666667, 4),
                        maxFromBase(ModelDefaults.DEFAULT_DG_COUNT_TOTAL, 1.33333333333, 8),
                        (b, v) -> b.setTotalDieselGeneratorCount((int) Math.round(v))
                ));

        m.put(TunableParamId.DG_POWER,
                new TunableParameter(
                        TunableParamId.DG_POWER,
                        "DG_POWER",
                        minFromBase(ModelDefaults.DEFAULT_DG_POWER_KW, 0.672, 420),
                        maxFromBase(ModelDefaults.DEFAULT_DG_POWER_KW, 1.6, 1000),
                        SystemParametersBuilder::setDieselGeneratorPowerKw
                ));

        // ----- АКБ -----
        m.put(TunableParamId.BT_CAPACITY_PER_BUS,
                new TunableParameter(
                        TunableParamId.BT_CAPACITY_PER_BUS,
                        "BT_CAPACITY_PER_BUS",
                        minFromBase(ModelDefaults.DEFAULT_BT_CAPACITY_KWH_PER_BUS, 0.5, 450),
                        maxFromBase(ModelDefaults.DEFAULT_BT_CAPACITY_KWH_PER_BUS, 1.5, 1250),
                        SystemParametersBuilder::setBatteryCapacityKwhPerBus
                ));
        m.put(TunableParamId.BT_MAX_CHARGE_CURRENT,
                new TunableParameter(
                        TunableParamId.BT_MAX_CHARGE_CURRENT,
                        "BT_MAX_CHARGE_CURRENT",
                        minFromBase(ModelDefaults.DEFAULT_BT_MAX_CHARGE_CURRENT, 1, 0.6),
                        maxFromBase(ModelDefaults.DEFAULT_BT_MAX_CHARGE_CURRENT, 1.66666666667, 1.0),
                        SystemParametersBuilder::setMaxChargeCurrent
                ));
        m.put(TunableParamId.BT_MAX_DISCHARGE_CURRENT,
                new TunableParameter(
                        TunableParamId.BT_MAX_DISCHARGE_CURRENT,
                        "BT_MAX_DISCHARGE_CURRENT",
                        minFromBase(ModelDefaults.DEFAULT_BT_MAX_DISCHARGE_CURRENT, 0.5, 1.0),
                        maxFromBase(ModelDefaults.DEFAULT_BT_MAX_DISCHARGE_CURRENT, 1.5, 3.0),
                        SystemParametersBuilder::setMaxDischargeCurrent
                ));
        m.put(TunableParamId.BT_NON_RESERVE_DISCHARGE_LVL,
                new TunableParameter(
                        TunableParamId.BT_NON_RESERVE_DISCHARGE_LVL,
                        "BT_NON_RESERVE_DISCHARGE_LVL",
                        clamp(minFromBase(ModelDefaults.DEFAULT_BT_NON_RESERVE_DISCHARGE_LEVEL, 0, 0.0), 0.0, 0.8),
                        clamp(maxFromBase(ModelDefaults.DEFAULT_BT_NON_RESERVE_DISCHARGE_LEVEL, 2, 0.8), 0.0, 0.8),
                        SystemParametersBuilder::setNonReserveDischargeLevel
                ));

        // ----- Economics / prices -----

        m.put(TunableParamId.DISCOUNT_RATE,
                new TunableParameter(
                        TunableParamId.DISCOUNT_RATE,
                        "DISCOUNT_RATE",
                        minFromBase(ModelDefaults.DEFAULT_DISCOUNT_RATE, 0.5, 0.04),
                        maxFromBase(ModelDefaults.DEFAULT_DISCOUNT_RATE, 2, 0.16),
                        SystemParametersBuilder::setDiscountRatePerYear
                ));

        m.put(TunableParamId.COST_RU_RUB,
                new TunableParameter(
                        TunableParamId.COST_RU_RUB,
                        "COST_RU_RUB",
                        minFromBase(ModelDefaults.DEFAULT_COST_RU_RUB, 0.5, 2000000),
                        maxFromBase(ModelDefaults.DEFAULT_COST_RU_RUB, 2, 8000000),
                        SystemParametersBuilder::setCostRuRub
                ));

        m.put(TunableParamId.COST_DG_RUB_PER_KW,
                new TunableParameter(
                        TunableParamId.COST_DG_RUB_PER_KW,
                        "COST_DG_RUB_PER_KW",
                        minFromBase(ModelDefaults.DEFAULT_COST_DG_RUB_PER_KW, 0.5, 20000),
                        maxFromBase(ModelDefaults.DEFAULT_COST_DG_RUB_PER_KW, 1.5, 60000),
                        SystemParametersBuilder::setCostDgRubPerKw
                ));

        m.put(TunableParamId.COST_DG_RUB_PER_KW_PER_KMH,
                new TunableParameter(
                        TunableParamId.COST_DG_RUB_PER_KW_PER_KMH,
                        "COST_DG_RUB_PER_KW_PER_KMH",
                        minFromBase(ModelDefaults.DEFAULT_COST_DG_RUB_PER_KW_PER_KMH, 0.5, 800),
                        maxFromBase(ModelDefaults.DEFAULT_COST_DG_RUB_PER_KW_PER_KMH, 1.5, 2400),
                        SystemParametersBuilder::setCostDgRubPerKwPerKmh
                ));

        m.put(TunableParamId.COST_FUEL_RUB_PER_KT,
                new TunableParameter(
                        TunableParamId.COST_FUEL_RUB_PER_KT,
                        "COST_FUEL_RUB_PER_KT",
                        minFromBase(ModelDefaults.DEFAULT_COST_FUEL_RUB_PER_KT, 0.5, 45000000),
                        maxFromBase(ModelDefaults.DEFAULT_COST_FUEL_RUB_PER_KT, 1.5, 135000000),
                        SystemParametersBuilder::setCostFuelRubPerKt
                ));

        m.put(TunableParamId.COST_WT_RUB_PER_KW,
                new TunableParameter(
                        TunableParamId.COST_WT_RUB_PER_KW,
                        "COST_WT_RUB_PER_KW",
                        minFromBase(ModelDefaults.DEFAULT_COST_WT_RUB_PER_KW, 0.5, 75000),
                        maxFromBase(ModelDefaults.DEFAULT_COST_WT_RUB_PER_KW, 1.5, 225000),
                        SystemParametersBuilder::setCostWtRubPerKw
                ));

        m.put(TunableParamId.COST_WT_RUB_PER_KW_PER_YEAR,
                new TunableParameter(
                        TunableParamId.COST_WT_RUB_PER_KW_PER_YEAR,
                        "COST_WT_RUB_PER_KW_PER_YEAR",
                        minFromBase(ModelDefaults.DEFAULT_COST_WT_RUB_PER_KW_PER_YEAR, 0.5, 1500),
                        maxFromBase(ModelDefaults.DEFAULT_COST_WT_RUB_PER_KW_PER_YEAR, 1.5, 4500),
                        SystemParametersBuilder::setCostWtRubPerKwPerYear
                ));

        m.put(TunableParamId.COST_BT_RUB_PER_KWH,
                new TunableParameter(
                        TunableParamId.COST_BT_RUB_PER_KWH,
                        "COST_BT_RUB_PER_KWH",
                        minFromBase(ModelDefaults.DEFAULT_COST_BT_RUB_PER_KWH, 0.5, 44000),
                        maxFromBase(ModelDefaults.DEFAULT_COST_BT_RUB_PER_KWH, 1.5, 132000),
                        SystemParametersBuilder::setCostBtRubPerKwh
                ));

        m.put(TunableParamId.COST_BT_RUB_PER_KWH_PER_YEAR,
                new TunableParameter(
                        TunableParamId.COST_BT_RUB_PER_KWH_PER_YEAR,
                        "COST_BT_RUB_PER_KWH_PER_YEAR",
                        minFromBase(ModelDefaults.DEFAULT_COST_BT_RUB_PER_KWH_PER_YEAR, 0.5, 1100),
                        maxFromBase(ModelDefaults.DEFAULT_COST_BT_RUB_PER_KWH_PER_YEAR, 1.5, 3300),
                        SystemParametersBuilder::setCostBtRubPerKwhPerYear
                ));

        m.put(TunableParamId.DAMAGE_RUB_PER_KWH_CAT1,
                new TunableParameter(
                        TunableParamId.DAMAGE_RUB_PER_KWH_CAT1,
                        "DAMAGE_RUB_PER_KWH_CAT1",
                        minFromBase(ModelDefaults.DEFAULT_DAMAGE_RUB_PER_KWH_CAT1, 0.5, 3500),
                        maxFromBase(ModelDefaults.DEFAULT_DAMAGE_RUB_PER_KWH_CAT1, 2, 14000),
                        SystemParametersBuilder::setDamageRubPerKwhCat1
                ));

        m.put(TunableParamId.DAMAGE_RUB_PER_KWH_CAT2,
                new TunableParameter(
                        TunableParamId.DAMAGE_RUB_PER_KWH_CAT2,
                        "DAMAGE_RUB_PER_KWH_CAT2",
                        minFromBase(ModelDefaults.DEFAULT_DAMAGE_RUB_PER_KWH_CAT2, 0.3, 1050),
                        maxFromBase(ModelDefaults.DEFAULT_DAMAGE_RUB_PER_KWH_CAT2, 1.2, 4200),
                        SystemParametersBuilder::setDamageRubPerKwhCat2
                ));

        m.put(TunableParamId.DAMAGE_RUB_PER_KWH_CAT3,
                new TunableParameter(
                        TunableParamId.DAMAGE_RUB_PER_KWH_CAT3,
                        "DAMAGE_RUB_PER_KWH_CAT3",
                        minFromBase(ModelDefaults.DEFAULT_DAMAGE_RUB_PER_KWH_CAT3, 0.5, 350),
                        maxFromBase(ModelDefaults.DEFAULT_DAMAGE_RUB_PER_KWH_CAT3, 1.5, 1050),
                        SystemParametersBuilder::setDamageRubPerKwhCat3
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

    private static double minFromBase(double base, double kMin, double absMin) {
        return (Math.abs(base) > 0.0) ? (base * kMin) : absMin;
    }

    private static double maxFromBase(double base, double kMax, double absMax) {
        return (Math.abs(base) > 0.0) ? (base * kMax) : absMax;
    }

    private static double clamp(double x, double lo, double hi) {
        if (x < lo) return lo;
        return Math.min(x, hi);
    }

    private static double clamp01(double x) {
        if (x < 0.0) return 0.0;
        return Math.min(x, 1.0);
    }

}
