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

            // moto: RUB per (kW * 1000 moto-hours)
            // NOTE: motoHoursByYear is the SUM of moto-hours across all DG units (Σ Hi).
            // Therefore the correct kW multiplier is per-unit DG power (Pi), not ΣPi.
            // If dgUnitKw is missing (e.g., old CSV), fall back to dgTotalKw (legacy behavior).
            final double dgKwForMoto = (d.dgUnitKw > 0.0) ? d.dgUnitKw : d.dgTotalKw;
            final double motoRub = (d.motoHoursByYear[y] / 1000.0) * dgKwForMoto * c.costDgRubPerKwPerKmh;

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
