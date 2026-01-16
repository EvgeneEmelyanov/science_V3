package simcore.engine.diesel;

import simcore.model.DieselGenerator;
import simcore.model.PowerBus;

import java.util.Arrays;
import java.util.List;

/**
 * Utilities for controlling diesel generators on a bus.
 * Extracted from SingleRunSimulator.
 */
public final class DieselFleetController {

    private DieselFleetController() {}

    private static final ThreadLocal<DieselGenerator[]> DG_SORT_BUF = new ThreadLocal<>();

    public static DieselGenerator[] getSortedDgs(PowerBus bus) {
        List<DieselGenerator> dgList = bus.getDieselGenerators();
        int n = dgList.size();

        DieselGenerator[] buf = DG_SORT_BUF.get();
        if (buf == null || buf.length != n) {
            buf = new DieselGenerator[n];
            DG_SORT_BUF.set(buf);
        }
        for (int i = 0; i < n; i++) buf[i] = dgList.get(i);

        Arrays.sort(buf, DieselGenerator.DISPATCH_COMPARATOR);
        return buf;
    }

    /**
     * Sorted array from an arbitrary list (used by shared-bus dispatch helpers).
     */
    public static DieselGenerator[] getSortedDgs(List<DieselGenerator> dgList) {
        int n = dgList.size();
        DieselGenerator[] arr = new DieselGenerator[n];
        for (int i = 0; i < n; i++) arr[i] = dgList.get(i);
        Arrays.sort(arr, DieselGenerator.DISPATCH_COMPARATOR);
        return arr;
    }

    public static void stopAllDieselsOnBus(PowerBus bus) {
        for (DieselGenerator dg : bus.getDieselGenerators()) {
            if (!dg.isAvailable()) {
                hardStopDg(dg);
                continue;
            }

            dg.stopWork();
            dg.setCurrentLoad(0.0);
            dg.setIdle(false);
        }
    }

    /**
     * If load == 0, keep all available DGs in hot-standby: working==true, load==0.
     */
    public static void keepAllDieselsReadyHotStandby(PowerBus bus) {
        DieselGenerator[] dgs = getSortedDgs(bus);

        for (DieselGenerator dg : dgs) {
            if (!dg.isAvailable()) {
                hardStopDg(dg);
                continue;
            }

            dg.startWork();
            dg.setCurrentLoad(0.0);
            dg.setIdle(false);
        }
    }

    public static boolean isMaintenanceStartedThisHour(DieselGenerator[] dgs) {
        for (DieselGenerator dg : dgs) {
            if (!dg.isAvailable()) continue;
            if (dg.isInMaintenance() && dg.getRepairTimeHours() == 4) return true;
        }
        return false;
    }

    public static void hardStopDg(DieselGenerator dg) {
        dg.stopWork();
        dg.setCurrentLoad(0.0);
        dg.setIdle(false);
    }
}
