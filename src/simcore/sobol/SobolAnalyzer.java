package simcore.sobol;

import simcore.engine.MonteCarloEstimate;
import simcore.engine.MonteCarloRunner;
import simcore.engine.SimInput;

import java.util.*;
import java.util.concurrent.ExecutionException;

public final class SobolAnalyzer {

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

        // CRN strategy for stochastic model:
        // A(i) and AB_j(i) share stream; B(i) uses independent stream.
        for (int i = 0; i < N; i++) {
            final long rowA = (long) i;
            final long rowB = (long) (i + N);

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

        for (int j = 0; j < d; j++) {
            for (int i = 0; i < N; i++) {
                final long rowA = (long) i;

                double[] row = new double[d];
                System.arraycopy(A[i], 0, row, 0, d);
                row[j] = B[i][j];

                ParameterSet thetaAB = buildThetaFromUnitRow(row, cfg);

                yAB.get(j).add(mcRunner.evaluateForTheta(
                        baseInput, thetaAB, cfg,
                        cfg.getMcIterations(), cfg.getMcBaseSeed(),
                        rowA,
                        false
                ));
            }
        }

        double[] sEns = new double[d], stEns = new double[d];
        double[] sFuel = new double[d], stFuel = new double[d];
        double[] sMoto = new double[d], stMoto = new double[d];
        double[] sLcoe = new double[d], stLcoe = new double[d];

        computeSobolIndicesSaltelli2002Jansen(yA, yB, yAB, d, Metric.ENS,  sEns,  stEns, true);
        computeSobolIndicesSaltelli2002Jansen(yA, yB, yAB, d, Metric.FUEL, sFuel, stFuel, true);
        computeSobolIndicesSaltelli2002Jansen(yA, yB, yAB, d, Metric.MOTO, sMoto, stMoto, true);
        computeSobolIndicesSaltelli2002Jansen(yA, yB, yAB, d, Metric.LCOE, sLcoe, stLcoe, true);

        return new SobolResult(cfg, yA, yB, yAB, sEns, stEns, sFuel, stFuel, sMoto, stMoto, sLcoe, stLcoe);
    }

    private enum Metric { ENS, FUEL, MOTO, LCOE }

    private static void computeSobolIndicesSaltelli2002Jansen(
            List<MonteCarloEstimate> yA,
            List<MonteCarloEstimate> yB,
            List<List<MonteCarloEstimate>> yAB,
            int d,
            Metric metric,
            double[] S,
            double[] ST,
            boolean printDiagnostics) {

        final int N = yA.size();
        double[] a = new double[N];
        double[] b = new double[N];

        for (int i = 0; i < N; i++) {
            a[i] = extractMetric(yA.get(i), metric);
            b[i] = extractMetric(yB.get(i), metric);
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
                double ab = extractMetric(yAB.get(j).get(i), metric);

                // First-order: Saltelli 2002 (robust under noise)
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
