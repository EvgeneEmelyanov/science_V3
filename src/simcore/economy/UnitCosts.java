package simcore.economy;

/**
 * Unit costs for LCOE post-processing (same semantics as SystemParameters cost fields).
 */
public final class UnitCosts {

    public final double costRuRub;
    public final double costDgRubPerKw;
    public final double costWtRubPerKw;
    public final double costBtRubPerKwh;

    public final double costFuelRubPerKt;
    public final double costDgRubPerKwPerKmh;

    public final double costWtRubPerKwPerYear;
    public final double costBtRubPerKwhPerYear;

    public final double damageRubPerKwhCat1;
    public final double damageRubPerKwhCat2;
    public final double damageRubPerKwhCat3;

    public UnitCosts(double costRuRub,
                     double costDgRubPerKw,
                     double costWtRubPerKw,
                     double costBtRubPerKwh,
                     double costFuelRubPerKt,
                     double costDgRubPerKwPerKmh,
                     double costWtRubPerKwPerYear,
                     double costBtRubPerKwhPerYear,
                     double damageRubPerKwhCat1,
                     double damageRubPerKwhCat2,
                     double damageRubPerKwhCat3) {
        this.costRuRub = costRuRub;
        this.costDgRubPerKw = costDgRubPerKw;
        this.costWtRubPerKw = costWtRubPerKw;
        this.costBtRubPerKwh = costBtRubPerKwh;
        this.costFuelRubPerKt = costFuelRubPerKt;
        this.costDgRubPerKwPerKmh = costDgRubPerKwPerKmh;
        this.costWtRubPerKwPerYear = costWtRubPerKwPerYear;
        this.costBtRubPerKwhPerYear = costBtRubPerKwhPerYear;
        this.damageRubPerKwhCat1 = damageRubPerKwhCat1;
        this.damageRubPerKwhCat2 = damageRubPerKwhCat2;
        this.damageRubPerKwhCat3 = damageRubPerKwhCat3;
    }
}