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

    /** Сортировать ДГУ для равномерного износа */
    private final boolean considerSortDiesel;

    /** Учитывать расход энергии на горячий резерв */
    private final boolean considerHotReserve;

    /** Включать ли деградацию АКБ */
    private final boolean considerBatteryDegradation;

    /** Работа ДЭС во вращающемся резерве */
    private final boolean considerRotationReserve;

    public SimulationConfig(double[] windMs,
                            int iterations,
                            int threads,
                            boolean considerFailures,
                            boolean considerMaintenance,
                            boolean considerSortDiesel,
                            boolean considerHotReserve,
                            boolean considerBatteryDegradation,
                            boolean considerRotationReserve) {
        this.windMs = windMs;
        this.iterations = iterations;
        this.threads = threads;
        this.considerFailures = considerFailures;
        this.considerMaintenance = considerMaintenance;
        this.considerSortDiesel = considerSortDiesel;
        this.considerHotReserve = considerHotReserve;
        this.considerBatteryDegradation = considerBatteryDegradation;
        this.considerRotationReserve = considerRotationReserve;
    }

    public double[] getWindMs() {
        return windMs;
    }

    public int getIterations() {
        return iterations;
    }

    public int getThreads() {
        return threads;
    }

    public boolean isConsiderFailures() {
        return considerFailures;
    }

    public boolean isConsiderMaintenance() {
        return considerMaintenance;
    }

    public boolean isConsiderSortDiesel() {
        return considerSortDiesel;
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
}
