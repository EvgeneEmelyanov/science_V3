package simcore.engine.bus;

import simcore.config.SimulationConstants;
import simcore.config.SystemParameters;
import simcore.model.PowerBus;

import java.util.List;

/**
 * Решение о замыкании межсекционного выключателя (для SINGLE_SECTIONAL_BUS).
 */
public final class TieBreakerController {

    private TieBreakerController() {
    }

    public static boolean shouldCloseTieBreakerThisHour(SystemParameters sp,
                                                        List<PowerBus> buses,
                                                        double[] loads,
                                                        double windV,
                                                        double dgMaxKw) {
        // Avoid small array allocations in the hourly loop: use scalars.
        PowerBus bus0 = buses.get(0);
        PowerBus bus1 = buses.get(1);

        double load0 = loads[0];
        double load1 = loads[1];

        double pot0 = BusPotential.windPotentialNoSideEffects(bus0, windV)
                + BusPotential.dieselPotential(bus0, dgMaxKw)
                + BusPotential.batteryDischargePotential(bus0, sp);
        double pot1 = BusPotential.windPotentialNoSideEffects(bus1, windV)
                + BusPotential.dieselPotential(bus1, dgMaxKw)
                + BusPotential.batteryDischargePotential(bus1, sp);

        double deficit0 = Math.max(0.0, load0 - pot0);
        double deficit1 = Math.max(0.0, load1 - pot1);
        double surplus0 = Math.max(0.0, pot0 - load0);
        double surplus1 = Math.max(0.0, pot1 - load1);

        // Замыкать межсекционный имеет смысл только если на одной секции есть дефицит,
        // а на другой есть запас для покрытия (иначе объединение не помогает).
        boolean close01 = deficit0 > SimulationConstants.EPSILON && surplus1 > SimulationConstants.EPSILON;
        boolean close10 = deficit1 > SimulationConstants.EPSILON && surplus0 > SimulationConstants.EPSILON;
        return close01 || close10;
    }
}
