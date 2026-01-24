package simcore.model;

import simcore.config.SimulationConstants;

import java.util.Comparator;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

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

    /** Snapshot of {@link #isWorking} taken at the beginning of the current simulation hour. */
    private boolean workingAtHourStart = true;

    // ===== Fuel model constants (из старого кода) =====
    private static final double K11 = 0.0185;
    private static final double K21 = -0.0361;
    private static final double K31 = 0.2745;
    private static final double K12 = 5.3978;
    private static final double K22 = -11.4831;
    private static final double K32 = 11.6284;

    public DieselGenerator(int id,
                           double ratedPowerKw,
                           double failureRatePerYear,
                           int repairTimeHours) {
        super("DG", id, failureRatePerYear, repairTimeHours);
        this.ratedPowerKw = ratedPowerKw;
    }

    private boolean wasIdleThisHour = false;

    public void markIdleThisHour() {
        wasIdleThisHour = true;
    }

    public boolean wasIdleThisHour() {
        return wasIdleThisHour;
    }

    public void resetIdleHourFlag() {
        wasIdleThisHour = false;
    }

    public boolean isWorking() {
        return isWorking;
    }

    /** Capture {@link #isWorking} at the start of an hour (after failures, before dispatch). */
    public void snapshotWorkingAtHourStart() {
        this.workingAtHourStart = this.isWorking;
    }

    /** @return {@code true} if the DG was working at the beginning of the current hour. */
    public boolean wasWorkingAtHourStart() {
        return workingAtHourStart;
    }

    public int getIdleTime() {
        return idleTime;
    }

    public void incrementIdleTime() {
        idleTime++;
    }

    public void resetIdleTime() {
        idleTime = 0;
    }

    public boolean isIdle() {
        return isIdle;
    }

    public void setIdle(boolean idle) {
        this.isIdle = idle;
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

    @Override
    public void updateFailureOneHour(boolean considerFailures) {
        updateFailureOneHour(considerFailures, true);
    }

    public void updateFailureOneHour(boolean considerFailures, boolean allowMaintenanceStart) {
        if (!considerFailures) return;

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

        if (!status) return;

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

    private static final ThreadLocal<DieselGenerator[]> DG_SORT_BUF = new ThreadLocal<>();

    public static DieselGenerator[] getSortedDgs(PowerBus bus) {
        List<DieselGenerator> dgList = bus.getDieselGenerators();
        int n = dgList.size();

        DieselGenerator[] buf = DG_SORT_BUF.get();
        if (buf == null || buf.length != n) {
            buf = new DieselGenerator[n];
            DG_SORT_BUF.set(buf);
        }
        for (int i = 0; i < n; i++) buf[i] = dgList.get(i);

        Arrays.sort(buf, DieselGenerator.DISPATCH_COMPARATOR);
        return buf;
    }

    public static DieselGenerator[] getSortedDgs(List<DieselGenerator> dgList) {
        int n = dgList.size();
        DieselGenerator[] arr = new DieselGenerator[n];
        for (int i = 0; i < n; i++) arr[i] = dgList.get(i);
        Arrays.sort(arr, DieselGenerator.DISPATCH_COMPARATOR);
        return arr;
    }

    public static void stopAllDieselsOnBus(PowerBus bus) {
        for (DieselGenerator dg : bus.getDieselGenerators()) {
            if (!dg.isAvailable()) {
                hardStopDg(dg);
                continue;
            }

            dg.stopWork();
            dg.setCurrentLoad(0.0);
            dg.setIdle(false);
        }
    }

    public static void keepAllDieselsReadyHotStandby(PowerBus bus) {
        DieselGenerator[] dgs = getSortedDgs(bus);

        for (DieselGenerator dg : dgs) {
            if (!dg.isAvailable()) {
                hardStopDg(dg);
                continue;
            }

            dg.startWork();
            dg.setCurrentLoad(0.0);
            dg.setIdle(false);
        }
    }

    public static boolean isMaintenanceStartedThisHour(DieselGenerator[] dgs) {
        for (DieselGenerator dg : dgs) {
            if (!dg.isAvailable()) continue;
            if (dg.isInMaintenance() && dg.getRepairTimeHours() == 4) return true;
        }
        return false;
    }

    public static void hardStopDg(DieselGenerator dg) {
        dg.stopWork();
        dg.setCurrentLoad(0.0);
        dg.setIdle(false);
    }

    public double fuelLitersOneHour(double ratedKw) {
        if (!isAvailable()) return 0.0;
        if (ratedKw <= 0.0) return 0.0;

        final double pSigned = getCurrentLoad();
        double loadLevel = Math.abs(pSigned) / ratedKw;

        if (loadLevel <= SimulationConstants.EPSILON) return 0.0;
        if (loadLevel > 1.0) loadLevel = 1.0;

        final double k1 = K11 + (K12 / ratedKw);
        final double k2 = K21 + (K22 / ratedKw);
        final double k3 = K31 + (K32 / ratedKw);

        final double unitFuel = k1 * loadLevel * loadLevel + k2 * loadLevel + k3;
        final double liters = 0.84 * ratedKw * loadLevel * unitFuel;

        return Math.max(0.0, liters);
    }
}
