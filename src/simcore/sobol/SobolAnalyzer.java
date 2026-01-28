package simcore.sobol;

import simcore.engine.MonteCarloEstimate;
import simcore.engine.MonteCarloRunner;
import simcore.engine.SimInput;

import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.function.DoubleUnaryOperator;

public final class SobolAnalyzer {

    /**
     * We encode (row i, streamId) -> sobolRowIdx.
     * MonteCarloRunner expands sobolRowIdx into a seed block, therefore we must keep streams disjoint.
     */
    private static final long SOBOL_STREAM_STRIDE_ROWS = 1_000_000L;

    private final MonteCarloRunner mcRunner;

    public SobolAnalyzer(MonteCarloRunner mcRunner) {
        this.mcRunner = mcRunner;
    }

    public SobolResult run(SimInput baseInput, SobolConfig cfg)
            throws InterruptedException, ExecutionException {

        final int N = cfg.getSobolN();
        final int d = cfg.dim();

        if (cfg.getFactors().size() != d) {
            throw new IllegalArgumentException(
                    "SobolConfig.dim() must equal factors.size(): dim=" + d +
                            " factors=" + cfg.getFactors().size()
            );
        }

        double[][][] ab = SobolMath.generateABBySobolSequence(N, d, 1024);
        double[][] A = ab[0];
        double[][] B = ab[1];

        List<MonteCarloEstimate> yA = new ArrayList<>(N);
        List<MonteCarloEstimate> yB = new ArrayList<>(N);
        List<List<MonteCarloEstimate>> yAB = new ArrayList<>(d);
        for (int j = 0; j < d; j++) yAB.add(new ArrayList<>(N));

        final SobolConfig.SeedMode seedMode = cfg.getSeedMode();

        // --------- A and B ---------
        for (int i = 0; i < N; i++) {
            final long rowA = rowIdx(i, streamA());
            final long rowB = rowIdx(i, streamB(seedMode));

            ParameterSet thetaA = buildThetaFromUnitRow(A[i], cfg);
            ParameterSet thetaB = buildThetaFromUnitRow(B[i], cfg);

            yA.add(mcRunner.evaluateForTheta(
                    baseInput, thetaA, cfg,
                    cfg.getMcIterations(), cfg.getMcBaseSeed(),
                    rowA,
                    false
            ));

            yB.add(mcRunner.evaluateForTheta(
                    baseInput, thetaB, cfg,
                    cfg.getMcIterations(), cfg.getMcBaseSeed(),
                    rowB,
                    false
            ));
        }

        // --------- AB_j ---------
        for (int j = 0; j < d; j++) {
            final double[] unitRow = new double[d];
            SobolFactor f = cfg.getFactors().get(j);
            final long streamAB = streamAB(seedMode, j, f);

            for (int i = 0; i < N; i++) {
                final long rowAB = rowIdx(i, streamAB);

                System.arraycopy(A[i], 0, unitRow, 0, d);
                unitRow[j] = B[i][j];

                ParameterSet thetaAB = buildThetaFromUnitRow(unitRow, cfg);

                yAB.get(j).add(mcRunner.evaluateForTheta(
                        baseInput, thetaAB, cfg,
                        cfg.getMcIterations(), cfg.getMcBaseSeed(),
                        rowAB,
                        false
                ));
            }
        }

        // ===== RAW indices =====
        double[] sEns = new double[d], stEns = new double[d];
        double[] sFuel = new double[d], stFuel = new double[d];
        double[] sMoto = new double[d], stMoto = new double[d];
        double[] sLcoe = new double[d], stLcoe = new double[d];

        computeSobolIndicesSaltelli2002Jansen(yA, yB, yAB, d, Metric.ENS,  v -> v, sEns,  stEns, true);
        computeSobolIndicesSaltelli2002Jansen(yA, yB, yAB, d, Metric.FUEL, v -> v, sFuel, stFuel, true);
        computeSobolIndicesSaltelli2002Jansen(yA, yB, yAB, d, Metric.MOTO, v -> v, sMoto, stMoto, true);
        computeSobolIndicesSaltelli2002Jansen(yA, yB, yAB, d, Metric.LCOE, v -> v, sLcoe, stLcoe, true);

        System.out.println("=== Sobol table (RAW) seedMode=" + seedMode + " ===");
        printCombinedTable(cfg.getFactors(), sLcoe, stLcoe, sEns, stEns, sFuel, stFuel, sMoto, stMoto);

        // ===== LOG1P indices =====
        DoubleUnaryOperator log1p = v -> Math.log1p(Math.max(0.0, v));

        double[] sEnsLog = new double[d], stEnsLog = new double[d];
        double[] sFuelLog = new double[d], stFuelLog = new double[d];
        double[] sMotoLog = new double[d], stMotoLog = new double[d];
        double[] sLcoeLog = new double[d], stLcoeLog = new double[d];

        computeSobolIndicesSaltelli2002Jansen(yA, yB, yAB, d, Metric.ENS,  log1p, sEnsLog,  stEnsLog, false);
        computeSobolIndicesSaltelli2002Jansen(yA, yB, yAB, d, Metric.FUEL, log1p, sFuelLog, stFuelLog, false);
        computeSobolIndicesSaltelli2002Jansen(yA, yB, yAB, d, Metric.MOTO, log1p, sMotoLog, stMotoLog, false);
        computeSobolIndicesSaltelli2002Jansen(yA, yB, yAB, d, Metric.LCOE, log1p, sLcoeLog, stLcoeLog, false);

        System.out.println("=== Sobol table (LOG1P: log(metric+1)) seedMode=" + seedMode + " ===");
        printCombinedTable(cfg.getFactors(), sLcoeLog, stLcoeLog, sEnsLog, stEnsLog, sFuelLog, stFuelLog, sMotoLog, stMotoLog);

        // Return RAW result to keep existing pipeline unchanged.
        return new SobolResult(cfg, yA, yB, yAB, sEns, stEns, sFuel, stFuel, sMoto, stMoto, sLcoe, stLcoe);
    }

    private static long rowIdx(int i, long streamId) {
        if (i < 0) throw new IllegalArgumentException("row i must be >= 0");
        if (streamId < 0) throw new IllegalArgumentException("streamId must be >= 0");
        return (long) i + streamId * SOBOL_STREAM_STRIDE_ROWS;
    }

    private static long streamA() {
        return 0L;
    }

    private static long streamB(SobolConfig.SeedMode mode) {
        return (mode == SobolConfig.SeedMode.ALL_SAME) ? 0L : 1L;
    }

    private static long streamAB(SobolConfig.SeedMode mode, int j, SobolFactor f) {
        return switch (mode) {
            case ALL_SAME -> 0L;
            case ALL_INDEPENDENT -> 100L + j;
            case HYBRID_BY_TYPE -> f.isReliabilityLike() ? (100L + j) : 0L;
        };
    }

    private enum Metric { ENS, FUEL, MOTO, LCOE }

    /**
     * First-order: Saltelli 2002 (noise-robust)
     * Total-order: Jansen
     */
    private static void computeSobolIndicesSaltelli2002Jansen(
            List<MonteCarloEstimate> yA,
            List<MonteCarloEstimate> yB,
            List<List<MonteCarloEstimate>> yAB,
            int d,
            Metric metric,
            DoubleUnaryOperator transform,
            double[] S,
            double[] ST,
            boolean printDiagnostics) {

        final int N = yA.size();
        double[] a = new double[N];
        double[] b = new double[N];

        for (int i = 0; i < N; i++) {
            a[i] = transform.applyAsDouble(extractMetric(yA.get(i), metric));
            b[i] = transform.applyAsDouble(extractMetric(yB.get(i), metric));
        }

        double[] yAll = concat(a, b);
        double meanY = mean(yAll);
        double varY = variancePopulation(yAll);

        if (printDiagnostics) {
            System.out.printf(
                    "Sobol metric=%s: meanY=%.6f varY=%.6e, A[min..max]=[%.6f..%.6f], B[min..max]=[%.6f..%.6f]%n",
                    metric, meanY, varY, min(a), max(a), min(b), max(b)
            );
        }

        if (!(varY > 0.0) || Double.isNaN(varY) || Double.isInfinite(varY)) {
            Arrays.fill(S, Double.NaN);
            Arrays.fill(ST, Double.NaN);
            return;
        }

        int stLessThanS = 0;
        double sumS = 0.0;
        double sumST = 0.0;

        for (int j = 0; j < d; j++) {
            double sumS_first = 0.0;
            double sumSt = 0.0;

            for (int i = 0; i < N; i++) {
                double ab = transform.applyAsDouble(extractMetric(yAB.get(j).get(i), metric));

                // First-order: Saltelli 2002
                sumS_first += b[i] * (ab - a[i]);

                // Total-order: Jansen
                double diff = a[i] - ab;
                sumSt += diff * diff;
            }

            double sj  = (sumS_first / N) / varY;
            double stj = (sumSt / (2.0 * N)) / varY;

            S[j] = sj;
            ST[j] = stj;

            sumS += sj;
            sumST += stj;
            if (stj + 1e-12 < sj) stLessThanS++;
        }

        if (printDiagnostics) {
            System.out.printf("Sobol metric=%s: sumS=%.6f sumST=%.6f count(ST<S)=%d/%d%n",
                    metric, sumS, sumST, stLessThanS, d);
        }
    }

    private static double extractMetric(MonteCarloEstimate e, Metric m) {
        return switch (m) {
            case ENS -> e.ensStats.getMean();
            case FUEL -> e.meanFuelLiters;
            case MOTO -> e.meanMotoHours;
            case LCOE -> e.meanLcoeRubPerKwh;
        };
    }

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

    private static void printCombinedTable(List<SobolFactor> factors,
                                           double[] sLcoe, double[] stLcoe,
                                           double[] sEns,  double[] stEns,
                                           double[] sFuel, double[] stFuel,
                                           double[] sMoto, double[] stMoto) {

        System.out.println("param\tS_LCOE\tST_LCOE\tS_ENS\tST_ENS\tS_Fuel\tST_Fuel\tS_Moto\tST_Moto");
        for (int j = 0; j < factors.size(); j++) {
            System.out.printf(Locale.US,
                    "%s\t%.4f\t%.4f\t%.4f\t%.4f\t%.4f\t%.4f\t%.4f\t%.4f%n",
                    factors.get(j).getName(),
                    sLcoe[j], stLcoe[j],
                    sEns[j],  stEns[j],
                    sFuel[j], stFuel[j],
                    sMoto[j], stMoto[j]
            );
        }
    }

    private static double[] concat(double[] a, double[] b) {
        double[] r = new double[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    private static double mean(double[] x) {
        double s = 0.0;
        for (double v : x) s += v;
        return s / x.length;
    }

    private static double variancePopulation(double[] x) {
        double m = mean(x);
        double s = 0.0;
        for (double v : x) {
            double d = v - m;
            s += d * d;
        }
        return s / x.length;
    }

    private static double min(double[] x) {
        double m = Double.POSITIVE_INFINITY;
        for (double v : x) m = Math.min(m, v);
        return m;
    }

    private static double max(double[] x) {
        double m = Double.NEGATIVE_INFINITY;
        for (double v : x) m = Math.max(m, v);
        return m;
    }
}
