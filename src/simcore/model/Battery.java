package simcore.model;

import simcore.config.SimulationConstants;
import simcore.config.SystemParameters;

import java.util.Random;

/**
 * Аккумуляторная батарея для почасового моделирования.
 */
public class Battery extends Equipment {

    private static final double DEGRADATION_THRESHOLD = 0.8; // порог деградации до замены

    private final double nominalCapacityKwh; // Паспортное значение емкости, которые задается изначально
    private double maxCapacityKwh;      // Текущая максимально доступная ёмкость (с учётом деградации)
    private double soc;              // Текущий уровень заряда (0..1)

    private final Random rnd = new Random();

    /**
     * @param id id АКБ * @param capacityKwh номинальная ёмкость, кВт·ч
     * @param failureRatePerYear частота случайных отказов, 1/год
     * @param repairTimeHours длительность ремонта/замены, ч
     */
    public Battery(int id, double capacityKwh, double failureRatePerYear, int repairTimeHours) {
        super("BT", id, failureRatePerYear, repairTimeHours);
        this.nominalCapacityKwh = capacityKwh;
        this.maxCapacityKwh = capacityKwh;
        this.soc = SimulationConstants.BATTERY_START_SOC;
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
    public void initFailureModel(Random rnd, boolean considerFailures) { //todo все ли тут норм?
        super.initFailureModel(rnd, considerFailures);
        // при старте уровня degradation уже учтена во внешней логике (если нужно),
        // либо можно установить maxCapacityKwh = nominalCapacityKwh здесь.
    }

    @Override
    public void updateFailureOneHour(boolean considerFailures) { // todo все ли тут норм?
        super.updateFailureOneHour(considerFailures);

        // если в ремонте или уже упала — ничего дополнительно не делаем
        if (repairDurationHours > 0 || !status) {
            return;
        }

        // деградационный отказ: если maxCapacityKwh упала ниже порога, переводим в отказ/ремонт
        double minAllowed = DEGRADATION_THRESHOLD * nominalCapacityKwh;
        if (maxCapacityKwh <= minAllowed) {
            status = false;
            failureCount++;
            repairDurationHours = getRepairTimeHours();
        }
    }

    @Override
    protected void onRepairFinished() {
        this.maxCapacityKwh = this.nominalCapacityKwh;
        this.soc = SimulationConstants.BATTERY_START_SOC;
    }

    public double getChargeCapacity(SystemParameters systemParameters) {
        if (!isAvailableForUse()) return 0.0;
        double maxByCapacity = Math.max(0.0, maxCapacityKwh * (SimulationConstants.BATTERY_MAX_SOC - soc)
                / SimulationConstants.BATTERY_EFFICIENCY);
        double maxByCurrent = maxCapacityKwh * systemParameters.getMaxChargeCurrent();

        return Math.min(maxByCapacity, maxByCurrent);
    }

    public double getDischargeCapacity(SystemParameters systemParameters) {
        if (!isAvailableForUse()) return 0.0;
        double maxByCapacity = Math.max(0.0, (soc - SimulationConstants.BATTERY_MIN_SOC) * maxCapacityKwh
                * SimulationConstants.BATTERY_EFFICIENCY);
        double maxByCurrent = maxCapacityKwh * systemParameters.getMaxDischargeCurrent();

        return Math.min(maxByCapacity, maxByCurrent);
    }

    public static boolean useBattery(SystemParameters systemParameters, Battery battery,
                                     double deficitKwh, double canDischargeKwh) {

        double socAfterDischarge = (canDischargeKwh - deficitKwh) / battery.getMaxCapacityKwh();
        double minSocAllowed = systemParameters.getNonReserveDischargeLevel();

        return socAfterDischarge > minSocAllowed;
    }

    public void adjustCapacity(Battery battery, double energyDelta, double current, boolean doubleTime,
                               boolean considerDegradation) {
        double prevSoc = getStateOfCharge();

        if (energyDelta > 0) {
            soc = Math.min(SimulationConstants.BATTERY_MAX_SOC, soc + (energyDelta / maxCapacityKwh) *
                    SimulationConstants.BATTERY_EFFICIENCY);
        } else {
            soc = Math.max(SimulationConstants.BATTERY_MIN_SOC, soc + (energyDelta / maxCapacityKwh) /
                    SimulationConstants.BATTERY_EFFICIENCY);
        }

        // отметить наработку
        if (!doubleTime && Math.abs(energyDelta) > 0.005 * nominalCapacityKwh) {
            battery.timeWorked++;
        }

        // деградация: если это разряд и включена деградация — уменьшаем maxCapacityKwh
        if (energyDelta < -0.005 * nominalCapacityKwh &&
                considerDegradation) {

            maxCapacityKwh = maxCapacityKwh;
            // todo реализовать нормальную модель деградации емкости

        }
    }

    public void selfDischargeOneHour() { // todo вызвать где-то в движке
        double rate = SimulationConstants.BATTERY_SELF_DISCHARGE_PER_HOUR;
        soc = Math.max(0.0, soc * (1.0 - rate));
    }

}
