package simcore.sobol;

import simcore.config.SystemParametersBuilder;

@FunctionalInterface
public interface SobolApplier {
    void apply(SystemParametersBuilder b, double v);
}
