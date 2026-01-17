package simcore.engine;

import simcore.config.SystemParameters;
import simcore.engine.trace.TraceSession;

/**
 * Immutable context for one simulation hour.
 *
 * The purpose is to reduce huge argument lists and prevent accidental parameter mixups.
 * This class is deliberately a plain data holder; it must not contain logic.
 */
final class HourContext {

    final SystemParameters sp;

    final double windV;

    final boolean considerDegradation;
    final boolean considerChargeByDg;
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

    HourContext(
            SystemParameters sp,
            double windV,
            boolean considerDegradation,
            boolean considerChargeByDg,
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
        this.considerChargeByDg = considerChargeByDg;
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
    }
}
