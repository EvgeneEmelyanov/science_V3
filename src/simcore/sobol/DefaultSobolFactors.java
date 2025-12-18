package simcore.sobol;

import simcore.config.SystemParameters;

import java.util.List;

public final class DefaultSobolFactors {

    private DefaultSobolFactors() {}

    public static List<SobolFactor> batteryFactors() {
        return List.of(
                new SimpleSobolFactor(
                        "C_ch",
                        0.1, 1.0,
                        (SystemParameters p, Double v) -> p.withMaxChargeCurrent(v)
                ),
                new SimpleSobolFactor(
                        "C_dis",
                        0.1, 2.0,
                        (SystemParameters p, Double v) -> p.withMaxDischargeCurrent(v)
                ),
                new SimpleSobolFactor(
                        "DoD_nonRes",
                        0.0, 0.9,
                        (SystemParameters p, Double v) -> p.withNonReserveDischargeLevel(v)
                ),
                new SimpleSobolFactor(
                        "BT_kWh",
                        0.0, 2000.0,
                        (SystemParameters p, Double v) -> p.withBatteryCapacityKwhPerBus(v)
                )
        );
    }
}
