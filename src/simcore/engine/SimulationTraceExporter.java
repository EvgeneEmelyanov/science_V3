package simcore.engine;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;
import java.util.Locale;

public final class SimulationTraceExporter {

    private static final Locale RU = Locale.forLanguageTag("ru-RU");

    private SimulationTraceExporter() {}

    public static void exportToCsv(String path, List<SimulationStepRecord> recs)
            throws IOException {

        if (recs.isEmpty()) {
            throw new IllegalArgumentException("Empty trace");
        }

        int busCnt = recs.get(0).getBusLoadKw().length;

        try (BufferedWriter w = new BufferedWriter(new FileWriter(path))) {

            /* ---------- HEADER ---------- */
            StringBuilder h = new StringBuilder("t;L");
            for (int b = 0; b < busCnt; b++) {
                int bi = b + 1;
                h.append(";B").append(bi).append("_L");
                h.append(";B").append(bi).append("_Def");
                h.append(";B").append(bi).append("_W");

                int dgCnt = recs.get(0).getBusGenDgLoadKw()[b].length;
                for (int i = 0; i < dgCnt; i++) {
                    int di = i + 1;
                    h.append(";B").append(bi).append("_D").append(di);
                    h.append(";B").append(bi).append("_D").append(di).append("_T");
                }

                h.append(";B").append(bi).append("_B");
                h.append(";B").append(bi).append("_C");
                h.append(";B").append(bi).append("_SOC");

            }

            w.write(h.toString());
            w.newLine();

            /* ---------- DATA ---------- */
            for (SimulationStepRecord r : recs) {

                StringBuilder s = new StringBuilder();
                s.append(r.getTimeIndex()).append(';')
                        .append(f(r.getTotalLoadKw()));

                for (int b = 0; b < busCnt; b++) {

                    s.append(';').append(f(r.getBusLoadKw()[b]));
                    s.append(';').append(f(r.getBusDeficitKw()[b]));
                    s.append(';').append(f(r.getBusGenWindKw()[b]));

                    int dgCnt = r.getBusGenDgLoadKw()[b].length;
                    for (int i = 0; i < dgCnt; i++) {

                        if (!r.getDgAvailable()[b][i]) {
                            s.append(r.getDgInMaintenance()[b][i] ? ";TO" : ";OFF");
                        } else {
                            s.append(';').append(f(r.getBusGenDgLoadKw()[b][i]));
                        }

                        s.append(';')
                                .append(f(r.getBusGenDgTotalTimeWorked()[b][i]));
                    }

                    s.append(';').append(f(r.getBusGenBtKw()[b]));
                    s.append(';').append(f(r.getBtActualCapacity()[b]));
                    s.append(';').append(f(r.getBtActualSOC()[b]));

                }

                w.write(s.toString());
                w.newLine();
            }
        }
    }

    private static String f(double v) {
        if (!Double.isFinite(v)) return "";
        return String.format(RU, "%.1f", v);
    }
}
