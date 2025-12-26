package simcore.model;

import java.util.Collections;
import java.util.List;

/**
 * Энергосистема: набор шин + (опционально) автомат между ними + (опционально) отказ помещения/РУ.
 */
public class PowerSystem {

    private final List<PowerBus> buses;
    private final Breaker tieBreaker;

    /**
     * Набор помещений/ячееек РУ, влияющих на доступность шин.
     * Может содержать 1 элемент (общее помещение) или по одному на каждую шину.
     */
    private final List<SwitchgearRoom> rooms;

    /**
     * Для каждой шины — индекс помещения в списке rooms.
     * Например, для секционированной шины обе секции могут ссылаться на один и тот же roomIndex.
     */
    private final int[] roomIndexByBus;

    public PowerSystem(List<PowerBus> buses, Breaker tieBreaker, List<SwitchgearRoom> rooms, int[] roomIndexByBus) {
        this.buses = buses;
        this.tieBreaker = tieBreaker;
        this.rooms = rooms;
        this.roomIndexByBus = roomIndexByBus;
    }

    public List<PowerBus> getBuses() {
        return Collections.unmodifiableList(buses);
    }

    /**
     * Возвращает автомат между шинами, если он есть.
     * Для SINGLE_NOT_SECTIONAL_BUS возвращается null.
     */
    public Breaker getTieBreaker() {
        return tieBreaker;
    }

    public List<SwitchgearRoom> getRooms() {
        return Collections.unmodifiableList(rooms);
    }

    public int[] getRoomIndexByBus() {
        return roomIndexByBus;
    }

    public SwitchgearRoom getRoomForBus(int busIndex) {
        return rooms.get(roomIndexByBus[busIndex]);
    }
}
