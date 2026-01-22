package simcore.engine;

/**
 * Накопители суммарных метрик single-run симуляции.
 * Ранее был вложенным классом в SingleRunSimulator.
 */
public final class Totals {
    public double loadKwh;
    public double ensKwh;
    public double ensCat1Kwh;
    public double ensCat2Kwh;
    /** ENS caused by DG start delay ("start ENS"), kWh. */
    public double startEnsKwh;
    public double wreKwh;
    public double wtToLoadKwh;
    public double dgToLoadKwh;
    public double btToLoadKwh;
    public double fuelLiters;

    /** ENS event statistics (counts by duration buckets). */
    public final EnsEventStats ensEventStats = new EnsEventStats();
}
