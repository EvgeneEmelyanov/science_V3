package simcore.io;

/**
 * Входные временные ряды для симуляции.
 */
public final class InputData {

    private final double[] loadKw;
    private final double[] windMs;

    public InputData(double[] loadKw, double[] windMs) {
        this.loadKw = loadKw;
        this.windMs = windMs;
    }

    public double[] getLoadKw() {
        return loadKw;
    }

    public double[] getWindMs() {
        return windMs;
    }
}
