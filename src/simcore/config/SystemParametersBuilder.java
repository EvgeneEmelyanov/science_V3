package simcore.config;

/**
 * Builder для SystemParameters.
 */
public class SystemParametersBuilder {

    private BusSystemType busSystemType;

    private double firstCat;
    private double secondCat;

    private int totalWindTurbineCount;
    private double windTurbinePowerKw;

    private int totalDieselGeneratorCount;
    private double dieselGeneratorPowerKw;

    private double batteryCapacityKwhPerBus;

    private double maxChargeCurrent;
    private double maxDischargeCurrent;
    private double nonReserveDischargeLevel;
    private boolean btUseAdaptiveNonReserveDischargeLevel;
    private double btAdaptiveReserveRiskWeight;
    private double btAdaptiveDeficitRiskWeight;
    private double btAdaptiveAccelerationRiskWeight;
    private double btAdaptiveReserveRiskScaleKw;
    private double btAdaptiveDeficitRiskScaleKw;
    private double btAdaptiveAccelerationRiskScaleKw;
    private double btAdaptiveAccelerationEmaAlpha;
    private double btAdaptiveRiskGain;
    private double btGridFormingReserveShare;


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

    private double switchgearRoomFailureRatePerYear;
    private int switchgearRoomRepairTimeHours;

    private double busCcfBetaSectional;
    private double busCcfBetaDouble;

    private double idleReserveCoeff;
    private double rotationReserveCoeff;
    private boolean keepOneDgInstantStartReadyAfterWtBessGridForming;

    // ----- economics -----
    private double discountRatePerYear;
    private double costRuRub;
    private double costDgRubPerKw;
    private double costDgRubPerKwPerKmh;
    private double costFuelRubPerKt;
    private double costWtRubPerKw;
    private double costWtRubPerKwPerYear;
    private double costBtRubPerKwh;
    private double costBtRubPerKwhPerYear;
    private double damageRubPerKwhCat1;
    private double damageRubPerKwhCat2;
    private double damageRubPerKwhCat3;

    public SystemParametersBuilder() {
    }

    /**
     * Создать builder на основе уже существующих параметров.
     */
    public static SystemParametersBuilder from(SystemParameters base) {
        SystemParametersBuilder b = new SystemParametersBuilder();
        b.firstCat = base.getFirstCat();
        b.secondCat = base.getSecondCat();

        b.busSystemType = base.getBusSystemType();
        b.totalWindTurbineCount = base.getTotalWindTurbineCount();
        b.windTurbinePowerKw = base.getWindTurbinePowerKw();
        b.totalDieselGeneratorCount = base.getTotalDieselGeneratorCount();
        b.dieselGeneratorPowerKw = base.getDieselGeneratorPowerKw();
        b.batteryCapacityKwhPerBus = base.getBatteryCapacityKwhPerBus();
        b.maxChargeCurrent = base.getMaxChargeCurrent();
        b.maxDischargeCurrent = base.getMaxDischargeCurrent();
        b.nonReserveDischargeLevel = base.getNonReserveDischargeLevel();
        b.btUseAdaptiveNonReserveDischargeLevel = base.isBtUseAdaptiveNonReserveDischargeLevel();
        b.btAdaptiveReserveRiskWeight = base.getBtAdaptiveReserveRiskWeight();
        b.btAdaptiveDeficitRiskWeight = base.getBtAdaptiveDeficitRiskWeight();
        b.btAdaptiveAccelerationRiskWeight = base.getBtAdaptiveAccelerationRiskWeight();
        b.btAdaptiveReserveRiskScaleKw = base.getBtAdaptiveReserveRiskScaleKw();
        b.btAdaptiveDeficitRiskScaleKw = base.getBtAdaptiveDeficitRiskScaleKw();
        b.btAdaptiveAccelerationRiskScaleKw = base.getBtAdaptiveAccelerationRiskScaleKw();
        b.btAdaptiveAccelerationEmaAlpha = base.getBtAdaptiveAccelerationEmaAlpha();
        b.btAdaptiveRiskGain = base.getBtAdaptiveRiskGain();
        b.btGridFormingReserveShare = base.getBtGridFormingReserveShare();
        b.idleReserveCoeff = base.getIdleReserveCoeff();
        b.rotationReserveCoeff = base.getRotationReserveCoeff();
        b.keepOneDgInstantStartReadyAfterWtBessGridForming = base.isKeepOneDgInstantStartReadyAfterWtBessGridForming();

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
        b.switchgearRoomFailureRatePerYear = base.getSwitchgearRoomFailureRatePerYear();
        b.switchgearRoomRepairTimeHours = base.getSwitchgearRoomRepairTimeHours();
        b.busCcfBetaSectional = base.getBusCcfBetaSectional();
        b.busCcfBetaDouble = base.getBusCcfBetaDouble();
        b.discountRatePerYear = base.getDiscountRatePerYear();
        b.costRuRub = base.getCostRuRub();
        b.costDgRubPerKw = base.getCostDgRubPerKw();
        b.costDgRubPerKwPerKmh = base.getCostDgRubPerKwPerKmh();
        b.costFuelRubPerKt = base.getCostFuelRubPerKt();
        b.costWtRubPerKw = base.getCostWtRubPerKw();
        b.costWtRubPerKwPerYear = base.getCostWtRubPerKwPerYear();
        b.costBtRubPerKwh = base.getCostBtRubPerKwh();
        b.costBtRubPerKwhPerYear = base.getCostBtRubPerKwhPerYear();
        b.damageRubPerKwhCat1 = base.getDamageRubPerKwhCat1();
        b.damageRubPerKwhCat2 = base.getDamageRubPerKwhCat2();
        b.damageRubPerKwhCat3 = base.getDamageRubPerKwhCat3();
        return b;
    }

    public SystemParameters build() {
        return new SystemParameters(
                busSystemType,
                firstCat,
                secondCat,

                totalWindTurbineCount,
                windTurbinePowerKw,
                totalDieselGeneratorCount,
                dieselGeneratorPowerKw,
                batteryCapacityKwhPerBus,
                maxChargeCurrent,
                maxDischargeCurrent,
                nonReserveDischargeLevel,
                btUseAdaptiveNonReserveDischargeLevel,
                btAdaptiveReserveRiskWeight,
                btAdaptiveDeficitRiskWeight,
                btAdaptiveAccelerationRiskWeight,
                btAdaptiveReserveRiskScaleKw,
                btAdaptiveDeficitRiskScaleKw,
                btAdaptiveAccelerationRiskScaleKw,
                btAdaptiveAccelerationEmaAlpha,
                btAdaptiveRiskGain,
                btGridFormingReserveShare,

                windTurbineFailureRatePerYear,
                windTurbineRepairTimeHours,
                dieselGeneratorFailureRatePerYear,
                dieselGeneratorRepairTimeHours,
                batteryFailureRatePerYear,
                batteryRepairTimeHours,
                busFailureRatePerYear,
                busRepairTimeHours,
                breakerFailureRatePerYear,
                breakerRepairTimeHours,
                switchgearRoomFailureRatePerYear,
                switchgearRoomRepairTimeHours,
                busCcfBetaSectional,
                busCcfBetaDouble,
                idleReserveCoeff,
                rotationReserveCoeff,
                keepOneDgInstantStartReadyAfterWtBessGridForming,

                discountRatePerYear,
                costRuRub,
                costDgRubPerKw,
                costDgRubPerKwPerKmh,
                costFuelRubPerKt,
                costWtRubPerKw,
                costWtRubPerKwPerYear,
                costBtRubPerKwh,
                costBtRubPerKwhPerYear,
                damageRubPerKwhCat1,
                damageRubPerKwhCat2,
                damageRubPerKwhCat3
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

    public boolean isBtUseAdaptiveNonReserveDischargeLevel() {
        return btUseAdaptiveNonReserveDischargeLevel;
    }

    public SystemParametersBuilder setBtUseAdaptiveNonReserveDischargeLevel(boolean btUseAdaptiveNonReserveDischargeLevel) {
        this.btUseAdaptiveNonReserveDischargeLevel = btUseAdaptiveNonReserveDischargeLevel;
        return this;
    }

    public double getBtAdaptiveReserveRiskWeight() {
        return btAdaptiveReserveRiskWeight;
    }

    public SystemParametersBuilder setBtAdaptiveReserveRiskWeight(double v) {
        this.btAdaptiveReserveRiskWeight = v;
        return this;
    }

    public double getBtAdaptiveDeficitRiskWeight() {
        return btAdaptiveDeficitRiskWeight;
    }

    public SystemParametersBuilder setBtAdaptiveDeficitRiskWeight(double v) {
        this.btAdaptiveDeficitRiskWeight = v;
        return this;
    }

    public double getBtAdaptiveAccelerationRiskWeight() {
        return btAdaptiveAccelerationRiskWeight;
    }

    public SystemParametersBuilder setBtAdaptiveAccelerationRiskWeight(double v) {
        this.btAdaptiveAccelerationRiskWeight = v;
        return this;
    }

    public double getBtAdaptiveReserveRiskScaleKw() {
        return btAdaptiveReserveRiskScaleKw;
    }

    public SystemParametersBuilder setBtAdaptiveReserveRiskScaleKw(double v) {
        this.btAdaptiveReserveRiskScaleKw = v;
        return this;
    }

    public double getBtAdaptiveDeficitRiskScaleKw() {
        return btAdaptiveDeficitRiskScaleKw;
    }

    public SystemParametersBuilder setBtAdaptiveDeficitRiskScaleKw(double v) {
        this.btAdaptiveDeficitRiskScaleKw = v;
        return this;
    }

    public double getBtAdaptiveAccelerationRiskScaleKw() {
        return btAdaptiveAccelerationRiskScaleKw;
    }

    public SystemParametersBuilder setBtAdaptiveAccelerationRiskScaleKw(double v) {
        this.btAdaptiveAccelerationRiskScaleKw = v;
        return this;
    }

    public double getBtAdaptiveAccelerationEmaAlpha() {
        return btAdaptiveAccelerationEmaAlpha;
    }

    public SystemParametersBuilder setBtAdaptiveAccelerationEmaAlpha(double v) {
        this.btAdaptiveAccelerationEmaAlpha = v;
        return this;
    }

    public double getBtAdaptiveRiskGain() {
        return btAdaptiveRiskGain;
    }

    public SystemParametersBuilder setBtAdaptiveRiskGain(double v) {
        this.btAdaptiveRiskGain = v;
        return this;
    }

    public double getBtGridFormingReserveShare() {
        return btGridFormingReserveShare;
    }

    public SystemParametersBuilder setBtGridFormingReserveShare(double btGridFormingReserveShare) {
        this.btGridFormingReserveShare = Math.max(0.0, Math.min(1.0, btGridFormingReserveShare));
        return this;
    }

    public boolean isKeepOneDgInstantStartReadyAfterWtBessGridForming() {
        return keepOneDgInstantStartReadyAfterWtBessGridForming;
    }

    public SystemParametersBuilder setKeepOneDgInstantStartReadyAfterWtBessGridForming(boolean v) {
        this.keepOneDgInstantStartReadyAfterWtBessGridForming = v;
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


    public double getSwitchgearRoomFailureRatePerYear() {
        return switchgearRoomFailureRatePerYear;
    }

    public SystemParametersBuilder setSwitchgearRoomFailureRatePerYear(double switchgearRoomFailureRatePerYear) {
        this.switchgearRoomFailureRatePerYear = switchgearRoomFailureRatePerYear;
        return this;
    }

    public int getSwitchgearRoomRepairTimeHours() {
        return switchgearRoomRepairTimeHours;
    }

    public SystemParametersBuilder setSwitchgearRoomRepairTimeHours(int switchgearRoomRepairTimeHours) {
        this.switchgearRoomRepairTimeHours = switchgearRoomRepairTimeHours;
        return this;
    }


    public double getBusCcfBetaSectional() {
        return busCcfBetaSectional;
    }

    public SystemParametersBuilder setBusCcfBetaSectional(double busCcfBetaSectional) {
        this.busCcfBetaSectional = busCcfBetaSectional;
        return this;
    }

    public double getBusCcfBetaDouble() {
        return busCcfBetaDouble;
    }

    public SystemParametersBuilder setBusCcfBetaDouble(double busCcfBetaDouble) {
        this.busCcfBetaDouble = busCcfBetaDouble;
        return this;
    }

    // ===== economics setters =====

    public double getDiscountRatePerYear() { return discountRatePerYear; }
    public SystemParametersBuilder setDiscountRatePerYear(double v) { this.discountRatePerYear = v; return this; }

    public double getCostRuRub() { return costRuRub; }
    public SystemParametersBuilder setCostRuRub(double v) { this.costRuRub = v; return this; }

    public double getCostDgRubPerKw() { return costDgRubPerKw; }
    public SystemParametersBuilder setCostDgRubPerKw(double v) { this.costDgRubPerKw = v; return this; }

    public double getCostDgRubPerKwPerKmh() { return costDgRubPerKwPerKmh; }
    public SystemParametersBuilder setCostDgRubPerKwPerKmh(double v) { this.costDgRubPerKwPerKmh = v; return this; }

    public double getCostFuelRubPerKt() { return costFuelRubPerKt; }
    public SystemParametersBuilder setCostFuelRubPerKt(double v) { this.costFuelRubPerKt = v; return this; }

    public double getCostWtRubPerKw() { return costWtRubPerKw; }
    public SystemParametersBuilder setCostWtRubPerKw(double v) { this.costWtRubPerKw = v; return this; }

    public double getCostWtRubPerKwPerYear() { return costWtRubPerKwPerYear; }
    public SystemParametersBuilder setCostWtRubPerKwPerYear(double v) { this.costWtRubPerKwPerYear = v; return this; }

    public double getCostBtRubPerKwh() { return costBtRubPerKwh; }
    public SystemParametersBuilder setCostBtRubPerKwh(double v) { this.costBtRubPerKwh = v; return this; }

    public double getCostBtRubPerKwhPerYear() { return costBtRubPerKwhPerYear; }
    public SystemParametersBuilder setCostBtRubPerKwhPerYear(double v) { this.costBtRubPerKwhPerYear = v; return this; }

    public double getDamageRubPerKwhCat1() { return damageRubPerKwhCat1; }
    public SystemParametersBuilder setDamageRubPerKwhCat1(double v) { this.damageRubPerKwhCat1 = v; return this; }

    public double getDamageRubPerKwhCat2() { return damageRubPerKwhCat2; }
    public SystemParametersBuilder setDamageRubPerKwhCat2(double v) { this.damageRubPerKwhCat2 = v; return this; }

    public double getDamageRubPerKwhCat3() { return damageRubPerKwhCat3; }
    public SystemParametersBuilder setDamageRubPerKwhCat3(double v) { this.damageRubPerKwhCat3 = v; return this; }

    public double getIdleReserveCoeff() {
        return idleReserveCoeff;
    }

    public SystemParametersBuilder setIdleReserveCoeff(double idleReserveCoeff) {
        this.idleReserveCoeff = idleReserveCoeff;
        return this;
    }

    public double getRotationReserveCoeff() {
        return rotationReserveCoeff;
    }

    public SystemParametersBuilder setRotationReserveCoeff(double rotationReserveCoeff) {
        this.rotationReserveCoeff = rotationReserveCoeff;
        return this;
    }

}
