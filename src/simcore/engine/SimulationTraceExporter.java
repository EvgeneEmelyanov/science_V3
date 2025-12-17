package simcore.engine;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;
import java.util.Locale;

public final class SimulationTraceExporter {

    private static final Locale RU_LOCALE = new Locale("ru", "RU");

    private SimulationTraceExporter() {}

    public static void exportToCsv(String filePath,
                                   List<SimulationStepRecord> records,
                                   int busCount) throws IOException {

        if (busCount < 1 || busCount > 2) {
            throw new IllegalArgumentException("Поддерживаются только 1 или 2 шины, busCount=" + busCount);
        }

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(filePath))) {

            // --- Заголовок ---
            StringBuilder header = new StringBuilder("t;TotalLoad");
            for (int b = 0; b < busCount; b++) {
                header.append(";Bus").append(b + 1).append("Load");
                header.append(";Bus").append(b + 1).append("WT");

                // Кол-во ДГУ на шине берём из первой записи
                double[] dgLoads = records.get(0).getBusGenDgPerUnitKw()[b];
                for (int i = 0; i < dgLoads.length; i++) {
                    header.append(";Bus").append(b + 1).append("DG").append(i + 1);
                }

                header.append(";Bus").append(b + 1).append("BT");
                header.append(";Bus").append(b + 1).append("Deficit");
            }

            writer.write(header.toString());
            writer.newLine();

            // --- Данные ---
            for (SimulationStepRecord r : records) {
                StringBuilder sb = new StringBuilder();
                sb.append(r.getTimeIndex()).append(';')
                        .append(formatDouble(r.getTotalLoadKw()));

                for (int b = 0; b < busCount; b++) {
                    sb.append(';').append(formatDouble(r.getBusLoadKw()[b]))
                            .append(';').append(formatDouble(r.getBusGenWindKw()[b]));

                    // Записываем нагрузку каждого ДГУ
                    double[] dgLoads = r.getBusGenDgPerUnitKw()[b];
                    for (double dgLoad : dgLoads) {
                        sb.append(';').append(formatDouble(dgLoad));
                    }

                    sb.append(';').append(formatDouble(r.getBusGenBtKw()[b]))
                            .append(';').append(formatDouble(r.getBusDeficitKw()[b]));
                }

                writer.write(sb.toString());
                writer.newLine();
            }
        }
    }

    private static String formatDouble(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return "";
        }
        return String.format(RU_LOCALE, "%.1f", value);
    }
}
