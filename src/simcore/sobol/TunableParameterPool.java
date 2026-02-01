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

    /**
     * Optional coupled constraint between DG_COUNT and DG_POWER:
     * total installed DG power must be >= this threshold.
     *
     * If 0 -> disabled.
     */
    private static volatile double MIN_TOTAL_DG_POWER_KW = 1346;

    // Precomputed bounds (also used by coupled constraints)
    private static final double DG_COUNT_MIN = minFromBase(ModelDefaults.DEFAULT_DG_COUNT_TOTAL, 0.666666666667, 4);
    private static final double DG_COUNT_MAX = maxFromBase(ModelDefaults.DEFAULT_DG_COUNT_TOTAL, 1.33333333333, 8);
    private static final double DG_POWER_MIN = minFromBase(ModelDefaults.DEFAULT_DG_POWER_KW, 0.5, 168.25);
    private static final double DG_POWER_MAX = maxFromBase(ModelDefaults.DEFAULT_DG_POWER_KW, 1.5, 505);

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
                        minFromBase(ModelDefaults.DEFAULT_WT_POWER_KW, 0.5, 336.5),
                        maxFromBase(ModelDefaults.DEFAULT_WT_POWER_KW, 1.5, 1009.5),
                        SystemParametersBuilder::setWindTurbinePowerKw
                ));

        // ----- Параметры ДГУ -----
        m.put(TunableParamId.DG_COUNT,
                new TunableParameter(
                        TunableParamId.DG_COUNT,
                        "DG_COUNT",
                        DG_COUNT_MIN,
                        DG_COUNT_MAX,
                        (b, v) -> {
                            int n = (int) Math.round(clamp(v, DG_COUNT_MIN, DG_COUNT_MAX));
                            b.setTotalDieselGeneratorCount(n);
                            enforceMinTotalDgPower(b, true);
                        }
                ));

        m.put(TunableParamId.DG_POWER,
                new TunableParameter(
                        TunableParamId.DG_POWER,
                        "DG_POWER",
                        DG_POWER_MIN,
                        DG_POWER_MAX,
                        (b, v) -> {
                            double p = clamp(v, DG_POWER_MIN, DG_POWER_MAX);
                            b.setDieselGeneratorPowerKw(p);
                            enforceMinTotalDgPower(b, false);
                        }
                ));

        // ----- АКБ -----
        m.put(TunableParamId.BT_CAPACITY_PER_BUS,
                new TunableParameter(
                        TunableParamId.BT_CAPACITY_PER_BUS,
                        "BT_CAPACITY_PER_BUS",
                        minFromBase(ModelDefaults.DEFAULT_BT_CAPACITY_KWH_PER_BUS, 0.5, 0),
                        maxFromBase(ModelDefaults.DEFAULT_BT_CAPACITY_KWH_PER_BUS, 2, 673),
                        SystemParametersBuilder::setBatteryCapacityKwhPerBus
                ));
        m.put(TunableParamId.BT_MAX_CHARGE_CURRENT,
                new TunableParameter(
                        TunableParamId.BT_MAX_CHARGE_CURRENT,
                        "BT_MAX_CHARGE_CURRENT",
                        minFromBase(ModelDefaults.DEFAULT_BT_MAX_CHARGE_CURRENT, 0.5, 0.3),
                        maxFromBase(ModelDefaults.DEFAULT_BT_MAX_CHARGE_CURRENT, 1.7, 1.0),
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
                        minFromBase(ModelDefaults.DEFAULT_BT_NON_RESERVE_DISCHARGE_LEVEL, 0, 0.0),
                        maxFromBase(ModelDefaults.DEFAULT_BT_NON_RESERVE_DISCHARGE_LEVEL, 2, 0.8),
                        SystemParametersBuilder::setNonReserveDischargeLevel
                ));

        // ----- Economics / prices -----

        m.put(TunableParamId.DISCOUNT_RATE,
                new TunableParameter(
                        TunableParamId.DISCOUNT_RATE,
                        "DISCOUNT_RATE",
                        minFromBase(ModelDefaults.DEFAULT_DISCOUNT_RATE, 0.7, 0.07),
                        maxFromBase(ModelDefaults.DEFAULT_DISCOUNT_RATE, 1.5, 0.15),
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

    /**
     * Enable/disable coupled DG constraint: DG_COUNT * DG_POWER >= minTotalDgPowerKw.
     * Pass 0 to disable.
     */
    public static void setMinTotalDgPowerKw(double minTotalDgPowerKw) {
        MIN_TOTAL_DG_POWER_KW = Math.max(0.0, minTotalDgPowerKw);
    }

    private static void enforceMinTotalDgPower(SystemParametersBuilder b, boolean changedCount) {
        double minTotal = MIN_TOTAL_DG_POWER_KW;
        if (minTotal <= 0.0) return;

        int n0 = b.getTotalDieselGeneratorCount();
        double p0 = b.getDieselGeneratorPowerKw();

        int n = Math.max(1, n0);
        double p = p0;
        if (p <= 0.0) p = DG_POWER_MIN;

        if (n * p >= minTotal) {
            if (n != n0) b.setTotalDieselGeneratorCount(n);
            if (p != p0) b.setDieselGeneratorPowerKw(p);
            return;
        }

        if (changedCount) {
            // Prefer keeping sampled count; raise power first.
            double needP = minTotal / n;
            if (needP <= DG_POWER_MAX) {
                b.setDieselGeneratorPowerKw(Math.max(p, needP));
                return;
            }
            // Power maxed out -> increase count.
            b.setDieselGeneratorPowerKw(DG_POWER_MAX);
            int needN = (int) Math.ceil(minTotal / DG_POWER_MAX);
            int newN = (int) Math.round(clamp(needN, DG_COUNT_MIN, DG_COUNT_MAX));
            b.setTotalDieselGeneratorCount(newN);
            if (newN * DG_POWER_MAX < minTotal) {
                throw new IllegalStateException(
                        "Infeasible minTotalDgPowerKw=" + minTotal +
                                " with DG_COUNT<= " + (int) Math.round(DG_COUNT_MAX) +
                                " and DG_POWER<= " + DG_POWER_MAX
                );
            }
        } else {
            // Prefer keeping sampled power; raise count first.
            int needN = (int) Math.ceil(minTotal / p);
            if (needN <= (int) Math.round(DG_COUNT_MAX)) {
                int newN = (int) Math.round(clamp(needN, DG_COUNT_MIN, DG_COUNT_MAX));
                b.setTotalDieselGeneratorCount(Math.max(n, newN));
                return;
            }
            // Count maxed out -> raise power.
            int maxN = (int) Math.round(DG_COUNT_MAX);
            b.setTotalDieselGeneratorCount(maxN);
            double needP = minTotal / maxN;
            if (needP > DG_POWER_MAX) {
                throw new IllegalStateException(
                        "Infeasible minTotalDgPowerKw=" + minTotal +
                                " with DG_COUNT<= " + maxN +
                                " and DG_POWER<= " + DG_POWER_MAX
                );
            }
            b.setDieselGeneratorPowerKw(Math.max(p, needP));
        }
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
