package simcore.model;

/**
 * Отказ помещения/ячейки РУ (общая причина), которая может выводить из строя одну или несколько шин.
 * Моделируется как отдельное оборудование с собственной интенсивностью отказов и временем ремонта.
 */
public final class SwitchgearRoom extends Equipment {

    public SwitchgearRoom(int id, double failureRatePerYear, int repairTimeHours) {
        super("ROOM", id, failureRatePerYear, repairTimeHours);
    }
}
