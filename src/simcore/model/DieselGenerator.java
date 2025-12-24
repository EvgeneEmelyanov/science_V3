package simcore.model;

import java.util.Comparator;
import java.util.Random;

/**
 * Дизель-генератор с:
 *  - случайными отказами по экспоненте;
 *  - плановым ТО каждые MAINTENANCE_INTERVAL_HOURS часов работы
 *    на MAINTENANCE_DURATION_HOURS часов.
 *
 * Важно: ограничение "одна ДГУ в ТО на шине" реализуется тем,
 * что движок/шина передаёт allowMaintenanceStart=false, если на шине
 * уже есть ДГУ в ТО.
 */
public class DieselGenerator extends Equipment {

    private final double ratedPowerKw;
    private double currentLoad;

    private int totalTimeWorked = 0;
    private int idleTime = 0;

    private static final int MAINTENANCE_INTERVAL_HOURS = 250;
    private static final int MAINTENANCE_DURATION_HOURS = 4;

    private double hoursSinceMaintenance = 0.0;
    private int maintenanceCount = 0;

    private boolean isWorking = true;
    private boolean inMaintenance = false;
    private boolean isIdle = false;

    public DieselGenerator(int id,
                           double ratedPowerKw,
                           double failureRatePerYear,
                           int repairTimeHours) {
        super("DG", id, failureRatePerYear, repairTimeHours);
        this.ratedPowerKw = ratedPowerKw;
    }

    public boolean isWorking() { return isWorking; }

    public int getIdleTime() { return idleTime; }
    public void incrementIdleTime() { idleTime++; }
    public void resetIdleTime() { idleTime = 0; }

    public boolean isIdle() { return isIdle; }
    public void setIdle(boolean idle) { this.isIdle = idle; }

    public double getRatedPowerKw() { return ratedPowerKw; }
    public double getHoursSinceMaintenance() { return hoursSinceMaintenance; }
    public int getMaintenanceCount() { return maintenanceCount; }
    public boolean isInMaintenance() { return inMaintenance; }

    public double getCurrentLoad() { return currentLoad; }
    public void setCurrentLoad(double currentLoad) { this.currentLoad = currentLoad; }

    public int getTotalTimeWorked() { return totalTimeWorked; }

    @Override
    public void initFailureModel(Random rnd, boolean considerFailures) {
        super.initFailureModel(rnd, considerFailures);
        this.hoursSinceMaintenance = 0.0;
        this.maintenanceCount = 0;
        this.inMaintenance = false;
    }

    public void startWork() {
        if (isAvailable()) isWorking = true;
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

    public double getAvailablePowerKw(double demandedKw) {
        if (!isAvailable()) return 0.0;
        if (demandedKw <= 0.0) return 0.0;
        return Math.min(ratedPowerKw, demandedKw);
    }

    /**
     * Старый метод оставлен для совместимости: ТО разрешено.
     */
    @Override
    public void updateFailureOneHour(boolean considerFailures) {
        updateFailureOneHour(considerFailures, true);
    }

    /**
     * Обновление состояния на один час.
     *
     * @param considerFailures      учитывать ли отказы/ТО
     * @param allowMaintenanceStart разрешено ли НАЧИНАТЬ ТО в этом часу
     *                              (движок ставит false, если на шине уже есть ДГУ в ТО)
     */
    public void updateFailureOneHour(boolean considerFailures, boolean allowMaintenanceStart) {
        if (!considerFailures) return;

        // Ремонт или ТО: просто считаем таймер
        if (repairDurationHours > 0) {
            repairDurationHours--;
            if (repairDurationHours <= 0) {
                repairDurationHours = 0;
                status = true;

                if (!inMaintenance) {
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

        // Если отключён внешне — ничего не делаем
        if (!status) return;

        // ЖЁСТКО разрешаем начинать ТО всегда
//        allowMaintenanceStart = true;

        // ТО: начинать можно только если allowMaintenanceStart == true
        if (allowMaintenanceStart && hoursSinceMaintenance >= MAINTENANCE_INTERVAL_HOURS) {
            status = false;
            inMaintenance = true;
            maintenanceCount++;
            hoursSinceMaintenance = 0.0;
            repairDurationHours = MAINTENANCE_DURATION_HOURS;

            isWorking = false;
            currentLoad = 0.0;
            return;
        }

        // Случайный отказ
        if (getFailureRatePerYear() > 0.0 && timeWorked >= nextFailureTimeHours) {
            status = false;
            inMaintenance = false;
            failureCount++;
            repairDurationHours = getRepairTimeHours();

            isWorking = false;
            currentLoad = 0.0;
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
