package simcore.engine.bus;

import simcore.config.SimulationConstants;
import simcore.config.SystemParameters;
import simcore.model.DieselGenerator;
import simcore.model.PowerBus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * DOUBLE_BUS-only helper: when BOTH buses are alive and one bus has a DG capacity deficit while the other has
 * excess DG capacity, transfer a minimal number of available DGs from the donor bus to the receiver bus.
 *
 * Important: transfers GENERATORS (DG objects), not loads.
 */
public final class DgTransferController {

    private DgTransferController() {
    }

    public static void transferDieselsIfNeeded(SystemParameters sp,
                                               List<PowerBus> buses,
                                               boolean[] busAlive,
                                               double[] rawLoadThisHourKw,
                                               double windV,
                                               double dgMaxKw) {
        if (buses == null || buses.size() != 2) return;
        if (busAlive == null || busAlive.length != 2) return;
        if (rawLoadThisHourKw == null || rawLoadThisHourKw.length != 2) return;
        if (!busAlive[0] || !busAlive[1]) return;

        final PowerBus bus0 = buses.get(0);
        final PowerBus bus1 = buses.get(1);

        // DG demand after non-DG resources (WT + battery). Load is NOT transferred.
        final double wind0 = BusPotential.windPotentialNoSideEffects(bus0, windV);
        final double wind1 = BusPotential.windPotentialNoSideEffects(bus1, windV);
        final double bt0 = BusPotential.batteryDischargePotential(bus0, sp);
        final double bt1 = BusPotential.batteryDischargePotential(bus1, sp);

        final double needDg0 = Math.max(0.0, rawLoadThisHourKw[0] - wind0 - bt0);
        final double needDg1 = Math.max(0.0, rawLoadThisHourKw[1] - wind1 - bt1);

        final int avail0 = countAvailableDiesels(bus0);
        final int avail1 = countAvailableDiesels(bus1);

        final double cap0 = avail0 * dgMaxKw;
        final double cap1 = avail1 * dgMaxKw;

        final double excess0 = Math.max(0.0, cap0 - needDg0);
        final double excess1 = Math.max(0.0, cap1 - needDg1);
        final double short0 = Math.max(0.0, needDg0 - cap0);
        final double short1 = Math.max(0.0, needDg1 - cap1);

        int donor = -1;
        int recv = -1;
        double transferKw;

        if (short0 > SimulationConstants.EPSILON && excess1 > SimulationConstants.EPSILON) {
            donor = 1; recv = 0;
            transferKw = Math.min(short0, excess1);
        } else if (short1 > SimulationConstants.EPSILON && excess0 > SimulationConstants.EPSILON) {
            donor = 0; recv = 1;
            transferKw = Math.min(short1, excess0);
        } else {
            return;
        }

        if (transferKw <= SimulationConstants.EPSILON) return;

        int moveCount = (int) Math.ceil(transferKw / dgMaxKw);
        if (moveCount <= 0) return;

        final PowerBus donorBus = buses.get(donor);
        final PowerBus recvBus = buses.get(recv);

        // Prefer idle DGs first (less disruption). Only available DGs can be moved.
        final ArrayList<DieselGenerator> candidates = new ArrayList<>(donorBus.getDieselGenerators());
        candidates.sort(Comparator
                .comparing((DieselGenerator dg) -> !dg.isAvailable())
                .thenComparing(dg -> Math.abs(dg.getCurrentLoad()) > SimulationConstants.EPSILON));

        for (DieselGenerator dg : candidates) {
            if (moveCount <= 0) break;
            if (!dg.isAvailable()) continue;

            if (donorBus.removeDieselGenerator(dg)) {
                dg.setBusId(recvBus.getId()); // marker
                recvBus.addDieselGenerator(dg);
                moveCount--;
            }
        }
    }

    private static int countAvailableDiesels(PowerBus bus) {
        int c = 0;
        for (DieselGenerator dg : bus.getDieselGenerators()) {
            if (dg.isAvailable()) c++;
        }
        return c;
    }
}
