package simcore.engine;

/**
 * Одна строка "пошагового" вывода результатов моделирования
 * для отладки и анализа в Excel.
 */
public class SimulationStepRecord {

    /** Номер шага моделирования (например, час), начиная с 0 или 1 — на твой выбор. */
    private final int timeIndex;

    /** Общая нагрузка системы в этот момент, кВт. */
    private final double totalLoadKw;

    /** Нагрузка на шину 1, кВт (0, если шины нет/не используется). */
    private final double bus1LoadKw;

    /** Генерация всех ВЭУ на шине 1, кВт. */
    private final double bus1WindGenKw;

    /** Баланс шины 1: генерация - нагрузка (профицит > 0, дефицит < 0). */
    private final double bus1BalanceKw;

    /** Нагрузка на шину 2, кВт (0, если шины нет). */
    private final double bus2LoadKw;

    /** Генерация всех ВЭУ на шине 2, кВт. */
    private final double bus2WindGenKw;

    /** Баланс шины 2: генерация - нагрузка. */
    private final double bus2BalanceKw;

    public SimulationStepRecord(int timeIndex,
                                double totalLoadKw,
                                double bus1LoadKw,
                                double bus1WindGenKw,
                                double bus1BalanceKw,
                                double bus2LoadKw,
                                double bus2WindGenKw,
                                double bus2BalanceKw) {
        this.timeIndex = timeIndex;
        this.totalLoadKw = totalLoadKw;
        this.bus1LoadKw = bus1LoadKw;
        this.bus1WindGenKw = bus1WindGenKw;
        this.bus1BalanceKw = bus1BalanceKw;
        this.bus2LoadKw = bus2LoadKw;
        this.bus2WindGenKw = bus2WindGenKw;
        this.bus2BalanceKw = bus2BalanceKw;
    }

    public int getTimeIndex() {
        return timeIndex;
    }

    public double getTotalLoadKw() {
        return totalLoadKw;
    }

    public double getBus1LoadKw() {
        return bus1LoadKw;
    }

    public double getBus1WindGenKw() {
        return bus1WindGenKw;
    }

    public double getBus1BalanceKw() {
        return bus1BalanceKw;
    }

    public double getBus2LoadKw() {
        return bus2LoadKw;
    }

    public double getBus2WindGenKw() {
        return bus2WindGenKw;
    }

    public double getBus2BalanceKw() {
        return bus2BalanceKw;
    }
}
