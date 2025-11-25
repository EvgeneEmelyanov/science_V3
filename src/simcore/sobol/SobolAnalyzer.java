package simcore.sobol;

import simcore.config.SimulationConfig;
import simcore.config.SystemParameters;
import simcore.config.SystemParametersBuilder;
import simcore.engine.SimulationEngine;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * Анализ чувствительности по методу Соболя (схема Saltelli/Jansen).
 *
 * Для d параметров и числа выборок N выполняется:
 *  - 2N + 2Nd запусков модели.
 */
public class SobolAnalyzer {

    private final SimulationConfig baseConfig;
    private final SystemParameters baseParams;
    private final double[] totalLoadKw;
    private final List<SobolParameter> parameters;
    private final SimulationMetric metric;

    public SobolAnalyzer(SimulationConfig baseConfig,
                         SystemParameters baseParams,
                         double[] totalLoadKw,
                         List<SobolParameter> parameters,
                         SimulationMetric metric) {
        if (parameters == null || parameters.isEmpty()) {
            throw new IllegalArgumentException("Список параметров для Соболя пуст");
        }
        if (totalLoadKw == null || totalLoadKw.length == 0) {
            throw new IllegalArgumentException("Профиль нагрузки пуст");
        }
        this.baseConfig = baseConfig;
        this.baseParams = baseParams;
        this.totalLoadKw = totalLoadKw;
        this.parameters = List.copyOf(parameters);
        this.metric = metric;
    }

    /**
     * Выполнить анализ чувствительности при заданном числе базовых выборок N.
     *
     * @param sampleCount число строк в матрицах A и B (N), N > 0
     */
    public SobolResult analyze(int sampleCount)
            throws ExecutionException, InterruptedException, IOException {

        if (sampleCount <= 0) {
            throw new IllegalArgumentException("sampleCount must be > 0");
        }

        int d = parameters.size();
        int N = sampleCount;

        // Матрицы A и B: N x d
        double[][] A = new double[N][d];
        double[][] B = new double[N][d];

        SobolSequence sobolA = new SobolSequence(d, 12345L);
        SobolSequence sobolB = new SobolSequence(d, 67890L);

        for (int i = 0; i < N; i++) {
            A[i] = sobolA.next();
            B[i] = sobolB.next();
        }

        double[] yA = new double[N];
        double[] yB = new double[N];
        double[][] yAB = new double[d][N]; // yAB[j][i] = f(A_B^(j)_i)

        // 1. Считаем f(A_i) и f(B_i)
        for (int i = 0; i < N; i++) {
            yA[i] = evaluateAt(A[i]);
            yB[i] = evaluateAt(B[i]);
        }

        // 2. Для каждого параметра j и каждой строки i считаем A_B^(j)_i
        for (int j = 0; j < d; j++) {
            for (int i = 0; i < N; i++) {
                double[] ABj = buildABRow(A[i], B[i], j);
                yAB[j][i] = evaluateAt(ABj);
            }
        }

        // 3. Оценка дисперсии Var(Y)
        double meanY = 0.0;
        for (int i = 0; i < N; i++) {
            meanY += yA[i] + yB[i];
        }
        meanY /= (2.0 * N);

        double varY = 0.0;
        for (int i = 0; i < N; i++) {
            double da = yA[i] - meanY;
            double db = yB[i] - meanY;
            varY += da * da + db * db;
        }
        varY /= (2.0 * N);

        if (varY <= 0.0) {
            throw new IllegalStateException("Дисперсия Var(Y) <= 0, индексы Соболя не определены");
        }

        double[] S = new double[d];   // первые индексы
        double[] ST = new double[d];  // тотальные индексы

        // Формулы:
        // S_j  ≈ (1/N) * Σ_i [ yB_i * (yAB_j_i - yA_i) ] / Var(Y)
        // ST_j ≈ (1/(2N)) * Σ_i [ (yA_i - yAB_j_i)^2 ] / Var(Y)
        for (int j = 0; j < d; j++) {
            double numFirst = 0.0;
            double numTotal = 0.0;
            for (int i = 0; i < N; i++) {
                double yAi = yA[i];
                double yBi = yB[i];
                double yABji = yAB[j][i];

                numFirst += yBi * (yABji - yAi);
                double diff = yAi - yABji;
                numTotal += diff * diff;
            }
            S[j] = numFirst / (N * varY);
            ST[j] = numTotal / (2.0 * N * varY);
        }

        return new SobolResult(parameters, S, ST);
    }

    /**
     * Построить строку A_B^(j):
     *  - все столбцы как в A,
     *  - но j-й столбец взят из B.
     */
    private double[] buildABRow(double[] rowA, double[] rowB, int j) {
        int d = rowA.length;
        double[] res = new double[d];
        System.arraycopy(rowA, 0, res, 0, d);
        res[j] = rowB[j];
        return res;
    }

    /**
     * Один запуск модели для набора нормированных параметров u[0..d-1].
     */
    private double evaluateAt(double[] u)
            throws ExecutionException, InterruptedException, IOException {

        SystemParametersBuilder builder = SystemParametersBuilder.from(baseParams);

        for (int j = 0; j < parameters.size(); j++) {
            SobolParameter p = parameters.get(j);
            p.applyFromUnit(builder, u[j]);
        }

        SystemParameters params = builder.build();

        SimulationEngine engine = new SimulationEngine(baseConfig, params, totalLoadKw);
        SimulationEngine.SimulationSummary summary = engine.runMonteCarlo();

        return metric.extract(summary);
    }
}
