package simcore.engine;

/**
 * Одна строка "пошагового" вывода результатов моделирования
 * для отладки и анализа.
 */
public class SimulationStepRecord {

    /** Номер шага моделирования (например, час), начиная с 0. */
    private final int timeIndex;

    /** Общая нагрузка системы в этот момент, кВт. */
    private final double totalLoadKw;

    /** Общий дефицит в этот момент, кВт. */
    private final double totalDeficitKw;

    /** Общая неиспользованная энергия ветра (WRE), кВт. */
    private final double totalWreKw;

    /** Статус каждой шины: true = рабочая, false = отказ. */
    private final boolean[] busStatus;

    /** Нагрузка по каждой шине, кВт. */
    private final double[] busLoadKw;

    /** Генерация всех ВЭУ по каждой шине, кВт. */
    private final double[] busGenWindKw;

    /** Генерация всех ДГУ по каждой шине, кВт. */
    private final double[] busGenDgKw;

    /** Генерация АКБ по каждой шине, кВт. */
    private final double[] busGenBtKw;

    /** Дефицит по каждой шине: load - (wind + DG + battery), кВт. */
    private final double[] busDeficitKw;

    /** Нагрузка каждой дгу по каждой шине */
    private final double[][] busGenDgPerUnitKw; // [bus][dgIndex]


    public SimulationStepRecord(int timeIndex,
                                double totalLoadKw,
                                double totalDeficitKw,
                                double totalWreKw,
                                boolean[] busStatus,
                                double[] busLoadKw,
                                double[] busGenWindKw,
                                double[] busGenDgKw,
                                double[] busGenBtKw,
                                double[] busDeficitKw,
                                double [][] busGenDgPerUnitKw) {
        this.timeIndex = timeIndex;
        this.totalLoadKw = totalLoadKw;
        this.totalDeficitKw = totalDeficitKw;
        this.totalWreKw = totalWreKw;
        this.busStatus = busStatus.clone();
        this.busLoadKw = busLoadKw.clone();
        this.busGenWindKw = busGenWindKw.clone();
        this.busGenDgKw = busGenDgKw.clone();
        this.busGenBtKw = busGenBtKw.clone();
        this.busDeficitKw = busDeficitKw.clone();
        this.busGenDgPerUnitKw = busGenDgPerUnitKw.clone();
    }

    public int getTimeIndex() {
        return timeIndex;
    }

    public double getTotalLoadKw() {
        return totalLoadKw;
    }

    public double getTotalDeficitKw() {
        return totalDeficitKw;
    }

    public double getTotalWreKw() {
        return totalWreKw;
    }

    public boolean[] getBusStatus() {
        return busStatus.clone();
    }

    public double[] getBusLoadKw() {
        return busLoadKw.clone();
    }

    public double[] getBusGenWindKw() {
        return busGenWindKw.clone();
    }

    public double[] getBusGenDgKw() {
        return busGenDgKw.clone();
    }

    public double[] getBusGenBtKw() {
        return busGenBtKw.clone();
    }

    public double[] getBusDeficitKw() {
        return busDeficitKw.clone();
    }

    public double[][] getBusGenDgPerUnitKw() {
        return busGenDgPerUnitKw.clone(); // поверхностная копия массива шин
    }

}
