package simcore.engine;

import simcore.config.SimulationConfig;
import simcore.config.SystemParameters;

/**
 * Единый вход для single-run симуляции.
 * Важно: iterations/threads сюда не кладём — это execution-level.
 */
public final class SimInput {

    private final SimulationConfig config;
    private final SystemParameters systemParameters;
    private final double[] totalLoadKw;

    public SimInput(SimulationConfig config,
                    SystemParameters systemParameters,
                    double[] totalLoadKw) {
        this.config = config;
        this.systemParameters = systemParameters;
        this.totalLoadKw = totalLoadKw;
    }

    public SimulationConfig getConfig() {
        return config;
    }

    public SystemParameters getSystemParameters() {
        return systemParameters;
    }

    public double[] getTotalLoadKw() {
        return totalLoadKw;
    }

    /**
     * Создаёт новый SimInput с другими параметрами (для наборов Соболя).
     * SystemParameters желательно делать immutable/copy, но пока просто подставляем новый объект.
     */
    public SimInput withSystemParameters(SystemParameters newParams) {
        return new SimInput(config, newParams, totalLoadKw);
    }
}
