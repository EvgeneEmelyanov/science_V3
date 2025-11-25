package simcore.config;

/**
 * Тип системы шин: одна шина или две.
 */
public enum BusSystemType {
    SINGLE_NOT_SECTIONAL_BUS,  // одиночная несекционированная система шин
    SINGLE_SECTIONAL_BUS,  // одиночная секционированная система шин
    DOUBLE_BUS   // двойная система шин
}
