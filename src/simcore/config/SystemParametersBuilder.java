package simcore.config;

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
    private double btAdaptiveTrendWeight;
    private double btAdaptiveAccelerationWeight;
    private double btAdaptiveNoDgPrevHourWeight;
    private double btAdaptiveReplacementWeight;
    private double btAdaptiveDgAvailabilityWeight;
    private double btGridFormingReserveShare;
    private double idleReserveCoeff;
    private double rotationReserveCoeff;
    private boolean keepOneDgInstantStartReadyAfterWtBessGridForming;
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

    public static SystemParametersBuilder from(SystemParameters base) {
        SystemParametersBuilder b = new SystemParametersBuilder();
        b.busSystemType = base.getBusSystemType();
        b.firstCat = base.getFirstCat();
        b.secondCat = base.getSecondCat();
        b.totalWindTurbineCount = base.getTotalWindTurbineCount();
        b.windTurbinePowerKw = base.getWindTurbinePowerKw();
        b.totalDieselGeneratorCount = base.getTotalDieselGeneratorCount();
        b.dieselGeneratorPowerKw = base.getDieselGeneratorPowerKw();
        b.batteryCapacityKwhPerBus = base.getBatteryCapacityKwhPerBus();
        b.maxChargeCurrent = base.getMaxChargeCurrent();
        b.maxDischargeCurrent = base.getMaxDischargeCurrent();
        b.nonReserveDischargeLevel = base.getNonReserveDischargeLevel();
        b.btUseAdaptiveNonReserveDischargeLevel = base.isBtUseAdaptiveNonReserveDischargeLevel();
        b.btAdaptiveTrendWeight = base.getBtAdaptiveTrendWeight();
        b.btAdaptiveAccelerationWeight = base.getBtAdaptiveAccelerationWeight();
        b.btAdaptiveNoDgPrevHourWeight = base.getBtAdaptiveNoDgPrevHourWeight();
        b.btAdaptiveReplacementWeight = base.getBtAdaptiveReplacementWeight();
        b.btAdaptiveDgAvailabilityWeight = base.getBtAdaptiveDgAvailabilityWeight();
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
                busSystemType, firstCat, secondCat,
                totalWindTurbineCount, windTurbinePowerKw,
                totalDieselGeneratorCount, dieselGeneratorPowerKw,
                batteryCapacityKwhPerBus,
                maxChargeCurrent, maxDischargeCurrent, nonReserveDischargeLevel,
                btUseAdaptiveNonReserveDischargeLevel,
                btAdaptiveTrendWeight, btAdaptiveAccelerationWeight,
                btAdaptiveNoDgPrevHourWeight, btAdaptiveReplacementWeight,
                btAdaptiveDgAvailabilityWeight, btGridFormingReserveShare,
                windTurbineFailureRatePerYear, windTurbineRepairTimeHours,
                dieselGeneratorFailureRatePerYear, dieselGeneratorRepairTimeHours,
                batteryFailureRatePerYear, batteryRepairTimeHours,
                busFailureRatePerYear, busRepairTimeHours,
                breakerFailureRatePerYear, breakerRepairTimeHours,
                switchgearRoomFailureRatePerYear, switchgearRoomRepairTimeHours,
                busCcfBetaSectional, busCcfBetaDouble,
                idleReserveCoeff, rotationReserveCoeff,
                keepOneDgInstantStartReadyAfterWtBessGridForming,
                discountRatePerYear, costRuRub, costDgRubPerKw, costDgRubPerKwPerKmh,
                costFuelRubPerKt, costWtRubPerKw, costWtRubPerKwPerYear,
                costBtRubPerKwh, costBtRubPerKwhPerYear,
                damageRubPerKwhCat1, damageRubPerKwhCat2, damageRubPerKwhCat3
        );
    }

    public BusSystemType getBusSystemType() { return busSystemType; }
    public SystemParametersBuilder setBusSystemType(BusSystemType v) { this.busSystemType = v; return this; }
    public double getFirstCat() { return firstCat; }
    public SystemParametersBuilder setFirstCat(double v) { this.firstCat = v; return this; }
    public double getSecondCat() { return secondCat; }
    public SystemParametersBuilder setSecondCat(double v) { this.secondCat = v; return this; }
    public int getTotalWindTurbineCount() { return totalWindTurbineCount; }
    public SystemParametersBuilder setTotalWindTurbineCount(int v) { this.totalWindTurbineCount = v; return this; }
    public double getWindTurbinePowerKw() { return windTurbinePowerKw; }
    public SystemParametersBuilder setWindTurbinePowerKw(double v) { this.windTurbinePowerKw = v; return this; }
    public int getTotalDieselGeneratorCount() { return totalDieselGeneratorCount; }
    public SystemParametersBuilder setTotalDieselGeneratorCount(int v) { this.totalDieselGeneratorCount = v; return this; }
    public double getDieselGeneratorPowerKw() { return dieselGeneratorPowerKw; }
    public SystemParametersBuilder setDieselGeneratorPowerKw(double v) { this.dieselGeneratorPowerKw = v; return this; }
    public double getBatteryCapacityKwhPerBus() { return batteryCapacityKwhPerBus; }
    public SystemParametersBuilder setBatteryCapacityKwhPerBus(double v) { this.batteryCapacityKwhPerBus = v; return this; }
    public double getMaxChargeCurrent() { return maxChargeCurrent; }
    public SystemParametersBuilder setMaxChargeCurrent(double v) { this.maxChargeCurrent = v; return this; }
    public double getMaxDischargeCurrent() { return maxDischargeCurrent; }
    public SystemParametersBuilder setMaxDischargeCurrent(double v) { this.maxDischargeCurrent = v; return this; }
    public double getNonReserveDischargeLevel() { return nonReserveDischargeLevel; }
    public SystemParametersBuilder setNonReserveDischargeLevel(double v) { this.nonReserveDischargeLevel = v; return this; }
    public boolean isBtUseAdaptiveNonReserveDischargeLevel() { return btUseAdaptiveNonReserveDischargeLevel; }
    public SystemParametersBuilder setBtUseAdaptiveNonReserveDischargeLevel(boolean v) { this.btUseAdaptiveNonReserveDischargeLevel = v; return this; }
    public double getBtAdaptiveTrendWeight() { return btAdaptiveTrendWeight; }
    public SystemParametersBuilder setBtAdaptiveTrendWeight(double v) { this.btAdaptiveTrendWeight = v; return this; }
    public double getBtAdaptiveAccelerationWeight() { return btAdaptiveAccelerationWeight; }
    public SystemParametersBuilder setBtAdaptiveAccelerationWeight(double v) { this.btAdaptiveAccelerationWeight = v; return this; }
    public double getBtAdaptiveNoDgPrevHourWeight() { return btAdaptiveNoDgPrevHourWeight; }
    public SystemParametersBuilder setBtAdaptiveNoDgPrevHourWeight(double v) { this.btAdaptiveNoDgPrevHourWeight = v; return this; }
    public double getBtAdaptiveReplacementWeight() { return btAdaptiveReplacementWeight; }
    public SystemParametersBuilder setBtAdaptiveReplacementWeight(double v) { this.btAdaptiveReplacementWeight = v; return this; }
    public double getBtAdaptiveDgAvailabilityWeight() { return btAdaptiveDgAvailabilityWeight; }
    public SystemParametersBuilder setBtAdaptiveDgAvailabilityWeight(double v) { this.btAdaptiveDgAvailabilityWeight = v; return this; }
    public double getBtGridFormingReserveShare() { return btGridFormingReserveShare; }
    public SystemParametersBuilder setBtGridFormingReserveShare(double v) { this.btGridFormingReserveShare = Math.max(0.0, Math.min(1.0, v)); return this; }
    public boolean isKeepOneDgInstantStartReadyAfterWtBessGridForming() { return keepOneDgInstantStartReadyAfterWtBessGridForming; }
    public SystemParametersBuilder setKeepOneDgInstantStartReadyAfterWtBessGridForming(boolean v) { this.keepOneDgInstantStartReadyAfterWtBessGridForming = v; return this; }
    public double getIdleReserveCoeff() { return idleReserveCoeff; }
    public SystemParametersBuilder setIdleReserveCoeff(double v) { this.idleReserveCoeff = v; return this; }
    public double getRotationReserveCoeff() { return rotationReserveCoeff; }
    public SystemParametersBuilder setRotationReserveCoeff(double v) { this.rotationReserveCoeff = v; return this; }
    public double getWindTurbineFailureRatePerYear() { return windTurbineFailureRatePerYear; }
    public SystemParametersBuilder setWindTurbineFailureRatePerYear(double v) { this.windTurbineFailureRatePerYear = v; return this; }
    public int getWindTurbineRepairTimeHours() { return windTurbineRepairTimeHours; }
    public SystemParametersBuilder setWindTurbineRepairTimeHours(int v) { this.windTurbineRepairTimeHours = v; return this; }
    public double getDieselGeneratorFailureRatePerYear() { return dieselGeneratorFailureRatePerYear; }
    public SystemParametersBuilder setDieselGeneratorFailureRatePerYear(double v) { this.dieselGeneratorFailureRatePerYear = v; return this; }
    public int getDieselGeneratorRepairTimeHours() { return dieselGeneratorRepairTimeHours; }
    public SystemParametersBuilder setDieselGeneratorRepairTimeHours(int v) { this.dieselGeneratorRepairTimeHours = v; return this; }
    public double getBatteryFailureRatePerYear() { return batteryFailureRatePerYear; }
    public SystemParametersBuilder setBatteryFailureRatePerYear(double v) { this.batteryFailureRatePerYear = v; return this; }
    public int getBatteryRepairTimeHours() { return batteryRepairTimeHours; }
    public SystemParametersBuilder setBatteryRepairTimeHours(int v) { this.batteryRepairTimeHours = v; return this; }
    public double getBusFailureRatePerYear() { return busFailureRatePerYear; }
    public SystemParametersBuilder setBusFailureRatePerYear(double v) { this.busFailureRatePerYear = v; return this; }
    public int getBusRepairTimeHours() { return busRepairTimeHours; }
    public SystemParametersBuilder setBusRepairTimeHours(int v) { this.busRepairTimeHours = v; return this; }
    public double getBreakerFailureRatePerYear() { return breakerFailureRatePerYear; }
    public SystemParametersBuilder setBreakerFailureRatePerYear(double v) { this.breakerFailureRatePerYear = v; return this; }
    public int getBreakerRepairTimeHours() { return breakerRepairTimeHours; }
    public SystemParametersBuilder setBreakerRepairTimeHours(int v) { this.breakerRepairTimeHours = v; return this; }
    public double getSwitchgearRoomFailureRatePerYear() { return switchgearRoomFailureRatePerYear; }
    public SystemParametersBuilder setSwitchgearRoomFailureRatePerYear(double v) { this.switchgearRoomFailureRatePerYear = v; return this; }
    public int getSwitchgearRoomRepairTimeHours() { return switchgearRoomRepairTimeHours; }
    public SystemParametersBuilder setSwitchgearRoomRepairTimeHours(int v) { this.switchgearRoomRepairTimeHours = v; return this; }
    public double getBusCcfBetaSectional() { return busCcfBetaSectional; }
    public SystemParametersBuilder setBusCcfBetaSectional(double v) { this.busCcfBetaSectional = v; return this; }
    public double getBusCcfBetaDouble() { return busCcfBetaDouble; }
    public SystemParametersBuilder setBusCcfBetaDouble(double v) { this.busCcfBetaDouble = v; return this; }
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
}
