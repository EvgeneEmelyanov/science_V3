package simcore.sobol;

import simcore.config.SystemParameters;

import java.util.Objects;
import java.util.function.BiFunction;

public final class SobolFactor {

    private final String name;
    private final double min;
    private final double max;

    private final BiFunction<SystemParameters, Double, SystemParameters> applier;

    public SobolFactor(String name,
                       double min,
                       double max,
                       BiFunction<SystemParameters, Double, SystemParameters> applier) {
        if (max < min) throw new IllegalArgumentException("max < min for " + name);
        this.name = Objects.requireNonNull(name);
        this.min = min;
        this.max = max;
        this.applier = Objects.requireNonNull(applier);
    }

    public String getName() { return name; }
    public double getMin() { return min; }
    public double getMax() { return max; }

    public SystemParameters apply(SystemParameters base, double v) {
        return applier.apply(base, v);
    }

    /** Перевод из [0..1] в [min..max]. */
    public double scaleFromUnit(double u) {
        if (u < 0.0) u = 0.0;
        if (u > 1.0) u = 1.0;
        return min + u * (max - min);
    }
}
