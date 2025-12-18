package simcore.io;

import simcore.config.SimulationConfig;
import simcore.config.SystemParameters;
import simcore.engine.MonteCarloEstimate;
import simcore.engine.MonteCarloStats;
import simcore.sobol.ParameterSet;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * CSV writer:
 *  - первая строка: “паспорт” конфигурации
 *  - вторая строка: заголовок колонок
 *  - далее по строке на каждый MonteCarloEstimate
 */
public final class ResultsCsvWriter {

    private static final Locale RU = new Locale("ru", "RU");

    private ResultsCsvWriter() {}

    public static void writeSobolEstimates(String path,
                                           SimulationConfig baseCfg,
                                           SystemParameters baseParams,
                                           List<MonteCarloEstimate> estimates) throws IOException {

        try (BufferedWriter w = new BufferedWriter(new FileWriter(path, false))) {

            w.write(buildHeaderLine(baseCfg, baseParams));
            w.newLine();

            w.write("k;ENS_mean;ENS_ciLo;ENS_ciHi;ENS_reqN;Fuel_L;Moto_h;WRE_%;WT_%_mean;DG_%_mean;BT_%_mean;theta");
            w.newLine();

            for (int k = 0; k < estimates.size(); k++) {
                MonteCarloEstimate e = estimates.get(k);
                MonteCarloStats.Stats s = e.ensStats;

                String thetaStr = (e.theta == null) ? "" : safeTheta(e.theta);
                double fuelML = e.meanFuelLiters / 1_000_000.0;   // million liters
                double motoKh = e.meanMotoHours / 1_000.0;        // thousand hours

                w.write(k + ";"
                        + fmt2(s.getMean()) + ";"
                        + fmt2(s.getCiLow()) + ";"
                        + fmt2(s.getCiHigh()) + ";"
                        + s.getRequiredSampleSize() + ";"
                        + fmt3(fuelML) + ";"           // Fuel_ML: 3 знака
                        + fmt3(motoKh) + ";"           // Moto_kh: 3 знака
                        + fmt2(e.meanWre) + ";"        // WRE_%: 2 знака
                        + fmt2(e.meanWtPct) + ";"      // WT_%: 2 знака
                        + fmt2(e.meanDgPct) + ";"      // DG_%: 2 знака
                        + fmt2(e.meanBtPct) + ";"      // BT_%: 2 знака
                        + thetaStr
                );

            }
        }
    }

    private static String buildHeaderLine(SimulationConfig cfg, SystemParameters sp) {
        return String.format(RU,
                "bus=%s; MC=%d; fail=%b; deg=%b; sort=%b; WT=%dx%.0f; DG=%dx%.0f; BT=%.1f; Ib=%.2f/%.2f; nonRes=%.2f",
                sp.getBusSystemType(),
                cfg.getIterations(),
                cfg.isConsiderFailures(),
                cfg.isConsiderBatteryDegradation(),
                cfg.isConsiderSortDiesel(),
                sp.getTotalWindTurbineCount(),
                sp.getWindTurbinePowerKw(),
                sp.getTotalDieselGeneratorCount(),
                sp.getDieselGeneratorPowerKw(),
                sp.getBatteryCapacityKwhPerBus(),
                sp.getMaxChargeCurrent(),
                sp.getMaxDischargeCurrent(),
                sp.getNonReserveDischargeLevel()
        );
    }

    private static String safeTheta(ParameterSet theta) {
        return theta.getValues().toString().replace(";", ",");
    }

    private static String fmt1(double v) { return String.format(RU, "%.1f", v); }
    private static String fmt2(double v) { return String.format(RU, "%.2f", v); }
    private static String fmt3(double v) { return String.format(RU, "%.3f", v); }
}
