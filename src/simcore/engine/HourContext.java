package simcore.engine;

import simcore.config.SystemParameters;
import simcore.engine.trace.TraceSession;

final class HourContext {

    final SystemParameters sp;
    final double windV;

    final boolean considerDegradation;
    final boolean reserveThirdCategory;
    final boolean considerRotationReserve;

    final double cat1;
    final double cat2;

    final double dgRatedKw;
    final double dgMaxKw;
    final double dgMinKw;
    final double perDgOptimalKw;
    final double dgStartDelayHours;

    final Totals totals;
    /** A single-element array used as a mutable accumulator for WRE per-hour in tracing paths. */
    final double[] hourWreRef;
    final TraceSession trace;

    final StatusCollector status;

    static final class StatusCollector {
        // Higher value => higher priority.
        static final int PRI_NORMAL = 0;
        static final int PRI_FAILURE = 30;
        static final int PRI_UFLS = 60;
        static final int PRI_TRANSFER = 70;
        static final int PRI_PARTIAL_BLACKOUT = 80;
        static final int PRI_BLACKOUT = 90;

        private int pri = PRI_NORMAL;
        private String text = "NORMAL";

        void set(int priority, String value) {
            if (value == null || value.isEmpty()) return;
            if (priority > pri) {
                pri = priority;
                text = value;
            } else if (priority == pri && text != null && !text.isEmpty() && !text.equals(value)) {
                // Keep short: append only if different and same priority.
                text = text + "; " + value;
            }
        }

        String get() {
            return (text == null) ? "" : text;
        }
    }

    HourContext(
            SystemParameters sp,
            double windV,
            boolean considerDegradation,
            boolean reserveThirdCategory,
            boolean considerRotationReserve,
            double cat1,
            double cat2,
            double dgRatedKw,
            double dgMaxKw,
            double dgMinKw,
            double perDgOptimalKw,
            double dgStartDelayHours,
            Totals totals,
            double[] hourWreRef,
            TraceSession trace
    ) {
        this.sp = sp;
        this.windV = windV;
        this.considerDegradation = considerDegradation;
        this.reserveThirdCategory = reserveThirdCategory;
        this.considerRotationReserve = considerRotationReserve;
        this.cat1 = cat1;
        this.cat2 = cat2;
        this.dgRatedKw = dgRatedKw;
        this.dgMaxKw = dgMaxKw;
        this.dgMinKw = dgMinKw;
        this.perDgOptimalKw = perDgOptimalKw;
        this.dgStartDelayHours = dgStartDelayHours;
        this.totals = totals;
        this.hourWreRef = hourWreRef;
        this.trace = trace;
        this.status = new StatusCollector();
    }
}
