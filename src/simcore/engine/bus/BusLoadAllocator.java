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

    private static final ThreadLocal<double[]> TL_OUT_2 = ThreadLocal.withInitial(() -> new double[2]);

    /**
     * outageHours[i] = сколько часов подряд данная шина/секция "мертва" (отказ шины ИЛИ нет grid-forming оборудования).
     * - 0 => жива
     * - 1 => первый час отказа
     * - 2.. => второй и последующие часы
     *
     * Возвращает массив эффективных нагрузок (2 элемента) или null, если не применимо.
     */
    public static double[] maybeComputeEffectiveLoadsOnOutage(SystemParameters sp,
                                                              List<PowerBus> buses,
                                                              int t,
                                                              double cat1,
                                                              double cat2,
                                                              double[] baseLoadsThisHourKw,
                                                              int[] outageHours) {

        if (buses.size() != 2) return null;

        final BusSystemType busType = sp.getBusSystemType();
        if (busType != BusSystemType.SINGLE_SECTIONAL_BUS && busType != BusSystemType.DOUBLE_BUS) return null;

        double[] out = TL_OUT_2.get();
        out[0] = (baseLoadsThisHourKw != null) ? baseLoadsThisHourKw[0] : buses.get(0).getLoadKw()[t];
        out[1] = (baseLoadsThisHourKw != null) ? baseLoadsThisHourKw[1] : buses.get(1).getLoadKw()[t];

        // Если обе живы или обе мертвы — тут перенос не решаем.
        boolean alive0 = outageHours[0] == 0;
        boolean alive1 = outageHours[1] == 0;
        if (alive0 == alive1) return out;

        int dead = alive0 ? 1 : 0;
        int live = 1 - dead;

        // По таблице:
        // SS: I сразу, II со следующего часа, III -> ENS
        // D : I сразу, II+III со следующего часа (и генерация со следующего часа - это обрабатываем в SingleRunSimulator)
        boolean isFirstHour = outageHours[dead] == 1;

        double ratio;
        if (busType == BusSystemType.SINGLE_SECTIONAL_BUS) {
            ratio = isFirstHour ? cat1 : (cat1 + cat2); // III никогда
        } else {
            // DOUBLE_BUS
            ratio = isFirstHour ? cat1 : 1.0; // II+III со следующего часа
        }

        ratio = Math.max(0.0, Math.min(1.0, ratio));
        double transfer = out[dead] * ratio;

        out[dead] = Math.max(0.0, out[dead] - transfer);
        out[live] += transfer;
        return out;
    }
}
