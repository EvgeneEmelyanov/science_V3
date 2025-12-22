package simcore.config;

/**
 * Параметры энергосистемы (immutable).
 */
public class SystemParameters {

    /**
     * Тип системы шин.
     */
    private final BusSystemType busSystemType;

    /**
     * Доля потребителей 1,2 и 3 категорий надежности электроснабжения
     */
    private final double firstCat;
    private final double secondCat;
    private final double thirdCat;

    /**
     * Общее количество ВЭУ в системе.
     */
    private final int totalWindTurbineCount;

    /**
     * Номинальная мощность одной ВЭУ, кВт.
     */
    private final double windTurbinePowerKw;

    /**
     * Общее количество ДГУ в системе.
     */
    private final int totalDieselGeneratorCount;

    /**
     * Номинальная мощность одного ДГУ, кВт.
     */
    private final double dieselGeneratorPowerKw;

    /**
     * Ёмкость АКБ на одну шину, кВт·ч (0 — если АКБ нет).
     */
    private final double batteryCapacityKwhPerBus;

    // ---------- Параметры надёжности ----------

    /**
     * Частота отказов ВЭУ, 1/год.
     */
    private final double windTurbineFailureRatePerYear;

    /**
     * Время ремонта ВЭУ, ч.
     */
    private final int windTurbineRepairTimeHours;

    /**
     * Частота отказов ДГУ, 1/год.
     */
    private final double dieselGeneratorFailureRatePerYear;

    /**
     * Время ремонта ДГУ, ч.
     */
    private final int dieselGeneratorRepairTimeHours;

    /**
     * Частота отказов АКБ, 1/год.
     */
    private final double batteryFailureRatePerYear;

    /**
     * Время ремонта/замены АКБ, ч.
     */
    private final int batteryRepairTimeHours;

    /**
     * Частота отказов шины, 1/год.
     */
    private final double busFailureRatePerYear;

    /**
     * Время ремонта шины, ч.
     */
    private final int busRepairTimeHours;

    /**
     * Частота отказов автомата, 1/год.
     */
    private final double breakerFailureRatePerYear;

    /**
     * Время ремонта автомата, ч.
     */
    private final int breakerRepairTimeHours;

    // ---------- Параметры АКБ ----------

    /**
     * Максимальный ток заряда относительно емкости, С
     */
    private final double maxChargeCurrent;

    /**
     * Максимальный ток разряда относительно емкости, С
     */
    private final double maxDischargeCurrent;

    /**
     * Допустимый уровень разряда не в целях резервирования
     */
    private final double nonReserveDischargeLevel;

    public SystemParameters(BusSystemType busSystemType,
                            double firstCat,
                            double secondCat,
                            double thirdCat,

                            int totalWindTurbineCount,
                            double windTurbinePowerKw,
                            int totalDieselGeneratorCount,
                            double dieselGeneratorPowerKw,
                            double batteryCapacityKwhPerBus,
                            double maxChargeCurrent,
                            double maxDischargeCurrent,
                            double nonReserveDischargeLevel,

                            double windTurbineFailureRatePerYear,
                            int windTurbineRepairTimeHours,
                            double dieselGeneratorFailureRatePerYear,
                            int dieselGeneratorRepairTimeHours,
                            double batteryFailureRatePerYear,
                            int batteryRepairTimeHours,
                            double busFailureRatePerYear,
                            int busRepairTimeHours,
                            double breakerFailureRatePerYear,
                            int breakerRepairTimeHours) {

        this.busSystemType = busSystemType;
        this.firstCat = firstCat;
        this.secondCat = secondCat;
        this.thirdCat = thirdCat;

        this.totalWindTurbineCount = totalWindTurbineCount;
        this.windTurbinePowerKw = windTurbinePowerKw;
        this.totalDieselGeneratorCount = totalDieselGeneratorCount;
        this.dieselGeneratorPowerKw = dieselGeneratorPowerKw;
        this.batteryCapacityKwhPerBus = batteryCapacityKwhPerBus;
        this.maxChargeCurrent = maxChargeCurrent;
        this.maxDischargeCurrent = maxDischargeCurrent;
        this.nonReserveDischargeLevel = nonReserveDischargeLevel;

        this.windTurbineFailureRatePerYear = windTurbineFailureRatePerYear;
        this.windTurbineRepairTimeHours = windTurbineRepairTimeHours;
        this.dieselGeneratorFailureRatePerYear = dieselGeneratorFailureRatePerYear;
        this.dieselGeneratorRepairTimeHours = dieselGeneratorRepairTimeHours;
        this.batteryFailureRatePerYear = batteryFailureRatePerYear;
        this.batteryRepairTimeHours = batteryRepairTimeHours;
        this.busFailureRatePerYear = busFailureRatePerYear;
        this.busRepairTimeHours = busRepairTimeHours;
        this.breakerFailureRatePerYear = breakerFailureRatePerYear;
        this.breakerRepairTimeHours = breakerRepairTimeHours;
    }

    // --------- Copy helpers ---------

    public SystemParameters copy() {
        return new SystemParameters(
                busSystemType,
                firstCat,
                secondCat,
                thirdCat,
                totalWindTurbineCount,
                windTurbinePowerKw,
                totalDieselGeneratorCount,
                dieselGeneratorPowerKw,
                batteryCapacityKwhPerBus,
                maxChargeCurrent,
                maxDischargeCurrent,
                nonReserveDischargeLevel,
                windTurbineFailureRatePerYear,
                windTurbineRepairTimeHours,
                dieselGeneratorFailureRatePerYear,
                dieselGeneratorRepairTimeHours,
                batteryFailureRatePerYear,
                batteryRepairTimeHours,
                busFailureRatePerYear,
                busRepairTimeHours,
                breakerFailureRatePerYear,
                breakerRepairTimeHours
        );
    }

    // --------- Getters ---------

    public BusSystemType getBusSystemType() {
        return busSystemType;
    }

    public double getFirstCat() {
        return firstCat;
    }

    public double getSecondCat() {
        return secondCat;
    }

    public double getThirdCat() {
        return thirdCat;
    }

    public int getTotalWindTurbineCount() {
        return totalWindTurbineCount;
    }

    public double getWindTurbinePowerKw() {
        return windTurbinePowerKw;
    }

    public int getTotalDieselGeneratorCount() {
        return totalDieselGeneratorCount;
    }

    public double getDieselGeneratorPowerKw() {
        return dieselGeneratorPowerKw;
    }

    public double getBatteryCapacityKwhPerBus() {
        return batteryCapacityKwhPerBus;
    }

    public double getMaxChargeCurrent() {
        return maxChargeCurrent;
    }

    public double getMaxDischargeCurrent() {
        return maxDischargeCurrent;
    }

    public double getNonReserveDischargeLevel() {
        return nonReserveDischargeLevel;
    }

    public double getWindTurbineFailureRatePerYear() {
        return windTurbineFailureRatePerYear;
    }

    public int getWindTurbineRepairTimeHours() {
        return windTurbineRepairTimeHours;
    }

    public double getDieselGeneratorFailureRatePerYear() {
        return dieselGeneratorFailureRatePerYear;
    }

    public int getDieselGeneratorRepairTimeHours() {
        return dieselGeneratorRepairTimeHours;
    }

    public double getBatteryFailureRatePerYear() {
        return batteryFailureRatePerYear;
    }

    public int getBatteryRepairTimeHours() {
        return batteryRepairTimeHours;
    }

    public double getBusFailureRatePerYear() {
        return busFailureRatePerYear;
    }

    public int getBusRepairTimeHours() {
        return busRepairTimeHours;
    }

    public double getBreakerFailureRatePerYear() {
        return breakerFailureRatePerYear;
    }

    public int getBreakerRepairTimeHours() {
        return breakerRepairTimeHours;
    }
}
