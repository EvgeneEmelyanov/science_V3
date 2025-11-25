package simcore.sobol;

import java.util.Random;

/**
 * Заглушка для последовательности Соболя.
 *
 * Сейчас это просто равномерный псевдослучайный генератор U(0,1)
 * в d-мерном пространстве. При желании можно заменить на
 * настоящую Sobol sequence (или взять готовую либу).
 */
public class SobolSequence {

    private final int dimension;
    private final Random rnd;

    /**
     * @param dimension размерность пространства (d)
     * @param seed      зерно генератора (для повторяемости)
     */
    public SobolSequence(int dimension, long seed) {
        if (dimension <= 0) {
            throw new IllegalArgumentException("dimension must be > 0");
        }
        this.dimension = dimension;
        this.rnd = new Random(seed);
    }

    /**
     * @return массив длины d с координатами в [0;1)
     */
    public double[] next() {
        double[] x = new double[dimension];
        for (int i = 0; i < dimension; i++) {
            x[i] = rnd.nextDouble();
        }
        return x;
    }
}
