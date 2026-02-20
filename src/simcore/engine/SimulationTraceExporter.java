package simcore.engine;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Locale;

public final class SimulationTraceExporter {

    private static final Locale RU = Locale.forLanguageTag("ru-RU");

    /**
     * false = краткая форма (по умолчанию)
     * true  = подробная форма
     */
    private static final boolean DETAILED_OUTPUT = false;

    private SimulationTraceExporter() {}

    /**
     * Экспорт трассы в Excel (XLSX):
     * - все значения центрированы по горизонтали и вертикали
     * - закреплена верхняя строка
     * - включен автофильтр (data-filter) на заголовке
     *
     * В КРАТКОМ выводе (DETAILED_OUTPUT=false) УБРАНЫ:
     * - время работы ДГУ и время работы ДГУ на низкой загрузке (B*_D*_T и B*_D*_I)
     * - время работы АКБ (B*_H)
     * - максимальная/фактическая емкость АКБ (B*_C)
     */
    public static void exportToXlsx(String path, List<SimulationStepRecord> recs) throws IOException {
        if (recs == null || recs.isEmpty()) {
            throw new IllegalArgumentException("Empty trace");
        }

        final int busCnt = recs.get(0).getBusLoadKw().length;

        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sh = wb.createSheet("TRACE");
            wb.setForceFormulaRecalculation(false);

            // ===== Styles =====
            DataFormat df = wb.createDataFormat();

            CellStyle headerStyle = wb.createCellStyle();
            headerStyle.setAlignment(HorizontalAlignment.CENTER);
            headerStyle.setVerticalAlignment(VerticalAlignment.CENTER);
            headerStyle.setWrapText(false);

            Font headerFont = wb.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);

            CellStyle num1Style = wb.createCellStyle();
            num1Style.setAlignment(HorizontalAlignment.CENTER);
            num1Style.setVerticalAlignment(VerticalAlignment.CENTER);
            num1Style.setDataFormat(df.getFormat("0.0"));

            CellStyle intStyle = wb.createCellStyle();
            intStyle.setAlignment(HorizontalAlignment.CENTER);
            intStyle.setVerticalAlignment(VerticalAlignment.CENTER);
            intStyle.setDataFormat(df.getFormat("0"));

            CellStyle textStyle = wb.createCellStyle();
            textStyle.setAlignment(HorizontalAlignment.CENTER);
            textStyle.setVerticalAlignment(VerticalAlignment.CENTER);

            // ===== Header =====
            int r = 0;
            Row hdr = sh.createRow(r++);
            int c = 0;

            writeHeaderCell(hdr, c++, "t", headerStyle);
            writeHeaderCell(hdr, c++, "L", headerStyle);
            writeHeaderCell(hdr, c++, "BRK", headerStyle);
            writeHeaderCell(hdr, c++, "STATUS", headerStyle);

            for (int b = 0; b < busCnt; b++) {
                int bi = b + 1;

                writeHeaderCell(hdr, c++, "B" + bi + "_L", headerStyle);
                writeHeaderCell(hdr, c++, "B" + bi + "_Def", headerStyle);
                writeHeaderCell(hdr, c++, "B" + bi + "_W", headerStyle);

                int dgCnt = recs.get(0).getBusGenDgLoadKw()[b].length;
                for (int i = 0; i < dgCnt; i++) {
                    int di = i + 1;
                    writeHeaderCell(hdr, c++, "B" + bi + "_D" + di, headerStyle);

                    if (DETAILED_OUTPUT) {
                        // Подробный режим (в этом задании выключен)
                        writeHeaderCell(hdr, c++, "B" + bi + "_D" + di + "_T", headerStyle);
                        writeHeaderCell(hdr, c++, "B" + bi + "_D" + di + "_I", headerStyle);
                    }
                }

                writeHeaderCell(hdr, c++, "B" + bi + "_B", headerStyle);     // BT generation kW
                if (DETAILED_OUTPUT) {
                    writeHeaderCell(hdr, c++, "B" + bi + "_C", headerStyle); // BT capacity (removed in short)
                }
                writeHeaderCell(hdr, c++, "B" + bi + "_SOC", headerStyle);   // SOC
                if (DETAILED_OUTPUT) {
                    writeHeaderCell(hdr, c++, "B" + bi + "_H", headerStyle); // BT time worked (removed in short)
                }
            }

            final int lastCol0 = c - 1;

            // ===== Data =====
            for (SimulationStepRecord rec : recs) {
                Row row = sh.createRow(r++);
                int cc = 0;

                // t
                Cell ct = row.createCell(cc++);
                ct.setCellValue(rec.getTimeIndex());
                ct.setCellStyle(intStyle);

                // L
                writeNum1(row, cc++, rec.getTotalLoadKw(), num1Style);

                // BRK
                Cell cbrk = row.createCell(cc++);
                cbrk.setCellValue(brk(rec.getBreakerClosed()));
                cbrk.setCellStyle(textStyle);

                // STATUS
                Cell cst = row.createCell(cc++);
                cst.setCellValue(escape(rec.getStatus()));
                cst.setCellStyle(textStyle);

                boolean[] busStatus = rec.getBusStatus();
                double[] busLoad = rec.getBusLoadKw();
                double[] busDef = rec.getBusDeficitKw();
                double[] busW = rec.getBusGenWindKw();
                double[] busB = rec.getBusGenBtKw();

                double[][] dgLoad = rec.getBusGenDgLoadKw();
                boolean[][] dgAvail = rec.getDgAvailable();
                boolean[][] dgMaint = rec.getDgInMaintenance();

                // These are used only in detailed mode; keep lazy access.
                double[][] dgTotalT = null;
                int[][] dgIdleT = null;
                double[] btCap = null;
                double[] btH = null;

                if (DETAILED_OUTPUT) {
                    dgTotalT = rec.getBusGenDgTotalTimeWorked();
                    dgIdleT = rec.getBusGenDgIdleTime();
                    btCap = rec.getBtActualCapacity();
                    btH = rec.getBtTimeWorked();
                }

                double[] btSoc = rec.getBtActualSOC();

                for (int b = 0; b < busCnt; b++) {
                    // B*_L
                    if (busStatus[b]) {
                        writeNum1(row, cc++, busLoad[b], num1Style);
                    } else {
                        writeText(row, cc++, "OFF", textStyle);
                    }

                    // B*_Def
                    writeNum1(row, cc++, busDef[b], num1Style);

                    // B*_W
                    writeNum1(row, cc++, busW[b], num1Style);

                    // DG columns
                    int dgCnt = dgLoad[b].length;
                    for (int i = 0; i < dgCnt; i++) {
                        if (!dgAvail[b][i]) {
                            writeText(row, cc++, dgMaint[b][i] ? "TO" : "OFF", textStyle);
                        } else {
                            writeNum1(row, cc++, dgLoad[b][i], num1Style);
                        }

                        if (DETAILED_OUTPUT) {
                            writeNum1(row, cc++, dgTotalT[b][i], num1Style);
                            Cell idle = row.createCell(cc++);
                            idle.setCellValue(dgIdleT[b][i]);
                            idle.setCellStyle(intStyle);
                        }
                    }

                    // B*_B (BT generation kW)
                    writeNum1(row, cc++, busB[b], num1Style);

                    if (DETAILED_OUTPUT) {
                        // B*_C (capacity)
                        writeNum1(row, cc++, btCap[b], num1Style);
                    }

                    // B*_SOC
                    writeNum1(row, cc++, btSoc[b], num1Style);

                    if (DETAILED_OUTPUT) {
                        // B*_H (time worked)
                        writeNum1(row, cc++, btH[b], num1Style);
                    }
                }
            }

            // ===== Freeze top row + Filter =====
            sh.createFreezePane(0, 1);
            sh.setAutoFilter(new CellRangeAddress(0, 0, 0, lastCol0));

            // ===== Autosize =====
            for (int col = 0; col <= lastCol0; col++) {
                sh.autoSizeColumn(col);
                // небольшой минимум, чтобы "STATUS" не был слишком узкий при коротких значениях
                int w = sh.getColumnWidth(col);
                sh.setColumnWidth(col, Math.min(Math.max(w, 10 * 256), 80 * 256));
            }

            try (FileOutputStream out = new FileOutputStream(path)) {
                wb.write(out);
            }
        }
    }

    // ===== Helpers =====

    private static void writeHeaderCell(Row row, int col, String text, CellStyle style) {
        Cell cell = row.createCell(col, CellType.STRING);
        cell.setCellValue(text);
        cell.setCellStyle(style);
    }

    private static void writeNum1(Row row, int col, double v, CellStyle style) {
        Cell cell = row.createCell(col);
        if (Double.isFinite(v)) {
            cell.setCellValue(round1(v));
        } else {
            cell.setBlank();
        }
        cell.setCellStyle(style);
    }

    private static void writeText(Row row, int col, String text, CellStyle style) {
        Cell cell = row.createCell(col, CellType.STRING);
        cell.setCellValue(text == null ? "" : text);
        cell.setCellStyle(style);
    }

    private static String brk(Boolean closed) {
        if (closed == null) return "";
        return closed ? "CLOSED" : "OPEN";
    }

    private static double round1(double v) {
        return Math.rint(v * 10.0) / 10.0;
    }

    private static String escape(String s) {
        if (s == null) return "";
        // В Excel ';' не разделитель, но оставим замену чтобы статус был "чистым".
        return s.replace(';', ',');
    }
}