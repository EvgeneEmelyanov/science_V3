package simcore.sobol;

import simcore.config.SystemParametersBuilder;

/**
 * Описание изменяемого параметра в "каталоге":
 *  - id (enum),
 *  - имя (для отображения),
 *  - диапазон [min, max],
 *  - функция, которая применяет значение к SystemParametersBuilder.
 */
public class TunableParameter {

    private final TunableParamId id;
    private final String displayName;
    private final double min;
    private final double max;
    private final SobolApplier applier;

    public TunableParameter(TunableParamId id,
                            String displayName,
                            double min,
                            double max,
                            SobolApplier applier) {
        if (max < min) {
            throw new IllegalArgumentException("max < min для параметра " + id);
        }
        this.id = id;
        this.displayName = displayName;
        this.min = min;
        this.max = max;
        this.applier = applier;
    }

    public TunableParamId getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
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
     * Преобразовать в SobolParameter для конкретного эксперимента.
     * Здесь в качестве name можно использовать displayName или id.name().
     */
    public SobolParameter toSobolParameter() {
        return new SobolParameter(
                displayName,
                min,
                max,
                applier
        );
    }
}
