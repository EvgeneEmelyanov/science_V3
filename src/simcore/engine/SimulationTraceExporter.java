package simcore.engine;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;
import java.util.Locale;

public final class SimulationTraceExporter {

    private static final Locale RU_LOCALE = new Locale("ru", "RU");

    private SimulationTraceExporter() {}

    public static void exportToCsv(String filePath, List<SimulationStepRecord> records) throws IOException {
        if (records.isEmpty()) {
            throw new IllegalArgumentException("Нет записей для экспорта");
        }

        int busCount = records.get(0).getBusLoadKw().length;

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(filePath))) {

            // --- Заголовок ---
            StringBuilder header = new StringBuilder("t;TotalLoad");
            for (int b = 0; b < busCount; b++) {
                header.append(";Bus").append(b + 1).append("Load");
                header.append(";Bus").append(b + 1).append("WT");

                int dgCount = records.get(0).getBusGenDgLoadKw()[b].length;
                for (int i = 0; i < dgCount; i++) {
                    header.append(";Bus").append(b + 1).append("DG").append(i + 1).append("_Load");
//                    header.append(";Bus").append(b + 1).append("DG").append(i + 1).append("_HoursSinceMaintenance");
//                    header.append(";Bus").append(b + 1).append("DG").append(i + 1).append("_TimeWorked");
                    header.append(";Bus").append(b + 1).append("DG").append(i + 1).append("_TotalTimeWorked");
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

                    int dgCount = r.getBusGenDgLoadKw()[b].length;
                    for (int i = 0; i < dgCount; i++) {
                        if (!r.getDgAvailable()[b][i]) {
                            if (r.getDgInMaintenance()[b][i]) {
                                sb.append(";TO");
                            } else {
                                sb.append(";OFF");
                            }
                        } else {
                            sb.append(';').append(formatDouble(r.getBusGenDgLoadKw()[b][i]));
                        }
//                        sb.append(';').append(formatDouble(r.getBusGenDgHoursSinceMaintenance()[b][i]));
//                        sb.append(';').append(formatDouble(r.getBusGenDgTimeWorked()[b][i]));
                        sb.append(';').append(formatDouble(r.getBusGenDgTotalTimeWorked()[b][i]));
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
        if (Double.isNaN(value) || Double.isInfinite(value)) return "";
        return String.format(RU_LOCALE, "%.1f", value);
    }
}
