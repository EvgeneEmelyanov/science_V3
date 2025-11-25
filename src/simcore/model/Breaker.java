package simcore.model;

/**
 * Автомат (секционный / шинный) между шинами.
 * Наследник Equipment, т.к. у него есть свои отказы и ремонты.
 */
public class Breaker extends Equipment {

    /** true = замкнут, false = разомкнут. */
    private boolean closed;

    /**
     * @param id                 id автомата
     * @param initiallyClosed    начальное состояние (замкнут/разомкнут)
     * @param failureRatePerYear частота отказов автомата, 1/год
     * @param repairTimeHours    длительность ремонта автомата, ч
     */
    public Breaker(int id,
                   boolean initiallyClosed,
                   double failureRatePerYear,
                   int repairTimeHours) {
        super("BRK", id, failureRatePerYear, repairTimeHours);
        this.closed = initiallyClosed;
    }

    public boolean isClosed() {
        return closed;
    }

    public void setClosed(boolean closed) {
        this.closed = closed;
    }
}
