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
    private Double prevWindT1Kw;
    private Double prevWindT2Kw;
    private Double prevAvailableDgPowerT1Kw;
    private double previousTrendKw = 0.0;
    private boolean hasPreviousTrend = false;
    private double emaAccelerationKw = 0.0;
    private boolean hasAccelerationEma = false;
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
                ? currentNonReserveDischargeLevel
                : clampRange(sp.getNonReserveDischargeLevel(), SimulationConstants.BATTERY_MIN_SOC, 1.0);
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

    public void updateAdaptiveNonReserveDischargeLevel(SystemParameters sp,
                                                       double previousLoadKw,
                                                       double previousWindKw,
                                                       double previousAvailableDgPowerKw) {
        shiftAdaptiveHistory(previousLoadKw, previousWindKw, previousAvailableDgPowerKw);

        double fixed = clampRange(sp.getNonReserveDischargeLevel(), SimulationConstants.BATTERY_MIN_SOC, 1.0);
        if (!sp.isBtUseAdaptiveNonReserveDischargeLevel()) {
            currentNonReserveDischargeLevel = fixed;
            return;
        }

        if (prevLoadT1Kw == null || prevWindT1Kw == null || prevAvailableDgPowerT1Kw == null
                || prevLoadT2Kw == null || prevWindT2Kw == null) {
            currentNonReserveDischargeLevel = fixed;
            adaptiveLevelHistory.add(currentNonReserveDischargeLevel);
            return;
        }

        double deltaT1 = prevLoadT1Kw - prevWindT1Kw;
        double deltaT2 = prevLoadT2Kw - prevWindT2Kw;
        double dgReserve = prevAvailableDgPowerT1Kw - deltaT1;
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

        double reserveRisk = riskFromLowReserve(dgReserve, sp.getBtAdaptiveReserveRiskScaleKw());
        double deficitRisk = riskFromPositive(deltaT1, sp.getBtAdaptiveDeficitRiskScaleKw());
        double accelerationRisk = riskFromPositive(Math.max(0.0, emaAccelerationKw), sp.getBtAdaptiveAccelerationRiskScaleKw());

        double weightedRisk =
                sp.getBtAdaptiveReserveRiskWeight() * reserveRisk
                        + sp.getBtAdaptiveDeficitRiskWeight() * deficitRisk
                        + sp.getBtAdaptiveAccelerationRiskWeight() * accelerationRisk;

        double candidate = fixed + sp.getBtAdaptiveRiskGain() * weightedRisk;
        currentNonReserveDischargeLevel = clampRange(candidate, SimulationConstants.BATTERY_MIN_SOC, 1.0);
        adaptiveLevelHistory.add(currentNonReserveDischargeLevel);
    }

    private void shiftAdaptiveHistory(double previousLoadKw, double previousWindKw, double previousAvailableDgPowerKw) {
        if (!Double.isFinite(previousLoadKw) || !Double.isFinite(previousWindKw) || !Double.isFinite(previousAvailableDgPowerKw)) {
            return;
        }
        prevLoadT2Kw = prevLoadT1Kw;
        prevWindT2Kw = prevWindT1Kw;
        prevLoadT1Kw = previousLoadKw;
        prevWindT1Kw = previousWindKw;
        prevAvailableDgPowerT1Kw = previousAvailableDgPowerKw;
    }

    private static double riskFromLowReserve(double reserveKw, double scaleKw) {
        double scale = Math.max(SimulationConstants.EPSILON, scaleKw);
        return clamp01((scale - reserveKw) / scale);
    }

    private static double riskFromPositive(double valueKw, double scaleKw) {
        double scale = Math.max(SimulationConstants.EPSILON, scaleKw);
        return clamp01(Math.max(0.0, valueKw) / scale);
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
        if (maxCapacityKwh <= SimulationConstants.EPSILON) return;
        double prevSoc = soc;
        if (energyDelta > 0.0) {
            soc = Math.min(SimulationConstants.BATTERY_MAX_SOC, soc + (energyDelta / maxCapacityKwh) * SimulationConstants.BATTERY_EFFICIENCY);
        } else if (energyDelta < 0.0) {
            soc = Math.max(SimulationConstants.BATTERY_MIN_SOC, soc + (energyDelta / maxCapacityKwh) / SimulationConstants.BATTERY_EFFICIENCY);
        }
        double socDelta = soc - prevSoc;
        double effEnergyDelta;
        if (Math.abs(socDelta) <= SimulationConstants.EPSILON) {
            effEnergyDelta = 0.0;
        } else if (socDelta > 0.0) {
            effEnergyDelta = (socDelta * maxCapacityKwh) / SimulationConstants.BATTERY_EFFICIENCY;
        } else {
            effEnergyDelta = (socDelta * maxCapacityKwh) * SimulationConstants.BATTERY_EFFICIENCY;
        }
        if (!doubleTime && Math.abs(effEnergyDelta) > 0.0005 * nominalCapacityKwh) {
            if (!workedCountedThisHour) {
                battery.timeWorked++;
                workedCountedThisHour = true;
            }
        }
        if (considerDegradation && Math.abs(effEnergyDelta) > SimulationConstants.EPSILON) {
            int flowSign = (effEnergyDelta > 0.0) ? 1 : -1;
            if (lastFlowSign == 0) {
                lastFlowSign = flowSign;
                halfCycleStartSoc = prevSoc;
            } else if (flowSign != lastFlowSign) {
                halfCycleStartSoc = prevSoc;
                lastFlowSign = flowSign;
            }
            double dEfcBase = Math.abs(effEnergyDelta) / (2.0 * nominalCapacityKwh);
            double powerKw = Math.abs(current);
            if (powerKw <= SimulationConstants.EPSILON) powerKw = Math.abs(effEnergyDelta);
            double cRate = powerKw / Math.max(nominalCapacityKwh, SimulationConstants.EPSILON);
            double cRateFactor = Math.max(0.1, Math.pow(cRate / SimulationConstants.BATTERY_DEG_CRATE_REF, SimulationConstants.BATTERY_DEG_H));
            double dod = clamp01(Math.abs(soc - halfCycleStartSoc));
            double dodFactor = Math.max(0.1, Math.pow(dod / SimulationConstants.BATTERY_DEG_DOD_REF, SimulationConstants.BATTERY_DEG_M));
            double dEfcEff = dEfcBase * cRateFactor * dodFactor;
            double efcPrev = efcEff;
            double efcNew = efcPrev + dEfcEff;
            efcEff = efcNew;
            double lossPrevFrac = SimulationConstants.BATTERY_DEG_K * Math.pow(Math.max(0.0, efcPrev), SimulationConstants.BATTERY_DEG_Z);
            double lossNewFrac = SimulationConstants.BATTERY_DEG_K * Math.pow(Math.max(0.0, efcNew), SimulationConstants.BATTERY_DEG_Z);
            double dLossFrac = Math.max(0.0, lossNewFrac - lossPrevFrac);
            applyCapacityLossKwh(nominalCapacityKwh * dLossFrac);
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
