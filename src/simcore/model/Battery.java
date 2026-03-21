// File: simcore/model/Battery.java
package simcore.model;

import simcore.config.SimulationConstants;
import simcore.config.SystemParameters;

import java.util.Random;

/**
 * Аккумуляторная батарея для почасового моделирования.
 *
 * Реализовано:
 *  1) Саморазряд: вычитаем фиксированную энергию (кВт·ч) каждый час.
 *  2) Календарная деградация: фиксированная доля от nominalCapacityKwh в год.
 *  3) Цикловая деградация:
 *     - учитываются и заряд, и разряд;
 *     - базовый счётчик через EFC:
 *          dEFC_base = |E_term| / (2 * C_nom)
 *     - C-rate и DoD работают только как штрафующие множители вверх:
 *          factor >= 1.0
 *     - калибровка по-прежнему:
 *          20% потери ёмкости на 2000 EFC в базовых условиях.
 */
public class Battery extends Equipment {

    private final double nominalCapacityKwh; // паспортная ёмкость
    private double maxCapacityKwh;           // текущая доступная ёмкость (деградирует)
    private double soc;                      // SOC (0..1) относительно maxCapacityKwh
    private double efcEff = 0.0;             // накопленный эффективный EFC
    private boolean replaceOnRepair = false;
    private long replacementCount = 0;       // количество замен АКБ

    // Guard against counting battery work time multiple times within the same simulated hour.
    private boolean workedCountedThisHour = false;

    // Для оценки DoD текущего полуцикла
    private double halfCycleStartSoc;
    // -1 = discharge, +1 = charge, 0 = idle/unknown
    private int lastFlowSign = 0;

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

    public double getNominalCapacityKwh() {
        return nominalCapacityKwh;
    }

    public double getMaxCapacityKwh() {
        return maxCapacityKwh;
    }

    public double getStateOfCharge() {
        return soc;
    }

    public boolean isAvailableForUse() {
        return status && repairDurationHours == 0;
    }

    @Override
    public void initFailureModel(Random rnd, boolean considerFailures) {
        super.initFailureModel(rnd, considerFailures);
    }

    /**
     * 1 час: ремонт/отказ (super), затем календарная деградация, саморазряд,
     * и контроль порога деградации.
     */
    @Override
    public void updateFailureOneHour(boolean considerFailures) {
        super.updateFailureOneHour(considerFailures);

        // Новый час: разрешаем не более одного инкремента наработки за час
        workedCountedThisHour = false;

        if (repairDurationHours > 0 || !status) {
            return;
        }

        // Календарная деградация
        if (SimulationConstants.BATTERY_CALENDAR_LOSS_PER_YEAR > 0.0) {
            double lossKwhPerHour =
                    (SimulationConstants.BATTERY_CALENDAR_LOSS_PER_YEAR / 8760.0) * nominalCapacityKwh;
            applyCapacityLossKwh(lossKwhPerHour);
        }

        // Саморазряд
        selfDischargeOneHour();

        // "Отказ по деградации" / критерий замены
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

    public double getChargeCapacity(SystemParameters systemParameters) {
        // Backward-compatible name: return CHARGE POWER CAP (kW)
        return getChargePowerCapKw(systemParameters);
    }

    public double getDischargeCapacity(SystemParameters systemParameters) {
        // Backward-compatible name: return DISCHARGE POWER CAP (kW)
        return getDischargePowerCapKw(systemParameters);
    }

    /**
     * Максимальная мощность разряда (кВт) по ограничению тока (C-rate).
     */
    public double getDischargePowerCapKw(SystemParameters systemParameters) {
        if (!isAvailableForUse()) return 0.0;
        return Math.max(0.0, maxCapacityKwh * systemParameters.getMaxDischargeCurrent());
    }

    /**
     * Максимальная мощность заряда (кВт) по ограничению тока (C-rate).
     */
    public double getChargePowerCapKw(SystemParameters systemParameters) {
        if (!isAvailableForUse()) return 0.0;
        return Math.max(0.0, maxCapacityKwh * systemParameters.getMaxChargeCurrent());
    }

    /**
     * Доступная энергия разряда (кВт·ч) выше заданного SOC floor.
     */
    public double getAvailableDischargeEnergyKwhAbove(double socFloor) {
        if (!isAvailableForUse()) return 0.0;
        double usableSoc = Math.max(0.0, soc - socFloor);
        return usableSoc * maxCapacityKwh * SimulationConstants.BATTERY_EFFICIENCY;
    }

    /**
     * Доступная энергия заряда (кВт·ч со стороны сети) до заданного SOC ceiling.
     */
    public double getAvailableChargeEnergyKwhBelow(double socCeil) {
        if (!isAvailableForUse()) return 0.0;
        double headroomSoc = Math.max(0.0, socCeil - soc);
        double eff = Math.max(SimulationConstants.EPSILON, SimulationConstants.BATTERY_EFFICIENCY);
        return headroomSoc * maxCapacityKwh / eff;
    }

    /**
     * Решение "можно ли разряжать ниже нерезервного уровня".
     */
    public static boolean useBattery(SystemParameters systemParameters, Battery battery,
                                     double deficitKwh, double canDischargeKwh) {
        double socAfterDischarge = (canDischargeKwh - deficitKwh) / battery.getMaxCapacityKwh();
        double minSocAllowed = systemParameters.getNonReserveDischargeLevel();
        return socAfterDischarge > minSocAllowed;
    }

    /**
     * energyDelta: +заряд, -разряд (кВт·ч за шаг)
     * current: мощность по модулю/со знаком (кВт)
     * doubleTime: флаг "короткого мостика"
     * considerDegradation: учитывать ли деградацию
     */
    public void adjustCapacity(Battery battery,
                               double energyDelta,
                               double current,
                               boolean doubleTime,
                               boolean considerDegradation) {

        if (!isAvailableForUse()) return;
        if (maxCapacityKwh <= SimulationConstants.EPSILON) return;

        double prevSoc = soc;

        // -------------------------
        // 1) Обновление SOC с ограничениями
        // -------------------------
        if (energyDelta > 0.0) {
            // заряд
            soc = Math.min(
                    SimulationConstants.BATTERY_MAX_SOC,
                    soc + (energyDelta / maxCapacityKwh) * SimulationConstants.BATTERY_EFFICIENCY
            );
        } else if (energyDelta < 0.0) {
            // разряд
            soc = Math.max(
                    SimulationConstants.BATTERY_MIN_SOC,
                    soc + (energyDelta / maxCapacityKwh) / SimulationConstants.BATTERY_EFFICIENCY
            );
        }

        // -------------------------
        // 2) Фактически прошедшая терминальная энергия после clamp по SOC
        //    Это защищает от ложной деградации у полностью заряженной/разряженной АКБ.
        // -------------------------
        double socDelta = soc - prevSoc;
        double effEnergyDelta;

        if (Math.abs(socDelta) <= SimulationConstants.EPSILON) {
            effEnergyDelta = 0.0;
        } else if (socDelta > 0.0) {
            // заряд: socDelta = (E * eff) / C  =>  E = socDelta * C / eff
            effEnergyDelta = (socDelta * maxCapacityKwh) / SimulationConstants.BATTERY_EFFICIENCY;
        } else {
            // разряд: socDelta = E / (C * eff)  =>  E = socDelta * C * eff
            effEnergyDelta = (socDelta * maxCapacityKwh) * SimulationConstants.BATTERY_EFFICIENCY;
        }

        // -------------------------
        // 3) Наработка: не более +1 за час
        // -------------------------
        if (!doubleTime && Math.abs(effEnergyDelta) > 0.0005 * nominalCapacityKwh) {
            if (!workedCountedThisHour) {
                battery.timeWorked++;
                workedCountedThisHour = true;
            }
        }

        // -------------------------
        // 4) Цикловая деградация
        // -------------------------
        if (considerDegradation && Math.abs(effEnergyDelta) > SimulationConstants.EPSILON) {

            int flowSign = (effEnergyDelta > 0.0) ? 1 : -1;

            // Если направление изменилось — начинаем новый полуцикл
            if (lastFlowSign == 0) {
                lastFlowSign = flowSign;
                halfCycleStartSoc = prevSoc;
            } else if (flowSign != lastFlowSign) {
                halfCycleStartSoc = prevSoc;
                lastFlowSign = flowSign;
            }

            // 4.1 Базовый EFC: учитываем и заряд, и разряд
            double dEfcBase = Math.abs(effEnergyDelta) / (2.0 * nominalCapacityKwh);

            // 4.2 C-rate
            // Берём по модулю мощности; если current пришёл 0, используем энергию за шаг как fallback
            double powerKw = Math.abs(current);
            if (powerKw <= SimulationConstants.EPSILON) {
                powerKw = Math.abs(effEnergyDelta); // при шаге 1 час это эквивалентно средней мощности
            }

            // Нормируем на паспортную ёмкость, чтобы калибровка не "уплывала" при деградации
            double cRate = powerKw / Math.max(nominalCapacityKwh, SimulationConstants.EPSILON);

            // ВАЖНО:
            // C-rate не может смягчать деградацию: только 1.0 или больше
            double cRateFactor = Math.max(
                    0.1,
                    Math.pow(
                            cRate / SimulationConstants.BATTERY_DEG_CRATE_REF,
                            SimulationConstants.BATTERY_DEG_H
                    )
            );


            // 4.3 DoD текущего полуцикла
            double dod = Math.abs(soc - halfCycleStartSoc);
            dod = clamp01(dod);

            // DoD тоже не может смягчать деградацию: только 1.0 или больше
            double dodFactor = Math.max(
                    0.1,
                    Math.pow(
                            dod / SimulationConstants.BATTERY_DEG_DOD_REF,
                            SimulationConstants.BATTERY_DEG_M
                    )
            );

            // 4.4 Эффективный EFC
            double dEfcEff = dEfcBase * cRateFactor * dodFactor;

            double efcPrev = efcEff;
            double efcNew = efcPrev + dEfcEff;
            efcEff = efcNew;

            // 4.5 Кумулятивная power-law деградация
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

        // После уменьшения maxCapacityKwh текущий SOC должен остаться в допустимых границах
        soc = clamp01(soc);
        if (soc < SimulationConstants.BATTERY_MIN_SOC) {
            soc = SimulationConstants.BATTERY_MIN_SOC;
        }
        if (soc > SimulationConstants.BATTERY_MAX_SOC) {
            soc = SimulationConstants.BATTERY_MAX_SOC;
        }
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

        soc = clamp01(soc);
    }
}