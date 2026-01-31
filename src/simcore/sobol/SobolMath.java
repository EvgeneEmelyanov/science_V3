package simcore.sobol;

import org.apache.commons.math3.random.SobolSequenceGenerator;

import java.util.Arrays;

/**
 * Shared Sobol helpers for both "hard" (simulator + Monte-Carlo) and "econ" (drivers + unit costs) Sobol runs.
 *
 * Notes for stochastic models (Monte-Carlo inside f(x)):
 * - Use Saltelli 2002 estimator for first-order indices (more robust under noise).
 * - Use Jansen estimator for total-order indices.
 */
public final class SobolMath {

    private SobolMath() {}

    /**
     * Generates two matrices A and B (NxD) from a Sobol low-discrepancy sequence of dimension 2D.
     * The first D coordinates form A, the next D form B.
     */
    public static double[][][] generateABBySobolSequence(int N, int d, int skip) {
        SobolSequenceGenerator sobol = new SobolSequenceGenerator(2 * d);
        for (int i = 0; i < skip; i++) sobol.nextVector();

        double[][] A = new double[N][d];
        double[][] B = new double[N][d];

        for (int i = 0; i < N; i++) {
            double[] v = sobol.nextVector();
            System.arraycopy(v, 0, A[i], 0, d);
            System.arraycopy(v, d, B[i], 0, d);
        }
        return new double[][][] { A, B };
    }

    /**
     * Computes Sobol first-order S and total-order ST indices using:
     * - First-order: Saltelli 2002 estimator:  S_j = E[ f(B) * (f(AB_j) - f(A)) ] / Var(Y)
     * - Total-order: Jansen estimator:        ST_j = E[ (f(A) - f(AB_j))^2 ] / (2 Var(Y))
     *
     * Where AB_j is built from A by replacing column j with column j from B.
     *
     * @param a  f(A_i), length N
     * @param b  f(B_i), length N
     * @param ab f(AB_j,i), shape [d][N]
     */
    public static void computeIndicesSaltelli2002Jansen(double[] a, double[] b, double[][] ab, double[] S, double[] ST) {
        int N = a.length;
        if (b.length != N) throw new IllegalArgumentException("b.length != a.length");
        int d = ab.length;
        if (S.length != d || ST.length != d) throw new IllegalArgumentException("S/ST length must be d");
        for (int j = 0; j < d; j++) {
            if (ab[j].length != N) throw new IllegalArgumentException("ab[" + j + "].length != a.length");
        }

        // variance over pooled samples (A ∪ B)
        double[] yAll = new double[2 * N];
        System.arraycopy(a, 0, yAll, 0, N);
        System.arraycopy(b, 0, yAll, N, N);

        double meanY = mean(yAll);
        double varY = variancePopulation(yAll, meanY);

        if (!(varY > 0.0) || Double.isNaN(varY) || Double.isInfinite(varY)) {
            Arrays.fill(S, Double.NaN);
            Arrays.fill(ST, Double.NaN);
            return;
        }

        for (int j = 0; j < d; j++) {
            double sumS = 0.0;
            double sumST = 0.0;

            for (int i = 0; i < N; i++) {
                double yAB = ab[j][i];

                // First-order (Saltelli 2002) - robust under noise
                sumS += b[i] * (yAB - a[i]);

                // Total-order (Jansen)
                double diff = a[i] - yAB;
                sumST += diff * diff;
            }

            S[j] = (sumS / N) / varY;
            ST[j] = (sumST / (2.0 * N)) / varY;
        }
    }

    public static double mean(double[] x) {
        double s = 0.0;
        for (double v : x) s += v;
        return s / x.length;
    }

    /**
     * Population variance over pooled samples (A ∪ B) without allocating a 2N array.
     */
    public static double variancePooledPopulation(double[] a, double[] b) {
        int N = a.length;
        if (b.length != N) throw new IllegalArgumentException("b.length != a.length");

        double sum = 0.0;
        for (int i = 0; i < N; i++) {
            sum += a[i];
            sum += b[i];
        }
        double mean = sum / (2.0 * N);

        double s = 0.0;
        for (int i = 0; i < N; i++) {
            double da = a[i] - mean;
            s += da * da;
            double db = b[i] - mean;
            s += db * db;
        }
        return s / (2.0 * N);
    }

    public static double variancePopulation(double[] x, double mean) {
        double s = 0.0;
        for (double v : x) {
            double d = v - mean;
            s += d * d;
        }
        return s / x.length;
    }

    /**
     * Min over pooled samples (A ∪ B) without allocating a 2N array.
     */
    public static double minPooled(double[] a, double[] b) {
        int N = a.length;
        if (b.length != N) throw new IllegalArgumentException("b.length != a.length");
        double m = Double.POSITIVE_INFINITY;
        for (int i = 0; i < N; i++) {
            double va = a[i];
            if (va < m) m = va;
            double vb = b[i];
            if (vb < m) m = vb;
        }
        return m;
    }

    /**
     * Max over pooled samples (A ∪ B) without allocating a 2N array.
     */
    public static double maxPooled(double[] a, double[] b) {
        int N = a.length;
        if (b.length != N) throw new IllegalArgumentException("b.length != a.length");
        double m = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < N; i++) {
            double va = a[i];
            if (va > m) m = va;
            double vb = b[i];
            if (vb > m) m = vb;
        }
        return m;
    }
}
