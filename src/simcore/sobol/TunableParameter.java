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

    public SobolFactor toSobolFactor() {
        return new SobolFactor(
                name,
                min,
                max,
                (base, value) -> {
                    // копия через builder.from(base) -> применяем параметр -> build
                    SystemParametersBuilder b = SystemParametersBuilder.from(base);
                    applier.apply(b, value);
                    return b.build();
                }
        );
    }
}
