package simcore.sobol;

import simcore.config.SystemParametersBuilder;

/**
 * Функциональный интерфейс: применяет значение параметра к SystemParametersBuilder.
 *
 * Используется внутри TunableParameter / SobolParameter.
 */
@FunctionalInterface
public interface SobolApplier {

    /**
     * Применить значение v к builder'у (например, поменять частоту отказов ДГУ).
     *
     * @param builder билдер параметров системы
     * @param value   значение параметра (уже в реальных единицах, НE [0;1])
     */
    void apply(SystemParametersBuilder builder, double value);
}
