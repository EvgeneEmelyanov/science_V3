package simcore.engine.fuel;

import simcore.config.SimulationConstants;
import simcore.model.DieselGenerator;

import java.util.List;

/**
 * Текущая (legacy) модель расхода топлива, вынесенная из SingleRunSimulator.
 * Поведение должно совпадать 1:1 с прежними формулами.
 */
public final class LegacyFuelModel implements FuelModel {

    // ===== Fuel model constants (из старого кода) =====
    private static final double K11 = 0.0185;
    private static final double K21 = -0.0361;
    private static final double K31 = 0.2745;
    private static final double K12 = 5.3978;
    private static final double K22 = -11.4831;
    private static final double K32 = 11.6284;

    @Override
    public double fuelLitersOneHour(double loadLevel, double powerKw) {
        if (powerKw <= SimulationConstants.EPSILON) return 0.0;
        double a = (K11 * loadLevel * loadLevel + K21 * loadLevel + K31);
        double b = (K12 * loadLevel * loadLevel + K22 * loadLevel + K32);
        return a * powerKw + b;
    }

    @Override
    public double computeFuelLitersOneHour(List<DieselGenerator> dgs, double dgRatedKw) {
        double liters = 0.0;
        for (DieselGenerator dg : dgs) {
            if (!dg.isAvailable()) continue;
            if (!dg.isWorking()) continue;

            // Важно: currentLoad может быть отрицательным как "маркер режима".
            // В топливе используем модуль, как и в прежнем коде.
            double loadLevel = Math.abs(dg.getCurrentLoad()) / dgRatedKw;
            liters += fuelLitersOneHour(loadLevel, dgRatedKw);
        }
        return liters;
    }
}
