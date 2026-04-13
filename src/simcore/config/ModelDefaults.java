package simcore.config;

public final class ModelDefaults {
    private ModelDefaults() {
    }

    // Categories share (k1, k2); k3 implied = 1 - k1 - k2
    public static final double DEFAULT_FIRST_CAT = 0.1; //0,25
    public static final double DEFAULT_SECOND_CAT = 0.4; //0,25
    // DG
    public static final int DEFAULT_DG_COUNT_TOTAL = 8; //8
    public static final double DEFAULT_DG_POWER_KW = 200;// 340
    // WT
    public static final int DEFAULT_WT_COUNT_TOTAL = 2; //2
    public static final double DEFAULT_WT_POWER_KW = 1346 / 2 * 1.5;
    // Battery
    public static final double DEFAULT_BT_CAPACITY_KWH_PER_BUS = (double) 1346 / 2 * 1;
    public static final double DEFAULT_BT_NON_RESERVE_DISCHARGE_LEVEL = 1;

    public static final double DEFAULT_BT_MAX_CHARGE_CURRENT = 1;
    public static final double DEFAULT_BT_MAX_DISCHARGE_CURRENT = 1.0; //2

    public static final boolean DEFAULT_BT_USE_ADAPTIVE_NON_RESERVE_DISCHARGE_LEVEL = false;
    public static final double DEFAULT_BT_ADAPTIVE_DEFICIT_WEIGHT = 0.0053; // wE
    public static final double DEFAULT_BT_ADAPTIVE_TREND_WEIGHT = 0.4607; // wT
    public static final double DEFAULT_BT_ADAPTIVE_ACCELERATION_WEIGHT = 1.4112; // wA
    public static final double DEFAULT_BT_ADAPTIVE_NO_DG_PREV_HOUR_WEIGHT = 0.9095; // wH 0.0427
    public static final double DEFAULT_BT_ADAPTIVE_DG_AVAILABILITY_WEIGHT = 0.0109; // wD
    public static final double DEFAULT_BT_ADAPTIVE_REPLACEMENT_WEIGHT = 0.6874; // wR 0.8166

    // Reliability (rates are double, repair times are int)
    public static final double DEFAULT_WT_FAILURE_RATE_PER_YEAR = 1.94;
    public static final int DEFAULT_WT_REPAIR_TIME_HOURS = 46;
    public static final double DEFAULT_DG_FAILURE_RATE_PER_YEAR = 4.75;
    public static final int DEFAULT_DG_REPAIR_TIME_HOURS = 50;
    public static final double DEFAULT_BT_FAILURE_RATE_PER_YEAR = 0.575;//0,575
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
    public static final double DEFAULT_DISCOUNT_RATE = 0.08; //0.1
    public static final double DEFAULT_COST_RU_RUB = 5_000_000;
    public static final double DEFAULT_COST_DG_RUB_PER_KW = 60_000;
    public static final double DEFAULT_COST_DG_RUB_PER_KW_PER_KMH = 5_000;
    public static final double DEFAULT_COST_FUEL_RUB_PER_KT = 90_000_000.0;
    public static final double DEFAULT_COST_WT_RUB_PER_KW = 200_000;
    public static final double DEFAULT_COST_WT_RUB_PER_KW_PER_YEAR = 4_000;
    public static final double DEFAULT_COST_BT_RUB_PER_KWH = 45_000.0;
    public static final double DEFAULT_COST_BT_RUB_PER_KWH_PER_YEAR = 1_125.0;
    public static final double DEFAULT_DAMAGE_RUB_PER_KWH_CAT3 = 200.0;
    public static final double DEFAULT_DAMAGE_RUB_PER_KWH_CAT2 = DEFAULT_DAMAGE_RUB_PER_KWH_CAT3 * 5;
    public static final double DEFAULT_DAMAGE_RUB_PER_KWH_CAT1 = DEFAULT_DAMAGE_RUB_PER_KWH_CAT3 * 10;

    public static final boolean CFG_CONSIDER_FAILURES = true;
    public static final boolean CFG_CONSIDER_MAINTENANCE = true;
    public static final boolean CFG_CONSIDER_HOT_RESERVE = true;
    public static final boolean CFG_CONSIDER_BATTERY_DEGRADATION = true;
    public static final boolean CFG_RESERVE_THIRD_CATEGORY = true;
    public static final boolean CFG_CONSIDER_ROTATION_RESERVE = true;

    public static final double DEFAULT_BT_GRID_FORMING_RESERVE_SHARE = 1;
    public static final boolean CFG_USE_AVG_LOAD_RESERVE_POLICY = false;
    public static final double CFG_IDLE_RESERVE_COEFF = 1;
    public static final double CFG_ROTATION_RESERVE_COEFF = 1.7;
    public static final boolean CFG_KEEP_ONE_DG_INSTANT_START_READY_AFTER_WT_BESS_GRID_FORMING = false;
}
