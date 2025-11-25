package simcore.config;

/**
 * Builder для SystemParameters.
 *
 * Нужен для метода Соболя:
 *  - есть базовый набор параметров (base),
 *  - для каждой точки в пространстве (u1, u2, ..., ud) меняем нужные параметры,
 *  - создаём новый SystemParameters через build().
 *
 * Так мы можем удобно варьировать любой набор параметров:
 *  - только частоты отказов;
 *  - только параметры ВЭУ;
 *  - только параметры ДГУ;
 *  - или всё сразу.
 */
public class SystemParametersBuilder {

    private BusSystemType busSystemType;

    private int totalWindTurbineCount;
    private double windTurbinePowerKw;

    private int totalDieselGeneratorCount;
    private double dieselGeneratorPowerKw;

    private double batteryCapacityKwhPerBus;

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
        b.busSystemType = base.getBusSystemType();
        b.totalWindTurbineCount = base.getTotalWindTurbineCount();
        b.windTurbinePowerKw = base.getWindTurbinePowerKw();
        b.totalDieselGeneratorCount = base.getTotalDieselGeneratorCount();
        b.dieselGeneratorPowerKw = base.getDieselGeneratorPowerKw();
        b.batteryCapacityKwhPerBus = base.getBatteryCapacityKwhPerBus();
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
                totalWindTurbineCount,
                windTurbinePowerKw,
                totalDieselGeneratorCount,
                dieselGeneratorPowerKw,
                batteryCapacityKwhPerBus,
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
