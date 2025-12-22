package simcore.config;

/**
 * Builder для SystemParameters.
 */
public class SystemParametersBuilder {

    private BusSystemType busSystemType;

    private double firstCat;
    private double secondCat;
    private double thirdCat;

    private int totalWindTurbineCount;
    private double windTurbinePowerKw;

    private int totalDieselGeneratorCount;
    private double dieselGeneratorPowerKw;

    private double batteryCapacityKwhPerBus;

    private double maxChargeCurrent;
    private double maxDischargeCurrent;
    private double nonReserveDischargeLevel;


    private double windTurbineFailureRatePerYear;
    private int windTurbineRepairTimeHours;

    private double dieselGeneratorFailureRatePerYear;
    private int dieselGeneratorRepairTimeHours;

    private double batteryFailureRatePerYear;
    private int batteryRepairTimeHours;

    private double busFailureRatePerYear;
    private int busRepairTimeHours;

    private double breakerFailureRatePerYear;
    private int breakerRepairTimeHours;

    public SystemParametersBuilder() {
    }

    /**
     * Создать builder на основе уже существующих параметров.
     */
    public static SystemParametersBuilder from(SystemParameters base) {
        SystemParametersBuilder b = new SystemParametersBuilder();
        b.firstCat = base.getFirstCat();
        b.secondCat = base.getSecondCat();
        b.thirdCat = base.getThirdCat();

        b.busSystemType = base.getBusSystemType();
        b.totalWindTurbineCount = base.getTotalWindTurbineCount();
        b.windTurbinePowerKw = base.getWindTurbinePowerKw();
        b.totalDieselGeneratorCount = base.getTotalDieselGeneratorCount();
        b.dieselGeneratorPowerKw = base.getDieselGeneratorPowerKw();
        b.batteryCapacityKwhPerBus = base.getBatteryCapacityKwhPerBus();
        b.maxChargeCurrent = base.getMaxChargeCurrent();
        b.maxDischargeCurrent = base.getMaxDischargeCurrent();
        b.nonReserveDischargeLevel = base.getNonReserveDischargeLevel();

        b.windTurbineFailureRatePerYear = base.getWindTurbineFailureRatePerYear();
        b.windTurbineRepairTimeHours = base.getWindTurbineRepairTimeHours();
        b.dieselGeneratorFailureRatePerYear = base.getDieselGeneratorFailureRatePerYear();
        b.dieselGeneratorRepairTimeHours = base.getDieselGeneratorRepairTimeHours();
        b.batteryFailureRatePerYear = base.getBatteryFailureRatePerYear();
        b.batteryRepairTimeHours = base.getBatteryRepairTimeHours();
        b.busFailureRatePerYear = base.getBusFailureRatePerYear();
        b.busRepairTimeHours = base.getBusRepairTimeHours();
        b.breakerFailureRatePerYear = base.getBreakerFailureRatePerYear();
        b.breakerRepairTimeHours = base.getBreakerRepairTimeHours();
        return b;
    }

    public SystemParameters build() {
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

    // --------- геттеры/сеттеры ---------

    public BusSystemType getBusSystemType() {
        return busSystemType;
    }

    public SystemParametersBuilder setBusSystemType(BusSystemType busSystemType) {
        this.busSystemType = busSystemType;
        return this;
    }

    public double getFirstCat() {
        return firstCat;
    }

    public SystemParametersBuilder setFirstCat(double firstCat) {
        this.firstCat = firstCat;
        return this;
    }

    public double getSecondCat() {
        return secondCat;
    }

    public SystemParametersBuilder setSecondCat(double secondCat) {
        this.secondCat = secondCat;
        return this;
    }

    public double getThirdCat() {
        return thirdCat;
    }

    public SystemParametersBuilder setThirdCat(double thirdCat) {
        this.thirdCat = thirdCat;
        return this;
    }

    public int getTotalWindTurbineCount() {
        return totalWindTurbineCount;
    }

    public SystemParametersBuilder setTotalWindTurbineCount(int totalWindTurbineCount) {
        this.totalWindTurbineCount = totalWindTurbineCount;
        return this;
    }

    public double getWindTurbinePowerKw() {
        return windTurbinePowerKw;
    }

    public SystemParametersBuilder setWindTurbinePowerKw(double windTurbinePowerKw) {
        this.windTurbinePowerKw = windTurbinePowerKw;
        return this;
    }

    public int getTotalDieselGeneratorCount() {
        return totalDieselGeneratorCount;
    }

    public SystemParametersBuilder setTotalDieselGeneratorCount(int totalDieselGeneratorCount) {
        this.totalDieselGeneratorCount = totalDieselGeneratorCount;
        return this;
    }

    public double getDieselGeneratorPowerKw() {
        return dieselGeneratorPowerKw;
    }

    public SystemParametersBuilder setDieselGeneratorPowerKw(double dieselGeneratorPowerKw) {
        this.dieselGeneratorPowerKw = dieselGeneratorPowerKw;
        return this;
    }

    public double getBatteryCapacityKwhPerBus() {
        return batteryCapacityKwhPerBus;
    }

    public SystemParametersBuilder setBatteryCapacityKwhPerBus(double batteryCapacityKwhPerBus) {
        this.batteryCapacityKwhPerBus = batteryCapacityKwhPerBus;
        return this;
    }

    public double getMaxChargeCurrent() {
        return maxChargeCurrent;
    }

    public SystemParametersBuilder setMaxChargeCurrent(double maxChargeCurrent) {
        this.maxChargeCurrent = maxChargeCurrent;
        return this;
    }

    public double getMaxDischargeCurrent() {
        return maxDischargeCurrent;
    }

    public SystemParametersBuilder setMaxDischargeCurrent(double maxDischargeCurrent) {
        this.maxDischargeCurrent = maxDischargeCurrent;
        return this;
    }

    public double getNonReserveDischargeLevel() {
        return nonReserveDischargeLevel;
    }

    public SystemParametersBuilder setNonReserveDischargeLevel(double nonReserveDischargeLevel) {
        this.nonReserveDischargeLevel = nonReserveDischargeLevel;
        return this;
    }


    public double getWindTurbineFailureRatePerYear() {
        return windTurbineFailureRatePerYear;
    }

    public SystemParametersBuilder setWindTurbineFailureRatePerYear(double windTurbineFailureRatePerYear) {
        this.windTurbineFailureRatePerYear = windTurbineFailureRatePerYear;
        return this;
    }

    public int getWindTurbineRepairTimeHours() {
        return windTurbineRepairTimeHours;
    }

    public SystemParametersBuilder setWindTurbineRepairTimeHours(int windTurbineRepairTimeHours) {
        this.windTurbineRepairTimeHours = windTurbineRepairTimeHours;
        return this;
    }

    public double getDieselGeneratorFailureRatePerYear() {
        return dieselGeneratorFailureRatePerYear;
    }

    public SystemParametersBuilder setDieselGeneratorFailureRatePerYear(double dieselGeneratorFailureRatePerYear) {
        this.dieselGeneratorFailureRatePerYear = dieselGeneratorFailureRatePerYear;
        return this;
    }

    public int getDieselGeneratorRepairTimeHours() {
        return dieselGeneratorRepairTimeHours;
    }

    public SystemParametersBuilder setDieselGeneratorRepairTimeHours(int dieselGeneratorRepairTimeHours) {
        this.dieselGeneratorRepairTimeHours = dieselGeneratorRepairTimeHours;
        return this;
    }

    public double getBatteryFailureRatePerYear() {
        return batteryFailureRatePerYear;
    }

    public SystemParametersBuilder setBatteryFailureRatePerYear(double batteryFailureRatePerYear) {
        this.batteryFailureRatePerYear = batteryFailureRatePerYear;
        return this;
    }

    public int getBatteryRepairTimeHours() {
        return batteryRepairTimeHours;
    }

    public SystemParametersBuilder setBatteryRepairTimeHours(int batteryRepairTimeHours) {
        this.batteryRepairTimeHours = batteryRepairTimeHours;
        return this;
    }

    public double getBusFailureRatePerYear() {
        return busFailureRatePerYear;
    }

    public SystemParametersBuilder setBusFailureRatePerYear(double busFailureRatePerYear) {
        this.busFailureRatePerYear = busFailureRatePerYear;
        return this;
    }

    public int getBusRepairTimeHours() {
        return busRepairTimeHours;
    }

    public SystemParametersBuilder setBusRepairTimeHours(int busRepairTimeHours) {
        this.busRepairTimeHours = busRepairTimeHours;
        return this;
    }

    public double getBreakerFailureRatePerYear() {
        return breakerFailureRatePerYear;
    }

    public SystemParametersBuilder setBreakerFailureRatePerYear(double breakerFailureRatePerYear) {
        this.breakerFailureRatePerYear = breakerFailureRatePerYear;
        return this;
    }

    public int getBreakerRepairTimeHours() {
        return breakerRepairTimeHours;
    }

    public SystemParametersBuilder setBreakerRepairTimeHours(int breakerRepairTimeHours) {
        this.breakerRepairTimeHours = breakerRepairTimeHours;
        return this;
    }
}
