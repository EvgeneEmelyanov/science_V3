package simcore.config;

/**
 * Параметры энергосистемы (immutable).
 */
public class SystemParameters {

    private final BusSystemType busSystemType;
    private final double firstCat;
    private final double secondCat;
    private final int totalWindTurbineCount;
    private final double windTurbinePowerKw;
    private final int totalDieselGeneratorCount;
    private final double dieselGeneratorPowerKw;
    private final double batteryCapacityKwhPerBus;

    private final double windTurbineFailureRatePerYear;
    private final int windTurbineRepairTimeHours;
    private final double dieselGeneratorFailureRatePerYear;
    private final int dieselGeneratorRepairTimeHours;
    private final double batteryFailureRatePerYear;
    private final int batteryRepairTimeHours;
    private final double busFailureRatePerYear;
    private final int busRepairTimeHours;
    private final double breakerFailureRatePerYear;
    private final int breakerRepairTimeHours;
    private final double switchgearRoomFailureRatePerYear;
    private final int switchgearRoomRepairTimeHours;
    private final double busCcfBetaSectional;
    private final double busCcfBetaDouble;

    private final double maxChargeCurrent;
    private final double maxDischargeCurrent;
    private final double nonReserveDischargeLevel;
    private final boolean btUseAdaptiveNonReserveDischargeLevel;
    private final double btAdaptiveTrendWeight;
    private final double btAdaptiveAccelerationWeight;
    private final double btAdaptiveNoDgPrevHourWeight;
    private final double btAdaptiveReplacementWeight;
    private final double btAdaptiveDgAvailabilityWeight;
    private final double btGridFormingReserveShare;

    private final double idleReserveCoeff;
    private final double rotationReserveCoeff;
    private final boolean keepOneDgInstantStartReadyAfterWtBessGridForming;

    private final double discountRatePerYear;
    private final double costRuRub;
    private final double costDgRubPerKw;
    private final double costDgRubPerKwPerKmh;
    private final double costFuelRubPerKt;
    private final double costWtRubPerKw;
    private final double costWtRubPerKwPerYear;
    private final double costBtRubPerKwh;
    private final double costBtRubPerKwhPerYear;
    private final double damageRubPerKwhCat1;
    private final double damageRubPerKwhCat2;
    private final double damageRubPerKwhCat3;

    public SystemParameters(BusSystemType busSystemType,
                            double firstCat,
                            double secondCat,
                            int totalWindTurbineCount,
                            double windTurbinePowerKw,
                            int totalDieselGeneratorCount,
                            double dieselGeneratorPowerKw,
                            double batteryCapacityKwhPerBus,
                            double maxChargeCurrent,
                            double maxDischargeCurrent,
                            double nonReserveDischargeLevel,
                            boolean btUseAdaptiveNonReserveDischargeLevel,
                            double btAdaptiveTrendWeight,
                            double btAdaptiveAccelerationWeight,
                            double btAdaptiveNoDgPrevHourWeight,
                            double btAdaptiveReplacementWeight,
                            double btAdaptiveDgAvailabilityWeight,
                            double btGridFormingReserveShare,
                            double windTurbineFailureRatePerYear,
                            int windTurbineRepairTimeHours,
                            double dieselGeneratorFailureRatePerYear,
                            int dieselGeneratorRepairTimeHours,
                            double batteryFailureRatePerYear,
                            int batteryRepairTimeHours,
                            double busFailureRatePerYear,
                            int busRepairTimeHours,
                            double breakerFailureRatePerYear,
                            int breakerRepairTimeHours,
                            double switchgearRoomFailureRatePerYear,
                            int switchgearRoomRepairTimeHours,
                            double busCcfBetaSectional,
                            double busCcfBetaDouble,
                            double idleReserveCoeff,
                            double rotationReserveCoeff,
                            boolean keepOneDgInstantStartReadyAfterWtBessGridForming,
                            double discountRatePerYear,
                            double costRuRub,
                            double costDgRubPerKw,
                            double costDgRubPerKwPerKmh,
                            double costFuelRubPerKt,
                            double costWtRubPerKw,
                            double costWtRubPerKwPerYear,
                            double costBtRubPerKwh,
                            double costBtRubPerKwhPerYear,
                            double damageRubPerKwhCat1,
                            double damageRubPerKwhCat2,
                            double damageRubPerKwhCat3) {
        this.busSystemType = busSystemType;
        this.firstCat = firstCat;
        this.secondCat = secondCat;
        this.totalWindTurbineCount = totalWindTurbineCount;
        this.windTurbinePowerKw = windTurbinePowerKw;
        this.totalDieselGeneratorCount = totalDieselGeneratorCount;
        this.dieselGeneratorPowerKw = dieselGeneratorPowerKw;
        this.batteryCapacityKwhPerBus = batteryCapacityKwhPerBus;
        this.maxChargeCurrent = maxChargeCurrent;
        this.maxDischargeCurrent = maxDischargeCurrent;
        this.nonReserveDischargeLevel = nonReserveDischargeLevel;
        this.btUseAdaptiveNonReserveDischargeLevel = btUseAdaptiveNonReserveDischargeLevel;
        this.btAdaptiveTrendWeight = btAdaptiveTrendWeight;
        this.btAdaptiveAccelerationWeight = btAdaptiveAccelerationWeight;
        this.btAdaptiveNoDgPrevHourWeight = btAdaptiveNoDgPrevHourWeight;
        this.btAdaptiveReplacementWeight = btAdaptiveReplacementWeight;
        this.btAdaptiveDgAvailabilityWeight = btAdaptiveDgAvailabilityWeight;
        this.btGridFormingReserveShare = btGridFormingReserveShare;
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
        this.switchgearRoomFailureRatePerYear = switchgearRoomFailureRatePerYear;
        this.switchgearRoomRepairTimeHours = switchgearRoomRepairTimeHours;
        this.busCcfBetaSectional = busCcfBetaSectional;
        this.busCcfBetaDouble = busCcfBetaDouble;
        this.idleReserveCoeff = idleReserveCoeff;
        this.rotationReserveCoeff = rotationReserveCoeff;
        this.keepOneDgInstantStartReadyAfterWtBessGridForming = keepOneDgInstantStartReadyAfterWtBessGridForming;
        this.discountRatePerYear = discountRatePerYear;
        this.costRuRub = costRuRub;
        this.costDgRubPerKw = costDgRubPerKw;
        this.costDgRubPerKwPerKmh = costDgRubPerKwPerKmh;
        this.costFuelRubPerKt = costFuelRubPerKt;
        this.costWtRubPerKw = costWtRubPerKw;
        this.costWtRubPerKwPerYear = costWtRubPerKwPerYear;
        this.costBtRubPerKwh = costBtRubPerKwh;
        this.costBtRubPerKwhPerYear = costBtRubPerKwhPerYear;
        this.damageRubPerKwhCat1 = damageRubPerKwhCat1;
        this.damageRubPerKwhCat2 = damageRubPerKwhCat2;
        this.damageRubPerKwhCat3 = damageRubPerKwhCat3;
    }

    public SystemParameters copy() {
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
    public double getFirstCat() { return firstCat; }
    public double getSecondCat() { return secondCat; }
    public int getTotalWindTurbineCount() { return totalWindTurbineCount; }
    public double getWindTurbinePowerKw() { return windTurbinePowerKw; }
    public int getTotalDieselGeneratorCount() { return totalDieselGeneratorCount; }
    public double getDieselGeneratorPowerKw() { return dieselGeneratorPowerKw; }
    public double getBatteryCapacityKwhPerBus() { return batteryCapacityKwhPerBus; }
    public double getWindTurbineFailureRatePerYear() { return windTurbineFailureRatePerYear; }
    public int getWindTurbineRepairTimeHours() { return windTurbineRepairTimeHours; }
    public double getDieselGeneratorFailureRatePerYear() { return dieselGeneratorFailureRatePerYear; }
    public int getDieselGeneratorRepairTimeHours() { return dieselGeneratorRepairTimeHours; }
    public double getBatteryFailureRatePerYear() { return batteryFailureRatePerYear; }
    public int getBatteryRepairTimeHours() { return batteryRepairTimeHours; }
    public double getBusFailureRatePerYear() { return busFailureRatePerYear; }
    public int getBusRepairTimeHours() { return busRepairTimeHours; }
    public double getBreakerFailureRatePerYear() { return breakerFailureRatePerYear; }
    public int getBreakerRepairTimeHours() { return breakerRepairTimeHours; }
    public double getSwitchgearRoomFailureRatePerYear() { return switchgearRoomFailureRatePerYear; }
    public int getSwitchgearRoomRepairTimeHours() { return switchgearRoomRepairTimeHours; }
    public double getBusCcfBetaSectional() { return busCcfBetaSectional; }
    public double getBusCcfBetaDouble() { return busCcfBetaDouble; }
    public double getMaxChargeCurrent() { return maxChargeCurrent; }
    public double getMaxDischargeCurrent() { return maxDischargeCurrent; }
    public double getNonReserveDischargeLevel() { return nonReserveDischargeLevel; }
    public boolean isBtUseAdaptiveNonReserveDischargeLevel() { return btUseAdaptiveNonReserveDischargeLevel; }
    public double getBtAdaptiveTrendWeight() { return btAdaptiveTrendWeight; }
    public double getBtAdaptiveAccelerationWeight() { return btAdaptiveAccelerationWeight; }
    public double getBtAdaptiveNoDgPrevHourWeight() { return btAdaptiveNoDgPrevHourWeight; }
    public double getBtAdaptiveReplacementWeight() { return btAdaptiveReplacementWeight; }
    public double getBtAdaptiveDgAvailabilityWeight() { return btAdaptiveDgAvailabilityWeight; }
    public double getBtGridFormingReserveShare() { return btGridFormingReserveShare; }
    public double getIdleReserveCoeff() { return idleReserveCoeff; }
    public double getRotationReserveCoeff() { return rotationReserveCoeff; }
    public boolean isKeepOneDgInstantStartReadyAfterWtBessGridForming() { return keepOneDgInstantStartReadyAfterWtBessGridForming; }
    public double getDiscountRatePerYear() { return discountRatePerYear; }
    public double getCostRuRub() { return costRuRub; }
    public double getCostDgRubPerKw() { return costDgRubPerKw; }
    public double getCostDgRubPerKwPerKmh() { return costDgRubPerKwPerKmh; }
    public double getCostFuelRubPerKt() { return costFuelRubPerKt; }
    public double getCostWtRubPerKw() { return costWtRubPerKw; }
    public double getCostWtRubPerKwPerYear() { return costWtRubPerKwPerYear; }
    public double getCostBtRubPerKwh() { return costBtRubPerKwh; }
    public double getCostBtRubPerKwhPerYear() { return costBtRubPerKwhPerYear; }
    public double getDamageRubPerKwhCat1() { return damageRubPerKwhCat1; }
    public double getDamageRubPerKwhCat2() { return damageRubPerKwhCat2; }
    public double getDamageRubPerKwhCat3() { return damageRubPerKwhCat3; }
}
