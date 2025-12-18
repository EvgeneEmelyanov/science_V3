package simcore.sobol;

import simcore.config.SystemParameters;

import java.util.Objects;
import java.util.function.BiFunction;

/**
 * Универсальная реализация фактора:
 * name, [min,max], и функция apply(base, value) -> newParams.
 */
public final class SimpleSobolFactor implements SobolFactor {

    private final String name;
    private final double min;
    private final double max;
    private final BiFunction<SystemParameters, Double, SystemParameters> applier;

    public SimpleSobolFactor(String name,
                             double min,
                             double max,
                             BiFunction<SystemParameters, Double, SystemParameters> applier) {
        this.name = Objects.requireNonNull(name);
        this.min = min;
        this.max = max;
        this.applier = Objects.requireNonNull(applier);
        if (!(max > min)) {
            throw new IllegalArgumentException("max must be > min for factor: " + name);
        }
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public double getMin() {
        return min;
    }

    @Override
    public double getMax() {
        return max;
    }

    @Override
    public SystemParameters apply(SystemParameters base, double value) {
        return applier.apply(base, value);
    }
}
