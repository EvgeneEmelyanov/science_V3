// File: simcore/engine/MonteCarloRunner.java
package simcore.engine;

import simcore.config.SimulationConstants;
import simcore.config.SystemParameters;
import simcore.sobol.ParameterSet;
import simcore.sobol.SobolConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * Monte-Carlo evaluation for a fixed parameter point theta.
 *
 * Seeding:
 *  - seed depends on (sobolRowIdx, mcIdx)
 *  - For a fixed sobolRowIdx, mcIdx enumerates independent MC scenarios
 *  - Independence between different theta points is achieved by using different sobolRowIdx
 *    (handled by SobolAnalyzer: A, B and AB_j use different sobolRowIdx blocks).
 */
public final class MonteCarloRunner {

    private static final long MC_SEED_STRIDE = 10_000L;

    // Must be >> mcIterations * MC_SEED_STRIDE to avoid overlaps.
    private static final long SOBOL_ROW_SEED_STRIDE = 10_000_000_000L; // 1e10

    private final ExecutorService executor;
    private final SingleRunSimulator simulator;

    private final boolean removeOutliers;
    private final double tScore;
    private final double relativeError;

    public MonteCarloRunner(ExecutorService executor,
                            SingleRunSimulator simulator,
                            boolean removeOutliers,
                            double tScore,
                            double relativeError) {
        this.executor = executor;
        this.simulator = simulator;
        this.removeOutliers = removeOutliers;
        this.tScore = tScore;
        this.relativeError = relativeError;
    }

    /**
     * Backward-compatible wrapper: for non-Sobol usage we keep sobolRowIdx=0.
     */
    public MonteCarloEstimate evaluateForTheta(SimInput baseInput,
                                               ParameterSet theta,
                                               SobolConfig sobolCfg,
                                               int mcIterations,
                                               long mcBaseSeed,
                                               boolean traceIfSingle)
            throws InterruptedException, ExecutionException {

        return evaluateForTheta(
                baseInput,
                theta,
                sobolCfg,
                mcIterations,
                mcBaseSeed,
                0L,
                traceIfSingle
        );
    }

    public MonteCarloEstimate evaluateForTheta(SimInput baseInput,
                                               ParameterSet theta,
                                               SobolConfig sobolCfg,
                                               int mcIterations,
                                               long mcBaseSeed,
                                               long sobolRowIdx,
                                               boolean traceIfSingle)
            throws InterruptedException, ExecutionException {

        // 1) apply theta -> new SystemParameters (only if Sobol is used)
        SimInput input = baseInput;
        if (theta != null && sobolCfg != null) {
            SystemParameters baseParams = baseInput.getSystemParameters();
            SystemParameters tuned = theta.applyTo(baseParams, sobolCfg);
            input = baseInput.withSystemParameters(tuned);
        }

        // 2) single-run mode (iterations == 1): may enable trace
        if (mcIterations == 1) {
            long seed = seedFor(mcBaseSeed, sobolRowIdx, 0);

            SimulationMetrics m = simulator.simulate(input, seed, traceIfSingle);

            double[] ensArr = new double[]{m.ensKwh};
            MonteCarloStats.Stats ensStats = MonteCarloStats.compute(ensArr, removeOutliers, tScore, relativeError);

            double wtPct = pct(m.wtToLoadKwh, m.loadKwh);
            double dgPct = pct(m.dgToLoadKwh, m.loadKwh);
            double btPct = pct(m.btToLoadKwh, m.loadKwh);
            double wrePct = pct(m.wreKwh, m.loadKwh);

            SingleRunMetrics singleRun = (m.trace != null) ? new SingleRunMetrics(m.trace) : null;

            return new MonteCarloEstimate(
                    theta,
                    ensStats,
                    m.fuelLiters,
                    (double) m.totalMotoHours,
                    wrePct,
                    wtPct,
                    dgPct,
                    btPct,
                    singleRun
            );
        }

        // 3) regular MC: parallelize single-run simulations
        final SimInput inputFinal = input;

        List<Future<SimulationMetrics>> futures = new ArrayList<>(mcIterations);
        for (int i = 0; i < mcIterations; i++) {
            final int mcIdx = i;
            futures.add(executor.submit(() -> {
                long seed = seedFor(mcBaseSeed, sobolRowIdx, mcIdx);
                return simulator.simulate(inputFinal, seed, false);
            }));
        }

        double[] ens = new double[mcIterations];

        double fuelSum = 0.0;
        double motoSum = 0.0;

        double wrePctSum = 0.0;
        double wtPctSum = 0.0;
        double dgPctSum = 0.0;
        double btPctSum = 0.0;

        for (int i = 0; i < mcIterations; i++) {
            SimulationMetrics m = futures.get(i).get();

            ens[i] = m.ensKwh;
            fuelSum += m.fuelLiters;
            motoSum += (double) m.totalMotoHours;

            wrePctSum += pct(m.wreKwh, m.loadKwh);

            wtPctSum += pct(m.wtToLoadKwh, m.loadKwh);
            dgPctSum += pct(m.dgToLoadKwh, m.loadKwh);
            btPctSum += pct(m.btToLoadKwh, m.loadKwh);
        }

        MonteCarloStats.Stats ensStats = MonteCarloStats.compute(ens, removeOutliers, tScore, relativeError);

        double inv = 1.0 / mcIterations;

        return new MonteCarloEstimate(
                theta,
                ensStats,
                fuelSum * inv,
                motoSum * inv,
                wrePctSum * inv,
                wtPctSum * inv,
                dgPctSum * inv,
                btPctSum * inv,
                null
        );
    }

    private static long seedFor(long mcBaseSeed, long sobolRowIdx, int mcIdx) {
        return mcBaseSeed
                + sobolRowIdx * SOBOL_ROW_SEED_STRIDE
                + (long) mcIdx * MC_SEED_STRIDE;
    }

    private static double pct(double part, double total) {
        if (total <= SimulationConstants.EPSILON) return 0.0;
        return (part / total) * 100.0;
    }
}
