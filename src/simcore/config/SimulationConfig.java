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

    public SimulationConfig(double[] windMs,
                            int iterations,
                            int threads,
                            boolean considerFailures) {
        this.windMs = windMs;
        this.iterations = iterations;
        this.threads = threads;
        this.considerFailures = considerFailures;
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
}
