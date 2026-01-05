package simcore.engine;

import java.util.Arrays;

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

        double[] data = Arrays.copyOf(sample, sample.length);
        int n = data.length;

        if (removeOutliers && n >= 4) {
            Arrays.sort(data);

            double q1 = data[n / 4];
            double q3 = data[(3 * n) / 4];
            double iqr = q3 - q1;
            double lower = q1 - 1.5 * iqr;
            double upper = q3 + 1.5 * iqr;

            int filteredSize = 0;
            for (int i = 0; i < n; i++) {
                double v = data[i];
                if (v >= lower && v <= upper) {
                    data[filteredSize++] = v;
                }
            }

            if (filteredSize != n) {
                data = Arrays.copyOf(data, filteredSize);
                n = filteredSize;
            }
        } else if (removeOutliers) {
            data = Arrays.copyOf(data, n);
        }

        if (n == 0) {
            return new Stats(0.0, 0.0, 0.0, 0, 0);
        }

        double mean = 0.0;
        double m2 = 0.0;
        int count = 0;
        for (int i = 0; i < n; i++) {
            double x = data[i];
            count++;
            double delta = x - mean;
            mean += delta / count;
            double delta2 = x - mean;
            m2 += delta * delta2;
        }
        double variance = (count > 1) ? (m2 / (count - 1)) : 0.0;
        double std = Math.sqrt(variance);

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
}
