package simcore.engine;

import simcore.sobol.ParameterSet;
import simcore.sobol.SobolConfig;

import java.util.concurrent.ExecutionException;

public final class SimulationEngine {

    private final MonteCarloRunner mcRunner;

    public SimulationEngine(MonteCarloRunner mcRunner) {
        this.mcRunner = mcRunner;
    }

    /**
     * Обычный Monte Carlo без Соболя (theta=null).
     */
    public MonteCarloEstimate runMonteCarlo(SimInput input,
                                            int mcIterations,
                                            long mcBaseSeed,
                                            boolean traceIfSingle)
            throws InterruptedException, ExecutionException {

        return mcRunner.evaluateForTheta(
                input,
                null,
                null,
                mcIterations,
                mcBaseSeed,
                traceIfSingle
        );
    }

    /**
     * Monte Carlo для конкретного theta (используется Соболем).
     */
    public MonteCarloEstimate runMonteCarloForTheta(SimInput baseInput,
                                                    ParameterSet theta,
                                                    SobolConfig sobolCfg,
                                                    boolean traceIfSingle)
            throws InterruptedException, ExecutionException {

        return mcRunner.evaluateForTheta(
                baseInput,
                theta,
                sobolCfg,
                sobolCfg.getMcIterations(),
                sobolCfg.getMcBaseSeed(),
                traceIfSingle
        );
    }
}
