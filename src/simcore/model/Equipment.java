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

    /** Длительность ремонта после отказа, ч. */
    private int repairTimeHours;

    /** Текущее состояние: true = исправен (может работать), false = отключен/в отказе. */
    protected boolean status = true;

    /** Наработка с момента последнего определения nextFailureTimeHours, ч. */
    protected int timeWorked = 0;

    /** Время (наработки), через которое произойдёт следующий случайный отказ, ч. */
    protected double nextFailureTimeHours = Double.POSITIVE_INFINITY;

    /** Сколько часов ремонта осталось (если в ремонте/отказе). */
    protected int repairDurationHours = 0;

    /** Количество отказов (счётчик). */
    protected int failureCount = 0;

    /** Генератор случайных чисел для отказов. */
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
     * Оборудование считается доступным к работе, если оно не в ремонте и status = true.
     */
    public boolean isAvailable() {
        return status && repairDurationHours == 0;
    }

    /**
     * Инициализация модели отказов перед одним прогоном Monte Carlo.
     *
     * @param rnd              генератор случайных чисел для этого типа оборудования
     * @param considerFailures учитывать ли отказы
     */
    public void initFailureModel(Random rnd, boolean considerFailures) {
        this.failureRandom = rnd;
        this.timeWorked = 0;
        this.repairDurationHours = 0;
        this.failureCount = 0;
        this.status = true;

        if (considerFailures && failureRatePerYear > 0.0 && failureRandom != null) {
            this.nextFailureTimeHours = generateNextFailureTime(failureRatePerYear, failureRandom);
        } else {
            this.nextFailureTimeHours = Double.POSITIVE_INFINITY;
        }
    }

    /**
     * Базовое обновление состояния отказа/ремонта на один час.
     * Используется для оборудования без особых правил (например, ВЭУ, шины, автомат).
     * ДГУ и АКБ переопределяют этот метод.
     */
    public void updateFailureOneHour(boolean considerFailures) {
        if (!considerFailures) {
            return;
        }

        // Если идёт ремонт — уменьшаем оставшееся время.
        if (repairDurationHours > 0) {
            repairDurationHours--;
            if (repairDurationHours <= 0) {
                repairDurationHours = 0;
                status = true;
                timeWorked = 0;

                if (failureRatePerYear > 0.0 && failureRandom != null) {
                    nextFailureTimeHours = generateNextFailureTime(failureRatePerYear, failureRandom);
                } else {
                    nextFailureTimeHours = Double.POSITIVE_INFINITY;
                }
                onRepairFinished();
            }
            return;
        }

        // Если отключен внешне (но не в ремонте) — не проверяем случайный отказ
        if (!status) { // todo что?
            return;
        }

        // Проверка на случайный отказ по наработке
        if (failureRatePerYear > 0.0
                && timeWorked >= nextFailureTimeHours) {
            status = false;
            failureCount++;
            repairDurationHours = repairTimeHours;
        }
    }

    /**
     * Увеличение наработки оборудования на заданное количество часов.
     * Вызывается только когда оборудование реально работало этот интервал.
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
     * Принудительный вывод оборудования в отказ с назначением ремонта.
     */
    public void forceFailNow() {
        this.status = false;
        this.timeWorked = 0;
        this.repairDurationHours = repairTimeHours;
        this.failureCount++;
    }

    /**
     * Хук, вызываемый после завершения ремонта.
     * По умолчанию — ничего не делает.
     */
    protected void onRepairFinished() {
        // по умолчанию — ничего
    }

    /**
     * Генерация времени до отказа по экспоненциальному распределению.
     *
     * @param failureRatePerYear интенсивность отказов, 1/год
     * @param rnd                генератор случайных чисел
     * @return время до отказа в часах
     */
    protected static double generateNextFailureTime(double failureRatePerYear, Random rnd) {
        if (failureRatePerYear <= 0.0) {
            return Double.POSITIVE_INFINITY;
        }
        double u = rnd.nextDouble();
        double lambdaPerHour = failureRatePerYear / 8760.0;
        return -Math.log(1.0 - u) / lambdaPerHour;
    }
}
