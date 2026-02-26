package simcore.engine;

import java.util.List;
import simcore.economy.EconomyDrivers;

public final class SimulationMetrics {

    public final double loadKwh;       // суммарная нагрузка за горизонт
    public final double ensKwh;        // недоотпуск
    public final double ensCat1Kwh;    // недоотпуск 1 категории
    public final double ensCat2Kwh;    // недоотпуск 2 категории
    public final double wreKwh;        // неиспользованный ветер

    public final double wtToLoadKwh;   // сколько в нагрузку от ВЭУ
    public final double dgToLoadKwh;   // сколько в нагрузку от ДГУ
    public final double btToLoadKwh;   // сколько в нагрузку от АКБ (только разряд)

    /** LCOE, руб/кВт·ч (discounted), на отпущенную энергию (без ENS). */
    public final double lcoeRubPerKwh;

    /** Optional: per-year cost drivers for fast LCOE post-processing (may be null). */
    public final EconomyDrivers economyDrivers;

    public final double fuelLiters;    // суммарный расход топлива за горизонт
    public final long totalMotoHours;  // суммарные моточасы ДГУ за горизонт

    public final List<SimulationStepRecord> trace; // null если trace выключен

    public final long failBus;
    public final long failDg;
    public final long failWt;
    public final long failBt;
    public final long failBrk;
    public final long failRoom;
    public final long repBt;

    // ===== ENS event statistics (single-run) =====
    public final long ensEventsTotal;
    /** Events classified as "<1h" (start-only ENS inside one hour). */
    public final long ensEventsStartOnly;
    public final long ensEvents1H;
    public final long ensEvents2to4H;
    public final long ensEvents5to12H;
    public final long ensEvents13to24H;
    public final long ensEventsGt24H;
    public final long ensEventsMaxHours;

    // ===== Reliability-of-supply metrics derived from ENS =====
    /** LOLE, hours: number of hours with ENS(t) > 0 over the horizon. */
    public final double loleHours;
    /** LOLP, probability: LOLE_h / horizonHours. */
    public final double lolp;
    /** LPSP, energy-based: ENS / Load. */
    public final double lpsp;

    public SimulationMetrics(double loadKwh,
                             double ensKwh,
                             double ensCat1Kwh,
                             double ensCat2Kwh,
                             double wreKwh,
                             double wtToLoadKwh,
                             double dgToLoadKwh,
                             double btToLoadKwh,
                             double lcoeRubPerKwh,
                             double fuelLiters,
                             long totalMotoHours,
                             List<SimulationStepRecord> trace,
                             long failBus,
                             long failDg,
                             long failWt,
                             long failBt,
                             long failBrk,
                             long failRoom,
                             long repBt,
                             long ensEventsTotal,
                             long ensEventsStartOnly,
                             long ensEvents1H,
                             long ensEvents2to4H,
                             long ensEvents5to12H,
                             long ensEvents13to24H,
                             long ensEventsGt24H,
                             long ensEventsMaxHours,
                             double loleHours,
                             double lolp,
                             double lpsp,
                             EconomyDrivers economyDrivers) {
        this.loadKwh = loadKwh;
        this.ensKwh = ensKwh;
        this.ensCat1Kwh = ensCat1Kwh;
        this.ensCat2Kwh = ensCat2Kwh;
        this.wreKwh = wreKwh;
        this.wtToLoadKwh = wtToLoadKwh;
        this.dgToLoadKwh = dgToLoadKwh;
        this.btToLoadKwh = btToLoadKwh;
        this.lcoeRubPerKwh = lcoeRubPerKwh;
        this.economyDrivers = economyDrivers;
        this.fuelLiters = fuelLiters;
        this.totalMotoHours = totalMotoHours;
        this.trace = trace;
        this.failRoom = failRoom;
        this.failBus = failBus;
        this.failDg = failDg;
        this.failWt = failWt;
        this.failBt = failBt;
        this.failBrk = failBrk;
        this.repBt = repBt;

        this.ensEventsTotal = ensEventsTotal;
        this.ensEventsStartOnly = ensEventsStartOnly;
        this.ensEvents1H = ensEvents1H;
        this.ensEvents2to4H = ensEvents2to4H;
        this.ensEvents5to12H = ensEvents5to12H;
        this.ensEvents13to24H = ensEvents13to24H;
        this.ensEventsGt24H = ensEventsGt24H;
        this.ensEventsMaxHours = ensEventsMaxHours;

        this.loleHours = loleHours;
        this.lolp = lolp;
        this.lpsp = lpsp;
    }
}
