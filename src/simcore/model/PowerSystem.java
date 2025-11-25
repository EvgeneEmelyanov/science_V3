package simcore.model;

import java.util.Collections;
import java.util.List;

/**
 * Энергосистема: набор шин + (опционально) автомат между ними.
 */
public class PowerSystem {

    private final List<PowerBus> buses;
    private final Breaker tieBreaker;

    public PowerSystem(List<PowerBus> buses, Breaker tieBreaker) {
        this.buses = buses;
        this.tieBreaker = tieBreaker;
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
}
