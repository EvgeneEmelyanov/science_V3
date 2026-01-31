package simcore.sobol;

/**
 * Minimal Sobol output used by SobolResultPrinter.
 *
 * We keep the historical getter names (snake_case) for compatibility.
 */
public final class SobolResult {

    private final SobolConfig cfg;

    private final double[] s_ens;
    private final double[] st_ens;

    private final double[] s_fuel;
    private final double[] st_fuel;

    private final double[] s_moto;
    private final double[] st_moto;

    private final double[] s_lcoe;
    private final double[] st_lcoe;

    // pooled statistics over Sobol sample means (A ∪ B)
    private final double var_ens;
    private final double var_fuel;
    private final double var_moto;
    private final double var_lcoe;

    private final double min_ens;
    private final double max_ens;
    private final double min_fuel;
    private final double max_fuel;
    private final double min_moto;
    private final double max_moto;
    private final double min_lcoe;
    private final double max_lcoe;

    public SobolResult(
            SobolConfig cfg,
            double[] sEns, double[] stEns,
            double[] sFuel, double[] stFuel,
            double[] sMoto, double[] stMoto,
            double[] sLcoe, double[] stLcoe,
            double varEns, double varFuel, double varMoto, double varLcoe,
            double minEns, double maxEns,
            double minFuel, double maxFuel,
            double minMoto, double maxMoto,
            double minLcoe, double maxLcoe) {
        this.cfg = cfg;
        this.s_ens = sEns;
        this.st_ens = stEns;
        this.s_fuel = sFuel;
        this.st_fuel = stFuel;
        this.s_moto = sMoto;
        this.st_moto = stMoto;
        this.s_lcoe = sLcoe;
        this.st_lcoe = stLcoe;

        this.var_ens = varEns;
        this.var_fuel = varFuel;
        this.var_moto = varMoto;
        this.var_lcoe = varLcoe;

        this.min_ens = minEns;
        this.max_ens = maxEns;
        this.min_fuel = minFuel;
        this.max_fuel = maxFuel;
        this.min_moto = minMoto;
        this.max_moto = maxMoto;
        this.min_lcoe = minLcoe;
        this.max_lcoe = maxLcoe;
    }

    public SobolConfig getCfg() { return cfg; }

    public double[] getS_ens() { return s_ens; }
    public double[] getSt_ens() { return st_ens; }

    public double[] getS_fuel() { return s_fuel; }
    public double[] getSt_fuel() { return st_fuel; }

    public double[] getS_moto() { return s_moto; }
    public double[] getSt_moto() { return st_moto; }

    public double[] getS_lcoe() { return s_lcoe; }
    public double[] getSt_lcoe() { return st_lcoe; }

    public double getVar_ens() { return var_ens; }
    public double getVar_fuel() { return var_fuel; }
    public double getVar_moto() { return var_moto; }
    public double getVar_lcoe() { return var_lcoe; }

    public double getMin_ens() { return min_ens; }
    public double getMax_ens() { return max_ens; }
    public double getMin_fuel() { return min_fuel; }
    public double getMax_fuel() { return max_fuel; }
    public double getMin_moto() { return min_moto; }
    public double getMax_moto() { return max_moto; }
    public double getMin_lcoe() { return min_lcoe; }
    public double getMax_lcoe() { return max_lcoe; }
}
