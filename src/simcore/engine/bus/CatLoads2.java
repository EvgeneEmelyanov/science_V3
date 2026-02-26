package simcore.engine.bus;

/**
 * Per-bus load decomposition into reliability categories for the CURRENT hour.
 * Values are in kW (treated as kWh for 1-hour step by the simulator).
 *
 * p1[b] = Cat I load on bus b
 * p2[b] = Cat II load on bus b
 * p3[b] = Cat III load on bus b
 */
public final class CatLoads2 {

    public final double[] p1 = new double[2];
    public final double[] p2 = new double[2];
    public final double[] p3 = new double[2];

    public double total(int b) {
        return p1[b] + p2[b] + p3[b];
    }
}
