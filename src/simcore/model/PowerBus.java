package simcore.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Шина (или секция шины).
 * Наследник Equipment, потому что у неё тоже есть частота отказов и время ремонта.
 */
public class PowerBus extends Equipment {

    /** Профиль нагрузки на этой шине, кВт (по часам). */
    private final double[] loadKw;

    private final List<WindTurbine> windTurbines = new ArrayList<>();
    private final List<DieselGenerator> dieselGenerators = new ArrayList<>();
    private Battery battery;

    /**
     * @param id                 id шины
     * @param loadKw             массив нагрузки по часам, кВт
     * @param failureRatePerYear частота отказов шины, 1/год
     * @param repairTimeHours    длительность ремонта шины, ч
     */
    public PowerBus(int id,
                    double[] loadKw,
                    double failureRatePerYear,
                    int repairTimeHours) {
        super("BUS", id, failureRatePerYear, repairTimeHours);
        this.loadKw = loadKw;
    }

    public double[] getLoadKw() {
        return loadKw;
    }

    public List<WindTurbine> getWindTurbines() {
        return Collections.unmodifiableList(windTurbines);
    }

    public List<DieselGenerator> getDieselGenerators() {
        return Collections.unmodifiableList(dieselGenerators);
    }

    public Battery getBattery() {
        return battery;
    }

    public void addWindTurbine(WindTurbine wt) {
        windTurbines.add(wt);
    }

    public void addDieselGenerator(DieselGenerator dg) {
        dieselGenerators.add(dg);
    }

    public void setBattery(Battery battery) {
        this.battery = battery;
    }
}
