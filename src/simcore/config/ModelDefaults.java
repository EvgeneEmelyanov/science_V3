package simcore.config;

public final class ModelDefaults {
    private ModelDefaults() {}

    // Categories share (k1, k2); k3 implied = 1 - k1 - k2
    public static final double DEFAULT_FIRST_CAT = 0.5;
    public static final double DEFAULT_SECOND_CAT = 0.0;

    // WT
    public static final int DEFAULT_WT_COUNT_TOTAL = 4;
    public static final double DEFAULT_WT_POWER_KW = 673;

    // DG
    public static final int DEFAULT_DG_COUNT_TOTAL = 6;
    public static final double DEFAULT_DG_POWER_KW = 336.5;

    // Battery
    public static final double DEFAULT_BT_CAPACITY_KWH_PER_BUS = 336.5;
    public static final double DEFAULT_BT_MAX_CHARGE_CURRENT = 0.6;
    public static final double DEFAULT_BT_MAX_DISCHARGE_CURRENT = 2.0;
    public static final double DEFAULT_BT_NON_RESERVE_DISCHARGE_LEVEL = 0.4;

    // Reliability (rates are double, repair times are int)
    public static final double DEFAULT_WT_FAILURE_RATE_PER_YEAR = 1.94;
    public static final int DEFAULT_WT_REPAIR_TIME_HOURS = 46;

    public static final double DEFAULT_DG_FAILURE_RATE_PER_YEAR = 4.75;
    public static final int DEFAULT_DG_REPAIR_TIME_HOURS = 50;

    public static final double DEFAULT_BT_FAILURE_RATE_PER_YEAR = 0.575;
    public static final int DEFAULT_BT_REPAIR_TIME_HOURS = 44;

    public static final double DEFAULT_BUS_FAILURE_RATE_PER_YEAR = 0.02;
    public static final int DEFAULT_BUS_REPAIR_TIME_HOURS = 12;

    public static final double DEFAULT_BRK_FAILURE_RATE_PER_YEAR = 0.05;
    public static final int DEFAULT_BRK_REPAIR_TIME_HOURS = 10;

    public static final double DEFAULT_SWITCHGEAR_ROOM_FAILURE_RATE_PER_YEAR = 0.0;
    public static final int DEFAULT_SWITCHGEAR_ROOM_REPAIR_TIME_HOURS = 24;
    public static final double DEFAULT_BUS_CCF_BETA_SECTIONAL = 0.0;
    public static final double DEFAULT_BUS_CCF_BETA_DOUBLE = 0.0;

    // ---- Economics defaults ----
    public static final double DEFAULT_DISCOUNT_RATE = 0.1;
    public static final double DEFAULT_COST_RU_RUB = 4_000_000;
    public static final double DEFAULT_COST_DG_RUB_PER_KW = 40_000;
    public static final double DEFAULT_COST_DG_RUB_PER_KW_PER_KMH = 1600.0;
    public static final double DEFAULT_COST_FUEL_RUB_PER_KT = 90_000_000.0;
    public static final double DEFAULT_COST_WT_RUB_PER_KW = 150_000;
    public static final double DEFAULT_COST_WT_RUB_PER_KW_PER_YEAR = 3_000;
    public static final double DEFAULT_COST_BT_RUB_PER_KWH = 88_000.0;
    public static final double DEFAULT_COST_BT_RUB_PER_KWH_PER_YEAR = 2_200.0;

    public static final double DEFAULT_DAMAGE_RUB_PER_KWH_CAT3 = 700.0;
    public static final double DEFAULT_DAMAGE_RUB_PER_KWH_CAT2 = DEFAULT_DAMAGE_RUB_PER_KWH_CAT3 * 5;
    public static final double DEFAULT_DAMAGE_RUB_PER_KWH_CAT1 = DEFAULT_DAMAGE_RUB_PER_KWH_CAT3 * 7;


    public static final boolean CFG_CONSIDER_FAILURES = true;
    public static final boolean CFG_CONSIDER_MAINTENANCE = true;
    public static final boolean CFG_CONSIDER_HOT_RESERVE = true;
    public static final boolean CFG_CONSIDER_BATTERY_DEGRADATION = true;

    public static final boolean CFG_RESERVE_THIRD_CATEGORY = false;
    public static final boolean CFG_CONSIDER_ROTATION_RESERVE = true;
}
