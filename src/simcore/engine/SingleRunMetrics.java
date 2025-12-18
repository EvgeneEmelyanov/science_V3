package simcore.engine;

import java.util.List;

/**
 * Результаты одного прогона (single-run), которые полезны для отладки:
 * - trace по часам (если включён сбор)
 */
public final class SingleRunMetrics {

    public final List<SimulationStepRecord> trace;

    public SingleRunMetrics(List<SimulationStepRecord> trace) {
        this.trace = trace;
    }
}
