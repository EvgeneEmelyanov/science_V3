package simcore.engine.bus;

import simcore.config.SystemParameters;
import simcore.model.Battery;
import simcore.model.DieselGenerator;
import simcore.model.PowerBus;
import simcore.model.WindTurbine;

/**
 * Потенциалы мощности по шине без побочных эффектов (не меняет состояние/наработку).
 */
public final class BusPotential {

    private BusPotential() {
    }

    public static double windPotentialNoSideEffects(PowerBus bus, double windV) {
        double pot = 0.0;
        for (WindTurbine wt : bus.getWindTurbines()) {
            if (wt.isAvailable()) {
                pot += wt.getPotentialGenerationKw(windV);
            }
        }
        return pot;
    }

    public static double dieselPotential(PowerBus bus, double dgMaxKw) {
        double pot = 0.0;
        for (DieselGenerator dg : bus.getDieselGenerators()) {
            if (dg.isAvailable()) {
                pot += dgMaxKw;
            }
        }
        return pot;
    }

    public static double batteryDischargePotential(PowerBus bus, SystemParameters sp) {
        Battery bt = bus.getBattery();
        if (bt == null || !bt.isAvailable()) {
            return 0.0;
        }
        return bt.getDischargeCapacity(sp);
    }
}
