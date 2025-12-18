package simcore.engine;

import simcore.config.SimulationConstants;
import simcore.config.SystemParameters;
import simcore.sobol.ParameterSet;
import simcore.sobol.SobolConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * Запуск Monte Carlo для одной точки параметров theta.
 * Важно: seed зависит только от индекса MC-итерации (common random numbers),
 * и НЕ зависит от индекса точки Соболя.
 */
public final class MonteCarloRunner {

    private static final long MC_SEED_STRIDE = 10_000L;

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

    public MonteCarloEstimate evaluateForTheta(SimInput baseInput,
                                               ParameterSet theta,
                                               SobolConfig sobolCfg,
                                               int mcIterations,
                                               long mcBaseSeed,
                                               boolean traceIfSingle)
            throws InterruptedException, ExecutionException {

        // 1) применяем theta -> новые SystemParameters (только если есть Sobol)
        SimInput input = baseInput;
        if (theta != null && sobolCfg != null) {
            SystemParameters baseParams = baseInput.getSystemParameters();
            SystemParameters tuned = theta.applyTo(baseParams, sobolCfg);
            input = baseInput.withSystemParameters(tuned);
        }

        // 2) single-run режим (iterations == 1): нужен trace по часам
        if (mcIterations == 1) {
            long seed = mcBaseSeed + 0L * MC_SEED_STRIDE;

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
                    wrePct,     // WRE_% (а не kWh)
                    wtPct,
                    dgPct,
                    btPct,
                    singleRun
            );
        }

        // 3) обычный MC: параллелим single-run по итерациям
        final SimInput inputFinal = input;

        List<Future<SimulationMetrics>> futures = new ArrayList<>(mcIterations);
        for (int i = 0; i < mcIterations; i++) {
            final int mcIdx = i;
            futures.add(executor.submit(() -> {
                long seed = mcBaseSeed + (long) mcIdx * MC_SEED_STRIDE;
                return simulator.simulate(inputFinal, seed, false);
            }));
        }

        double[] ens = new double[mcIterations];

        double fuelSum = 0.0;
        double motoSum = 0.0;

        // FIX: теперь аккумулируем WRE в процентах, чтобы совпадало с веткой iterations==1
        double wrePctSum = 0.0;

        double wtPctSum = 0.0;
        double dgPctSum = 0.0;
        double btPctSum = 0.0;

        for (int i = 0; i < mcIterations; i++) {
            SimulationMetrics m = futures.get(i).get();

            ens[i] = m.ensKwh;
            fuelSum += m.fuelLiters;
            motoSum += (double) m.totalMotoHours;

            // FIX
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
                wrePctSum * inv,  // FIX: WRE_% mean
                wtPctSum * inv,
                dgPctSum * inv,
                btPctSum * inv,
                null
        );
    }

    private static double pct(double part, double total) {
        if (total <= SimulationConstants.EPSILON) return 0.0;
        return (part / total) * 100.0;
    }
}
