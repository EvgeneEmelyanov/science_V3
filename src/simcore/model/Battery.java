// File: simcore/model/Battery.java
package simcore.model;

import simcore.config.SimulationConstants;
import simcore.config.SystemParameters;

import java.util.Random;

/**
 * Аккумуляторная батарея для почасового моделирования.
 *
 * Реализовано:
 *  1) Саморазряд: вычитаем фиксированную энергию (кВт·ч) от nominalCapacityKwh каждый час.
 *  2) Деградация: throughput/EFC-модель с учётом:
 *     - заряда и разряда;
 *     - C-rate;
 *     - глубины полуцикла (DoD).
 *
 * Калибровка базовой точки:
 *  - 2000 EFC до 80% SOH;
 *  - DoD_ref = 80%;
 *  - C-rate_ref = 1C.
 */
public class Battery extends Equipment {

    private final double nominalCapacityKwh; // паспортная ёмкость
    private double maxCapacityKwh;           // текущая доступная ёмкость (деградирует)
    private double soc;                      // SOC (0..1) относительно maxCapacityKwh
    private double efcEff = 0.0;             // накопленный эффективный EFC (с учётом штрафов)
    private boolean replaceOnRepair = false;
    private long replacementCount = 0;       // количество замен АКБ после деградации ниже указанного уровня

    // Guard against counting battery work time multiple times within the same simulated hour.
    // PerBusDispatcher may adjust capacity in two phases (start + steady) during one hour.
    private boolean workedCountedThisHour = false;

    // Упрощённый трекинг полуциклов для учёта DoD.
    // +1 = заряд, -1 = разряд, 0 = нет активного направления.
    private int activeDirection = 0;
    private double halfCycleStartSoc;

    public Battery(int id, double capacityKwh, double failureRatePerYear, int repairTimeHours) {
        super("BT", id, failureRatePerYear, repairTimeHours);
        this.nominalCapacityKwh = capacityKwh;
        this.maxCapacityKwh = capacityKwh;
        this.soc = SimulationConstants.BATTERY_START_SOC;
        this.halfCycleStartSoc = this.soc;
    }

    public long getReplacementCount() {
        return replacementCount;
    }

    public double getNominalCapacityKwh() { return nominalCapacityKwh; }
    public double getMaxCapacityKwh() { return maxCapacityKwh; }
    public double getStateOfCharge() { return soc; }

    public boolean isAvailableForUse() {
        return status && repairDurationHours == 0;
    }

    @Override
    public void initFailureModel(Random rnd, boolean considerFailures) {
        super.initFailureModel(rnd, considerFailures);
        this.activeDirection = 0;
        this.halfCycleStartSoc = this.soc;
    }

    /**
     * 1 час: ремонт/отказ (super), затем календарная деградация (опц.), саморазряд, и контроль порога деградации.
     */
    @Override
    public void updateFailureOneHour(boolean considerFailures) {
        super.updateFailureOneHour(considerFailures);

        // New hour begins (even if the battery is down/under repair):
        // allow at most one +timeWorked increment within this hour.
        workedCountedThisHour = false;

        if (repairDurationHours > 0 || !status) {
            return;
        }

        // Календарная деградация (если включена)
        if (SimulationConstants.BATTERY_CALENDAR_LOSS_PER_YEAR > 0.0) {
            double lossKwhPerHour =
                    (SimulationConstants.BATTERY_CALENDAR_LOSS_PER_YEAR / 8760.0) * nominalCapacityKwh;
            applyCapacityLossKwh(lossKwhPerHour);
        }

        // Саморазряд
        selfDischargeOneHour();

        // "Отказ по деградации": если maxCapacityKwh <= 0.8*nominal
        double minAllowed = SimulationConstants.BATTERY_DEGRADATION_THRESHOLD * nominalCapacityKwh;
        if (maxCapacityKwh <= minAllowed) {
            status = false;
            replacementCount++;
            repairDurationHours = getRepairTimeHours();
            replaceOnRepair = true;   // отметить, что это именно замена
        }
    }

    @Override
    protected void onRepairFinished() {
        if (replaceOnRepair) {
            maxCapacityKwh = nominalCapacityKwh;                // новая батарея
            efcEff = 0.0;
            soc = SimulationConstants.BATTERY_START_SOC;
            activeDirection = 0;
            halfCycleStartSoc = soc;
            replaceOnRepair = false;
        }
    }

    public double getChargeCapacity(SystemParameters systemParameters) {
        // Backward-compatible name: return CHARGE POWER CAP (kW).
        // Energy headroom must be checked separately via getAvailableChargeEnergyKwhBelow(...).
        return getChargePowerCapKw(systemParameters);
    }

    public double getDischargeCapacity(SystemParameters systemParameters) {
        // Backward-compatible name: return DISCHARGE POWER CAP (kW).
        // Energy availability must be checked separately via getAvailableDischargeEnergyKwhAbove(...).
        return getDischargePowerCapKw(systemParameters);
    }

    /**
     * Максимальная мощность разряда (кВт) по ограничению тока (C-rate).
     * Энергетический лимит (SOC floor) проверяйте отдельно.
     */
    public double getDischargePowerCapKw(SystemParameters systemParameters) {
        if (!isAvailableForUse()) return 0.0;
        return Math.max(0.0, maxCapacityKwh * systemParameters.getMaxDischargeCurrent());
    }

    /**
     * Максимальная мощность заряда (кВт) по ограничению тока (C-rate).
     * Свободную ёмкость (SOC ceiling) проверяйте отдельно.
     */
    public double getChargePowerCapKw(SystemParameters systemParameters) {
        if (!isAvailableForUse()) return 0.0;
        return Math.max(0.0, maxCapacityKwh * systemParameters.getMaxChargeCurrent());
    }

    /**
     * Доступная энергия разряда (кВт·ч) выше заданного SOC floor (учитывается КПД).
     */
    public double getAvailableDischargeEnergyKwhAbove(double socFloor) {
        if (!isAvailableForUse()) return 0.0;
        double usableSoc = Math.max(0.0, soc - socFloor);
        return usableSoc * maxCapacityKwh * SimulationConstants.BATTERY_EFFICIENCY;
    }

    /**
     * Доступная "ёмкость для заряда" (кВт·ч) до заданного SOC ceiling (учитывается КПД).
     * Это энергия со стороны сети, которую можно принять (т.е. с учетом КПД).
     */
    public double getAvailableChargeEnergyKwhBelow(double socCeil) {
        if (!isAvailableForUse()) return 0.0;
        double headroomSoc = Math.max(0.0, socCeil - soc);
        double eff = Math.max(SimulationConstants.EPSILON, SimulationConstants.BATTERY_EFFICIENCY);
        return headroomSoc * maxCapacityKwh / eff;
    }

    /**
     * Решение "можно ли разряжать ниже нерезервного уровня" — оставлено в духе твоей логики.
     */
    public static boolean useBattery(SystemParameters systemParameters, Battery battery,
                                     double deficitKwh, double canDischargeKwh) {
        double socAfterDischarge = (canDischargeKwh - deficitKwh) / battery.getMaxCapacityKwh();
        double minSocAllowed = systemParameters.getNonReserveDischargeLevel();
        return socAfterDischarge > minSocAllowed;
    }

    /**
     * energyDelta: +заряд, -разряд (кВт·ч за шаг)
     * current: мощность (кВт)
     * doubleTime: флаг "короткого мостика"
     */
    public void adjustCapacity(Battery battery,
                               double energyDelta,
                               double current,
                               boolean doubleTime,
                               boolean considerDegradation) {

        if (!isAvailableForUse()) return;

        double prevSoc = soc;

        // SOC update (with clamping)
        if (energyDelta > 0) {
            soc = Math.min(
                    SimulationConstants.BATTERY_MAX_SOC,
                    soc + (energyDelta / maxCapacityKwh) * SimulationConstants.BATTERY_EFFICIENCY
            );
        } else if (energyDelta < 0) {
            soc = Math.max(
                    SimulationConstants.BATTERY_MIN_SOC,
                    soc + (energyDelta / maxCapacityKwh) / SimulationConstants.BATTERY_EFFICIENCY
            );
        }

        // Effective terminal energy actually moved after clamping.
        // This prevents counting "work" / degradation when the battery is full/empty.
        double socDelta = soc - prevSoc;
        double effEnergyDelta;
        if (Math.abs(socDelta) <= SimulationConstants.EPSILON || maxCapacityKwh <= SimulationConstants.EPSILON) {
            effEnergyDelta = 0.0;
        } else if (socDelta > 0.0) {
            // charge: socDelta = (E * eff) / C  =>  E = socDelta * C / eff
            effEnergyDelta = (socDelta * maxCapacityKwh) / SimulationConstants.BATTERY_EFFICIENCY;
        } else {
            // discharge: socDelta = E / (C * eff)  =>  E = socDelta * C * eff
            effEnergyDelta = (socDelta * maxCapacityKwh) * SimulationConstants.BATTERY_EFFICIENCY;
        }

        // Наработка: не более +1 за час и только если реально прошла энергия после учёта пределов SOC.
        if (!doubleTime && Math.abs(effEnergyDelta) > 0.0005 * nominalCapacityKwh) {
            if (!workedCountedThisHour) {
                battery.timeWorked++;
                workedCountedThisHour = true;
            }
        }

        // Деградация: throughput power-law по эффективному EFC (заряд + разряд + штрафы C-rate и DoD).
        if (considerDegradation && Math.abs(effEnergyDelta) > SimulationConstants.EPSILON) {
            int stepDirection = effEnergyDelta > 0.0 ? 1 : -1;
            updateHalfCycleTracker(stepDirection, prevSoc);

            double dod = computeCurrentHalfCycleDod();
            double dEfcBase = Math.abs(effEnergyDelta) / (2.0 * nominalCapacityKwh);
            double crate = Math.abs(current) / Math.max(nominalCapacityKwh, SimulationConstants.EPSILON);

            double crateFactor = computeCrateFactor(crate, doubleTime);
            double dodFactor = computeDodFactor(dod);
            double dEfcEff = dEfcBase * crateFactor * dodFactor;

            double efcPrev = efcEff;
            double efcNew = efcPrev + dEfcEff;
            efcEff = efcNew;

            // Кумулятивная модель: lossFrac = K * (EFC_eff)^z
            double lossPrevFrac = SimulationConstants.BATTERY_DEG_K
                    * Math.pow(Math.max(0.0, efcPrev), SimulationConstants.BATTERY_DEG_Z);

            double lossNewFrac = SimulationConstants.BATTERY_DEG_K
                    * Math.pow(Math.max(0.0, efcNew), SimulationConstants.BATTERY_DEG_Z);

            double dLossFrac = Math.max(0.0, lossNewFrac - lossPrevFrac);

            // Потеря ёмкости от паспортной базы
            double lossKwh = nominalCapacityKwh * dLossFrac;
            applyCapacityLossKwh(lossKwh);
        }
    }

    private void updateHalfCycleTracker(int stepDirection, double prevSoc) {
        if (stepDirection == 0) return;
        if (activeDirection == 0) {
            activeDirection = stepDirection;
            halfCycleStartSoc = prevSoc;
            return;
        }
        if (activeDirection != stepDirection) {
            activeDirection = stepDirection;
            halfCycleStartSoc = prevSoc;
        }
    }

    private double computeCurrentHalfCycleDod() {
        return clamp(
                Math.abs(soc - halfCycleStartSoc),
                SimulationConstants.EPSILON,
                1.0
        );
    }

    private double computeCrateFactor(double crate, boolean doubleTime) {
        double crateSafe = clamp(crate, SimulationConstants.EPSILON, SimulationConstants.BATTERY_MAX_RELEVANT_CRATE);
        double factor = Math.pow(crateSafe / SimulationConstants.BATTERY_DEG_CRATE_REF,
                SimulationConstants.BATTERY_DEG_H);

        if (doubleTime) {
            factor = 1.0 + (factor - 1.0) * SimulationConstants.BATTERY_BRIDGE_CRATE_RELIEF;
        }
        return Math.max(SimulationConstants.EPSILON, factor);
    }

    private double computeDodFactor(double dod) {
        double dodSafe = clamp(dod, SimulationConstants.EPSILON, 1.0);
        return Math.pow(dodSafe / SimulationConstants.BATTERY_DEG_DOD_REF,
                SimulationConstants.BATTERY_DEG_M);
    }

    private void applyCapacityLossKwh(double lossKwh) {
        if (lossKwh <= 0.0) return;
        maxCapacityKwh = Math.max(0.0, maxCapacityKwh - lossKwh);
    }

    private static double clamp01(double v) {
        if (v < 0.0) return 0.0;
        if (v > 1.0) return 1.0;
        return v;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    public void selfDischargeOneHour() {
        if (!isAvailableForUse()) return;

        double lossKwh = nominalCapacityKwh * SimulationConstants.BATTERY_SELF_DISCHARGE_PER_HOUR;
        if (lossKwh <= 0.0) return;

        double storedKwh = soc * maxCapacityKwh;
        storedKwh = Math.max(0.0, storedKwh - lossKwh);

        if (maxCapacityKwh > SimulationConstants.EPSILON) {
            soc = clamp01(storedKwh / maxCapacityKwh);
        } else {
            soc = 0.0;
        }
    }
}
