package simcore.config;

/**
 * Глобальные константы симуляции, не меняющиеся от сценария к сценарию.
 */
public final class SimulationConstants {

    /** Количество точек во временных рядах нагрузки и ветра. */
    public static final int DATA_SIZE = 175_320;

    /** Эталонная высота измерения скорости ветра (м). */
    public static final double WIND_REFERENCE_HEIGHT_M = 50.0;

    /** Коэффициент шероховатости местности (z0) для логарифмического профиля. */
    public static final double Z_FACTOR = 0.03;

    /** Максимальная нагрузка системы (кВт), используется для пересчёта значений из о.е. */
    public static final double MAX_LOAD_KW = 1346.0;

    /** Высота мачты ВЭУ (м), для пересчёта скорости ветра. */
    public static final double MAST_HEIGHT_M = 50.0;

    private SimulationConstants() {}
}
