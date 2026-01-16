// File: simcore/config/SimulationConstants.java
package simcore.config;

/**
 * Глобальные константы симуляции.
 * Все параметры оборудования и физические коэффициенты должны находиться здесь.
 */
public final class SimulationConstants {

    /** Погрешность вычислений */
    public static final double EPSILON = 1e-6;

    /** Количество точек данных (например, 20 лет почасовых данных) */
    public static final int DATA_SIZE = 175_320;

    /** Максимальное значение часовой нагрузки */
    public static final double MAX_LOAD = 1346;

    public static final double DG_IDLE_K2 = 1.0;        // 0..1: резервировать ли Cat2 (1.0 = да, 0.0 = только Cat1)
    public static final double DG_IDLE_MARGIN_PCT = 0.0; // запас на резерв, например 10%

    // =========================================================================
    // ===========================    ВЕТРЯК  ==================================
    // =========================================================================

    public static final double WIND_REFERENCE_HEIGHT_M = 50.0;
    public static final double Z_FACTOR = 0.03;
    public static final double MAST_HEIGHT_M = 35.0;

    // =========================================================================
    // ===========================   АККУМУЛЯТОР (Battery)  ====================
    // =========================================================================

    /** Начальный уровень заряда АКБ (0..1) */
    public static final double BATTERY_START_SOC = 1.0;

    /** Минимальный уровень заряда */
    public static final double BATTERY_MIN_SOC = 0.20;

    /** Максимальный уровень заряда */
    public static final double BATTERY_MAX_SOC = 1.00;

    /** КПД заряда/разряда */
    public static final double BATTERY_EFFICIENCY = 0.93;

    public static final double BATTERY_SELF_DISCHARGE_PER_HOUR = 0.03 / 720.0;

    public static final double BATTERY_DEGRADATION_THRESHOLD = 0.80;

    /**
     * Ослабление влияния C-rate в режиме "короткого мостика" (0..1).
     * 0.5 означает "в 2 раза слабее токовый штраф".
     */
    public static final double BATTERY_BRIDGE_CRATE_RELIEF = 1;

    /**
     * Календарная деградация (опционально), доля от nominalCapacityKwh в год.
     */
    public static final double BATTERY_CALENDAR_LOSS_PER_YEAR = 0.0025;

    // ===== Throughput power-law degradation =====
    public static final double BATTERY_DEG_Z = 0.6;          // степень (типично 0.5..0.7)
    public static final double BATTERY_DEG_H = 0.37;          // чувствительность к C-rate (калибруется)

    // Калибровка "20% потери на 2000 EFC при базовых условиях (C≈0, DoD≈DOD_REF)"
    public static final double BATTERY_DEG_K =
            0.20 / Math.pow(2000.0, BATTERY_DEG_Z);

    // =========================================================================
    // ===========================    ДИЗЕЛЬ  ==================================
    // =========================================================================

    public static final double DG_MIN_POWER = 0.3;
    public static final double DG_MAX_POWER = 1;
    public static final double DG_OPTIMAL_POWER = 0.8;
    public static final double DG_START_DELAY_HOURS = 0.1;
    public static final int DG_MAX_IDLE_HOURS = 4;
    public static final int DG_MAX_START_FACTOR = 5;

    private SimulationConstants() {}
}
