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

    /**
     * Частота отказов помещения/РУ (общая причина), 1/год.
     * Для SINGLE_SECTIONAL_BUS обычно означает отказ общего помещения на две секции.
     * Для DOUBLE_BUS (если шины в разных РУ) — отказ помещения конкретной шины.
     */
    private final double switchgearRoomFailureRatePerYear;

    /**
     * Время ремонта/восстановления помещения/РУ, ч.
     */
    private final int switchgearRoomRepairTimeHours;

    /**
     * β для разложения отказов шин на независимую часть и общую (CCF) для SINGLE_SECTIONAL_BUS.
     * λ_ind = λ_bus*(1-β), λ_room = λ_bus*β
     */
    private final double busCcfBetaSectional;

    /**
     * β для разложения отказов шин на независимую часть и общую (CCF) для DOUBLE_BUS.
     * Обычно меньше, чем для секционированной (если разные РУ/помещения).
     */
    private final double busCcfBetaDouble;

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
    private final boolean btUseAdaptiveNonReserveDischargeLevel;
    private final double btAdaptiveReserveRiskWeight;
    private final double btAdaptiveDeficitRiskWeight;
    private final double btAdaptiveAccelerationRiskWeight;
    private final double btAdaptiveReserveRiskScaleKw;
    private final double btAdaptiveDeficitRiskScaleKw;
    private final double btAdaptiveAccelerationRiskScaleKw;
    private final double btAdaptiveAccelerationEmaAlpha;
    private final double btAdaptiveRiskGain;
    private final double btGridFormingReserveShare;

    private final double idleReserveCoeff;
    private final double rotationReserveCoeff;
    private final boolean keepOneDgInstantStartReadyAfterWtBessGridForming;

    // ---------- Экономические параметры (для LCOE) ----------

    /** Ставка дисконтирования, 1/год (например, 0.08 = 8%). */
    private final double discountRatePerYear;

    /** CAPEX: стоимость РУ, руб. */
    private final double costRuRub;

    /** CAPEX: стоимость ДГУ, руб/кВт установленной мощности. */
    private final double costDgRubPerKw;

    /** OPEX/ТО: стоимость ДГУ, руб/(кВт * тыс.мото-часов). */
    private final double costDgRubPerKwPerKmh;

    /** Топливо: стоимость, руб/кт (в модели расход топлива в "млн.л", как в Excel). */
    private final double costFuelRubPerKt;

    /** CAPEX: стоимость ВЭУ, руб/кВт установленной мощности. */
    private final double costWtRubPerKw;

    /** OPEX: обслуживание ВЭУ, руб/(кВт*год). */
    private final double costWtRubPerKwPerYear;

    /** CAPEX: стоимость АКБ, руб/(кВт*ч установленной ёмкости). */
    private final double costBtRubPerKwh;

    /** OPEX: обслуживание АКБ, руб/(кВт*ч*год). */
    private final double costBtRubPerKwhPerYear;

    /** Ущерб (VOLL), руб/кВт*ч недоотпуска 1 категории. */
    private final double damageRubPerKwhCat1;

    /** Ущерб (VOLL), руб/кВт*ч недоотпуска 2 категории. */
    private final double damageRubPerKwhCat2;

    /** Ущерб (VOLL), руб/кВт*ч недоотпуска 3 категории. */
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
                            double btAdaptiveReserveRiskWeight,
                            double btAdaptiveDeficitRiskWeight,
                            double btAdaptiveAccelerationRiskWeight,
                            double btAdaptiveReserveRiskScaleKw,
                            double btAdaptiveDeficitRiskScaleKw,
                            double btAdaptiveAccelerationRiskScaleKw,
                            double btAdaptiveAccelerationEmaAlpha,
                            double btAdaptiveRiskGain,
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
        this.btAdaptiveReserveRiskWeight = btAdaptiveReserveRiskWeight;
        this.btAdaptiveDeficitRiskWeight = btAdaptiveDeficitRiskWeight;
        this.btAdaptiveAccelerationRiskWeight = btAdaptiveAccelerationRiskWeight;
        this.btAdaptiveReserveRiskScaleKw = btAdaptiveReserveRiskScaleKw;
        this.btAdaptiveDeficitRiskScaleKw = btAdaptiveDeficitRiskScaleKw;
        this.btAdaptiveAccelerationRiskScaleKw = btAdaptiveAccelerationRiskScaleKw;
        this.btAdaptiveAccelerationEmaAlpha = btAdaptiveAccelerationEmaAlpha;
        this.btAdaptiveRiskGain = btAdaptiveRiskGain;
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

    // --------- Copy helpers ---------

    public SystemParameters copy() {
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
        return 1.0 - (firstCat + secondCat);
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

    public boolean isBtUseAdaptiveNonReserveDischargeLevel() {
        return btUseAdaptiveNonReserveDischargeLevel;
    }

    public double getBtAdaptiveReserveRiskWeight() {
        return btAdaptiveReserveRiskWeight;
    }

    public double getBtAdaptiveDeficitRiskWeight() {
        return btAdaptiveDeficitRiskWeight;
    }

    public double getBtAdaptiveAccelerationRiskWeight() {
        return btAdaptiveAccelerationRiskWeight;
    }

    public double getBtAdaptiveReserveRiskScaleKw() {
        return btAdaptiveReserveRiskScaleKw;
    }

    public double getBtAdaptiveDeficitRiskScaleKw() {
        return btAdaptiveDeficitRiskScaleKw;
    }

    public double getBtAdaptiveAccelerationRiskScaleKw() {
        return btAdaptiveAccelerationRiskScaleKw;
    }

    public double getBtAdaptiveAccelerationEmaAlpha() {
        return btAdaptiveAccelerationEmaAlpha;
    }

    public double getBtAdaptiveRiskGain() {
        return btAdaptiveRiskGain;
    }

    public double getBtGridFormingReserveShare() {
        return btGridFormingReserveShare;
    }

    // ----- Алиасы с новой семантикой adaptive non-reserve -----
    public double getBtAdaptiveReplacementWeight() {
        return btAdaptiveReserveRiskWeight;
    }

    public double getBtAdaptiveTrendWeight() {
        return btAdaptiveDeficitRiskWeight;
    }

    public double getBtAdaptiveAccelerationWeight() {
        return btAdaptiveAccelerationRiskWeight;
    }

    public double getBtAdaptiveTrendScaleKw() {
        return btAdaptiveReserveRiskScaleKw;
    }

    public double getBtAdaptiveAccelerationScaleKw() {
        return btAdaptiveDeficitRiskScaleKw;
    }

    public double getBtAdaptiveNoDgPrevHourBonus() {
        return Math.max(0.0, btAdaptiveAccelerationRiskScaleKw);
    }

    public double getBtAdaptiveReplacementExponent() {
        return btAdaptiveRiskGain;
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

    public double getSwitchgearRoomFailureRatePerYear() {
        return switchgearRoomFailureRatePerYear;
    }

    public int getSwitchgearRoomRepairTimeHours() {
        return switchgearRoomRepairTimeHours;
    }

    public double getBusCcfBetaSectional() {
        return busCcfBetaSectional;
    }

    public double getBusCcfBetaDouble() {
        return busCcfBetaDouble;
    }

    public double getIdleReserveCoeff() { return idleReserveCoeff; }
    public double getRotationReserveCoeff() { return rotationReserveCoeff; }
    public boolean isKeepOneDgInstantStartReadyAfterWtBessGridForming() { return keepOneDgInstantStartReadyAfterWtBessGridForming; }
    // ---------- getters: economics ----------

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
