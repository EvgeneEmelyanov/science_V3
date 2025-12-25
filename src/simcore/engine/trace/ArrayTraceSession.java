package simcore.engine.trace;

import simcore.engine.SimulationStepRecord;
import simcore.model.Battery;
import simcore.model.DieselGenerator;
import simcore.model.PowerBus;

import java.util.ArrayList;
import java.util.List;

public final class ArrayTraceSession implements TraceSession {

    private final List<SimulationStepRecord> records = new ArrayList<>();

    private boolean[] busStatus;
    private double[] busLoadKw;
    private double[] busWindToLoadKw;
    private double[] busDgToLoadKw;
    private double[] busBtNetKw;
    private double[] busDefKw;

    private double[][] dgLoadsKw;
    private double[][] dgHoursSinceMaintenance;
    private double[][] dgTimeWorked;
    private double[][] dgTotalTimeWorked;
    private boolean[][] dgAvailable;
    private boolean[][] dgInMaintenance;

    private double[] btActualCapacity;
    private double[] btActualSoc;
    private double[] btTimeWorked;

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public void startHour(int busCount) {
        busStatus = new boolean[busCount];
        busLoadKw = new double[busCount];
        busWindToLoadKw = new double[busCount];
        busDgToLoadKw = new double[busCount];
        busBtNetKw = new double[busCount];
        busDefKw = new double[busCount];

        dgLoadsKw = new double[busCount][];
        dgHoursSinceMaintenance = new double[busCount][];
        dgTimeWorked = new double[busCount][];
        dgTotalTimeWorked = new double[busCount][];
        dgAvailable = new boolean[busCount][];
        dgInMaintenance = new boolean[busCount][];

        btActualCapacity = new double[busCount];
        btActualSoc = new double[busCount];
        btTimeWorked = new double[busCount];
    }

    @Override
    public void setBusDown(int busIndex, double loadKw, double defKw) {
        busStatus[busIndex] = false;
        busLoadKw[busIndex] = loadKw;
        busWindToLoadKw[busIndex] = 0.0;
        busDgToLoadKw[busIndex] = 0.0;
        busBtNetKw[busIndex] = 0.0;
        busDefKw[busIndex] = defKw;
    }

    @Override
    public void setBusValues(int busIndex,
                             boolean busAlive,
                             double loadKw,
                             double windToLoadKw,
                             double dgToLoadKw,
                             double btNetKw,
                             double defKw) {
        busStatus[busIndex] = busAlive;
        busLoadKw[busIndex] = loadKw;
        busWindToLoadKw[busIndex] = windToLoadKw;
        busDgToLoadKw[busIndex] = dgToLoadKw;
        busBtNetKw[busIndex] = btNetKw;
        busDefKw[busIndex] = defKw;
    }

    @Override
    public void fillDgState(int busIndex, PowerBus bus) {
        List<DieselGenerator> dgList = bus.getDieselGenerators();
        int n = dgList.size();

        dgLoadsKw[busIndex] = new double[n];
        dgHoursSinceMaintenance[busIndex] = new double[n];
        dgTimeWorked[busIndex] = new double[n];
        dgTotalTimeWorked[busIndex] = new double[n];
        dgAvailable[busIndex] = new boolean[n];
        dgInMaintenance[busIndex] = new boolean[n];

        for (int i = 0; i < n; i++) {
            DieselGenerator dg = dgList.get(i);
            dgLoadsKw[busIndex][i] = dg.getCurrentLoad();
            dgHoursSinceMaintenance[busIndex][i] = dg.getHoursSinceMaintenance();
            dgTimeWorked[busIndex][i] = dg.getTimeWorked();
            dgTotalTimeWorked[busIndex][i] = dg.getTotalTimeWorked();
            dgAvailable[busIndex][i] = dg.isAvailable();
            dgInMaintenance[busIndex][i] = dg.isInMaintenance();
        }
    }

    @Override
    public void fillBatteryState(int busIndex, Battery battery) {
        if (battery != null) {
            btActualCapacity[busIndex] = battery.getMaxCapacityKwh();
            btActualSoc[busIndex] = battery.getStateOfCharge();
            btTimeWorked[busIndex] = battery.getTimeWorked();
        } else {
            btActualCapacity[busIndex] = Double.NaN;
            btActualSoc[busIndex] = Double.NaN;
            btTimeWorked[busIndex] = Double.NaN;
        }
    }

    @Override
    public void addHourRecord(int timeIndex,
                              double totalLoadKw,
                              double totalDeficitKw,
                              double totalWreKw,
                              Boolean breakerClosed) {

        records.add(new SimulationStepRecord(
                timeIndex,
                totalLoadKw,
                totalDeficitKw,
                totalWreKw,
                breakerClosed,
                busStatus,
                busLoadKw,
                busWindToLoadKw,
                busDgToLoadKw,
                busBtNetKw,
                busDefKw,
                dgLoadsKw,
                dgHoursSinceMaintenance,
                dgTimeWorked,
                dgTotalTimeWorked,
                dgAvailable,
                dgInMaintenance,
                btActualCapacity,
                btActualSoc,
                btTimeWorked
        ));
    }

    @Override
    public List<SimulationStepRecord> records() {
        return records;
    }
}
