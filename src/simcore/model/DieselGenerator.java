package simcore.model;

import java.util.Comparator;
import java.util.Random;

/**
 * Дизель-генератор с:
 *  - случайными отказами по экспоненте;
 *  - плановым ТО каждые MAINTENANCE_INTERVAL_HOURS часов работы
 *    на MAINTENANCE_DURATION_HOURS часов.
 *
 * Наработка увеличивается снаружи, через addWorkTime(hours),
 * когда ДГУ реально задействован в генерации.
 */
public class DieselGenerator extends Equipment {

    /** Номинальная мощность ДГУ, кВт. */
    private final double ratedPowerKw;

    /** Текущая загрузка ДГУ, кВт. */
    private double currentLoad;

    /** Наработка часов */
    private int totalTimeWorked = 0;

    /** Наработка на низкой загрузке */
    private int idleTime = 0;

    /** Период ТО по наработке, ч. */
    private static final int MAINTENANCE_INTERVAL_HOURS = 250;

    /** Длительность ТО, ч. */
    private static final int MAINTENANCE_DURATION_HOURS = 4;

    /** Наработка с момента последнего ТО, ч. */
    private double hoursSinceMaintenance = 0.0;

    /** Количество проведённых ТО. */
    private int maintenanceCount = 0;

    /** true, если ДГУ работает. */
    private boolean isWorking = true;

    /** true, если ДГУ сейчас в ТО (а не в ремонте после отказа). */
    private boolean inMaintenance = false;

    /** true, если ДГУ сейчас на низкой загрузке */
    private boolean isIdle = false;

    /**
     * @param id                   id ДГУ
     * @param ratedPowerKw         номинальная мощность ДГУ, кВт
     * @param failureRatePerYear   частота случайных отказов, 1/год
     * @param repairTimeHours      длительность ремонта после отказа, ч
     */
    public DieselGenerator(int id,
                           double ratedPowerKw,
                           double failureRatePerYear,
                           int repairTimeHours) {
        super("DG", id, failureRatePerYear, repairTimeHours);
        this.ratedPowerKw = ratedPowerKw;
    }

    public boolean isWorking() {
        return isWorking;
    }

    public boolean isIdle() {
        return isIdle;
    }

    public double getRatedPowerKw() {
        return ratedPowerKw;
    }

    public double getHoursSinceMaintenance() {
        return hoursSinceMaintenance;
    }

    public int getMaintenanceCount() {
        return maintenanceCount;
    }

    public boolean isInMaintenance() {
        return inMaintenance;
    }

    public double getCurrentLoad() {
        return currentLoad;
    }

    public void setCurrentLoad(double currentLoad) {
        this.currentLoad = currentLoad;
    }

    public int getTotalTimeWorked() {
        return totalTimeWorked;
    }

    /**
     * Инициализация модели отказов/ТО перед одним прогоном Monte Carlo.
     */
    @Override
    public void initFailureModel(Random rnd, boolean considerFailures) {
        super.initFailureModel(rnd, considerFailures);
        this.hoursSinceMaintenance = 0.0;
        this.maintenanceCount = 0;
        this.inMaintenance = false;
    }

    /**
     * Наработка учитывается и для общей наработки, и для ТО.
     */
    public void startWork() {
        if (isAvailable()) {
            isWorking = true;
        }
    }

    public void stopWork() {
        isWorking = false;
    }

    public void addWorkTime(int hours, int motoHours) {

        if (status && repairDurationHours == 0) {
            timeWorked += motoHours;
            totalTimeWorked += motoHours;
            hoursSinceMaintenance += hours;
        }
    }

    /**
     * Доступная мощность ДГУ в этот час с учётом отказов/ТО.
     *
     * @param demandedKw требуемая мощность, кВт
     * @return фактически доступная мощность, кВт
     */
    public double getAvailablePowerKw(double demandedKw) {
        if (!isAvailable()) {
            return 0.0;
        }
        if (demandedKw <= 0.0) {
            return 0.0;
        }
        return Math.min(ratedPowerKw, demandedKw);
    }

    /**
     * Обновление состояния отказа/ремонта/ТО на один час.
     * Логика:
     *  1) если идёт ремонт/ТО — уменьшаем repairDurationHours;
     *  2) если закончился ремонт/ТО — возвращаем в работу, генерируем новое время до отказа;
     *  3) если агрегат выключен вручную (status = false и не в ремонте) — ничего не делаем;
     *  4) если наработка с последнего ТО >= MAINTENANCE_INTERVAL_HOURS — уводим в ТО;
     *  5) иначе проверяем случайный отказ по экспоненте.
     */
    @Override
    public void updateFailureOneHour(boolean considerFailures) {
        if (!considerFailures) {
            return;
        }

        // Ремонт или ТО
        if (repairDurationHours > 0) {
            repairDurationHours--;
            if (repairDurationHours <= 0) {
                repairDurationHours = 0;
                status = true;
                if (!inMaintenance) {
                    // Был отказ → сбрасываем счетчик наработки
                    timeWorked = 0;

                    double lambdaYear = getFailureRatePerYear();
                    if (lambdaYear > 0.0 && failureRandom != null) {
                        nextFailureTimeHours = generateNextFailureTime(lambdaYear, failureRandom);
                    } else {
                        nextFailureTimeHours = Double.POSITIVE_INFINITY;
                    }
                }
                inMaintenance = false;

                onRepairFinished();
            }
            return;
        }

        // Если отключён внешне (но не в ремонте/ТО) — не проверяем ни ТО, ни отказы
        if (!status) {
            return;
        }

        // Проверка на ТО: если наработка с последнего ТО >= порога
        if (hoursSinceMaintenance >= MAINTENANCE_INTERVAL_HOURS) {
            status = false;
            inMaintenance = true;
            maintenanceCount++;
            hoursSinceMaintenance = 0.0;
            repairDurationHours = MAINTENANCE_DURATION_HOURS;
            return;
        }

        // Проверка на случайный отказ
        if (getFailureRatePerYear() > 0.0
                && timeWorked >= nextFailureTimeHours) {
            status = false;
            inMaintenance = false;
            failureCount++;
            repairDurationHours = getRepairTimeHours();
        }
    }

    public static Comparator<DieselGenerator> DISPATCH_COMPARATOR =
            (dg1, dg2) -> {
                if (dg1.isWorking() != dg2.isWorking()) {
                    return dg1.isWorking() ? -1 : 1;
                }
                return Integer.compare(dg1.timeWorked, dg2.timeWorked);
            };

}
