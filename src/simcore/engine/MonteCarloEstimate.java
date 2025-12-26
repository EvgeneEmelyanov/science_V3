package simcore.engine;

import simcore.sobol.ParameterSet;

/**
 * Итог MC для одной точки параметров (theta):
 * - ENS (mean + CI)
 * - средний Fuel / Moto / WRE
 * - доли WT/DG/BT (в % от нагрузки)
 * - singleRun (если mcIterations == 1 и включён trace)
 */
public final class MonteCarloEstimate {

    /** Набор параметров (null для обычного MC без Соболя). */
    public final ParameterSet theta;

    /** Статистика ENS по MC-выборке. */
    public final MonteCarloStats.Stats ensStats;

    /** Средний ENS 1 категории по MC-выборке, кВт·ч. */
    public final double meanEnsCat1Kwh;

    /** Средний ENS 2 категории по MC-выборке, кВт·ч. */
    public final double meanEnsCat2Kwh;

    /** Средний расход топлива за горизонт, литры. */
    public final double meanFuelLiters;

    /** Средняя суммарная наработка ДГУ за горизонт, моточасы. */
    public final double meanMotoHours;

    /** Средняя неиспользованная энергия ветра за горизонт, кВт·ч. */
    public final double meanWre;

    /** Средняя доля нагрузки, покрытая ВЭУ, %. */
    public final double meanWtPct;

    /** Средняя доля нагрузки, покрытая ДГУ, %. */
    public final double meanDgPct;

    /** Средняя доля нагрузки, покрытая АКБ (разряд), %. */
    public final double meanBtPct;

    /** Отладочные данные одного single-run (только при iterations==1 и включён trace). */
    public final SingleRunMetrics singleRun;


    public MonteCarloEstimate(ParameterSet theta,
                              MonteCarloStats.Stats ensStats,
                              double meanEnsCat1Kwh,
                              double meanEnsCat2Kwh,
                              double meanFuelLiters,
                              double meanMotoHours,
                              double meanWre,
                              double meanWtPct,
                              double meanDgPct,
                              double meanBtPct,
                              SingleRunMetrics singleRun) {
        this.theta = theta;
        this.ensStats = ensStats;
        this.meanEnsCat1Kwh = meanEnsCat1Kwh;
        this.meanEnsCat2Kwh = meanEnsCat2Kwh;
        this.meanFuelLiters = meanFuelLiters;
        this.meanMotoHours = meanMotoHours;
        this.meanWre = meanWre;
        this.meanWtPct = meanWtPct;
        this.meanDgPct = meanDgPct;
        this.meanBtPct = meanBtPct;
        this.singleRun = singleRun;
    }
}
