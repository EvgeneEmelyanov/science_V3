package simcore.sobol;

import simcore.engine.SimulationEngine;

/**
 * Функциональный интерфейс для извлечения метрики из результата симуляции.
 * Пример: summary -> summary.getAverageDeficit()
 */
@FunctionalInterface
public interface SimulationMetric {

    /**
     * @param summary сводка по результатам Monte Carlo
     * @return числовое значение метрики (например, средний дефицит)
     */
    double extract(SimulationEngine.SimulationSummary summary);
}
