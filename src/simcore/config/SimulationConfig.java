package simcore.config;

/**
 * Конфигурация запуска Monte Carlo-симуляции.
 */
public class SimulationConfig {

    /** Временной ряд скорости ветра, м/с. */
    private final double[] windMs;

    /** Количество итераций Monte Carlo. */
    private final int iterations;

    /** Количество потоков для параллельного запуска. */
    private final int threads;

    /** Учитывать ли отказы оборудования. */
    private final boolean considerFailures;

    /** Выводить ли ДГУ в ТО */
    private final boolean considerMaintenance;

    /** Резервировать ли нагрузку III категории (учитывать ли её в ХХ/вращающемся резерве). */
    private final boolean reserveThirdCategory;

    /** Учитывать расход энергии на горячий резерв */
    private final boolean considerHotReserve;

    /** Включать ли деградацию АКБ */
    private final boolean considerBatteryDegradation;

    /** Работа ДЭС во вращающемся резерве */
    private final boolean considerRotationReserve;

    /**
     * Считать ли экономические драйверы по годам (served/fuel/moto/btRepl/ENS by cat)
     * и возвращать их в {@code SimulationMetrics.economyDrivers}.
     *
     * Если {@code false} — драйверы по годам не накапливаются (экономия памяти), но LCOE всё равно
     * считается в ходе симуляции потоковым способом (без массивов по годам).
     */
    private final boolean computeEconomyDrivers;

    public SimulationConfig(double[] windMs,
                            int iterations,
                            int threads,
                            boolean considerFailures,
                            boolean considerMaintenance,
                            boolean reserveThirdCategory,
                            boolean considerHotReserve,
                            boolean considerBatteryDegradation,
                            boolean considerRotationReserve,
                            boolean computeEconomyDrivers) {
        this.windMs = windMs;
        this.iterations = iterations;
        this.threads = threads;
        this.considerFailures = considerFailures;
        this.considerMaintenance = considerMaintenance;
        this.reserveThirdCategory = reserveThirdCategory;
        this.considerHotReserve = considerHotReserve;
        this.considerBatteryDegradation = considerBatteryDegradation;
        this.considerRotationReserve = considerRotationReserve;
        this.computeEconomyDrivers = computeEconomyDrivers;
    }

    public double[] getWindMs() {
        return windMs;
    }

    public int getIterations() {
        return iterations;
    }

    public boolean isConsiderFailures() {
        return considerFailures;
    }

    public boolean isConsiderMaintenance() {
        return considerMaintenance;
    }

    public boolean isReserveThirdCategory() {
        return reserveThirdCategory;
    }

    public boolean isConsiderHotReserve() {
        return considerHotReserve;
    }

    public boolean isConsiderBatteryDegradation() {
        return considerBatteryDegradation;
    }

    public boolean isConsiderRotationReserve() {
        return considerRotationReserve;
    }

    public boolean isComputeEconomyDrivers() {
        return computeEconomyDrivers;
    }
}
