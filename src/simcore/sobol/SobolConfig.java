package simcore.sobol;

import simcore.config.SystemParameters;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;

public final class SobolConfig {

    private final int sobolN;
    private final int mcIterations;
    private final long mcBaseSeed;
    private final int threads;

    private final List<SobolFactor> factors;

    private final UnaryOperator<SystemParameters> parametersCopier;

    public SobolConfig(int sobolN,
                       int mcIterations,
                       long mcBaseSeed,
                       int threads,
                       List<SobolFactor> factors,
                       UnaryOperator<SystemParameters> parametersCopier) {
        if (sobolN <= 0) throw new IllegalArgumentException("sobolN must be > 0");
        if (mcIterations <= 0) throw new IllegalArgumentException("mcIterations must be > 0");
        if (threads <= 0) throw new IllegalArgumentException("threads must be > 0");
        this.sobolN = sobolN;
        this.mcIterations = mcIterations;
        this.mcBaseSeed = mcBaseSeed;
        this.threads = threads;
        this.factors = new ArrayList<>(Objects.requireNonNull(factors));
        this.parametersCopier = Objects.requireNonNull(parametersCopier);
        if (this.factors.isEmpty()) throw new IllegalArgumentException("factors must not be empty");
    }

    public int getSobolN() {
        return sobolN;
    }

    public int getMcIterations() {
        return mcIterations;
    }

    public long getMcBaseSeed() {
        return mcBaseSeed;
    }

    public int getThreads() {
        return threads;
    }

    public List<SobolFactor> getFactors() {
        return Collections.unmodifiableList(factors);
    }

    public int dim() {
        return factors.size();
    }

    public SystemParameters copyParameters(SystemParameters base) {
        return parametersCopier.apply(base);
    }
}
