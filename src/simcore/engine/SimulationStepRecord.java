package simcore.engine;

import java.util.Arrays;

/**
 * Одна строка "пошагового" вывода результатов моделирования для анализа.
 */
public class SimulationStepRecord {

    private final int timeIndex;
    private final double totalLoadKw;
    private final double totalDeficitKw;
    private final double totalWreKw;

    /** null если межсекционного автомата нет */
    private final Boolean breakerClosed;

    private final boolean[] busStatus;
    private final double[] busLoadKw;
    private final double[] busGenWindKw;
    private final double[] busGenDgKw;
    private final double[] busGenBtKw;
    private final double[] busDeficitKw;

    // ДГУ по шинам
    private final double[][] busGenDgLoadKw;
    private final double[][] busGenDgHoursSinceMaintenance;
    private final double[][] busGenDgTimeWorked;
    private final double[][] busGenDgTotalTimeWorked;

    // Состояние ДГУ
    private final boolean[][] dgAvailable;      // true = доступна
    private final boolean[][] dgInMaintenance;  // true = ТО

    // Состояние АКБ
    private final double[] btActualCapacity;
    private final double[] btActualSOC;

    // Наработка АКБ (часы)
    private final double[] btTimeWorked;

    public SimulationStepRecord(int timeIndex,
                                double totalLoadKw,
                                double totalDeficitKw,
                                double totalWreKw,
                                Boolean breakerClosed,
                                boolean[] busStatus,
                                double[] busLoadKw,
                                double[] busGenWindKw,
                                double[] busGenDgKw,
                                double[] busGenBtKw,
                                double[] busDeficitKw,
                                double[][] busGenDgLoadKw,
                                double[][] busGenDgHoursSinceMaintenance,
                                double[][] busGenDgTimeWorked,
                                double[][] busGenDgTotalTimeWorked,
                                boolean[][] dgAvailable,
                                boolean[][] dgInMaintenance,
                                double[] btActualCapacity,
                                double[] btActualSOC,
                                double[] btTimeWorked) {

        this.timeIndex = timeIndex;
        this.totalLoadKw = totalLoadKw;
        this.totalDeficitKw = totalDeficitKw;
        this.totalWreKw = totalWreKw;
        this.breakerClosed = breakerClosed;

        this.busStatus = busStatus.clone();
        this.busLoadKw = busLoadKw.clone();
        this.busGenWindKw = busGenWindKw.clone();
        this.busGenDgKw = busGenDgKw.clone();
        this.busGenBtKw = busGenBtKw.clone();
        this.busDeficitKw = busDeficitKw.clone();

        this.busGenDgLoadKw = deepCopy(busGenDgLoadKw);
        this.busGenDgHoursSinceMaintenance = deepCopy(busGenDgHoursSinceMaintenance);
        this.busGenDgTimeWorked = deepCopy(busGenDgTimeWorked);
        this.busGenDgTotalTimeWorked = deepCopy(busGenDgTotalTimeWorked);

        this.dgAvailable = deepCopy(dgAvailable);
        this.dgInMaintenance = deepCopy(dgInMaintenance);

        this.btActualCapacity = btActualCapacity.clone();
        this.btActualSOC = btActualSOC.clone();
        this.btTimeWorked = btTimeWorked.clone();
    }

    private static double[][] deepCopy(double[][] array) {
        return Arrays.stream(array).map(double[]::clone).toArray(double[][]::new);
    }

    private static boolean[][] deepCopy(boolean[][] array) {
        return Arrays.stream(array).map(boolean[]::clone).toArray(boolean[][]::new);
    }

    // --- Геттеры ---
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

    public Boolean getBreakerClosed() {
        return breakerClosed;
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

    public double[][] getBusGenDgLoadKw() {
        return deepCopy(busGenDgLoadKw);
    }

    public double[][] getBusGenDgHoursSinceMaintenance() {
        return deepCopy(busGenDgHoursSinceMaintenance);
    }

    public double[][] getBusGenDgTimeWorked() {
        return deepCopy(busGenDgTimeWorked);
    }

    public double[][] getBusGenDgTotalTimeWorked() {
        return deepCopy(busGenDgTotalTimeWorked);
    }

    public boolean[][] getDgAvailable() {
        return deepCopy(dgAvailable);
    }

    public boolean[][] getDgInMaintenance() {
        return deepCopy(dgInMaintenance);
    }

    public double[] getBtActualCapacity() {
        return btActualCapacity.clone();
    }

    public double[] getBtActualSOC() {
        return btActualSOC.clone();
    }

    public double[] getBtTimeWorked() {
        return btTimeWorked.clone();
    }
}
