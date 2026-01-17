package simcore.engine.bus;

import simcore.config.SimulationConstants;
import simcore.config.SystemParameters;
import simcore.model.PowerBus;

import java.util.List;

/**
 * Решение о замыкании межсекционного выключателя (для SINGLE_SECTIONAL_BUS).
 *
 * Логика перенесена 1:1 из SingleRunSimulator.
 */
public final class TieBreakerController {

    private TieBreakerController() {
    }

    public static boolean shouldCloseTieBreakerThisHour(SystemParameters sp,
                                                        List<PowerBus> buses,
                                                        double[] loads,
                                                        double windV,
                                                        double dgMaxKw) {
        double[] deficit = new double[2];
        double[] surplus = new double[2];

        for (int b = 0; b < 2; b++) {
            PowerBus bus = buses.get(b);
            double load = loads[b];

            double windPot = BusPotential.windPotentialNoSideEffects(bus, windV);
            double dgPot = BusPotential.dieselPotential(bus, dgMaxKw);
            double btPot = BusPotential.batteryDischargePotential(bus, sp);

            double pot = windPot + dgPot + btPot;

            deficit[b] = Math.max(0.0, load - pot);
            surplus[b] = Math.max(0.0, pot - load);
        }

        // Замыкать межсекционный имеет смысл только если на одной секции есть дефицит,
        // а на другой есть запас для покрытия (иначе объединение не помогает).
        boolean close01 = deficit[0] > SimulationConstants.EPSILON && surplus[1] > SimulationConstants.EPSILON;
        boolean close10 = deficit[1] > SimulationConstants.EPSILON && surplus[0] > SimulationConstants.EPSILON;
        return close01 || close10;
    }
}
