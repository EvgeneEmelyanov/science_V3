package simcore.sobol;

import simcore.config.SystemParameters;
import simcore.config.SystemParametersBuilder;

import java.util.*;
import java.util.function.UnaryOperator;

public final class SobolConfig {

    private final int sobolN;
    private final int mcIterations;
    private final long mcBaseSeed;
    private final int threads;

    private final List<SobolFactor> factors;
    private final UnaryOperator<SystemParameters> parametersCopier;

    private SobolConfig(int sobolN,
                        int mcIterations,
                        long mcBaseSeed,
                        int threads,
                        List<SobolFactor> factors,
                        UnaryOperator<SystemParameters> parametersCopier) {
        if (sobolN <= 0) throw new IllegalArgumentException("sobolN must be > 0");
        if (mcIterations <= 0) throw new IllegalArgumentException("mcIterations must be > 0");
        if (threads <= 0) throw new IllegalArgumentException("threads must be > 0");
        Objects.requireNonNull(factors, "factors");
        Objects.requireNonNull(parametersCopier, "parametersCopier");
        if (factors.isEmpty()) throw new IllegalArgumentException("factors must not be empty");

        this.sobolN = sobolN;
        this.mcIterations = mcIterations;
        this.mcBaseSeed = mcBaseSeed;
        this.threads = threads;
        this.factors = new ArrayList<>(factors);
        this.parametersCopier = parametersCopier;
    }

    /** Основной фабричный метод: выбираете только ids, диапазоны берём из пула. */
    public static SobolConfig fromIds(int sobolN,
                                      int mcIterations,
                                      long mcBaseSeed,
                                      int threads,
                                      List<TunableParamId> ids) {

        Objects.requireNonNull(ids, "ids");
        if (ids.isEmpty()) throw new IllegalArgumentException("ids must not be empty");

        List<SobolFactor> factors = TunableParameterPool.toSobolFactors(ids);

        // Надёжный copier: через SystemParametersBuilder.from(base)
        UnaryOperator<SystemParameters> copier = p -> SystemParametersBuilder.from(p).build();

        return new SobolConfig(
                sobolN,
                mcIterations,
                mcBaseSeed,
                threads,
                factors,
                copier
        );
    }

    /** Если вам нужен кастомный copier — оставляем второй фабричный метод. */
    public static SobolConfig fromIds(int sobolN,
                                      int mcIterations,
                                      long mcBaseSeed,
                                      int threads,
                                      List<TunableParamId> ids,
                                      UnaryOperator<SystemParameters> parametersCopier) {

        Objects.requireNonNull(ids, "ids");
        Objects.requireNonNull(parametersCopier, "parametersCopier");
        if (ids.isEmpty()) throw new IllegalArgumentException("ids must not be empty");

        List<SobolFactor> factors = TunableParameterPool.toSobolFactors(ids);

        return new SobolConfig(
                sobolN,
                mcIterations,
                mcBaseSeed,
                threads,
                factors,
                parametersCopier
        );
    }

    public int getSobolN() { return sobolN; }
    public int getMcIterations() { return mcIterations; }
    public long getMcBaseSeed() { return mcBaseSeed; }
    public int getThreads() { return threads; }

    public List<SobolFactor> getFactors() { return Collections.unmodifiableList(factors); }
    public int dim() { return factors.size(); }

    public SystemParameters copyParameters(SystemParameters base) {
        return parametersCopier.apply(base);
    }
}
