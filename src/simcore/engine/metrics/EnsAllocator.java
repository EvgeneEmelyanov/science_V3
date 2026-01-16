package simcore.engine.metrics;

import simcore.engine.Totals;
import simcore.config.SimulationConstants;

/**
 * Разложение недоотпуска (ENS) по категориям надёжности.
 * Вынесено из SingleRunSimulator.
 */
public final class EnsAllocator {

    private EnsAllocator() {}

    public static void addEnsByCategory(Totals totals, double loadKw, double ensKw, double cat1, double cat2) {
        // Алгоритм 1:1 с прежним SingleRunSimulator
        if (ensKw <= SimulationConstants.EPSILON) return;

        double ens1 = 0.0;
        double ens2 = 0.0;

        double p1 = loadKw * cat1;
        double p2 = loadKw * cat2;

        if (ensKw > SimulationConstants.EPSILON) {
            if (ensKw <= p1) {
                ens1 = ensKw;
            } else {
                ens1 = p1;
                double rest = ensKw - p1;
                ens2 = Math.min(rest, p2);
            }
        }

        totals.ensCat1Kwh += ens1;
        totals.ensCat2Kwh += ens2;
    }

    public static void addEnsByCategoryProportional(Totals totals, double loadKw, double ensKwh, double cat1, double cat2) {
        // Алгоритм 1:1 с прежним SingleRunSimulator
        if (ensKwh <= SimulationConstants.EPSILON) return;

        double cat3 = Math.max(0.0, 1.0 - cat1 - cat2);

        double p1 = loadKw * cat1;
        double p2 = loadKw * cat2;
        double p3 = loadKw * cat3;

        double sum = p1 + p2 + p3;
        if (sum <= SimulationConstants.EPSILON) return;

        double ens1 = ensKwh * (p1 / sum);
        double ens2 = ensKwh * (p2 / sum);

        totals.ensCat1Kwh += ens1;
        totals.ensCat2Kwh += ens2;
    }
}
