package simcore.engine;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * Утилита для экспорта пошаговых результатов моделирования в CSV,
 * который удобно открыть в Excel.
 */
public final class SimulationTraceExporter {

    private static final Locale RU_LOCALE = new Locale("ru", "RU");

    private SimulationTraceExporter() {
        // утилитарный класс
    }

    /**
     * Запись списка шагов моделирования в CSV.
     *
     * @param filePath путь к выходному файлу (например, "D:/simulation_trace.csv")
     * @param records  список шагов моделирования
     * @param busCount количество шин (1 или 2)
     */
    public static void exportToCsv(String filePath,
                                   List<SimulationStepRecord> records,
                                   int busCount) throws IOException {

        if (busCount < 1 || busCount > 2) {
            throw new IllegalArgumentException("Поддерживаются только 1 или 2 шины, busCount=" + busCount);
        }

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(filePath))) {

            // Заголовок
            if (busCount == 1) {
                writer.write("t;TotalLoad;Bus1Load;Bus1WindGen;Bus1Balance");
            } else {
                writer.write("t;TotalLoad;" +
                        "Bus1Load;Bus1WindGen;Bus1Balance;" +
                        "Bus2Load;Bus2WindGen;Bus2Balance");
            }
            writer.newLine();

            // Строки данных
            for (SimulationStepRecord r : records) {

                StringBuilder sb = new StringBuilder();

                sb.append(r.getTimeIndex()).append(';')
                        .append(formatDouble(r.getTotalLoadKw())).append(';')
                        .append(formatDouble(r.getBus1LoadKw())).append(';')
                        .append(formatDouble(r.getBus1WindGenKw())).append(';')
                        .append(formatDouble(r.getBus1BalanceKw()));

                if (busCount == 2) {
                    sb.append(';')
                            .append(formatDouble(r.getBus2LoadKw())).append(';')
                            .append(formatDouble(r.getBus2WindGenKw())).append(';')
                            .append(formatDouble(r.getBus2BalanceKw()));
                }

                writer.write(sb.toString());
                writer.newLine();
            }
        }
    }

    /**
     * Форматируем число в виде:
     *  - десятичная запятая (ru-RU),
     *  - один знак после запятой,
     *  - без лишних пробелов.
     */
    private static String formatDouble(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return "";
        }
        // "%.1f" -> один знак после запятой, Locale ru-RU -> запятая
        return String.format(RU_LOCALE, "%.1f", value);
    }
}
