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

public final class ResultsCsvWriter {

    private static final Locale RU = new Locale("ru", "RU");

    private ResultsCsvWriter() {}

    public static void writeSobolEstimates(String path,
                                           SimulationConfig baseCfg,
                                           SystemParameters baseParams,
                                           List<MonteCarloEstimate> estimates) throws IOException {

        try (BufferedWriter w = new BufferedWriter(new FileWriter(path, false))) {

            w.write(csvCell(buildHeaderLine(baseCfg, baseParams)));
            w.newLine();

            w.write("k;ENS_mean;ENS_ciLo;ENS_ciHi;ENS_reqN;Fuel_ML;Moto_kh;WRE_%;" +
                    "WT_%_mean;DG_%_mean;BT_%_mean;theta");
            w.newLine();

            for (int k = 0; k < estimates.size(); k++) {
                MonteCarloEstimate e = estimates.get(k);
                MonteCarloStats.Stats s = e.ensStats;

                String thetaStr = (e.theta == null) ? "" : csvCell(e.theta.getValues().toString());

                double fuelML = e.meanFuelLiters / 1_000_000.0;
                double motoKh = e.meanMotoHours / 1_000.0;

                w.write(k + ";"
                        + fmt2(s.getMean()) + ";"
                        + fmt2(s.getCiLow()) + ";"
                        + fmt2(s.getCiHigh()) + ";"
                        + s.getRequiredSampleSize() + ";"
                        + fmt3(fuelML) + ";"
                        + fmt3(motoKh) + ";"
                        + fmt2(e.meanWre) + ";"
                        + fmt2(e.meanWtPct) + ";"
                        + fmt2(e.meanDgPct) + ";"
                        + fmt2(e.meanBtPct) + ";"
                        + thetaStr
                );
                w.newLine();
            }
        }
    }

    private static String buildHeaderLine(SimulationConfig cfg, SystemParameters sp) {
        return String.format(RU,
                "bus=%s; MC=%d; fail=%b; deg=%b; chargeDg=%b; WT=%dx%.0f; DG=%dx%.0f; BT=%.1f; Ib=%.2f/%.2f; nonRes=%.2f",
                sp.getBusSystemType(),
                cfg.getIterations(),
                cfg.isConsiderFailures(),
                cfg.isConsiderBatteryDegradation(),
                cfg.isConsiderChargeByDg(),
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

    private static String csvCell(String s) {
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    private static String fmt2(double v) { return String.format(RU, "%.2f", v); }
    private static String fmt3(double v) { return String.format(RU, "%.3f", v); }
}
