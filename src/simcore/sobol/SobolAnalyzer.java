package simcore.sobol;

import simcore.engine.MonteCarloRunner;
import simcore.engine.SimInput;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * Tech Sobol for stochastic models: f(x) is estimated via Monte-Carlo.
 *
 * Performance goals:
 *  - Reduce model evaluations from (2 + 2d)N (Jansen S + ST) to (2 + d)N (Saltelli-2002 S + Jansen ST).
 *  - Avoid nested parallelism: parallelize at theta-level, and run MC sequentially inside each theta.
 */
public final class SobolAnalyzer {

    private static final long STREAM_STRIDE = 1_000_000L; // must be << MonteCarloRunner.SOBOL_ROW_SEED_STRIDE

    private final MonteCarloRunner mcRunner;
    private final ExecutorService thetaExecutor; // may be null => sequential

    public SobolAnalyzer(MonteCarloRunner mcRunner) {
        this(mcRunner, null);
    }

    public SobolAnalyzer(MonteCarloRunner mcRunner, ExecutorService thetaExecutor) {
        this.mcRunner = mcRunner;
        this.thetaExecutor = thetaExecutor;
    }

    public SobolResult run(SimInput baseInput, SobolConfig cfg) throws InterruptedException, ExecutionException {
        int N = cfg.getSobolN();
        int d = cfg.dim();

        // Sobol low-discrepancy in [0..1]^d
        double[][][] ab = SobolMath.generateABBySobolSequence(N, d, 1024);
        double[][] A = ab[0];
        double[][] B = ab[1];

        // store only metric means
        double[] aEns = new double[N];
        double[] aFuel = new double[N];
        double[] aMoto = new double[N];
        double[] aLcoe = new double[N];

        double[] bEns = new double[N];
        double[] bFuel = new double[N];
        double[] bMoto = new double[N];
        double[] bLcoe = new double[N];

        // evaluate A and B first (stage-by-stage to avoid a huge Future list)
        int iChunk = chunkSize(N, cfg.getThreads());

        List<Future<?>> futures = (thetaExecutor != null) ? new ArrayList<>() : null;

        // A
        for (int from = 0; from < N; from += iChunk) {
            final int f = from;
            final int t = Math.min(N, from + iChunk);
            Runnable r = () -> evalAChunk(baseInput, cfg, A, f, t, aEns, aFuel, aMoto, aLcoe);
            submitOrRun(r, futures);
        }
        awaitFutures(futures);

        // B
        for (int from = 0; from < N; from += iChunk) {
            final int f = from;
            final int t = Math.min(N, from + iChunk);
            Runnable r = () -> evalBChunk(baseInput, cfg, B, f, t, bEns, bFuel, bMoto, bLcoe);
            submitOrRun(r, futures);
        }
        awaitFutures(futures);

        // pooled variances Var(Y) over A ∪ B (population variance)
        final double varEns = SobolMath.variancePooledPopulation(aEns, bEns);
        final double varFuel = SobolMath.variancePooledPopulation(aFuel, bFuel);
        final double varMoto = SobolMath.variancePooledPopulation(aMoto, bMoto);
        final double varLcoe = SobolMath.variancePooledPopulation(aLcoe, bLcoe);

        final double minEns = SobolMath.minPooled(aEns, bEns);
        final double maxEns = SobolMath.maxPooled(aEns, bEns);
        final double minFuel = SobolMath.minPooled(aFuel, bFuel);
        final double maxFuel = SobolMath.maxPooled(aFuel, bFuel);
        final double minMoto = SobolMath.minPooled(aMoto, bMoto);
        final double maxMoto = SobolMath.maxPooled(aMoto, bMoto);
        final double minLcoe = SobolMath.minPooled(aLcoe, bLcoe);
        final double maxLcoe = SobolMath.maxPooled(aLcoe, bLcoe);

        // indices: First-order S via Saltelli-2002, Total-order ST via Jansen
        double[] sEns = new double[d];
        double[] stEns = new double[d];
        double[] sFuel = new double[d];
        double[] stFuel = new double[d];
        double[] sMoto = new double[d];
        double[] stMoto = new double[d];
        double[] sLcoe = new double[d];
        double[] stLcoe = new double[d];

        List<SobolFactor> factors = cfg.getFactors();

        if (!(varEns > 0.0) || Double.isNaN(varEns) || Double.isInfinite(varEns)
                || !(varFuel > 0.0) || Double.isNaN(varFuel) || Double.isInfinite(varFuel)
                || !(varMoto > 0.0) || Double.isNaN(varMoto) || Double.isInfinite(varMoto)
                || !(varLcoe > 0.0) || Double.isNaN(varLcoe) || Double.isInfinite(varLcoe)) {
            // if any metric variance collapses (rare but possible), keep behaviour explicit
            for (int j = 0; j < d; j++) {
                sEns[j] = stEns[j] = Double.NaN;
                sFuel[j] = stFuel[j] = Double.NaN;
                sMoto[j] = stMoto[j] = Double.NaN;
                sLcoe[j] = stLcoe[j] = Double.NaN;
            }
        } else {
            int jChunk = factorChunkSize(d, cfg.getThreads());
            for (int jFrom = 0; jFrom < d; jFrom += jChunk) {
                final int jf = jFrom;
                final int jt = Math.min(d, jFrom + jChunk);
                Runnable r = () -> evalABIndicesRange(
                        baseInput, cfg, factors,
                        A, B,
                        jf, jt,
                        aEns, bEns, varEns, sEns, stEns,
                        aFuel, bFuel, varFuel, sFuel, stFuel,
                        aMoto, bMoto, varMoto, sMoto, stMoto,
                        aLcoe, bLcoe, varLcoe, sLcoe, stLcoe
                );
                submitOrRun(r, futures);
            }
            awaitFutures(futures);
        }

        SobolResult res = new SobolResult(cfg,
                sEns, stEns,
                sFuel, stFuel,
                sMoto, stMoto,
                sLcoe, stLcoe,
                varEns, varFuel, varMoto, varLcoe,
                minEns, maxEns,
                minFuel, maxFuel,
                minMoto, maxMoto,
                minLcoe, maxLcoe);

        SobolResultPrinter.printTable(cfg.getFactors(), res);
        return res;
    }

    private void submitOrRun(Runnable r, List<Future<?>> futures) {
        if (thetaExecutor == null) {
            r.run();
        } else {
            futures.add(thetaExecutor.submit(() -> {
                r.run();
                return null;
            }));
        }
    }

    private void awaitFutures(List<Future<?>> futures) throws InterruptedException, ExecutionException {
        if (futures == null || futures.isEmpty()) return;
        try {
            for (Future<?> f : futures) {
                f.get();
            }
        } catch (ExecutionException ee) {
            Throwable c = ee.getCause();
            if (c instanceof RuntimeException re) throw re;
            throw ee;
        } finally {
            futures.clear();
        }
    }

    private void evalAChunk(SimInput baseInput,
                            SobolConfig cfg,
                            double[][] A,
                            int from,
                            int to,
                            double[] outEns,
                            double[] outFuel,
                            double[] outMoto,
                            double[] outLcoe) {
        for (int i = from; i < to; i++) {
            ParameterSet theta = buildThetaFromUnitRow(A[i], cfg);
            long row = sobolRowIdx(i, streamA(cfg));
            MonteCarloRunner.Means e = evalThetaMeans(baseInput, cfg, theta, row);
            outEns[i] = e.meanEnsKwh();
            outFuel[i] = e.meanFuelLiters();
            outMoto[i] = e.meanMotoHours();
            outLcoe[i] = e.meanLcoeRubPerKwh();
        }
    }

    private void evalBChunk(SimInput baseInput,
                            SobolConfig cfg,
                            double[][] B,
                            int from,
                            int to,
                            double[] outEns,
                            double[] outFuel,
                            double[] outMoto,
                            double[] outLcoe) {
        for (int i = from; i < to; i++) {
            ParameterSet theta = buildThetaFromUnitRow(B[i], cfg);
            long row = sobolRowIdx(i, streamB(cfg));
            MonteCarloRunner.Means e = evalThetaMeans(baseInput, cfg, theta, row);
            outEns[i] = e.meanEnsKwh();
            outFuel[i] = e.meanFuelLiters();
            outMoto[i] = e.meanMotoHours();
            outLcoe[i] = e.meanLcoeRubPerKwh();
        }
    }

    private void evalABIndicesRange(SimInput baseInput,
                                    SobolConfig cfg,
                                    List<SobolFactor> factors,
                                    double[][] A,
                                    double[][] B,
                                    int jFrom,
                                    int jTo,
                                    double[] aEns, double[] bEns, double varEns, double[] sEns, double[] stEns,
                                    double[] aFuel, double[] bFuel, double varFuel, double[] sFuel, double[] stFuel,
                                    double[] aMoto, double[] bMoto, double varMoto, double[] sMoto, double[] stMoto,
                                    double[] aLcoe, double[] bLcoe, double varLcoe, double[] sLcoe, double[] stLcoe) {

        int N = aEns.length;
        for (int j = jFrom; j < jTo; j++) {
            SobolFactor fct = factors.get(j);

            double sumSEns = 0.0, sumSTEns = 0.0;
            double sumSFuel = 0.0, sumSTFuel = 0.0;
            double sumSMoto = 0.0, sumSTMoto = 0.0;
            double sumSLcoe = 0.0, sumSTLcoe = 0.0;

            for (int i = 0; i < N; i++) {
                ParameterSet theta = buildThetaFromABRow(A[i], B[i], j, cfg);
                long row = sobolRowIdx(i, streamAB(cfg, j, fct));
                MonteCarloRunner.Means m = evalThetaMeans(baseInput, cfg, theta, row);

                double yEns = m.meanEnsKwh();
                double yFuel = m.meanFuelLiters();
                double yMoto = m.meanMotoHours();
                double yLcoe = m.meanLcoeRubPerKwh();

                // First-order (Saltelli 2002): E[f(B) * (f(AB_j) - f(A))] / Var(Y)
                sumSEns += bEns[i] * (yEns - aEns[i]);
                sumSFuel += bFuel[i] * (yFuel - aFuel[i]);
                sumSMoto += bMoto[i] * (yMoto - aMoto[i]);
                sumSLcoe += bLcoe[i] * (yLcoe - aLcoe[i]);

                // Total-order (Jansen): E[(f(A) - f(AB_j))^2] / (2 Var(Y))
                double dEns = aEns[i] - yEns;
                sumSTEns += dEns * dEns;
                double dFuel = aFuel[i] - yFuel;
                sumSTFuel += dFuel * dFuel;
                double dMoto = aMoto[i] - yMoto;
                sumSTMoto += dMoto * dMoto;
                double dLcoe = aLcoe[i] - yLcoe;
                sumSTLcoe += dLcoe * dLcoe;
            }

            double invN = 1.0 / N;
            sEns[j] = (sumSEns * invN) / varEns;
            stEns[j] = (sumSTEns * (invN / 2.0)) / varEns;

            sFuel[j] = (sumSFuel * invN) / varFuel;
            stFuel[j] = (sumSTFuel * (invN / 2.0)) / varFuel;

            sMoto[j] = (sumSMoto * invN) / varMoto;
            stMoto[j] = (sumSTMoto * (invN / 2.0)) / varMoto;

            sLcoe[j] = (sumSLcoe * invN) / varLcoe;
            stLcoe[j] = (sumSTLcoe * (invN / 2.0)) / varLcoe;
        }
    }

    private MonteCarloRunner.Means evalThetaMeans(SimInput baseInput, SobolConfig cfg, ParameterSet theta, long sobolRowIdx) {
        try {
            return mcRunner.evaluateMeansForThetaSequentialMc(
                    baseInput,
                    theta,
                    cfg,
                    cfg.getMcIterations(),
                    cfg.getMcBaseSeed(),
                    sobolRowIdx
            );
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(ie);
        }
    }

    private static int chunkSize(int N, int threads) {
        int t = Math.max(1, threads);
        int targetTasksPerGroup = t * 8; // coarse outer chunks
        return Math.max(1, (int) Math.ceil(N / (double) targetTasksPerGroup));
    }

    private static int factorChunkSize(int d, int threads) {
        int t = Math.max(1, threads);
        int targetTasks = t * 2;
        return Math.max(1, (int) Math.ceil(d / (double) targetTasks));
    }

    // ---------------- theta builders ----------------

    private static ParameterSet buildThetaFromUnitRow(double[] u01, SobolConfig cfg) {
        Map<String, Double> map = new LinkedHashMap<>();
        List<SobolFactor> factors = cfg.getFactors();
        for (int j = 0; j < factors.size(); j++) {
            SobolFactor f = factors.get(j);
            double value = f.scaleFromUnit(u01[j]);
            map.put(f.getName(), value);
        }
        return new ParameterSet(map);
    }

    /** AB_j: row A, but column j from row B. */
    private static ParameterSet buildThetaFromABRow(double[] aRow, double[] bRow, int jSwap, SobolConfig cfg) {
        Map<String, Double> map = new LinkedHashMap<>();
        List<SobolFactor> factors = cfg.getFactors();
        for (int j = 0; j < factors.size(); j++) {
            SobolFactor f = factors.get(j);
            double u = (j == jSwap) ? bRow[jSwap] : aRow[j];
            double value = f.scaleFromUnit(u);
            map.put(f.getName(), value);
        }
        return new ParameterSet(map);
    }

    // ---------------- seed/stream mapping (compatible with existing SobolConfig.SeedMode) ----------------

    private static long sobolRowIdx(int i, long streamId) {
        return (long) i + streamId * STREAM_STRIDE;
    }

    private static long streamA(SobolConfig cfg) {
        return 0L;
    }

    private static long streamB(SobolConfig cfg) {
        return switch (cfg.getSeedMode()) {
            case ALL_SAME -> 0L;
            case ALL_INDEPENDENT, HYBRID_BY_TYPE -> 1L;
        };
    }

    private static long streamAB(SobolConfig cfg, int j, SobolFactor f) {
        return switch (cfg.getSeedMode()) {
            case ALL_SAME -> 0L;
            case ALL_INDEPENDENT -> 100L + j;
            case HYBRID_BY_TYPE -> f.isReliabilityLike() ? (100L + j) : 0L;
        };
    }
}
