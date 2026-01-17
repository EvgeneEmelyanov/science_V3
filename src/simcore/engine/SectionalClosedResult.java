package simcore.engine;

/**
 * Result of dispatching one hour in "sectional tie breaker closed" mode.
 *
 * Extracted from {@link SingleRunSimulator} to reduce file size.
 */
final class SectionalClosedResult {
    final double loadKwh;
    final double ensKwh;
    final double wreKwh;
    final double wtToLoadKwh;
    final double dgToLoadKwh;
    final double btToLoadKwh;
    final double fuelLiters;

    final double[] windToLoadByBus;
    final double[] dgToLoadByBus;
    final double[] btNetByBus;
    final double[] defByBus;
    final double[] startEnsByBus;

    SectionalClosedResult(double loadKwh,
                          double ensKwh,
                          double wreKwh,
                          double wtToLoadKwh,
                          double dgToLoadKwh,
                          double btToLoadKwh,
                          double fuelLiters,
                          double[] windToLoadByBus,
                          double[] dgToLoadByBus,
                          double[] btNetByBus,
                          double[] defByBus,
                          double[] startEnsByBus) {
        this.loadKwh = loadKwh;
        this.ensKwh = ensKwh;
        this.wreKwh = wreKwh;
        this.wtToLoadKwh = wtToLoadKwh;
        this.dgToLoadKwh = dgToLoadKwh;
        this.btToLoadKwh = btToLoadKwh;
        this.fuelLiters = fuelLiters;
        this.windToLoadByBus = windToLoadByBus;
        this.dgToLoadByBus = dgToLoadByBus;
        this.btNetByBus = btNetByBus;
        this.defByBus = defByBus;
        this.startEnsByBus = startEnsByBus;
    }
}
