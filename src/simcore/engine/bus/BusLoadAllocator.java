package simcore.engine.bus;

import simcore.config.BusSystemType;
import simcore.config.SystemParameters;
import simcore.model.PowerBus;

import java.util.List;

/**
 * Эффективные нагрузки по шинам/секциям с учетом переноса категорий
 * при "отказе шины / отсутствии grid-forming оборудования" согласно новой таблице.
 *
 * ВАЖНО:
 * - Перенос по дефициту для SS/D делается в SingleRunSimulator (там нужен учет профицита/дефицита и автомата).
 * - Здесь только перенос при отказе/нет grid-forming.
 */
public final class BusLoadAllocator {

    private BusLoadAllocator() {}

    private static final ThreadLocal<CatLoads2> TL_CAT_2 = ThreadLocal.withInitial(CatLoads2::new);
    private static final ThreadLocal<double[]> TL_OUT_2 = ThreadLocal.withInitial(() -> new double[2]); // compat

    /**
     * outageHours[i] = сколько часов подряд данная шина/секция "мертва" (отказ шины ИЛИ нет grid-forming оборудования).
     * - 0 => жива
     * - 1 => первый час отказа
     * - 2.. => второй и последующие часы
     *
     * Возвращает разложение нагрузок по категориям (CatLoads2) или null, если не применимо.
     */
    public static CatLoads2 computeCatLoadsOnOutage(SystemParameters sp,
                                                    List<PowerBus> buses,
                                                    int t,
                                                    double cat1,
                                                    double cat2,
                                                    double[] baseLoadsThisHourKw,
                                                    int[] outageHours) {

        if (buses.size() != 2) return null;

        final BusSystemType busType = sp.getBusSystemType();
        if (busType != BusSystemType.SINGLE_SECTIONAL_BUS && busType != BusSystemType.DOUBLE_BUS) return null;

        final double l0 = (baseLoadsThisHourKw != null) ? baseLoadsThisHourKw[0] : buses.get(0).getLoadKw()[t];
        final double l1 = (baseLoadsThisHourKw != null) ? baseLoadsThisHourKw[1] : buses.get(1).getLoadKw()[t];

        CatLoads2 out = TL_CAT_2.get();
        init(out, 0, l0, cat1, cat2);
        init(out, 1, l1, cat1, cat2);

        // Если обе живы или обе мертвы — тут перенос не решаем.
        boolean alive0 = outageHours[0] == 0;
        boolean alive1 = outageHours[1] == 0;
        if (alive0 == alive1) return out;

        int dead = alive0 ? 1 : 0;
        int live = 1 - dead;

        // По таблице:
        // SS: I сразу, II со следующего часа, III -> ENS (не переносится)
        // D : I сразу, II+III со следующего часа (и генерация со следующего часа - это в SingleRunSimulator)
        boolean isFirstHour = outageHours[dead] == 1;

        if (busType == BusSystemType.SINGLE_SECTIONAL_BUS) {
            if (isFirstHour) {
                transferP1(out, dead, live);
            } else {
                transferP1(out, dead, live);
                transferP2(out, dead, live);
                // p3 остаётся на dead (III не переносится)
            }
        } else {
            // DOUBLE_BUS
            if (isFirstHour) {
                transferP1(out, dead, live);
            } else {
                transferP1(out, dead, live);
                transferP2(out, dead, live);
                transferP3(out, dead, live);
            }
        }

        return out;
    }

    /**
     * Backward-compatible helper: returns only total effective loads (p1+p2+p3).
     * Prefer computeCatLoadsOnOutage() in new logic.
     */
    public static double[] maybeComputeEffectiveLoadsOnOutage(SystemParameters sp,
                                                              List<PowerBus> buses,
                                                              int t,
                                                              double cat1,
                                                              double cat2,
                                                              double[] baseLoadsThisHourKw,
                                                              int[] outageHours) {
        CatLoads2 cl = computeCatLoadsOnOutage(sp, buses, t, cat1, cat2, baseLoadsThisHourKw, outageHours);
        if (cl == null) return null;
        double[] out = TL_OUT_2.get();
        out[0] = cl.total(0);
        out[1] = cl.total(1);
        return out;
    }

    private static void init(CatLoads2 out, int b, double load, double cat1, double cat2) {
        double p1 = load * cat1;
        double p2 = load * cat2;
        double p3 = Math.max(0.0, load - p1 - p2);
        out.p1[b] = p1;
        out.p2[b] = p2;
        out.p3[b] = p3;
    }

    private static void transferP1(CatLoads2 out, int from, int to) { out.p1[to] += out.p1[from]; out.p1[from] = 0.0; }
    private static void transferP2(CatLoads2 out, int from, int to) { out.p2[to] += out.p2[from]; out.p2[from] = 0.0; }
    private static void transferP3(CatLoads2 out, int from, int to) { out.p3[to] += out.p3[from]; out.p3[from] = 0.0; }
}