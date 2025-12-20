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
    BT_NON_RESERVE_DISCHARGE_LVL
}
