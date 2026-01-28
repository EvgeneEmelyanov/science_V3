package simcore.sobol;

/**
 * Перечень всех параметров, которые в принципе можно менять в Соболе.
 * Сюда можешь добавлять всё, что захочешь:
 *  - частоты отказов;
 *  - времена ремонта;
 *  - мощности/количества оборудования;
 *  - параметры ветровой кривой и т.п.
 */
public enum TunableParamId {

    // Доли потребителей I и II
    FIRST_CAT,
    SECOND_CAT,

    // Частоты отказов
    WT_FAILURE_RATE,
    DG_FAILURE_RATE,
    BT_FAILURE_RATE,
    BUS_FAILURE_RATE,
    BRK_FAILURE_RATE,

    // Времена ремонта
    WT_REPAIR_TIME,
    DG_REPAIR_TIME,
    BT_REPAIR_TIME,
    BUS_REPAIR_TIME,
    ROOM_REPAIR_TIME,
    BRK_REPAIR_TIME,

    // Параметры ВЭУ
    WT_COUNT,
    WT_POWER,

    // Параметры ДГУ
    DG_COUNT,
    DG_POWER,

    // Параметры АКБ
    BT_CAPACITY_PER_BUS,
    BT_MAX_CHARGE_CURRENT,
    BT_MAX_DISCHARGE_CURRENT,
    BT_NON_RESERVE_DISCHARGE_LVL,

    DISCOUNT_RATE,
    COST_RU_RUB,
    COST_DG_RUB_PER_KW,
    COST_DG_RUB_PER_KW_PER_KMH,
    COST_FUEL_RUB_PER_KT,
    COST_WT_RUB_PER_KW,
    COST_WT_RUB_PER_KW_PER_YEAR,
    COST_BT_RUB_PER_KWH,
    COST_BT_RUB_PER_KWH_PER_YEAR,
    DAMAGE_RUB_PER_KWH_CAT1,
    DAMAGE_RUB_PER_KWH_CAT2,
    DAMAGE_RUB_PER_KWH_CAT3

    ;

    /**
     * Parameters that affect the stochastic event logic (failures/repairs).
     * For them, full CRN between A and AB may collapse Jansen ST towards ~0,
     * so HYBRID seed mode uses a separate seed stream for AB_j.
     */
    public boolean isReliabilityLike() {
        return switch (this) {
            case WT_FAILURE_RATE,
                 DG_FAILURE_RATE,
                 BT_FAILURE_RATE,
                 BUS_FAILURE_RATE,
                 BRK_FAILURE_RATE,
                 WT_REPAIR_TIME,
                 DG_REPAIR_TIME,
                 BT_REPAIR_TIME,
                 BUS_REPAIR_TIME,
                 ROOM_REPAIR_TIME,
                 BRK_REPAIR_TIME -> true;
            default -> false;
        };
    }
}
