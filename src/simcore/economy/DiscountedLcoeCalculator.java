package simcore.economy;

/**
 * Discounted LCOE calculator:
 *   LCOE = PV(cost) / PV(served energy)
 * served = load - ENS (served energy is provided by drivers)
 *
 * Intended for fast post-processing and Sobol sensitivity over unit costs.
 */
public final class DiscountedLcoeCalculator {

    private DiscountedLcoeCalculator() {}

    public static double computeRubPerKwh(EconomyDrivers d, UnitCosts c) {
        final int years = d.servedKwhByYear.length;
        if (years == 0) return 0.0;

        final double r = d.discountRatePerYear;
        final double eps = 1e-12;

        // CAPEX at t=0
        final double capexRub =
                c.costRuRub
                        + c.costDgRubPerKw * d.dgTotalKw
                        + c.costWtRubPerKw * d.wtTotalKw
                        + c.costBtRubPerKwh * d.btTotalKwh;

        double pvCostRub = capexRub;
        double pvServedKwh = 0.0;

        for (int y = 0; y < years; y++) {
            final double df = 1.0 / Math.pow(1.0 + r, (y + 1));

            pvServedKwh += d.servedKwhByYear[y] * df;

            // fuel: rub/kt, simplest consistent conversion: kt = liters / 1e6
            final double fuelKt = d.fuelLitersByYear[y] / 1_000_000.0;
            final double fuelRub = fuelKt * c.costFuelRubPerKt;

            // moto: rub per (kW * 1000 moto-hours)
            final double motoRub = (d.motoHoursByYear[y] / 1000.0) * d.dgTotalKw * c.costDgRubPerKwPerKmh;

            // annual opex
            final double wtOpexRub = d.wtTotalKw * c.costWtRubPerKwPerYear;
            final double btOpexRub = d.btTotalKwh * c.costBtRubPerKwhPerYear;

            // battery replacements: replacementCount * (full pack cost)
            final double btReplRub = (double) d.btReplByYear[y] * (c.costBtRubPerKwh * d.btTotalKwh);

            final double damageRub =
                    d.ensCat1KwhByYear[y] * c.damageRubPerKwhCat1
                            + d.ensCat2KwhByYear[y] * c.damageRubPerKwhCat2
                            + d.ensCat3KwhByYear[y] * c.damageRubPerKwhCat3;

            final double yearCostRub = fuelRub + motoRub + wtOpexRub + btOpexRub + btReplRub + damageRub;
            pvCostRub += yearCostRub * df;
        }

        if (pvServedKwh <= eps) return 0.0;
        return pvCostRub / pvServedKwh;
    }
}
