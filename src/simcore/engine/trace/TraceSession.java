package simcore.engine.trace;

import simcore.engine.SimulationStepRecord;
import simcore.model.Battery;
import simcore.model.PowerBus;

import java.util.List;

public interface TraceSession {

    boolean enabled();

    void startHour(int busCount);

    void setBusDown(int busIndex, double loadKw, double defKw);

    void setBusValues(int busIndex,
                      boolean busAlive,
                      double loadKw,
                      double windToLoadKw,
                      double dgToLoadKw,
                      double btNetKw,
                      double defKw);

    void fillDgState(int busIndex, PowerBus bus);

    void fillBatteryState(int busIndex, Battery battery);

    void addHourRecord(int timeIndex,
                       double totalLoadKw,
                       double totalDeficitKw,
                       double totalWreKw,
                       Boolean breakerClosed);

    List<SimulationStepRecord> records();
}
