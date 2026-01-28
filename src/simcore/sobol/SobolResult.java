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

    public SobolResult(
            SobolConfig cfg,
            double[] sEns, double[] stEns,
            double[] sFuel, double[] stFuel,
            double[] sMoto, double[] stMoto,
            double[] sLcoe, double[] stLcoe) {
        this.cfg = cfg;
        this.s_ens = sEns;
        this.st_ens = stEns;
        this.s_fuel = sFuel;
        this.st_fuel = stFuel;
        this.s_moto = sMoto;
        this.st_moto = stMoto;
        this.s_lcoe = sLcoe;
        this.st_lcoe = stLcoe;
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
}
