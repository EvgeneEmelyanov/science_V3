package simcore.model;

public class WindTurbine extends Equipment {

    /** Номинальная мощность одной ВЭУ, кВт. */
    private final double ratedPowerKw;

    private static final double V_CUT_IN_MS = 4.0;
    private static final double V_RATED_MS  = 14.0;
    private static final double V_CUT_OUT_MS = 25.0;

    /** КПД ВЭУ */
    private static final double EFFICIENCY = 0.9;

    /**
     * @param id                   id ВЭУ в системе
     * @param ratedPowerKw         номинальная мощность, кВт
     * @param failureRatePerYear   частота отказов, 1/год
     * @param repairTimeHours      длительность ремонта, ч
     */
    public WindTurbine(int id,
                       double ratedPowerKw,
                       double failureRatePerYear,
                       int repairTimeHours) {
        super("WT", id, failureRatePerYear, repairTimeHours);
        this.ratedPowerKw = ratedPowerKw;
    }

    public double getRatedPowerKw() {
        return ratedPowerKw;
    }

    public double getPotentialGenerationKw(double windSpeedMs) {
        if (!isAvailable()) {
            return 0.0;
        }

        if (windSpeedMs < V_CUT_IN_MS || windSpeedMs >= V_CUT_OUT_MS) {
            return 0.0;
        }

        if (windSpeedMs >= V_RATED_MS) {
            return EFFICIENCY * ratedPowerKw;
        }

        // кубическая интерполяция между cut-in и rated
        double num = Math.pow(windSpeedMs, 3) - Math.pow(V_CUT_IN_MS, 3);
        double den = Math.pow(V_RATED_MS, 3) - Math.pow(V_CUT_IN_MS, 3);
        double p = EFFICIENCY * ratedPowerKw * (num / den);
        return Math.max(0.0, p);
    }
}
