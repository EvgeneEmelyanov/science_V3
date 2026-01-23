package simcore.economy;

import java.util.Arrays;

/**
 * Technical "cost drivers" needed to recompute discounted LCOE without rerunning the simulator.
 * All arrays are per-year over the modeled horizon.
 */
public final class EconomyDrivers {

    public final double[] servedKwhByYear;
    public final double[] fuelLitersByYear;
    public final double[] motoHoursByYear;
    public final long[] btReplByYear;

    public final double dgTotalKw;
    public final double wtTotalKw;
    public final double btTotalKwh;

    public final double discountRatePerYear;

    public EconomyDrivers(double[] servedKwhByYear,
                          double[] fuelLitersByYear,
                          double[] motoHoursByYear,
                          long[] btReplByYear,
                          double dgTotalKw,
                          double wtTotalKw,
                          double btTotalKwh,
                          double discountRatePerYear) {
        this.servedKwhByYear = servedKwhByYear;
        this.fuelLitersByYear = fuelLitersByYear;
        this.motoHoursByYear = motoHoursByYear;
        this.btReplByYear = btReplByYear;
        this.dgTotalKw = dgTotalKw;
        this.wtTotalKw = wtTotalKw;
        this.btTotalKwh = btTotalKwh;
        this.discountRatePerYear = discountRatePerYear;
    }

    public int years() {
        return servedKwhByYear.length;
    }

    public EconomyDrivers copy() {
        return new EconomyDrivers(
                Arrays.copyOf(servedKwhByYear, servedKwhByYear.length),
                Arrays.copyOf(fuelLitersByYear, fuelLitersByYear.length),
                Arrays.copyOf(motoHoursByYear, motoHoursByYear.length),
                Arrays.copyOf(btReplByYear, btReplByYear.length),
                dgTotalKw, wtTotalKw, btTotalKwh, discountRatePerYear
        );
    }
}
