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

    private double currentNonReserveDischargeLevel = ModelDefaults.DEFAULT_BT_NON_RESERVE_DISCHARGE_LEVEL;

    private Double prevLoadT1Kw;
    private Double prevLoadT2Kw;
    private Double prevLoadT3Kw;
    private Double prevWindT1Kw;
    private Double prevWindT2Kw;
    private Double prevWindT3Kw;
    private double prevAvailableDgPowerT1Kw = 0.0;
    private int prevRunningDgCountT1 = 0;
    private boolean hasPrevRunningDgCountT1 = false;

    private double currentAdaptiveBaseNonReserveLevel = 1.0;
    private double currentAdaptiveBaseR = 1.0;

    private final List<Double> adaptiveLevelHistory = new ArrayList<>();

    // ===== Adaptive diagnostics for trace =====
    private double traceAdaptiveFactorDeficit = Double.NaN;
    private double traceAdaptiveFactorTrend = Double.NaN;
    private double traceAdaptiveFactorAcceleration = Double.NaN;
    private double traceAdaptiveFactorNoDg = Double.NaN;
    private double traceAdaptiveFactorReplacement = Double.NaN;
    private double traceAdaptiveFactorDgAvailability = Double.NaN;
    private double traceAdaptiveR = Double.NaN;
    private double traceAdaptiveLevel = Double.NaN;

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

    public double getTraceAdaptiveFactorDeficit() { return traceAdaptiveFactorDeficit; }
    public double getTraceAdaptiveFactorTrend() { return traceAdaptiveFactorTrend; }
    public double getTraceAdaptiveFactorAcceleration() { return traceAdaptiveFactorAcceleration; }
    public double getTraceAdaptiveFactorNoDg() { return traceAdaptiveFactorNoDg; }
    public double getTraceAdaptiveFactorReplacement() { return traceAdaptiveFactorReplacement; }
    public double getTraceAdaptiveFactorDgAvailability() { return traceAdaptiveFactorDgAvailability; }
    public double getTraceAdaptiveR() { return traceAdaptiveR; }
    public double getTraceAdaptiveLevel() { return traceAdaptiveLevel; }

    public double getEffectiveNonReserveDischargeLevel(SystemParameters sp) {
        return sp.isBtUseAdaptiveNonReserveDischargeLevel()
                ? currentAdaptiveBaseNonReserveLevel
                : clampRange(sp.getNonReserveDischargeLevel(), SimulationConstants.BATTERY_MIN_SOC, 1.0);
    }

    public void setCurrentNonReserveDischargeLevelForTrace(double level) {
        this.currentNonReserveDischargeLevel = clampRange(level, SimulationConstants.BATTERY_MIN_SOC, 1.0);
        this.traceAdaptiveLevel = this.currentNonReserveDischargeLevel;
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
     * В первые три часа non-reserve разряд запрещен: уровень равен 1.0.
     */
    public void updateAdaptiveNonReserveDischargeLevel(SystemParameters sp,
                                                       double previousLoadKw,
                                                       double previousWindKw,
                                                       double previousAvailableDgPowerKw,
                                                       int previousRunningDgCount,
                                                       double totalDgPowerOnBusKw) {
        shiftAdaptiveHistory(previousLoadKw, previousWindKw, previousAvailableDgPowerKw, previousRunningDgCount);
        resetAdaptiveTraceDiagnostics();

        double fixed = clampRange(sp.getNonReserveDischargeLevel(), SimulationConstants.BATTERY_MIN_SOC, 1.0);
        currentAdaptiveBaseNonReserveLevel = fixed;
        currentAdaptiveBaseR = clampRange((fixed - 0.2) / 0.8, 0.0, 1.0);
        currentNonReserveDischargeLevel = fixed;
        traceAdaptiveLevel = fixed;

        if (!sp.isBtUseAdaptiveNonReserveDischargeLevel()) {
            return;
        }

        if (prevLoadT1Kw == null || prevLoadT2Kw == null || prevLoadT3Kw == null
                || prevWindT1Kw == null || prevWindT2Kw == null || prevWindT3Kw == null) {
            currentAdaptiveBaseNonReserveLevel = 1.0;
            currentAdaptiveBaseR = 1.0;
            currentNonReserveDischargeLevel = 1.0;
            traceAdaptiveR = 1.0;
            traceAdaptiveLevel = 1.0;
            adaptiveLevelHistory.add(currentAdaptiveBaseNonReserveLevel);
            return;
        }

        double deltaT1 = prevLoadT1Kw - prevWindT1Kw;
        double deltaT2 = prevLoadT2Kw - prevWindT2Kw;
        double deltaT3 = prevLoadT3Kw - prevWindT3Kw;

        double trend = deltaT1 - deltaT2;
        double previousTrend = deltaT2 - deltaT3;
        double acceleration = trend - previousTrend;
        double dgBusPowerKw = Math.max(SimulationConstants.EPSILON, totalDgPowerOnBusKw);

        double fDeficit = clampRange(deltaT1 / dgBusPowerKw, 0.0, 1.0);
        double fTrend = clampRange(trend / dgBusPowerKw, -1.0, 1.0);
        double fAcceleration = clampRange(acceleration / dgBusPowerKw, -1.0, 1.0);
        double fNoDg = (hasPrevRunningDgCountT1 && prevRunningDgCountT1 == 0) ? -1.0 : 0.0;
        double fDgAvailability = 0.0;
        if (deltaT1 > SimulationConstants.EPSILON) {
            double coverage = prevAvailableDgPowerT1Kw / deltaT1;
            fDgAvailability = 1.0 - Math.min(1.0, Math.max(0.0, coverage));
        }

        double rBase = sp.getBtAdaptiveDeficitWeight() * fDeficit
                + sp.getBtAdaptiveTrendWeight() * fTrend
                + sp.getBtAdaptiveAccelerationWeight() * fAcceleration
                + sp.getBtAdaptiveNoDgPrevHourWeight() * fNoDg
                + sp.getBtAdaptiveDgAvailabilityWeight() * fDgAvailability;
        currentAdaptiveBaseR = clampRange(rBase, 0.0, 1.0);

        currentAdaptiveBaseNonReserveLevel = 0.2 + 0.8 * currentAdaptiveBaseR;
        currentNonReserveDischargeLevel = currentAdaptiveBaseNonReserveLevel;
        adaptiveLevelHistory.add(currentAdaptiveBaseNonReserveLevel);

        traceAdaptiveFactorDeficit = fDeficit;
        traceAdaptiveFactorTrend = fTrend;
        traceAdaptiveFactorAcceleration = fAcceleration;
        traceAdaptiveFactorNoDg = fNoDg;
        traceAdaptiveFactorReplacement = 0.0;
        traceAdaptiveFactorDgAvailability = fDgAvailability;
        traceAdaptiveR = currentAdaptiveBaseR;
        traceAdaptiveLevel = currentAdaptiveBaseNonReserveLevel;
    }

    private static final class AdaptiveCandidateState {
        final double fReplacement;
        final double r;
        final double level;

        AdaptiveCandidateState(double fReplacement, double r, double level) {
            this.fReplacement = fReplacement;
            this.r = r;
            this.level = level;
        }
    }

    private AdaptiveCandidateState calculateAdaptiveCandidateState(SystemParameters sp,
                                                                   int naturalNeededDgCount,
                                                                   int candidateDgCount) {
        if (!sp.isBtUseAdaptiveNonReserveDischargeLevel()) {
            double level = clampRange(sp.getNonReserveDischargeLevel(), SimulationConstants.BATTERY_MIN_SOC, 1.0);
            return new AdaptiveCandidateState(0.0, Double.NaN, level);
        }

        int nNeed = Math.max(0, naturalNeededDgCount);
        int nCand = Math.max(0, candidateDgCount);
        double fReplacement = (nNeed <= 0) ? 0.0 : ((double) (nNeed - nCand) / Math.max(1, nNeed));
        fReplacement = clampRange(fReplacement, 0.0, 1.0);

        double baseR = currentAdaptiveBaseR;
        double r = clampRange(baseR + sp.getBtAdaptiveReplacementWeight() * fReplacement, 0.0, 1.0);
        double level = 0.2 + 0.8 * r;
        return new AdaptiveCandidateState(fReplacement, r, level);
    }

    /**
     * Рассчитать floor для кандидата без побочных эффектов.
     */
    public double previewAdaptiveNonReserveFloorForCandidate(SystemParameters sp,
                                                             int naturalNeededDgCount,
                                                             int candidateDgCount) {
        return calculateAdaptiveCandidateState(sp, naturalNeededDgCount, candidateDgCount).level;
    }

    /**
     * Зафиксировать финальный floor для выбранного кандидата и согласованно обновить trace.
     */
    public double commitAdaptiveNonReserveFloorForCandidate(SystemParameters sp,
                                                            int naturalNeededDgCount,
                                                            int candidateDgCount) {
        AdaptiveCandidateState state = calculateAdaptiveCandidateState(sp, naturalNeededDgCount, candidateDgCount);
        currentNonReserveDischargeLevel = state.level;
        traceAdaptiveFactorReplacement = state.fReplacement;
        traceAdaptiveR = state.r;
        traceAdaptiveLevel = state.level;
        return state.level;
    }

    private void shiftAdaptiveHistory(double previousLoadKw,
                                      double previousWindKw,
                                      double previousAvailableDgPowerKw,
                                      int previousRunningDgCount) {
        if (!Double.isFinite(previousLoadKw) || !Double.isFinite(previousWindKw)) {
            return;
        }
        prevLoadT3Kw = prevLoadT2Kw;
        prevWindT3Kw = prevWindT2Kw;
        prevLoadT2Kw = prevLoadT1Kw;
        prevWindT2Kw = prevWindT1Kw;
        prevLoadT1Kw = previousLoadKw;
        prevWindT1Kw = previousWindKw;
        prevAvailableDgPowerT1Kw = Math.max(0.0, previousAvailableDgPowerKw);
        prevRunningDgCountT1 = Math.max(0, previousRunningDgCount);
        hasPrevRunningDgCountT1 = true;
    }

    private void resetAdaptiveTraceDiagnostics() {
        traceAdaptiveFactorDeficit = Double.NaN;
        traceAdaptiveFactorTrend = Double.NaN;
        traceAdaptiveFactorAcceleration = Double.NaN;
        traceAdaptiveFactorNoDg = Double.NaN;
        traceAdaptiveFactorReplacement = Double.NaN;
        traceAdaptiveFactorDgAvailability = Double.NaN;
        traceAdaptiveR = Double.NaN;
        traceAdaptiveLevel = Double.NaN;
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
