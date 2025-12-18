package simcore.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class MonteCarloAnalyzer {

    private MonteCarloAnalyzer() {}

    public static Stats compute(double[] sample,
                                boolean removeOutliers,
                                double tScore,
                                double relativeError) {

        List<Double> vals = new ArrayList<>(sample.length);
        for (double v : sample) {
            if (Double.isFinite(v)) vals.add(v);
        }
        if (vals.isEmpty()) {
            return new Stats(0, 0, 0, 0, 0, 0);
        }

        if (removeOutliers && vals.size() >= 4) {
            vals = removeOutliersIqr(vals);
        }

        int n = vals.size();
        double mean = mean(vals);

        double std = (n < 2) ? 0.0 : sampleStd(vals, mean);

        double margin = (n < 2) ? 0.0 : (tScore * std / Math.sqrt(n));
        double ciLo = mean - margin;
        double ciHi = mean + margin;

        int requiredN = requiredSampleSize(mean, std, tScore, relativeError);

        return new Stats(mean, std, ciLo, ciHi, n, requiredN);
    }

    private static int requiredSampleSize(double mean, double std, double tScore, double relativeError) {
        if (mean == 0.0) return 1;
        double E = relativeError * Math.abs(mean);
        if (E <= 0.0) return 1;
        if (std <= 0.0) return 1;
        return (int) Math.ceil(Math.pow((tScore * std) / E, 2));
    }

    private static double mean(List<Double> data) {
        double s = 0.0;
        for (double v : data) s += v;
        return s / data.size();
    }

    private static double sampleStd(List<Double> data, double mean) {
        double sum = 0.0;
        for (double v : data) {
            double d = v - mean;
            sum += d * d;
        }
        return Math.sqrt(sum / (data.size() - 1));
    }

    private static List<Double> removeOutliersIqr(List<Double> data) {
        List<Double> sorted = new ArrayList<>(data);
        Collections.sort(sorted);
        int n = sorted.size();
        double q1 = sorted.get(n / 4);
        double q3 = sorted.get((3 * n) / 4);
        double iqr = q3 - q1;
        double lower = q1 - 1.5 * iqr;
        double upper = q3 + 1.5 * iqr;

        List<Double> filtered = new ArrayList<>();
        for (double v : sorted) {
            if (v >= lower && v <= upper) filtered.add(v);
        }
        return filtered;
    }

    public static final class Stats {
        public final double mean;
        public final double std;
        public final double ciLo;
        public final double ciHi;
        public final int n;
        public final int requiredN;

        public Stats(double mean, double std, double ciLo, double ciHi, int n, int requiredN) {
            this.mean = mean;
            this.std = std;
            this.ciLo = ciLo;
            this.ciHi = ciHi;
            this.n = n;
            this.requiredN = requiredN;
        }
    }
}
