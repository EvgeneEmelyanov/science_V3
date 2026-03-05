package simcore.model;

import java.util.Random;

/**
 * Базовый класс оборудования.
 */
public abstract class Equipment {

    /** Код типа оборудования (WT, DG, BT, BUS, BRK и т.п.). */
    private final String typeCode;

    /** Уникальный id в рамках типа. */
    private final int id;

    /** Частота отказов, 1/год. */
    private double failureRatePerYear;

    /** Среднее время ремонта (MTTR), ч. */
    private int repairTimeHours;

    /** Текущее состояние: true = исправен, false = отказ. */
    protected boolean status = true;

    /** Наработка с момента последнего определения nextFailureTimeHours, ч. */
    protected int timeWorked = 0;

    /** Время наработки до следующего отказа. */
    protected double nextFailureTimeHours = Double.POSITIVE_INFINITY;

    /** Сгенерированное время ремонта для следующего отказа. */
    protected int nextRepairTimeHours = 0;

    /** Сколько часов ремонта осталось. */
    protected int repairDurationHours = 0;

    /** Количество отказов. */
    protected int failureCount = 0;

    /** RNG для отказов. */
    protected transient Random failureRandom;

    protected Equipment(String typeCode, int id) {
        this(typeCode, id, 0.0, 0);
    }

    protected Equipment(String typeCode,
                        int id,
                        double failureRatePerYear,
                        int repairTimeHours) {
        this.typeCode = typeCode;
        this.id = id;
        this.failureRatePerYear = failureRatePerYear;
        this.repairTimeHours = repairTimeHours;
    }

    public String getTypeCode() {
        return typeCode;
    }

    public int getId() {
        return id;
    }

    public double getFailureRatePerYear() {
        return failureRatePerYear;
    }

    public void setFailureRatePerYear(double failureRatePerYear) {
        this.failureRatePerYear = failureRatePerYear;
    }

    public int getRepairTimeHours() {
        return repairTimeHours;
    }

    public void setRepairTimeHours(int repairTimeHours) {
        this.repairTimeHours = repairTimeHours;
    }

    public double getTimeWorked() {
        return timeWorked;
    }

    public int getRepairDurationHours() {
        return repairDurationHours;
    }

    public int getFailureCount() {
        return failureCount;
    }

    /**
     * Оборудование доступно, если не в ремонте.
     */
    public boolean isAvailable() {
        return status && repairDurationHours == 0;
    }

    /**
     * Инициализация модели отказов перед Monte Carlo.
     */
    public void initFailureModel(Random rnd, boolean considerFailures) {

        this.failureRandom = rnd;
        this.timeWorked = 0;
        this.repairDurationHours = 0;
        this.failureCount = 0;
        this.status = true;

        if (considerFailures && failureRatePerYear > 0.0 && failureRandom != null) {

            this.nextFailureTimeHours =
                    generateNextFailureTime(failureRatePerYear, failureRandom);

            this.nextRepairTimeHours =
                    generateRepairTime(repairTimeHours, failureRandom);

        } else {

            this.nextFailureTimeHours = Double.POSITIVE_INFINITY;
            this.nextRepairTimeHours = 0;
        }
    }

    /**
     * Обновление состояния отказов на 1 час.
     */
    public void updateFailureOneHour(boolean considerFailures) {

        if (!considerFailures) {
            return;
        }

        // идёт ремонт
        if (repairDurationHours > 0) {

            repairDurationHours--;

            if (repairDurationHours <= 0) {

                repairDurationHours = 0;
                status = true;
                timeWorked = 0;

                if (failureRatePerYear > 0.0 && failureRandom != null) {

                    nextFailureTimeHours =
                            generateNextFailureTime(failureRatePerYear, failureRandom);

                    nextRepairTimeHours =
                            generateRepairTime(repairTimeHours, failureRandom);

                } else {

                    nextFailureTimeHours = Double.POSITIVE_INFINITY;
                }

                onRepairFinished();
            }

            return;
        }

        if (!status) {
            return;
        }

        // проверка случайного отказа
        if (failureRatePerYear > 0.0 &&
                timeWorked >= nextFailureTimeHours) {

            status = false;
            failureCount++;

            repairDurationHours = nextRepairTimeHours;
        }
    }

    /**
     * Добавление наработки.
     */
    public void addWorkTime(int hours) {

        if (hours <= 0) {
            return;
        }

        if (status && repairDurationHours == 0) {
            timeWorked += hours;
        }
    }

    /**
     * Принудительный отказ.
     */
    public void forceFailNow() {

        this.status = false;
        this.timeWorked = 0;
        this.repairDurationHours =
                generateRepairTime(repairTimeHours, failureRandom);

        this.failureCount++;
    }

    /**
     * Хук после окончания ремонта.
     */
    protected void onRepairFinished() {
        // по умолчанию ничего
    }

    /**
     * Генерация времени до отказа.
     */
    protected static double generateNextFailureTime(double failureRatePerYear,
                                                    Random rnd) {

        if (failureRatePerYear <= 0.0) {
            return Double.POSITIVE_INFINITY;
        }

        double u = rnd.nextDouble();

        double lambdaPerHour = failureRatePerYear / 8760.0;

        return -Math.log(1.0 - u) / lambdaPerHour;
    }

    /**
     * Генерация времени ремонта (экспоненциальное распределение).
     */
    protected static int generateRepairTime(double meanRepairHours,
                                            Random rnd) {

        if (meanRepairHours <= 0) {
            return 0;
        }

        double u = rnd.nextDouble();

        return (int) Math.ceil(
                -Math.log(1.0 - u) * meanRepairHours
        );
    }
}