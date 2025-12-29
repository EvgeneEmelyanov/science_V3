// File: simcore/engine/MonteCarloRunner.java
package simcore.engine;

import simcore.config.SimulationConstants;
import simcore.config.SystemParameters;
import simcore.sobol.ParameterSet;
import simcore.sobol.SobolConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

public final class MonteCarloRunner {

    private static final long MC_SEED_STRIDE = 10_000L;
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

    public MonteCarloEstimate evaluateForTheta(SimInput baseInput,
                                               ParameterSet theta,
                                               SobolConfig sobolCfg,
                                               int mcIterations,
                                               long mcBaseSeed,
                                               boolean traceIfSingle)
            throws InterruptedException, ExecutionException {

        return evaluateForTheta(
                baseInput, theta, sobolCfg,
                mcIterations, mcBaseSeed,
                0L, traceIfSingle
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

        // apply theta
        SimInput input = baseInput;
        if (theta != null && sobolCfg != null) {
            SystemParameters baseParams = baseInput.getSystemParameters();
            SystemParameters tuned = theta.applyTo(baseParams, sobolCfg);
            input = baseInput.withSystemParameters(tuned);
        }

        if (mcIterations <= 0) {
            throw new IllegalArgumentException("mcIterations must be > 0");
        }

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
                    m.ensCat1Kwh,
                    m.ensCat2Kwh,
                    m.fuelLiters,
                    (double) m.totalMotoHours,
                    wrePct,
                    wtPct,
                    dgPct,
                    btPct,
                    singleRun,
                    (double) m.failRoom,
                    (double) m.failBus,
                    (double) m.failDg,
                    (double) m.failWt,
                    (double) m.failBt,
                    (double) m.failBrk,
                    (double) m.repBt
            );
        }

        final SimInput inputFinal = input;

        int parallelism = estimateParallelism(executor);
        int chunks = Math.min(mcIterations, Math.max(1, parallelism * 2));
        int chunkSize = (int) Math.ceil(mcIterations / (double) chunks);

        List<Future<ChunkAgg>> futures = new ArrayList<>(chunks);

        for (int c = 0; c < chunks; c++) {
            int from = c * chunkSize;
            int to = Math.min(mcIterations, from + chunkSize);
            if (from >= to) break;

            futures.add(executor.submit(() -> runChunk(inputFinal, mcBaseSeed, sobolRowIdx, from, to)));
        }

        double[] ens = new double[mcIterations];
        double ens1Sum = 0.0;
        double ens2Sum = 0.0;
        double fuelSum = 0.0;
        double motoSum = 0.0;

        double wrePctSum = 0.0;
        double wtPctSum = 0.0;
        double dgPctSum = 0.0;
        double btPctSum = 0.0;

        double failRoomSum = 0.0;
        double failBusSum = 0.0;
        double failDgSum = 0.0;
        double failWtSum = 0.0;
        double failBtSum = 0.0;
        double failBrkSum = 0.0;
        double repBtSum = 0.0;

        for (Future<ChunkAgg> f : futures) {
            ChunkAgg a = f.get();
            fuelSum += a.fuelSum;
            motoSum += a.motoSum;
            ens1Sum += a.ens1Sum;
            ens2Sum += a.ens2Sum;
            wrePctSum += a.wrePctSum;
            wtPctSum += a.wtPctSum;
            dgPctSum += a.dgPctSum;
            btPctSum += a.btPctSum;
            failRoomSum += a.failRoomSum;
            failBusSum  += a.failBusSum;
            failDgSum   += a.failDgSum;
            failWtSum   += a.failWtSum;
            failBtSum   += a.failBtSum;
            failBrkSum  += a.failBrkSum;
            repBtSum += a.repBtSum;


            System.arraycopy(a.ens, 0, ens, a.ensOffset, a.ens.length);
        }

        MonteCarloStats.Stats ensStats = MonteCarloStats.compute(ens, removeOutliers, tScore, relativeError);

        double inv = 1.0 / mcIterations;

        return new MonteCarloEstimate(
                theta,
                ensStats,
                ens1Sum * inv,
                ens2Sum * inv,
                fuelSum * inv,
                motoSum * inv,
                wrePctSum * inv,
                wtPctSum * inv,
                dgPctSum * inv,
                btPctSum * inv,
                null,
                failRoomSum * inv,
                failBusSum * inv,
                failDgSum * inv,
                failWtSum * inv,
                failBtSum * inv,
                failBrkSum * inv,
                repBtSum * inv
        );

    }

    private ChunkAgg runChunk(SimInput input,
                              long mcBaseSeed,
                              long sobolRowIdx,
                              int fromInclusive,
                              int toExclusive) {

        int n = toExclusive - fromInclusive;
        double[] ens = new double[n];

        double fuelSum = 0.0;
        double motoSum = 0.0;
        double ens1Sum = 0.0;
        double ens2Sum = 0.0;
        double wrePctSum = 0.0;
        double wtPctSum = 0.0;
        double dgPctSum = 0.0;
        double btPctSum = 0.0;
        double failRoomSum = 0.0;
        double failBusSum = 0.0;
        double failDgSum = 0.0;
        double failWtSum = 0.0;
        double failBtSum = 0.0;
        double failBrkSum = 0.0;
        double repBtSum = 0.0;


        for (int mcIdx = fromInclusive; mcIdx < toExclusive; mcIdx++) {
            long seed = seedFor(mcBaseSeed, sobolRowIdx, mcIdx);
            SimulationMetrics m = simulator.simulate(input, seed, false);

            int k = mcIdx - fromInclusive;
            ens[k] = m.ensKwh;
            ens1Sum += m.ensCat1Kwh;
            ens2Sum += m.ensCat2Kwh;

            fuelSum += m.fuelLiters;
            motoSum += (double) m.totalMotoHours;

            wrePctSum += pct(m.wreKwh, m.loadKwh);
            wtPctSum += pct(m.wtToLoadKwh, m.loadKwh);
            dgPctSum += pct(m.dgToLoadKwh, m.loadKwh);
            btPctSum += pct(m.btToLoadKwh, m.loadKwh);
            failRoomSum += m.failRoom;
            failBusSum  += m.failBus;
            failDgSum   += m.failDg;
            failWtSum   += m.failWt;
            failBtSum   += m.failBt;
            failBrkSum  += m.failBrk;
            repBtSum += m.repBt;

        }

        return new ChunkAgg(fromInclusive, ens, ens1Sum, ens2Sum, fuelSum, motoSum,
                wrePctSum, wtPctSum, dgPctSum, btPctSum,
                failRoomSum, failBusSum, failDgSum, failWtSum, failBtSum, failBrkSum, repBtSum);

    }

    private static final class ChunkAgg {
        final int ensOffset;
        final double[] ens;
        final double ens1Sum;
        final double ens2Sum;
        final double fuelSum;
        final double motoSum;
        final double wrePctSum;
        final double wtPctSum;
        final double dgPctSum;
        final double btPctSum;
        final double failRoomSum;
        final double failBusSum;
        final double failDgSum;
        final double failWtSum;
        final double failBtSum;
        final double failBrkSum;
        final double repBtSum;

        ChunkAgg(int ensOffset,
                 double[] ens,
                 double ens1Sum,
                 double ens2Sum,
                 double fuelSum,
                 double motoSum,
                 double wrePctSum,
                 double wtPctSum,
                 double dgPctSum,
                 double btPctSum,
                 double failRoomSum,
                 double failBusSum,
                 double failDgSum,
                 double failWtSum,
                 double failBtSum,
                 double failBrkSum,
                 double repBtSum) {
            this.ensOffset = ensOffset;
            this.ens = ens;
            this.ens1Sum = ens1Sum;
            this.ens2Sum = ens2Sum;
            this.fuelSum = fuelSum;
            this.motoSum = motoSum;
            this.wrePctSum = wrePctSum;
            this.wtPctSum = wtPctSum;
            this.dgPctSum = dgPctSum;
            this.btPctSum = btPctSum;
            this.failRoomSum = failRoomSum;
            this.failBusSum = failBusSum;
            this.failDgSum = failDgSum;
            this.failWtSum = failWtSum;
            this.failBtSum = failBtSum;
            this.failBrkSum = failBrkSum;
            this.repBtSum = repBtSum;
        }
    }

    private static int estimateParallelism(ExecutorService executor) {
        if (executor instanceof ForkJoinPool fjp) return Math.max(1, fjp.getParallelism());
        return Math.max(1, Runtime.getRuntime().availableProcessors());
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
