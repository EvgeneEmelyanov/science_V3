// File: simcore/model/Battery.java
package simcore.model;

import simcore.config.ModelDefaults;
import simcore.config.SimulationConstants;
import simcore.config.SystemParameters;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Аккумуляторная батарея для почасового моделирования.
 */
public class Battery extends Equipment {

    private final double nominalCapacityKwh;
    private double maxCapacityKwh;
    private double soc;
    private double efcEff = 0.0;
    private boolean replaceOnRepair = false;
    private long replacementCount = 0;
    private boolean workedCountedThisHour = false;
    private double halfCycleStartSoc;
    private int lastFlowSign = 0;

    /**
     * Для trace храним тот non-reserve floor, который реально использовался/был активен в текущем часу.
     */
    private double currentNonReserveDischargeLevel = ModelDefaults.DEFAULT_BT_NON_RESERVE_DISCHARGE_LEVEL;

    private Double prevLoadT1Kw;
    private Double prevLoadT2Kw;
    private Double prevWindT1Kw;
    private Double prevWindT2Kw;
    private int prevRunningDgCountT1 = 0;
    private boolean hasPrevRunningDgCountT1 = false;

    private double previousTrendKw = 0.0;
    private boolean hasPreviousTrend = false;
    private double emaAccelerationKw = 0.0;
    private boolean hasAccelerationEma = false;

    /**
     * Базовый adaptive floor на текущий час БЕЗ поправки за конкретную глубину замещения ДГУ.
     */
    private double currentAdaptiveBaseNonReserveLevel = ModelDefaults.DEFAULT_BT_NON_RESERVE_DISCHARGE_LEVEL;

    private final List<Double> adaptiveLevelHistory = new ArrayList<>();

    public Battery(int id, double capacityKwh, double failureRatePerYear, int repairTimeHours) {
        super("BT", id, failureRatePerYear, repairTimeHours);
        this.nominalCapacityKwh = capacityKwh;
        this.maxCapacityKwh = capacityKwh;
        this.soc = SimulationConstants.BATTERY_START_SOC;
        this.halfCycleStartSoc = this.soc;
    }

    public long getReplacementCount() { return replacementCount; }
    public double getNominalCapacityKwh() { return nominalCapacityKwh; }
    public double getMaxCapacityKwh() { return maxCapacityKwh; }
    public double getStateOfCharge() { return soc; }
    public double getCurrentNonReserveDischargeLevel() { return currentNonReserveDischargeLevel; }
    public boolean isAvailableForUse() { return status && repairDurationHours == 0; }

    public double getEffectiveNonReserveDischargeLevel(SystemParameters sp) {
        return sp.isBtUseAdaptiveNonReserveDischargeLevel()
                ? currentAdaptiveBaseNonReserveLevel
                : clampRange(sp.getNonReserveDischargeLevel(), SimulationConstants.BATTERY_MIN_SOC, 1.0);
    }

    public void setCurrentNonReserveDischargeLevelForTrace(double level) {
        this.currentNonReserveDischargeLevel = clampRange(level, SimulationConstants.BATTERY_MIN_SOC, 1.0);
    }

    public boolean hasAdaptiveLevelHistory() { return !adaptiveLevelHistory.isEmpty(); }
    public List<Double> getAdaptiveLevelHistorySnapshot() { return new ArrayList<>(adaptiveLevelHistory); }
    public double getAdaptiveLevelMean() {
        if (adaptiveLevelHistory.isEmpty()) return Double.NaN;
        double s = 0.0;
        for (double v : adaptiveLevelHistory) s += v;
        return s / adaptiveLevelHistory.size();
    }
    public double getAdaptiveLevelMedian() {
        if (adaptiveLevelHistory.isEmpty()) return Double.NaN;
        List<Double> copy = new ArrayList<>(adaptiveLevelHistory);
        Collections.sort(copy);
        int n = copy.size();
        return (n % 2 == 1) ? copy.get(n / 2) : 0.5 * (copy.get(n / 2 - 1) + copy.get(n / 2));
    }

    /**
     * Обновление базового adaptive floor в начале часа по данным только предыдущих часов.
     */
    public void updateAdaptiveNonReserveDischargeLevel(SystemParameters sp,
                                                       double previousLoadKw,
                                                       double previousWindKw,
                                                       int previousRunningDgCount) {
        shiftAdaptiveHistory(previousLoadKw, previousWindKw, previousRunningDgCount);

        double fixed = clampRange(sp.getNonReserveDischargeLevel(), SimulationConstants.BATTERY_MIN_SOC, 1.0);
        currentAdaptiveBaseNonReserveLevel = fixed;
        currentNonReserveDischargeLevel = fixed;

        if (!sp.isBtUseAdaptiveNonReserveDischargeLevel()) {
            return;
        }

        if (prevLoadT1Kw == null || prevWindT1Kw == null || prevLoadT2Kw == null || prevWindT2Kw == null) {
            adaptiveLevelHistory.add(currentAdaptiveBaseNonReserveLevel);
            return;
        }

        double deltaT1 = prevLoadT1Kw - prevWindT1Kw;
        double deltaT2 = prevLoadT2Kw - prevWindT2Kw;
        double trendNow = deltaT1 - deltaT2;
        double accelerationRaw = hasPreviousTrend ? (trendNow - previousTrendKw) : 0.0;
        previousTrendKw = trendNow;
        hasPreviousTrend = true;

        double alpha = clamp01(sp.getBtAdaptiveAccelerationEmaAlpha());
        if (!hasAccelerationEma) {
            emaAccelerationKw = accelerationRaw;
            hasAccelerationEma = true;
        } else {
            emaAccelerationKw = alpha * accelerationRaw + (1.0 - alpha) * emaAccelerationKw;
        }

        double trendScale = Math.max(SimulationConstants.EPSILON, sp.getBtAdaptiveTrendScaleKw());
        double accelerationScale = Math.max(SimulationConstants.EPSILON, sp.getBtAdaptiveAccelerationScaleKw());

        double trendSignal = clampRange(trendNow / trendScale, -1.0, 1.0);
        double accelerationSignal = clamp01(Math.max(0.0, emaAccelerationKw) / accelerationScale);
        double noDgBonus = (hasPrevRunningDgCountT1 && prevRunningDgCountT1 == 0)
                ? Math.max(0.0, sp.getBtAdaptiveNoDgPrevHourBonus())
                : 0.0;

        double candidate = fixed
                + sp.getBtAdaptiveTrendWeight() * trendSignal
                + sp.getBtAdaptiveAccelerationWeight() * accelerationSignal
                - noDgBonus;

        currentAdaptiveBaseNonReserveLevel = clampRange(candidate, SimulationConstants.BATTERY_MIN_SOC, 1.0);
        currentNonReserveDischargeLevel = currentAdaptiveBaseNonReserveLevel;
        adaptiveLevelHistory.add(currentAdaptiveBaseNonReserveLevel);
    }

    /**
     * Получить floor для конкретного кандидата по числу оставляемых ДГУ.
     * Чем агрессивнее сокращение дизельного состава, тем выше floor.
     */
    public double getAdaptiveNonReserveFloorForCandidate(SystemParameters sp,
                                                         int naturalNeededDgCount,
                                                         int candidateDgCount) {
        double base = getEffectiveNonReserveDischargeLevel(sp);
        if (!sp.isBtUseAdaptiveNonReserveDischargeLevel()) {
            return clampRange(base, SimulationConstants.BATTERY_MIN_SOC, 1.0);
        }

        int nNeed = Math.max(0, naturalNeededDgCount);
        int nCand = Math.max(0, candidateDgCount);
        if (nNeed <= 0 || nCand >= nNeed) {
            return clampRange(base, SimulationConstants.BATTERY_MIN_SOC, 1.0);
        }

        double replacementRatio = clamp01((double) (nNeed - nCand) / Math.max(1, nNeed));
        double needFactor = ((double) nNeed) / (nNeed + 1.0);
        double exponent = Math.max(1.0, sp.getBtAdaptiveReplacementExponent());

        double candidate = base
                + sp.getBtAdaptiveReplacementWeight() * needFactor * Math.pow(replacementRatio, exponent);

        return clampRange(candidate, SimulationConstants.BATTERY_MIN_SOC, 1.0);
    }

    private void shiftAdaptiveHistory(double previousLoadKw, double previousWindKw, int previousRunningDgCount) {
        if (!Double.isFinite(previousLoadKw) || !Double.isFinite(previousWindKw)) {
            return;
        }
        prevLoadT2Kw = prevLoadT1Kw;
        prevWindT2Kw = prevWindT1Kw;
        prevLoadT1Kw = previousLoadKw;
        prevWindT1Kw = previousWindKw;
        prevRunningDgCountT1 = Math.max(0, previousRunningDgCount);
        hasPrevRunningDgCountT1 = true;
    }

    @Override
    public void initFailureModel(Random rnd, boolean considerFailures) {
        super.initFailureModel(rnd, considerFailures);
    }

    @Override
    public void updateFailureOneHour(boolean considerFailures) {
        super.updateFailureOneHour(considerFailures);
        workedCountedThisHour = false;
        if (repairDurationHours > 0 || !status) return;
        if (SimulationConstants.BATTERY_CALENDAR_LOSS_PER_YEAR > 0.0) {
            double lossKwhPerHour = (SimulationConstants.BATTERY_CALENDAR_LOSS_PER_YEAR / 8760.0) * nominalCapacityKwh;
            applyCapacityLossKwh(lossKwhPerHour);
        }
        selfDischargeOneHour();
        double minAllowed = SimulationConstants.BATTERY_DEGRADATION_THRESHOLD * nominalCapacityKwh;
        if (maxCapacityKwh <= minAllowed) {
            status = false;
            replacementCount++;
            repairDurationHours = getRepairTimeHours();
            replaceOnRepair = true;
        }
    }

    @Override
    protected void onRepairFinished() {
        if (replaceOnRepair) {
            maxCapacityKwh = nominalCapacityKwh;
            efcEff = 0.0;
            soc = SimulationConstants.BATTERY_START_SOC;
            halfCycleStartSoc = soc;
            lastFlowSign = 0;
            replaceOnRepair = false;
        }
    }

    public double getChargeCapacity(SystemParameters systemParameters) { return getChargePowerCapKw(systemParameters); }
    public double getDischargeCapacity(SystemParameters systemParameters) { return getDischargePowerCapKw(systemParameters); }
    public double getDischargePowerCapKw(SystemParameters systemParameters) {
        if (!isAvailableForUse()) return 0.0;
        return Math.max(0.0, maxCapacityKwh * systemParameters.getMaxDischargeCurrent());
    }
    public double getChargePowerCapKw(SystemParameters systemParameters) {
        if (!isAvailableForUse()) return 0.0;
        return Math.max(0.0, maxCapacityKwh * systemParameters.getMaxChargeCurrent());
    }
    public double getAvailableDischargeEnergyKwhAbove(double socFloor) {
        if (!isAvailableForUse()) return 0.0;
        double usableSoc = Math.max(0.0, soc - socFloor);
        return usableSoc * maxCapacityKwh * SimulationConstants.BATTERY_EFFICIENCY;
    }
    public double getAvailableChargeEnergyKwhBelow(double socCeil) {
        if (!isAvailableForUse()) return 0.0;
        double headroomSoc = Math.max(0.0, socCeil - soc);
        double eff = Math.max(SimulationConstants.EPSILON, SimulationConstants.BATTERY_EFFICIENCY);
        return headroomSoc * maxCapacityKwh / eff;
    }

    public static boolean useBattery(SystemParameters systemParameters, Battery battery,
                                     double deficitKwh, double canDischargeKwh) {
        double socAfterDischarge = (canDischargeKwh - deficitKwh) / battery.getMaxCapacityKwh();
        double minSocAllowed = battery.getEffectiveNonReserveDischargeLevel(systemParameters);
        return socAfterDischarge > minSocAllowed;
    }

    public void adjustCapacity(Battery battery, double energyDelta, double current, boolean doubleTime, boolean considerDegradation) {
        if (!isAvailableForUse()) return;

        if (!workedCountedThisHour && Math.abs(current) > SimulationConstants.EPSILON) {
            addWorkTime(doubleTime ? 2 : 1);
            workedCountedThisHour = true;
        }

        double eff = Math.max(SimulationConstants.EPSILON, SimulationConstants.BATTERY_EFFICIENCY);
        double storedKwh = soc * maxCapacityKwh;
        double newStoredKwh;

        if (energyDelta >= 0.0) {
            newStoredKwh = storedKwh + energyDelta * eff;
        } else {
            newStoredKwh = storedKwh + energyDelta / eff;
        }
        newStoredKwh = clampRange(newStoredKwh, 0.0, maxCapacityKwh);
        soc = (maxCapacityKwh > SimulationConstants.EPSILON) ? (newStoredKwh / maxCapacityKwh) : 0.0;
        soc = clamp01(soc);

        if (!considerDegradation) return;
        if (Math.abs(energyDelta) <= SimulationConstants.EPSILON) return;

        int sign = (energyDelta > 0.0) ? +1 : -1;
        if (lastFlowSign == 0) {
            lastFlowSign = sign;
            halfCycleStartSoc = soc;
        } else if (sign != lastFlowSign) {
            halfCycleStartSoc = soc;
            lastFlowSign = sign;
        }

        double cNom = Math.max(SimulationConstants.EPSILON, nominalCapacityKwh);
        double dEfcBase = Math.abs(energyDelta) / (2.0 * cNom);

        double cRate = Math.abs(current) / cNom;
        double cRateFactor = Math.pow(
                Math.max(1.0, cRate / Math.max(SimulationConstants.EPSILON, SimulationConstants.BATTERY_DEG_CRATE_REF)),
                SimulationConstants.BATTERY_DEG_H
        );

        double dod = Math.abs(soc - halfCycleStartSoc);
        double dodFactor = Math.pow(
                Math.max(1.0, dod / Math.max(SimulationConstants.EPSILON, SimulationConstants.BATTERY_DEG_DOD_REF)),
                SimulationConstants.BATTERY_DEG_M
        );

        double dEfcEff = dEfcBase * cRateFactor * dodFactor;
        efcEff += dEfcEff;

        double fracLoss = SimulationConstants.BATTERY_DEG_K * Math.pow(Math.max(0.0, efcEff), SimulationConstants.BATTERY_DEG_Z);
        fracLoss = clampRange(fracLoss, 0.0, 1.0);
        double targetCapacity = nominalCapacityKwh * (1.0 - fracLoss);

        if (targetCapacity < maxCapacityKwh) {
            maxCapacityKwh = targetCapacity;
            double storedNow = soc * Math.max(maxCapacityKwh, 0.0);
            if (maxCapacityKwh > SimulationConstants.EPSILON) {
                soc = clampRange(storedNow / maxCapacityKwh, 0.0, 1.0);
            } else {
                soc = 0.0;
            }
        }
    }

    private void applyCapacityLossKwh(double lossKwh) {
        if (lossKwh <= 0.0) return;
        maxCapacityKwh = Math.max(0.0, maxCapacityKwh - lossKwh);
        soc = clampRange(soc, SimulationConstants.BATTERY_MIN_SOC, SimulationConstants.BATTERY_MAX_SOC);
    }

    public void selfDischargeOneHour() {
        if (!isAvailableForUse()) return;
        double lossKwh = nominalCapacityKwh * SimulationConstants.BATTERY_SELF_DISCHARGE_PER_HOUR;
        if (lossKwh <= 0.0) return;
        double storedKwh = soc * maxCapacityKwh;
        storedKwh = Math.max(0.0, storedKwh - lossKwh);
        soc = (maxCapacityKwh > SimulationConstants.EPSILON) ? (storedKwh / maxCapacityKwh) : 0.0;
        soc = clamp01(soc);
    }

    private static double clamp01(double v) { return clampRange(v, 0.0, 1.0); }
    private static double clampRange(double v, double lo, double hi) {
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }
}
