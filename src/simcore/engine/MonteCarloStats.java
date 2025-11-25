package simcore.engine;

import java.util.*;
import java.util.stream.Collectors;

public final class MonteCarloStats {

    private MonteCarloStats() {}

    public static final class Stats {
        private final double mean;
        private final double ciLow;
        private final double ciHigh;
        private final int requiredSampleSize;
        private final int actualSampleSize;

        public Stats(double mean,
                     double ciLow,
                     double ciHigh,
                     int requiredSampleSize,
                     int actualSampleSize) {
            this.mean = mean;
            this.ciLow = ciLow;
            this.ciHigh = ciHigh;
            this.requiredSampleSize = requiredSampleSize;
            this.actualSampleSize = actualSampleSize;
        }

        public double getMean()              { return mean; }
        public double getCiLow()            { return ciLow; }
        public double getCiHigh()           { return ciHigh; }
        public int getRequiredSampleSize()  { return requiredSampleSize; }
        public int getActualSampleSize()    { return actualSampleSize; }
    }

    public static Stats compute(double[] sample,
                                boolean removeOutliers,
                                double tScore,
                                double relativeError) {

        List<Double> data = Arrays.stream(sample)
                .boxed()
                .collect(Collectors.toList());

        if (removeOutliers) {
            data = removeOutliersIqr(data);
        }

        int n = data.size();
        if (n == 0) {
            return new Stats(0.0, 0.0, 0.0, 0, 0);
        }

        double mean = mean(data);
        double std = std(data);

        double margin = tScore * std / Math.sqrt(n);
        double ciLow = mean - margin;
        double ciHigh = mean + margin;

        double E = relativeError * Math.abs(mean);
        int requiredN = 0;
        if (E > 0.0 && std > 0.0) {
            requiredN = (int) Math.ceil(Math.pow((tScore * std) / E, 2));
        }

        return new Stats(mean, ciLow, ciHigh, requiredN, n);
    }

    private static double mean(List<Double> data) {
        return data.stream().mapToDouble(d -> d).average().orElse(0.0);
    }

    private static double std(List<Double> data) {
        double m = mean(data);
        double sum = 0.0;
        for (double v : data) {
            double d = v - m;
            sum += d * d;
        }
        return Math.sqrt(sum / (data.size() - 1));
    }

    private static List<Double> removeOutliersIqr(List<Double> data) {
        if (data.size() < 4) {
            return new ArrayList<>(data);
        }
        List<Double> sorted = new ArrayList<>(data);
        Collections.sort(sorted);
        int n = sorted.size();
        double q1 = sorted.get(n / 4);
        double q3 = sorted.get(3 * n / 4);
        double iqr = q3 - q1;
        double lower = q1 - 1.5 * iqr;
        double upper = q3 + 1.5 * iqr;
        return sorted.stream()
                .filter(d -> d >= lower && d <= upper)
                .collect(Collectors.toList());
    }
}
