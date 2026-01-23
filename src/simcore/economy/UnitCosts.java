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

    public UnitCosts(double costRuRub,
                     double costDgRubPerKw,
                     double costWtRubPerKw,
                     double costBtRubPerKwh,
                     double costFuelRubPerKt,
                     double costDgRubPerKwPerKmh,
                     double costWtRubPerKwPerYear,
                     double costBtRubPerKwhPerYear) {
        this.costRuRub = costRuRub;
        this.costDgRubPerKw = costDgRubPerKw;
        this.costWtRubPerKw = costWtRubPerKw;
        this.costBtRubPerKwh = costBtRubPerKwh;
        this.costFuelRubPerKt = costFuelRubPerKt;
        this.costDgRubPerKwPerKmh = costDgRubPerKwPerKmh;
        this.costWtRubPerKwPerYear = costWtRubPerKwPerYear;
        this.costBtRubPerKwhPerYear = costBtRubPerKwhPerYear;
    }
}
