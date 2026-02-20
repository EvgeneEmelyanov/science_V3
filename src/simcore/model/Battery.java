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
 *  2) Деградация: throughput/EFC модель с мягкими множителями C-rate и DoD.
 */
public class Battery extends Equipment {

    private final double nominalCapacityKwh; // паспортная ёмкость
    private double maxCapacityKwh;           // текущая доступная ёмкость (деградирует)
    private double soc;                      // SOC (0..1) относительно maxCapacityKwh
    private double efcEff = 0.0; // накопленный эффективный EFC (0..)
    private boolean replaceOnRepair = false;
    private long replacementCount = 0; // количество замен АКБ после деградации ниже указанного уровна

    // Guard against counting battery work time multiple times within the same simulated hour.
    // PerBusDispatcher may adjust capacity in two phases (start + steady) during one hour.
    private boolean workedCountedThisHour = false;

    public Battery(int id, double capacityKwh, double failureRatePerYear, int repairTimeHours) {
        super("BT", id, failureRatePerYear, repairTimeHours);
        this.nominalCapacityKwh = capacityKwh;
        this.maxCapacityKwh = capacityKwh;
        this.soc = SimulationConstants.BATTERY_START_SOC;
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
            return;
        }

    }

    @Override
    protected void onRepairFinished() {
        if (replaceOnRepair) {
            maxCapacityKwh = nominalCapacityKwh;                // новая батарея
            efcEff = 0.0;
            soc = SimulationConstants.BATTERY_START_SOC;
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
     * current: мощность (кВт) для оценки C-rate
     * doubleTime: у тебя это флаг "короткого мостика" (на деградацию даём ослабление C-rate штрафа)
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

// Наработка: только если реально прошла энергия (после учёта пределов SOC)
        if (!doubleTime && Math.abs(effEnergyDelta) > 0.0005 * nominalCapacityKwh) {
            if (!workedCountedThisHour) {
                battery.timeWorked++;
                workedCountedThisHour = true;
            }
        }

        // Наработка как у тебя, но не более +1 за час
        if (!doubleTime && Math.abs(energyDelta) > 0.0005 * nominalCapacityKwh) {
            if (!workedCountedThisHour) {
                battery.timeWorked++;
                workedCountedThisHour = true;
            }
        }


        // Деградация: throughput power-law + exp(C-rate)
        if (considerDegradation && energyDelta < 0.0) {

            double eDis = -energyDelta; // кВт·ч

            // dEFC по энергии разряда (один EFC = разряд на nominalCapacityKwh)
            double dEfc = eDis / nominalCapacityKwh;

            // C-rate по мощности: C = P / C_nom
            double p = Math.abs(current); // кВт
            double cRate = (nominalCapacityKwh > SimulationConstants.EPSILON)
                    ? (p / nominalCapacityKwh)
                    : 0.0;

            // Фактор C-rate (semi-empirical)
            double sevC = Math.exp(SimulationConstants.BATTERY_DEG_H * cRate);

            // Ослабление токового влияния в режиме мостика
            if (doubleTime) {
                double relief = clamp01(SimulationConstants.BATTERY_BRIDGE_CRATE_RELIEF);
                sevC = 1.0 + (sevC - 1.0) * relief;
            }

            // Накопление "эффективного" EFC
            double efcPrev = efcEff;
            double dEfcEff = dEfc * sevC;
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

    private void applyCapacityLossKwh(double lossKwh) {
        if (lossKwh <= 0.0) return;
        maxCapacityKwh = Math.max(0.0, maxCapacityKwh - lossKwh);
    }

    private static double clamp01(double v) {
        if (v < 0.0) return 0.0;
        if (v > 1.0) return 1.0;
        return v;
    }

    public void selfDischargeOneHour() {
        if (!isAvailableForUse()) return;

        double lossKwh = nominalCapacityKwh * SimulationConstants.BATTERY_SELF_DISCHARGE_PER_HOUR;
        if (lossKwh <= 0.0) return;

        double storedKwh = soc * maxCapacityKwh;
        storedKwh = Math.max(0.0, storedKwh - lossKwh);

        if (maxCapacityKwh > SimulationConstants.EPSILON) {
            soc = storedKwh / maxCapacityKwh;
        } else {
            soc = 0.0;
        }
    }
}
