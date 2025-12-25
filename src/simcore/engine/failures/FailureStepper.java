package simcore.engine.failures;

import simcore.model.*;

import java.util.List;
import java.util.Random;

public final class FailureStepper {

    private FailureStepper() {
    }

    public static void initFailureModels(long seed, boolean considerFailures, List<PowerBus> buses, Breaker breaker) {
        Random rndWT = new Random(seed + 10);
        Random rndDG = new Random(seed + 2);
        Random rndBT = new Random(seed + 3);
        Random rndBUS = new Random(seed + 4);
        Random rndBRK = new Random(seed + 5);

        if (breaker != null) breaker.initFailureModel(rndBRK, considerFailures);

        for (PowerBus bus : buses) {
            bus.initFailureModel(rndBUS, considerFailures);
            for (WindTurbine wt : bus.getWindTurbines()) wt.initFailureModel(rndWT, considerFailures);
            for (DieselGenerator dg : bus.getDieselGenerators()) dg.initFailureModel(rndDG, considerFailures);
            Battery bt = bus.getBattery();
            if (bt != null) bt.initFailureModel(rndBT, considerFailures);
        }
    }

    public static void updateNetworkFailuresOneHour(
            boolean considerFailures,
            List<PowerBus> buses,
            Breaker breaker,
            boolean[] busAvailBefore,
            boolean[] busAvailAfter,
            boolean[] busFailedThisHour,
            boolean[] busAlive
    ) {
        final int busCount = buses.size();

        for (int b = 0; b < busCount; b++) busAvailBefore[b] = buses.get(b).isAvailable();

        boolean brAvailBefore = breaker != null && breaker.isAvailable();
        boolean brClosedBefore = breaker != null && breaker.isClosed();

        if (breaker != null) breaker.updateFailureOneHour(considerFailures);
        for (PowerBus bus : buses) bus.updateFailureOneHour(considerFailures);

        boolean anyBusFailed = false;
        for (int b = 0; b < busCount; b++) {
            PowerBus bus = buses.get(b);
            busAvailAfter[b] = bus.isAvailable();
            busFailedThisHour[b] = busAvailBefore[b] && !busAvailAfter[b];
            anyBusFailed |= busFailedThisHour[b];
        }

        boolean brAvailAfter = breaker != null && breaker.isAvailable();
        boolean brFailedThisHour = breaker != null && brAvailBefore && !brAvailAfter;

        if (breaker != null && brClosedBefore && brFailedThisHour && anyBusFailed) {
            for (PowerBus bus : buses) if (bus.isAvailable()) bus.forceFailNow();
        } else if (breaker != null && brClosedBefore && anyBusFailed && !brFailedThisHour) {
            breaker.setClosed(false);
        }

        for (int b = 0; b < busCount; b++) busAlive[b] = buses.get(b).isAvailable();
    }

    public static void updateEquipmentFailuresOneHour(boolean considerFailures, List<PowerBus> buses, boolean[] busAlive) {
        for (int b = 0; b < buses.size(); b++) {
            if (!busAlive[b]) continue;

            PowerBus bus = buses.get(b);
            for (WindTurbine wt : bus.getWindTurbines()) wt.updateFailureOneHour(considerFailures);
            for (DieselGenerator dg : bus.getDieselGenerators()) dg.updateFailureOneHour(considerFailures);
            Battery bt = bus.getBattery();
            if (bt != null) bt.updateFailureOneHour(considerFailures);
        }
    }
}
