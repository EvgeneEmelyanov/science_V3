package simcore.sobol;

import simcore.config.SystemParametersBuilder;

public final class TunableParameter {

    private final TunableParamId id;
    private final String name;
    private final double min;
    private final double max;
    private final SobolApplier applier;

    public TunableParameter(TunableParamId id,
                            String name,
                            double min,
                            double max,
                            SobolApplier applier) {
        if (max < min) throw new IllegalArgumentException("max < min for " + id);
        this.id = id;
        this.name = name;
        this.min = min;
        this.max = max;
        this.applier = applier;
    }

    public TunableParamId getId() { return id; }
    public String getName() { return name; }
    public double getMin() { return min; }
    public double getMax() { return max; }
    public SobolApplier getApplier() { return applier; }

    /** Масштабирование u in [0..1] в значение параметра в диапазоне [min..max]. */
    public double scaleFromUnit(double u01) {
        if (u01 <= 0.0) return min;
        if (u01 >= 1.0) return max;
        return min + u01 * (max - min);
    }

    public SobolFactor toSobolFactor() {
        return new SobolFactor(
                id,
                name,
                min,
                max,
                (base, value) -> {
                    SystemParametersBuilder b = SystemParametersBuilder.from(base);
                    applier.apply(b, value);
                    return b.build();
                }
        );
    }
}
