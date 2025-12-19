package simcore.sobol;

import simcore.engine.MonteCarloEstimate;

import java.util.List;

public final class SobolResult {

    public final SobolConfig config;

    public final List<MonteCarloEstimate> yA;
    public final List<MonteCarloEstimate> yB;
    public final List<List<MonteCarloEstimate>> yAB;

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

    public double[] getS_ens() { return S_ens; }
    public double[] getSt_ens() { return ST_ens; }

    public double[] getS_fuel() { return S_fuel; }
    public double[] getSt_fuel() { return ST_fuel; }

    public double[] getS_moto() { return S_moto; }
    public double[] getSt_moto() { return ST_moto; }
}
