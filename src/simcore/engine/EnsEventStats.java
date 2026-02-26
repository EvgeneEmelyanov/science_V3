package simcore.engine;

import simcore.config.SimulationConstants;

/**
 * Compact ENS event statistics.
 *
 * ENS "event" = maximal consecutive run of hours with ENS(t) > EPS.
 * We aggregate only counts in duration buckets (fixed size).
 *
 * Buckets (hours):
 *  B0: <1h ("start-only" ENS inside one hour)
 *  B1: 1
 *  B2: 2..4
 *  B3: 5..12
 *  B4: 13..24
 *  B5: >24
 */
public final class EnsEventStats {

    public static final int BUCKETS = 6;

    private final long[] bucketCounts = new long[BUCKETS];

    private long eventsTotal;
    private long eventsStartOnly;
    private long maxRunHours;
    private long lolHours;

    // In-progress state
    private int currentRunHours;
    private boolean currentRunStartOnly;

    /**
     * Update stats with ENS for the current hour.
     *
     * @param ensKwhThisHour total ENS in this hour (kWh)
     * @param startEnsKwhThisHour ENS attributed to DG start delay in this hour (kWh)
     */
    public void updateHour(double ensKwhThisHour, double startEnsKwhThisHour) {
        boolean hasEns = ensKwhThisHour > SimulationConstants.EPSILON;

        if (hasEns) {
            lolHours++;
            currentRunHours++;

            // Decide whether this run is a pure "start-only" run:
            // it must start now (first hour), have start ENS, and have no rest ENS.
            if (currentRunHours == 1) {
                double rest = ensKwhThisHour - startEnsKwhThisHour;
                currentRunStartOnly = startEnsKwhThisHour > SimulationConstants.EPSILON
                        && rest <= SimulationConstants.EPSILON;
            } else {
                // If the event lasts more than 1 hour, it's not start-only.
                currentRunStartOnly = false;
            }
            return;
        }

        // No ENS this hour -> if we were inside a run, close the event.
        closeCurrentRunIfAny();
    }

    /** Call at the end of simulation horizon to close a trailing ENS run. */
    public void finish() {
        closeCurrentRunIfAny();
    }

    private void closeCurrentRunIfAny() {
        if (currentRunHours <= 0) return;

        eventsTotal++;
        if (currentRunHours > maxRunHours) maxRunHours = currentRunHours;

        if (currentRunHours == 1 && currentRunStartOnly) {
            eventsStartOnly++;
            bucketCounts[0]++;
        } else {
            bucketCounts[bucketForHours(currentRunHours)]++;
        }

        currentRunHours = 0;
        currentRunStartOnly = false;
    }

    private static int bucketForHours(int h) {
        if (h <= 1) return 1;
        if (h <= 4) return 2;
        if (h <= 12) return 3;
        if (h <= 24) return 4;
        return 5;
    }

    public long getEventsTotal() {
        return eventsTotal;
    }

    public long getEventsStartOnly() {
        return eventsStartOnly;
    }

    public long getMaxRunHours() {
        return maxRunHours;
    }

    /** LOLE in hours for this run: number of hours with ENS(t) > EPS. */
    public long getLolHours() {
        return lolHours;
    }

    /** Returns a copy of bucket counts array (length = BUCKETS). */
    public long[] getBucketCounts() {
        return bucketCounts.clone();
    }
}
