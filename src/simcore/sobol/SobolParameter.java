package simcore.sobol;

import simcore.config.SystemParametersBuilder;

/**
 * Параметр, который участвует в анализе Соболя.
 *
 * Хранит:
 *  - name      — имя (для вывода),
 *  - [min,max] — диапазон изменения,
 *  - applier   — как применить значение к SystemParametersBuilder.
 *
 * На вход от метода Соболя приходит u ∈ [0;1],
 * здесь это превращается в v = min + u * (max - min), а затем applier.apply(..., v).
 */
public class SobolParameter {

    private final String name;
    private final double min;
    private final double max;
    private final SobolApplier applier;

    public SobolParameter(String name,
                          double min,
                          double max,
                          SobolApplier applier) {
        if (max < min) {
            throw new IllegalArgumentException("max < min для SobolParameter: " + name);
        }
        this.name = name;
        this.min = min;
        this.max = max;
        this.applier = applier;
    }

    public String getName() {
        return name;
    }

    public double getMin() {
        return min;
    }

    public double getMax() {
        return max;
    }

    public SobolApplier getApplier() {
        return applier;
    }

    /**
     * Применить нормированное значение u ∈ [0;1] к builder'у.
     *
     * @param builder билдер параметров системы
     * @param u       нормированное значение [0;1]
     */
    public void applyFromUnit(SystemParametersBuilder builder, double u) {
        // на всякий случай подрежем
        double uu = u;
        if (uu < 0.0) uu = 0.0;
        if (uu > 1.0) uu = 1.0;

        double value = min + uu * (max - min);
        applier.apply(builder, value);
    }
}
