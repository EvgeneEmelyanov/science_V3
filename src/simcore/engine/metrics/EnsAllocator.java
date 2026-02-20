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
        if (ensKw <= SimulationConstants.EPSILON) return;

        // Приоритет отключения: III -> II -> I.
        // В totals храним только ENS по I и II, поэтому III идет "по остаточному принципу".
        double p1 = loadKw * cat1;
        double p2 = loadKw * cat2;
        double p3 = Math.max(0.0, loadKw - p1 - p2);

        double ens3 = Math.min(ensKw, p3);
        double rest = Math.max(0.0, ensKw - ens3);

        double ens2 = Math.min(rest, p2);
        rest = Math.max(0.0, rest - ens2);

        double ens1 = Math.min(rest, p1);

        totals.ensCat1Kwh += ens1;
        totals.ensCat2Kwh += ens2;
    }

    public static void addEnsByCategoryProportional(Totals totals, double loadKw, double ensKwh, double cat1, double cat2) {
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

    /**
     * ENS allocation with priority of shedding: Cat III -> Cat II -> Cat I.
     * (Used for UFLS-like mechanisms and blackout where categories are disconnected in priority order.)
     */
    public static void addEnsByCategoryPriority321(Totals totals, double loadKw, double ensKw, double cat1, double cat2) {
        if (ensKw <= SimulationConstants.EPSILON) return;
        if (loadKw <= SimulationConstants.EPSILON) return;

        double p1 = loadKw * cat1;
        double p2 = loadKw * cat2;
        double p3 = Math.max(0.0, loadKw - p1 - p2);

        double ens3 = Math.min(ensKw, p3);
        double restAfter3 = Math.max(0.0, ensKw - ens3);

        double ens2 = Math.min(restAfter3, p2);
        double restAfter2 = Math.max(0.0, restAfter3 - ens2);

        double ens1 = Math.min(restAfter2, p1);

        totals.ensCat1Kwh += ens1;
        totals.ensCat2Kwh += ens2;
    }
}
