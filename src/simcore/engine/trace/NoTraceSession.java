package simcore.engine.trace;

import simcore.engine.SimulationStepRecord;
import simcore.model.Battery;
import simcore.model.PowerBus;

import java.util.Collections;
import java.util.List;

public final class NoTraceSession implements TraceSession {

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public void startHour(int busCount) {
        // no-op
    }

    @Override
    public void setBusDown(int busIndex, double loadKw, double defKw) {
        // no-op
    }

    @Override
    public void setBusValues(int busIndex,
                             boolean busAlive,
                             double loadKw,
                             double windToLoadKw,
                             double dgToLoadKw,
                             double btNetKw,
                             double defKw) {
        // no-op
    }

    @Override
    public void fillDgState(int busIndex, PowerBus bus) {
        // no-op
    }

    @Override
    public void fillBatteryState(int busIndex, Battery battery) {
        // no-op
    }

    public void addHourRecord(int timeIndex,
                              double totalLoadKw,
                              double totalDeficitKw,
                              double totalWreKw,
                              Boolean breakerClosed) {
        // no-op
    }

    @Override
    public List<SimulationStepRecord> records() {
        return Collections.emptyList();
    }
}
