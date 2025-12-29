package simcore.engine;

import java.util.List;

public final class SimulationMetrics {

    public final double loadKwh;       // суммарная нагрузка за горизонт
    public final double ensKwh;        // недоотпуск
    public final double ensCat1Kwh;    // недоотпуск 1 категории
    public final double ensCat2Kwh;    // недоотпуск 2 категории
    public final double wreKwh;        // неиспользованный ветер

    public final double wtToLoadKwh;   // сколько в нагрузку от ВЭУ
    public final double dgToLoadKwh;   // сколько в нагрузку от ДГУ
    public final double btToLoadKwh;   // сколько в нагрузку от АКБ (только разряд)

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

    public SimulationMetrics(double loadKwh,
                             double ensKwh,
                             double ensCat1Kwh,
                             double ensCat2Kwh,
                             double wreKwh,
                             double wtToLoadKwh,
                             double dgToLoadKwh,
                             double btToLoadKwh,
                             double fuelLiters,
                             long totalMotoHours,
                             List<SimulationStepRecord> trace,
                             long failBus,
                             long failDg,
                             long failWt,
                             long failBt,
                             long failBrk,
                             long failRoom,
                             long repBt
    ) {
        this.loadKwh = loadKwh;
        this.ensKwh = ensKwh;
        this.ensCat1Kwh = ensCat1Kwh;
        this.ensCat2Kwh = ensCat2Kwh;
        this.wreKwh = wreKwh;
        this.wtToLoadKwh = wtToLoadKwh;
        this.dgToLoadKwh = dgToLoadKwh;
        this.btToLoadKwh = btToLoadKwh;
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
    }
}
