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
            StringBuilder h = new StringBuilder("t;L;BRK");
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
                h.append(";B").append(bi).append("_H");
            }

            w.write(h.toString());
            w.newLine();

            /* ---------- DATA ---------- */
            for (SimulationStepRecord r : recs) {

                StringBuilder s = new StringBuilder();
                s.append(r.getTimeIndex()).append(';')
                        .append(f(r.getTotalLoadKw()))
                        .append(';')
                        .append(brk(r.getBreakerClosed()));

                boolean[] busStatus = r.getBusStatus();
                double[] busLoad = r.getBusLoadKw();
                double[] busDef = r.getBusDeficitKw();
                double[] busW = r.getBusGenWindKw();
                double[] busB = r.getBusGenBtKw();

                double[][] dgLoad = r.getBusGenDgLoadKw();
                boolean[][] dgAvail = r.getDgAvailable();
                boolean[][] dgMaint = r.getDgInMaintenance();
                double[][] dgTotalT = r.getBusGenDgTotalTimeWorked();

                double[] btCap = r.getBtActualCapacity();
                double[] btSoc = r.getBtActualSOC();
                double[] btH = r.getBtTimeWorked();

                for (int b = 0; b < busCnt; b++) {

                    s.append(';').append(busStatus[b] ? f(busLoad[b]) : "OFF");
                    s.append(';').append(f(busDef[b]));
                    s.append(';').append(f(busW[b]));

                    int dgCnt = dgLoad[b].length;
                    for (int i = 0; i < dgCnt; i++) {

                        if (!dgAvail[b][i]) {
                            s.append(dgMaint[b][i] ? ";TO" : ";OFF");
                        } else {
                            s.append(';').append(f(dgLoad[b][i]));
                        }

                        s.append(';').append(f(dgTotalT[b][i]));
                    }

                    s.append(';').append(f(busB[b]));
                    s.append(';').append(f(btCap[b]));
                    s.append(';').append(f(btSoc[b]));
                    s.append(';').append(f(btH[b]));
                }

                w.write(s.toString());
                w.newLine();
            }
        }
    }

    private static String brk(Boolean closed) {
        if (closed == null) return "";
        return closed ? "CLOSED" : "OPEN";
    }

    private static String f(double v) {
        if (!Double.isFinite(v)) return "";
        return String.format(RU, "%.1f", v);
    }
}
