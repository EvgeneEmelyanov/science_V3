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

    /**
     * Саморазряд: 3% в месяц => 0.03/720 в долях в час.
     * В твоей постановке это эквивалентно "3/720 % от nominalCapacityKwh в час".
     */
    public static final double BATTERY_SELF_DISCHARGE_PER_HOUR = 0.03 / 720.0;

    /** Порог деградации до "замены" (ёмкость упала до 80%) */
    public static final double BATTERY_DEGRADATION_THRESHOLD = 0.80;

    /**
     * Базовая потеря ёмкости на 1 EFC при "средних" условиях.
     * Калибровка под 20% за ~2000 EFC: 0.20/2000 = 1e-4.
     */
    public static final double BATTERY_CAPACITY_LOSS_PER_EFC = 1.0e-4;

    /**
     * Вес влияния C-rate на циклическую деградацию (мягкий множитель).
     * wC = 1 + BATTERY_CRATE_WEIGHT * min(1, C-rate)
     */
    public static final double BATTERY_CRATE_WEIGHT = 0.30;

    /**
     * Вес влияния DoD шага разряда на циклическую деградацию (мягкий множитель).
     * wDoD = 1 + BATTERY_DOD_WEIGHT * min(1, DoD_step)
     */
    public static final double BATTERY_DOD_WEIGHT = 0.50;

    /**
     * Ослабление влияния C-rate в режиме "короткого мостика" (0..1).
     * 0.5 означает "в 2 раза слабее токовый штраф".
     */
    public static final double BATTERY_BRIDGE_CRATE_RELIEF = 0.50;

    /**
     * Календарная деградация (опционально), доля от nominalCapacityKwh в год.
     */
    public static final double BATTERY_CALENDAR_LOSS_PER_YEAR = 0.0025;

    // ===== Throughput power-law degradation =====
    public static final double BATTERY_DEG_Z = 0.57;          // степень (типично 0.5..0.7)
    public static final double BATTERY_DEG_H = 0.35;          // чувствительность к C-rate (калибруется)
    public static final double BATTERY_DEG_BETA_DOD = 1;    // влияние DoD (мягко), 0..1
    public static final double BATTERY_DOD_REF = 0.80;        // опорная DoD для нормировки

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

    private SimulationConstants() {}
}
