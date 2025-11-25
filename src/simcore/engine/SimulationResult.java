package simcore.engine;

/**
 * Результат одного прогона симуляции.
 */
public final class SimulationResult {

    private final double totalDeficit;

    public SimulationResult(double totalDeficit) {
        this.totalDeficit = totalDeficit;
    }

    public double getTotalDeficit() {
        return totalDeficit;
    }
}
