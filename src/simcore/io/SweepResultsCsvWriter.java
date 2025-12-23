package simcore.io;

import simcore.config.SimulationConfig;
import simcore.config.SystemParameters;
import simcore.engine.MonteCarloEstimate;
import simcore.engine.MonteCarloStats;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;
import java.util.Locale;

public final class SweepResultsCsvWriter {

    private static final Locale RU = new Locale("ru", "RU");

    private SweepResultsCsvWriter() {}

    public static void write(String path,
                             simcore.Main.RunMode mode,
                             SimulationConfig cfg,
                             SystemParameters baseParams,
                             List<SystemParameters> paramSets,
                             List<MonteCarloEstimate> estimates,
                             double[] param1,
                             double[] param2) throws IOException {

        if (paramSets.size() != estimates.size()) {
            throw new IllegalArgumentException("paramSets.size != estimates.size");
        }

        try (BufferedWriter w = new BufferedWriter(new FileWriter(path, false))) {

            w.write(csvCell(buildPassport(cfg, baseParams)));
            w.newLine();

            // Заголовок зависит от режима
            if (mode == simcore.Main.RunMode.SWEEP_2) {
                w.write("k;param1;param2;ENS_mean;ENS_ciLo;ENS_ciHi;ENS_reqN;Fuel_ML;Moto_kh;WRE_% ;WT_% ;DG_% ;BT_%");
            } else if (mode == simcore.Main.RunMode.SWEEP_1) {
                w.write("k;param1;ENS_mean;ENS_ciLo;ENS_ciHi;ENS_reqN;Fuel_ML;Moto_kh;WRE_% ;WT_% ;DG_% ;BT_%");
            } else { // SINGLE
                w.write("k;ENS_mean;ENS_ciLo;ENS_ciHi;ENS_reqN;Fuel_ML;Moto_kh;WRE_% ;WT_% ;DG_% ;BT_%");
            }
            w.newLine();

            final int m2 = (param2 != null) ? param2.length : 0;

            for (int k = 0; k < estimates.size(); k++) {

                MonteCarloEstimate e = estimates.get(k);
                MonteCarloStats.Stats s = e.ensStats;

                double fuelML = e.meanFuelLiters / 1_000_000.0;
                double motoKh = e.meanMotoHours / 1_000.0;

                StringBuilder sb = new StringBuilder(256);
                sb.append(k).append(';');

                // Параметры зависят от режима
                if (mode == simcore.Main.RunMode.SWEEP_2) {
                    // порядок: for p1 in param1 { for p2 in param2 { ... } }
                    int i1 = k / m2;
                    int i2 = k % m2;
                    sb.append(fmt1(param1[i1])).append(';')
                            .append(fmt2(param2[i2])).append(';');
                } else if (mode == simcore.Main.RunMode.SWEEP_1) {
                    sb.append(fmt1(param1[k])).append(';');
                } // SINGLE: параметров нет

                sb.append(fmt1(s.getMean())).append(';')
                        .append(fmt1(s.getCiLow())).append(';')
                        .append(fmt1(s.getCiHigh())).append(';')
                        .append(s.getRequiredSampleSize()).append(';')
                        .append(fmt2(fuelML)).append(';')
                        .append(fmt1(motoKh)).append(';')
                        .append(fmt2(e.meanWre)).append(';')
                        .append(fmt2(e.meanWtPct)).append(';')
                        .append(fmt2(e.meanDgPct)).append(';');

                w.write(sb.toString());
                w.newLine();
            }
        }
    }

    private static String buildPassport(SimulationConfig cfg, SystemParameters sp) {
        return String.format(RU,
                "bus=%s; I=%.2f; II=%.2f; III=%.2f; MC=%d; fail=%b; deg=%b; chargeDg=%b; WT=%dx%.0f; DG=%dx%.0f;" +
                        " BT_base=%.1f; Ib=%.2f/%.2f; nonRes=%.2f",
                sp.getBusSystemType(),
                sp.getFirstCat(),
                sp.getSecondCat(),
                sp.getThirdCat(),
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

    private static String fmt1(double v) { return String.format(RU, "%.1f", v); }
    private static String fmt2(double v) { return String.format(RU, "%.2f", v); }
    private static String fmt3(double v) { return String.format(RU, "%.3f", v); }
}
