package simcore.sobol;

import java.util.Arrays;
import java.util.List;

/**
 * Результаты анализа Соболя:
 *  - список параметров (в том же порядке, что и индексы),
 *  - первые индексы S_j,
 *  - тотальные индексы ST_j.
 */
public class SobolResult {

    private final List<SobolParameter> parameters;
    private final double[] firstOrderIndices;
    private final double[] totalOrderIndices;

    public SobolResult(List<SobolParameter> parameters,
                       double[] firstOrderIndices,
                       double[] totalOrderIndices) {
        if (parameters.size() != firstOrderIndices.length
                || parameters.size() != totalOrderIndices.length) {
            throw new IllegalArgumentException("Размеры параметров и массивов индексов не совпадают");
        }
        this.parameters = List.copyOf(parameters);
        this.firstOrderIndices = firstOrderIndices.clone();
        this.totalOrderIndices = totalOrderIndices.clone();
    }

    public List<SobolParameter> getParameters() {
        return parameters;
    }

    public double[] getFirstOrderIndices() {
        return firstOrderIndices.clone();
    }

    public double[] getTotalOrderIndices() {
        return totalOrderIndices.clone();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("SobolResult:\n");
        for (int i = 0; i < parameters.size(); i++) {
            sb.append(String.format("%-30s S = %8.4f ST = %8.4f%n",
                    parameters.get(i).getName(),
                    firstOrderIndices[i],
                    totalOrderIndices[i]));
        }
        return sb.toString();
    }
}
