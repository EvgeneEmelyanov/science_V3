package simcore.engine.bus;

import simcore.config.BusSystemType;
import simcore.config.SimulationConstants;
import simcore.config.SystemParameters;
import simcore.model.PowerBus;

import java.util.List;

/**
 * Расчет "эффективных" нагрузок по шинам с учетом возможности переноса нагрузки
 * в зависимости от схемы шин.
 *
 * Логика перенесена 1:1 из SingleRunSimulator.
 */
public final class BusLoadAllocator {

    private BusLoadAllocator() {
    }

    /**
     * @return массив эффективных нагрузок по шинам или null, если перенос не применим
     */
    public static double[] maybeComputeEffectiveLoads(SystemParameters sp,
                                                      List<PowerBus> buses,
                                                      boolean[] busAlive,
                                                      int t,
                                                      double cat1,
                                                      double cat2,
                                                      double windV,
                                                      double dgMaxKw) {
        final int busCount = buses.size();
        final BusSystemType busType = sp.getBusSystemType();

        if (busCount != 2) {
            return null;
        }
        if (busType != BusSystemType.SINGLE_SECTIONAL_BUS && busType != BusSystemType.DOUBLE_BUS) {
            return null;
        }

        if (busType == BusSystemType.SINGLE_SECTIONAL_BUS) {
            // Перенос 1/2 категории только при отказе секции (если одна секция недоступна)
            return computeEffectiveLoadsForSectional(sp, buses, busAlive, t, cat1, cat2);
        }

        // DOUBLE_BUS: перенос 1/2 категории при отказе шины И при дефиците мощности на одной из шин
        return computeEffectiveLoadsForDoubleBus(sp, buses, busAlive, t, cat1, cat2, windV, dgMaxKw);
    }

    private static double[] computeEffectiveLoadsForSectional(SystemParameters sp,
                                                              List<PowerBus> buses,
                                                              boolean[] busAlive,
                                                              int t,
                                                              double cat1,
                                                              double cat2) {
        double[] out = new double[buses.size()];
        for (int i = 0; i < buses.size(); i++) {
            out[i] = buses.get(i).getLoadKw()[t];
        }

        if (buses.size() != 2) {
            return out;
        }
        if (busAlive[0] == busAlive[1]) {
            return out;
        }

        int dead = busAlive[0] ? 1 : 0;
        int live = busAlive[0] ? 0 : 1;

        PowerBus deadBus = buses.get(dead);
        int busRepairTime = sp.getBusRepairTimeHours();
        boolean firstRepairHour = (deadBus.getRepairDurationHours() == busRepairTime);

        double ratio = firstRepairHour ? cat1 : (cat1 + cat2);
        double transfer = out[dead] * ratio;

        out[dead] = Math.max(0.0, out[dead] - transfer);
        out[live] += transfer;
        return out;
    }

    private static double[] computeEffectiveLoadsForDoubleBus(SystemParameters sp,
                                                              List<PowerBus> buses,
                                                              boolean[] busAlive,
                                                              int t,
                                                              double cat1,
                                                              double cat2,
                                                              double windV,
                                                              double dgMaxKw) {
        // Базовая нагрузка по шинам
        double[] out = new double[buses.size()];
        for (int i = 0; i < buses.size(); i++) {
            out[i] = buses.get(i).getLoadKw()[t];
        }

        if (buses.size() != 2) {
            return out;
        }

        // Если одна шина недоступна — используем ту же логику переноса (1-я сразу, 2-я с задержкой)
        if (busAlive[0] != busAlive[1]) {
            return computeEffectiveLoadsForSectional(sp, buses, busAlive, t, cat1, cat2);
        }

        // Если обе недоступны или обе доступны — работаем далее только для случая "обе доступны"
        if (!busAlive[0] && !busAlive[1]) {
            return out;
        }

        // Перенос при дефиците: если на одной шине не хватает потенциальной генерации (WT + DGmax + АКБ),
        // а на другой есть запас, переносим часть двухвводных потребителей (cat1+cat2) на другую шину.
        double[] pot = new double[2];
        for (int b = 0; b < 2; b++) {
            PowerBus bus = buses.get(b);
            double windPot = BusPotential.windPotentialNoSideEffects(bus, windV);
            double dgPot = BusPotential.dieselPotential(bus, dgMaxKw);
            double btPot = BusPotential.batteryDischargePotential(bus, sp);
            pot[b] = windPot + dgPot + btPot;
        }

        double deficit0 = Math.max(0.0, out[0] - pot[0]);
        double deficit1 = Math.max(0.0, out[1] - pot[1]);
        double surplus0 = Math.max(0.0, pot[0] - out[0]);
        double surplus1 = Math.max(0.0, pot[1] - out[1]);

        // Для DOUBLE_BUS при дефиците разрешаем переносить и III категорию (если требуется),
        // т.е. теоретически переносима вся нагрузка. Перенос I/II при отказе шины остаётся
        // в computeEffectiveLoadsForSectional(...).
        double movableRatio = 1.0;

        if (deficit0 > SimulationConstants.EPSILON && surplus1 > SimulationConstants.EPSILON) {
            double maxMovable = out[0] * movableRatio;
            double transfer = Math.min(deficit0, Math.min(surplus1, maxMovable));
            out[0] = Math.max(0.0, out[0] - transfer);
            out[1] += transfer;
        } else if (deficit1 > SimulationConstants.EPSILON && surplus0 > SimulationConstants.EPSILON) {
            double maxMovable = out[1] * movableRatio;
            double transfer = Math.min(deficit1, Math.min(surplus0, maxMovable));
            out[1] = Math.max(0.0, out[1] - transfer);
            out[0] += transfer;
        }

        return out;
    }
}
