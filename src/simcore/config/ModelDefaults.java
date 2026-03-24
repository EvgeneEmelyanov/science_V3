package simcore.config;

public final class ModelDefaults {
    private ModelDefaults() {
    }

    // Categories share (k1, k2); k3 implied = 1 - k1 - k2
    public static final double DEFAULT_FIRST_CAT = 0.1; //0,25
    public static final double DEFAULT_SECOND_CAT = 0.4; //0,25
    // WT
    public static final int DEFAULT_WT_COUNT_TOTAL = 2; //4
    public static final double DEFAULT_WT_POWER_KW = 673; //673
    // DG
    public static final int DEFAULT_DG_COUNT_TOTAL = 8; //6
    public static final double DEFAULT_DG_POWER_KW = 200;// 420
    // Battery
    public static final double DEFAULT_BT_CAPACITY_KWH_PER_BUS = (double) 1346 / 2 * 0.5;
    public static final double DEFAULT_BT_MAX_CHARGE_CURRENT = 0.6; //0,6
    public static final double DEFAULT_BT_MAX_DISCHARGE_CURRENT = 2.0;
    public static final double DEFAULT_BT_NON_RESERVE_DISCHARGE_LEVEL = 0.6;

    public static final boolean DEFAULT_BT_USE_ADAPTIVE_NON_RESERVE_DISCHARGE_LEVEL = true;
    // Адаптивный non-reserve алгоритм (новая упрощенная модель).
    // Для совместимости используются старые поля SystemParameters, но их семантика следующая:
    // reserve risk weight            -> weight of DG replacement aggressiveness (w_R)
    // deficit risk weight            -> weight of deficit trend (w_T)
    // acceleration risk weight       -> weight of deficit acceleration (w_A)
    // acceleration risk scale kw     -> weight of previous-hour no-DG factor (w_H)
    // reserve/deficit scales, EMA alpha and risk gain в новой модели не используются.
    public static final double DEFAULT_BT_ADAPTIVE_DEFICIT_RISK_WEIGHT = 0.35;       // w_T
    public static final double DEFAULT_BT_ADAPTIVE_ACCELERATION_RISK_WEIGHT = 0.15;  // w_A
    public static final double DEFAULT_BT_ADAPTIVE_ACCELERATION_RISK_SCALE_KW = 0.20; // w_H
    public static final double DEFAULT_BT_ADAPTIVE_RESERVE_RISK_WEIGHT = 0.30;       // w_R

    public static final double DEFAULT_BT_ADAPTIVE_RESERVE_RISK_SCALE_KW = DEFAULT_DG_POWER_KW;
    public static final double DEFAULT_BT_ADAPTIVE_DEFICIT_RISK_SCALE_KW = DEFAULT_DG_POWER_KW;
    public static final double DEFAULT_BT_ADAPTIVE_ACCELERATION_EMA_ALPHA = 1.0;
    public static final double DEFAULT_BT_ADAPTIVE_RISK_GAIN = 1.0;
    public static final double DEFAULT_BT_GRID_FORMING_RESERVE_SHARE = 1;

    // Алиасы с новой семантикой.
    public static final double DEFAULT_BT_ADAPTIVE_REPLACEMENT_WEIGHT = DEFAULT_BT_ADAPTIVE_RESERVE_RISK_WEIGHT;
    public static final double DEFAULT_BT_ADAPTIVE_TREND_WEIGHT = DEFAULT_BT_ADAPTIVE_DEFICIT_RISK_WEIGHT;
    public static final double DEFAULT_BT_ADAPTIVE_ACCELERATION_WEIGHT = DEFAULT_BT_ADAPTIVE_ACCELERATION_RISK_WEIGHT;
    public static final double DEFAULT_BT_ADAPTIVE_NO_DG_PREV_HOUR_WEIGHT = DEFAULT_BT_ADAPTIVE_ACCELERATION_RISK_SCALE_KW;

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
    public static final double DEFAULT_DISCOUNT_RATE = 0.08;
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

    public static final boolean CFG_USE_AVG_LOAD_RESERVE_POLICY = false;
    public static final double CFG_IDLE_RESERVE_COEFF = 1;
    public static final double CFG_ROTATION_RESERVE_COEFF = 1.7;
    public static final boolean CFG_KEEP_ONE_DG_INSTANT_START_READY_AFTER_WT_BESS_GRID_FORMING = false;
}
