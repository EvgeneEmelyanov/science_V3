package simcore.sobol;

import simcore.engine.MonteCarloEstimate;
import simcore.engine.MonteCarloRunner;
import simcore.engine.SimInput;

import java.util.*;
import java.util.concurrent.ExecutionException;

/**
 * Sobol (вариант C): для каждой точки theta считаем MC,
 * затем индексы Соболя считаем для 3 метрик: ENS, Fuel, Moto.
 *
 * Примечание: генерация матриц A/B здесь пока заглушка (Random),
 * позже замените на Sobol/Saltelli последовательность.
 */
public final class SobolAnalyzer {

    private final MonteCarloRunner mcRunner;

    public SobolAnalyzer(MonteCarloRunner mcRunner) {
        this.mcRunner = mcRunner;
    }

    public SobolResult run(SimInput baseInput, SobolConfig cfg)
            throws InterruptedException, ExecutionException {

        int N = cfg.getSobolN();
        int d = cfg.dim();

        double[][] A = randomUnitMatrix(N, d, 12345L);
        double[][] B = randomUnitMatrix(N, d, 67890L);

        List<MonteCarloEstimate> yA = new ArrayList<>(N);
        List<MonteCarloEstimate> yB = new ArrayList<>(N);
        List<List<MonteCarloEstimate>> yAB = new ArrayList<>(d);
        for (int j = 0; j < d; j++) yAB.add(new ArrayList<>(N));

        // A/B
        for (int i = 0; i < N; i++) {
            ParameterSet thetaA = buildThetaFromUnitRow(A[i], cfg);
            ParameterSet thetaB = buildThetaFromUnitRow(B[i], cfg);

            yA.add(mcRunner.evaluateForTheta(
                    baseInput, thetaA, cfg,
                    cfg.getMcIterations(), cfg.getMcBaseSeed(),
                    (long) i,   // sobolRowIdx
                    false
            ));

            yB.add(mcRunner.evaluateForTheta(
                    baseInput, thetaB, cfg,
                    cfg.getMcIterations(), cfg.getMcBaseSeed(),
                    (long) i,   // sobolRowIdx
                    false
            ));
        }

        // AB_j
        for (int j = 0; j < d; j++) {
            for (int i = 0; i < N; i++) {
                double[] row = new double[d];
                System.arraycopy(A[i], 0, row, 0, d);
                row[j] = B[i][j];

                ParameterSet thetaAB = buildThetaFromUnitRow(row, cfg);

                yAB.get(j).add(mcRunner.evaluateForTheta(
                        baseInput, thetaAB, cfg,
                        cfg.getMcIterations(), cfg.getMcBaseSeed(),
                        (long) i,   // sobolRowIdx — тот же i, что и для A/B
                        false
                ));
            }
        }

        // считаем индексы для 3 метрик
        double[] sEns = new double[d], stEns = new double[d];
        double[] sFuel = new double[d], stFuel = new double[d];
        double[] sMoto = new double[d], stMoto = new double[d];

        computeSobolIndices(yA, yB, yAB, d, Metric.ENS, sEns, stEns);
        computeSobolIndices(yA, yB, yAB, d, Metric.FUEL, sFuel, stFuel);
        computeSobolIndices(yA, yB, yAB, d, Metric.MOTO, sMoto, stMoto);

        return new SobolResult(cfg, yA, yB, yAB, sEns, stEns, sFuel, stFuel, sMoto, stMoto);
    }

    private enum Metric { ENS, FUEL, MOTO }

    private static void computeSobolIndices(List<MonteCarloEstimate> yA,
                                            List<MonteCarloEstimate> yB,
                                            List<List<MonteCarloEstimate>> yAB,
                                            int d,
                                            Metric metric,
                                            double[] S,
                                            double[] ST) {

        int N = yA.size();
        double[] a = new double[N];
        double[] b = new double[N];

        for (int i = 0; i < N; i++) {
            a[i] = extractMetric(yA.get(i), metric);
            b[i] = extractMetric(yB.get(i), metric);
        }

        double varY = variance(concat(a, b));
        if (varY <= 0.0) {
            Arrays.fill(S, 0.0);
            Arrays.fill(ST, 0.0);
            return;
        }

        // Формулы Saltelli (классический вариант):
        // S_j  = (1/N) * Σ f(B_i) * (f(AB_j_i) - f(A_i)) / Var(Y)
        // ST_j = (1/(2N)) * Σ (f(A_i) - f(AB_j_i))^2 / Var(Y)
        for (int j = 0; j < d; j++) {
            double sumS = 0.0;
            double sumST = 0.0;

            for (int i = 0; i < N; i++) {
                double ab = extractMetric(yAB.get(j).get(i), metric);
                sumS += b[i] * (ab - a[i]);

                double diff = a[i] - ab;
                sumST += diff * diff;
            }

            S[j] = (sumS / N) / varY;
            ST[j] = (sumST / (2.0 * N)) / varY;

            // защита от численного мусора
            if (S[j] < 0.0) S[j] = 0.0;
            if (ST[j] < 0.0) ST[j] = 0.0;
        }
    }

    private static double extractMetric(MonteCarloEstimate e, Metric m) {
        return switch (m) {
            case ENS -> e.ensStats.getMean();
            case FUEL -> e.meanFuelLiters;
            case MOTO -> e.meanMotoHours;
        };
    }

    private static double[][] randomUnitMatrix(int n, int d, long seed) {
        Random r = new Random(seed);
        double[][] m = new double[n][d];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < d; j++) {
                m[i][j] = r.nextDouble(); // [0;1)
            }
        }
        return m;
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

    private static double variance(double[] x) {
        if (x.length < 2) return 0.0;
        double mean = 0.0;
        for (double v : x) mean += v;
        mean /= x.length;

        double s = 0.0;
        for (double v : x) {
            double d = v - mean;
            s += d * d;
        }
        return s / (x.length - 1);
    }
}
