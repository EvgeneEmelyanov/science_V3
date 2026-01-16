package simcore.engine.fuel;

import simcore.model.DieselGenerator;

import java.util.List;

/**
 * Модель расхода топлива.
 * Выделено из SingleRunSimulator для упрощения сопровождения.
 */
public interface FuelModel {

    /**
     * Расход топлива за 1 час для одного ДГУ при заданном уровне загрузки (0..1)
     * и номинальной мощности powerKw.
     */
    double fuelLitersOneHour(double loadLevel, double powerKw);

    /**
     * Суммарный расход топлива за 1 час по всем доступным/работающим ДГУ на шине.
     */
    double computeFuelLitersOneHour(List<DieselGenerator> dgs, double dgRatedKw);
}
