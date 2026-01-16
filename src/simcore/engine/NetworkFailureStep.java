package simcore.engine.step;

import simcore.engine.failures.FailureStepper;
import simcore.model.Breaker;
import simcore.model.PowerBus;
import simcore.model.SwitchgearRoom;

import java.util.List;

/**
 * Шаг обновления отказов сети (шины/автомат/помещения) и оборудования (ДГУ/ВЭУ/АКБ).
 * Вынесено из SingleRunSimulator.
 */
public final class NetworkFailureStep {

    private NetworkFailureStep() {}

    public static void updateOneHour(boolean considerFailures,
                                     List<PowerBus> buses,
                                     Breaker breaker,
                                     List<SwitchgearRoom> rooms,
                                     int[] roomIndexByBus,
                                     boolean[] busAvailBefore,
                                     boolean[] busAvailAfter,
                                     boolean[] busFailedThisHour,
                                     boolean[] busAlive) {

        final int busCount = buses.size();
        final boolean tieWasClosedAtHourStart = (breaker != null && breaker.isClosed());

        FailureStepper.updateNetworkFailuresOneHour(
                considerFailures,
                buses,
                breaker,
                rooms,
                roomIndexByBus,
                busAvailBefore,
                busAvailAfter,
                busFailedThisHour,
                busAlive
        );

        FailureStepper.updateEquipmentFailuresOneHour(considerFailures, buses, busAlive);

        if (tieWasClosedAtHourStart && breaker != null) {

            boolean breakerFailed = !breaker.isAvailable();
            boolean anyBusFailed = false;
            for (int i = 0; i < busCount; i++) {
                if (!busAlive[i]) { anyBusFailed = true; break; }
            }

            if (breakerFailed && anyBusFailed) {
                // отказал автомат + отказала шина => падают обе шины
                for (int i = 0; i < busCount; i++) busAlive[i] = false;

                // отметить отказ, чтобы trace/статистика видели событие
                for (int i = 0; i < busCount; i++) busFailedThisHour[i] = true;

                breaker.setClosed(false);
            }

            // автомат работал в этот час (если существовал)
            breaker.addWorkTime(1);
        }
    }
}
