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

/**
 * CSV для пакетного запуска (несколько наборов SystemParameters без Соболя).
 *
 * Формат:
 *  - 1 строка: паспорт базового запуска
 *  - 2 строка: заголовок
 *  - далее по строке на каждый набор параметров
 */
public final class SweepResultsCsvWriter {

    private static final Locale RU = new Locale("ru", "RU");

    private SweepResultsCsvWriter() {}

    public static void write(String path,
                             SimulationConfig cfg,
                             SystemParameters baseParams,
                             List<SystemParameters> paramSets,
                             List<MonteCarloEstimate> estimates) throws IOException {

        if (paramSets.size() != estimates.size()) {
            throw new IllegalArgumentException("paramSets.size != estimates.size");
        }

        try (BufferedWriter w = new BufferedWriter(new FileWriter(path, false))) {

            w.write(csvCell(buildPassport(cfg, baseParams)));
            w.newLine();

            // k + меняемые параметры + метрики
            w.write("k;BTcap;NRL;ENS_mean;ENS_ciLo;ENS_ciHi;ENS_reqN;Fuel_ML;Moto_kh;WRE_% ;WT_% ;DG_% ;BT_%");
            w.newLine();

            for (int k = 0; k < estimates.size(); k++) {
                SystemParameters p = paramSets.get(k);
                MonteCarloEstimate e = estimates.get(k);
                MonteCarloStats.Stats s = e.ensStats;

                double fuelML = e.meanFuelLiters / 1_000_000.0; // тыс. тонн
                double motoKh = e.meanMotoHours / 1_000.0;      // тыс. моточасов

                w.write(k + ";"
                        + fmt1(p.getBatteryCapacityKwhPerBus()) + ";" // изменяемый 1 параметр
                        + fmt2(p.getMaxDischargeCurrent()) + ";" // изменяемый 2 параметр
                        + fmt1(s.getMean()) + ";"
                        + fmt1(s.getCiLow()) + ";"
                        + fmt1(s.getCiHigh()) + ";"
                        + s.getRequiredSampleSize() + ";"
                        + fmt2(fuelML) + ";"
                        + fmt1(motoKh) + ";"
                        + fmt2(e.meanWre) + ";"
                        + fmt2(e.meanWtPct) + ";"
                        + fmt2(e.meanDgPct) + ";"
                        + fmt2(e.meanBtPct)
                );
                w.newLine();
            }
        }
    }

    private static String buildPassport(SimulationConfig cfg, SystemParameters sp) {
        return String.format(RU,
                "bus=%s; MC=%d; fail=%b; deg=%b; sort=%b; WT=%dx%.0f; DG=%dx%.0f; BT_base=%.1f; Ib=%.2f/%.2f; nonRes=%.2f",
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

    private static String csvCell(String s) {
        // CSV экранирование: кавычки вокруг + " -> ""
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }


    private static String fmt1(double v) { return String.format(RU, "%.1f", v); }
    private static String fmt2(double v) { return String.format(RU, "%.2f", v); }
    private static String fmt3(double v) { return String.format(RU, "%.3f", v); }
}
