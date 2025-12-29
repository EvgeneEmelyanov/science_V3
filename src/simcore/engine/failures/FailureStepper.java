package simcore.engine.failures;

import simcore.model.*;

import java.util.List;
import java.util.Random;

public final class FailureStepper {

    private FailureStepper() {
    }

    public static void initFailureModels(long seed, boolean considerFailures, List<PowerBus> buses, Breaker breaker, List<SwitchgearRoom> rooms) {
        Random rndWT = new Random(seed + 1);
        Random rndDG = new Random(seed + 2);
        Random rndBT = new Random(seed + 3);
        Random rndBUS = new Random(seed + 4);
        Random rndBRK = new Random(seed + 5);
        Random rndROOM = new Random(seed + 6);

        for (SwitchgearRoom room : rooms) room.initFailureModel(rndROOM, considerFailures);

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
            List<SwitchgearRoom> rooms,
            int[] roomIndexByBus,
            boolean[] busAvailBefore,
            boolean[] busAvailAfter,
            boolean[] busFailedThisHour,
            boolean[] busAlive
    ) {
        final int busCount = buses.size();

        for (int b = 0; b < busCount; b++) busAvailBefore[b] = buses.get(b).isAvailable();

        final int roomCount = rooms.size();
        boolean[] roomAvailBefore = new boolean[roomCount];
        boolean[] roomAvailAfter = new boolean[roomCount];
        boolean[] roomFailedThisHour = new boolean[roomCount];
        for (int r = 0; r < roomCount; r++) roomAvailBefore[r] = rooms.get(r).isAvailable();


        boolean brAvailBefore = breaker != null && breaker.isAvailable();
        boolean brClosedBefore = breaker != null && breaker.isClosed();

        for (SwitchgearRoom room : rooms) room.updateFailureOneHour(considerFailures);
        if (breaker != null) breaker.updateFailureOneHour(considerFailures);
        for (PowerBus bus : buses) bus.updateFailureOneHour(considerFailures);

        for (int r = 0; r < roomCount; r++) {
            roomAvailAfter[r] = rooms.get(r).isAvailable();
            roomFailedThisHour[r] = roomAvailBefore[r] && !roomAvailAfter[r];
        }

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

        for (int b = 0; b < busCount; b++) {
            int rIdx = roomIndexByBus[b];
            boolean roomOk = rooms.get(rIdx).isAvailable();
            busAlive[b] = buses.get(b).isAvailable() && roomOk;

            // если шина формально исправна, но помещение отказало в этот час — пометим как отказ для статистики/trace
            if (roomFailedThisHour[rIdx] && buses.get(b).isAvailable()) {
                busFailedThisHour[b] = true;
            }
        }

        for (SwitchgearRoom room : rooms) {
            if (room.isAvailable()) {
                room.addWorkTime(1);
            }
        }
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
