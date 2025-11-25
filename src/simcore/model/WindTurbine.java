package simcore.model;

/**
 * Ветротурбина.
 * Модель генерации пока упрощённая:
 *  - ниже cut-in скорости: 0;
 *  - выше cut-out: 0;
 *  - от cut-in до rated: кубическая аппроксимация;
 *  - от rated до cut-out: номинальная мощность.
 */
public class WindTurbine extends Equipment {

    /** Номинальная мощность одной ВЭУ, кВт. */
    private final double ratedPowerKw;

    // Параметры ветровой кривой (можешь заменить на реальные):
    private static final double V_CUT_IN_MS = 3.0;
    private static final double V_RATED_MS  = 12.0;
    private static final double V_CUT_OUT_MS = 25.0;

    /** КПД модели (можно использовать для учёта доп. потерь). */
    private static final double EFFICIENCY = 1.0;

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

    /**
     * Потенциальная генерация ВЭУ при данной скорости ветра, кВт.
     * Если ВЭУ в отказе (isAvailable == false), возвращает 0.
     */
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
