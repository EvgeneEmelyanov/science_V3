package simcore.sobol;

import simcore.engine.MonteCarloEstimate;

import java.util.List;

/**
 * Результаты Соболя для 3 метрик: ENS, Fuel, Moto.
 *
 * Внутри также храним сырые MC-оценки по точкам (A,B,AB_j),
 * чтобы можно было сохранить их в CSV.
 */
public final class SobolResult {

    public final SobolConfig config;

    // Сырые оценки по точкам
    public final List<MonteCarloEstimate> yA;
    public final List<MonteCarloEstimate> yB;
    public final List<List<MonteCarloEstimate>> yAB;

    // Индексы Соболя (первого и полного порядка)
    public final double[] S_ens;
    public final double[] ST_ens;

    public final double[] S_fuel;
    public final double[] ST_fuel;

    public final double[] S_moto;
    public final double[] ST_moto;

    public SobolResult(SobolConfig config,
                       List<MonteCarloEstimate> yA,
                       List<MonteCarloEstimate> yB,
                       List<List<MonteCarloEstimate>> yAB,
                       double[] sEns, double[] stEns,
                       double[] sFuel, double[] stFuel,
                       double[] sMoto, double[] stMoto) {
        this.config = config;
        this.yA = yA;
        this.yB = yB;
        this.yAB = yAB;

        this.S_ens = sEns;
        this.ST_ens = stEns;

        this.S_fuel = sFuel;
        this.ST_fuel = stFuel;

        this.S_moto = sMoto;
        this.ST_moto = stMoto;
    }
}
