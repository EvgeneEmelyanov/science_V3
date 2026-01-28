package simcore.sobol;

import simcore.engine.MonteCarloEstimate;
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

        // store only metric means (avoid storing MonteCarloEstimate objects for every point)
        double[] aEns = new double[N];
        double[] aFuel = new double[N];
        double[] aMoto = new double[N];
        double[] aLcoe = new double[N];

        double[] bEns = new double[N];
        double[] bFuel = new double[N];
        double[] bMoto = new double[N];
        double[] bLcoe = new double[N];

        double[][] abEns = new double[d][N];
        double[][] abFuel = new double[d][N];
        double[][] abMoto = new double[d][N];
        double[][] abLcoe = new double[d][N];

        // evaluate all points
        int chunkSize = chunkSize(N, cfg.getThreads());
        List<Future<?>> futures = (thetaExecutor != null) ? new ArrayList<>() : null;

        // A
        for (int from = 0; from < N; from += chunkSize) {
            final int f = from;
            final int t = Math.min(N, from + chunkSize);
            Runnable r = () -> evalAChunk(baseInput, cfg, A, f, t, aEns, aFuel, aMoto, aLcoe);
            submitOrRun(r, futures);
        }


        // B
        for (int from = 0; from < N; from += chunkSize) {
            final int f = from;
            final int t = Math.min(N, from + chunkSize);
            Runnable r = () -> evalBChunk(baseInput, cfg, B, f, t, bEns, bFuel, bMoto, bLcoe);
            submitOrRun(r, futures);
        }


        // AB_j
        List<SobolFactor> factors = cfg.getFactors();
        for (int j = 0; j < d; j++) {
            final int jj = j;
            SobolFactor fct = factors.get(jj);

            for (int from = 0; from < N; from += chunkSize) {
                final int f = from;
                final int t = Math.min(N, from + chunkSize);

                Runnable r = () -> evalABChunk(baseInput, cfg, A, B, jj, fct, f, t,
                        abEns[jj], abFuel[jj], abMoto[jj], abLcoe[jj]);
                submitOrRun(r, futures);
            }
        }


        if (futures != null) {
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (ExecutionException ee) {
                    Throwable c = ee.getCause();
                    if (c instanceof RuntimeException re) throw re;
                    throw ee;
                }
            }
        }

        // indices: First-order S via Saltelli-2002, Total-order ST via Jansen
        double[] sEns = new double[d];
        double[] stEns = new double[d];
        SobolMath.computeIndicesSaltelli2002Jansen(aEns, bEns, abEns, sEns, stEns);

        double[] sFuel = new double[d];
        double[] stFuel = new double[d];
        SobolMath.computeIndicesSaltelli2002Jansen(aFuel, bFuel, abFuel, sFuel, stFuel);

        double[] sMoto = new double[d];
        double[] stMoto = new double[d];
        SobolMath.computeIndicesSaltelli2002Jansen(aMoto, bMoto, abMoto, sMoto, stMoto);

        double[] sLcoe = new double[d];
        double[] stLcoe = new double[d];
        SobolMath.computeIndicesSaltelli2002Jansen(aLcoe, bLcoe, abLcoe, sLcoe, stLcoe);

        SobolResult res = new SobolResult(cfg,
                sEns, stEns,
                sFuel, stFuel,
                sMoto, stMoto,
                sLcoe, stLcoe);

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
            MonteCarloEstimate e = evalTheta(baseInput, cfg, theta, row);
            outEns[i] = e.ensStats.getMean();
            outFuel[i] = e.meanFuelLiters;
            outMoto[i] = e.meanMotoHours;
            outLcoe[i] = e.meanLcoeRubPerKwh;
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
            MonteCarloEstimate e = evalTheta(baseInput, cfg, theta, row);
            outEns[i] = e.ensStats.getMean();
            outFuel[i] = e.meanFuelLiters;
            outMoto[i] = e.meanMotoHours;
            outLcoe[i] = e.meanLcoeRubPerKwh;
        }
    }

    private void evalABChunk(SimInput baseInput,
                             SobolConfig cfg,
                             double[][] A,
                             double[][] B,
                             int j,
                             SobolFactor f,
                             int from,
                             int to,
                             double[] outEns,
                             double[] outFuel,
                             double[] outMoto,
                             double[] outLcoe) {
        for (int i = from; i < to; i++) {
            ParameterSet theta = buildThetaFromABRow(A[i], B[i], j, cfg);
            long row = sobolRowIdx(i, streamAB(cfg, j, f));
            MonteCarloEstimate e = evalTheta(baseInput, cfg, theta, row);
            outEns[i] = e.ensStats.getMean();
            outFuel[i] = e.meanFuelLiters;
            outMoto[i] = e.meanMotoHours;
            outLcoe[i] = e.meanLcoeRubPerKwh;
        }
    }

    private MonteCarloEstimate evalTheta(SimInput baseInput, SobolConfig cfg, ParameterSet theta, long sobolRowIdx) {
        try {
            return mcRunner.evaluateForThetaSequentialMc(
                    baseInput,
                    theta,
                    cfg,
                    cfg.getMcIterations(),
                    cfg.getMcBaseSeed(),
                    sobolRowIdx,
                    false
            );
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(ie);
        } catch (ExecutionException ee) {
            throw new RuntimeException(ee.getCause() != null ? ee.getCause() : ee);
        }
    }

    private static int chunkSize(int N, int threads) {
        int t = Math.max(1, threads);
        int targetTasksPerGroup = t * 8; // coarse outer chunks
        return Math.max(1, (int) Math.ceil(N / (double) targetTasksPerGroup));
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
