package simcore.engine.bus;

import simcore.config.BusSystemType;
import simcore.config.SimulationConstants;
import simcore.config.SystemParameters;
import simcore.model.PowerBus;

import java.util.List;

/**
 * Расчет "эффективных" нагрузок по шинам с учетом возможности переноса нагрузки
 * в зависимости от схемы шин.
 */
public final class BusLoadAllocator {

    private BusLoadAllocator() {
    }

    /**
     * Avoid per-hour allocations for the common case busCount==2.
     * ThreadLocal is required because MC/Sobol can run in parallel.
     */
    private static final ThreadLocal<double[]> TL_OUT_2 = ThreadLocal.withInitial(() -> new double[2]);

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
        return maybeComputeEffectiveLoads(sp, buses, busAlive, t, cat1, cat2, windV, dgMaxKw, null);
    }

    /**
     * Variant that allows passing per-bus base loads for the given hour (instead of reading buses.get(i).getLoadKw()[t]).
     * This is used when we apply extra own-use load adjustments on the fly.
     */
    public static double[] maybeComputeEffectiveLoads(SystemParameters sp,
                                                      List<PowerBus> buses,
                                                      boolean[] busAlive,
                                                      int t,
                                                      double cat1,
                                                      double cat2,
                                                      double windV,
                                                      double dgMaxKw,
                                                      double[] baseLoadsThisHourKw) {
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
            return computeEffectiveLoadsForSectional(sp, buses, busAlive, t, cat1, cat2, false, baseLoadsThisHourKw);
        }

        // DOUBLE_BUS: перенос нагрузки только при отказе шины.
        // При дефиците на одной из двух живых шин нагрузку НЕ переносим.
        // Дефицит должен решаться переносом ДГУ (см. DgTransferController + SingleRunSimulator).
        return computeEffectiveLoadsForDoubleBus(sp, buses, busAlive, t, cat1, cat2, baseLoadsThisHourKw);
    }

    private static double[] computeEffectiveLoadsForSectional(SystemParameters sp,
                                                              List<PowerBus> buses,
                                                              boolean[] busAlive,
                                                              int t,
                                                              double cat1,
                                                              double cat2,
                                                              boolean allowCat3AfterFirstHour,
                                                              double[] baseLoadsThisHourKw) {
        final int n = buses.size();
        final double[] out = (n == 2) ? TL_OUT_2.get() : new double[n];
        for (int i = 0; i < n; i++) {
            out[i] = (baseLoadsThisHourKw != null) ? baseLoadsThisHourKw[i] : buses.get(i).getLoadKw()[t];
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

        double ratio;
        if (firstRepairHour) {
            ratio = cat1;
        } else {
            // Для DOUBLE_BUS разрешаем перенос III категории со 2-го часа (т.е. переносима вся нагрузка)
            ratio = allowCat3AfterFirstHour ? 1.0 : (cat1 + cat2);
        }
        ratio = Math.min(1.0, Math.max(0.0, ratio));
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
                                                              double[] baseLoadsThisHourKw) {
        final int n = buses.size();
        final double[] out = (n == 2) ? TL_OUT_2.get() : new double[n];
        for (int i = 0; i < n; i++) {
            out[i] = (baseLoadsThisHourKw != null) ? baseLoadsThisHourKw[i] : buses.get(i).getLoadKw()[t];
        }

        if (buses.size() != 2) {
            return out;
        }

        // Если одна шина недоступна — перенос нагрузки по категориям:
        // 1-я категория сразу, со 2-го часа ремонта переносится вся нагрузка (cat3 тоже).
        if (busAlive[0] != busAlive[1]) {
            return computeEffectiveLoadsForSectional(sp, buses, busAlive, t, cat1, cat2, true, baseLoadsThisHourKw);
        }

        // Две живые шины: нагрузку НЕ переносим
        return out;
    }
}
