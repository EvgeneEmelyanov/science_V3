package simcore.io;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import simcore.config.SimulationConfig;
import simcore.config.SystemParameters;
import simcore.engine.MonteCarloEstimate;
import simcore.engine.MonteCarloStats;

import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Locale;

public final class SweepResultsExcelWriter {

    private SweepResultsExcelWriter() {}

    public static void writeXlsx(String path,
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

        try (Workbook wb = new XSSFWorkbook()) {

            // ===== Styles =====
            DataFormat df = wb.createDataFormat();

            CellStyle passportStyle = wb.createCellStyle();
            passportStyle.setWrapText(false);
            passportStyle.setVerticalAlignment(VerticalAlignment.TOP);

            CellStyle headerStyle = wb.createCellStyle();
            headerStyle.setAlignment(HorizontalAlignment.CENTER);
            headerStyle.setVerticalAlignment(VerticalAlignment.CENTER);

            // numbers: centered, 2 decimals
            CellStyle centeredNumberStyle = wb.createCellStyle();
            centeredNumberStyle.setAlignment(HorizontalAlignment.CENTER);
            centeredNumberStyle.setVerticalAlignment(VerticalAlignment.CENTER);
            centeredNumberStyle.setDataFormat(df.getFormat("0.00"));

            // integers: centered, no decimals
            CellStyle centeredIntStyle = wb.createCellStyle();
            centeredIntStyle.setAlignment(HorizontalAlignment.CENTER);
            centeredIntStyle.setVerticalAlignment(VerticalAlignment.CENTER);
            centeredIntStyle.setDataFormat(df.getFormat("0"));

            // ===== RAW sheet =====
            Sheet raw = wb.createSheet("RAW");

            int r = 0;

            // A1: passport
            Row row0 = raw.createRow(r++);
            Cell passportCell = row0.createCell(0);
            passportCell.setCellValue(buildPassport(cfg, baseParams));
            passportCell.setCellStyle(passportStyle);

            // IMPORTANT: keep narrow column A (passport does NOT define width)
            raw.setColumnWidth(0, 10 * 256);
            row0.setHeightInPoints(14);

            // Headers
            Row hdr = raw.createRow(r++);
            int c = 0;

            if (mode == simcore.Main.RunMode.SWEEP_2) {
                Cell h1 = hdr.createCell(c++);
                h1.setCellValue("param1");
                h1.setCellStyle(headerStyle);

                Cell h2 = hdr.createCell(c++);
                h2.setCellValue("param2");
                h2.setCellStyle(headerStyle);

            } else if (mode == simcore.Main.RunMode.SWEEP_1) {
                Cell h1 = hdr.createCell(c++);
                h1.setCellValue("param1");
                h1.setCellStyle(headerStyle);
            }

            // Outputs
            c = writeHeader(hdr, c, "ENS_mean", headerStyle);
            c = writeHeader(hdr, c, "ENS_ciLo", headerStyle);
            c = writeHeader(hdr, c, "ENS_ciHi", headerStyle);
            c = writeHeader(hdr, c, "ENS_reqN", headerStyle);
            c = writeHeader(hdr, c, "ENS1_mean", headerStyle);
            c = writeHeader(hdr, c, "ENS2_mean", headerStyle);
            c = writeHeader(hdr, c, "Fuel_ML", headerStyle);
            c = writeHeader(hdr, c, "Moto_kh", headerStyle);
            c = writeHeader(hdr, c, "WRE_%", headerStyle);
            c = writeHeader(hdr, c, "WT_%", headerStyle);
            c = writeHeader(hdr, c, "DG_%", headerStyle);
            c = writeHeader(hdr, c, "BT_%", headerStyle);

            // Failures + replacements
            c = writeHeader(hdr, c, "FailRoom", headerStyle);
            c = writeHeader(hdr, c, "FailBus", headerStyle);
            c = writeHeader(hdr, c, "FailDg", headerStyle);
            c = writeHeader(hdr, c, "FailWt", headerStyle);
            c = writeHeader(hdr, c, "FailBt", headerStyle);
            c = writeHeader(hdr, c, "BtRepl", headerStyle);
            c = writeHeader(hdr, c, "FailBrk", headerStyle);

            // ===== RAW rows =====
            final int m2 = (param2 != null) ? param2.length : 0;
            final boolean canUseRectIndexing = (mode == simcore.Main.RunMode.SWEEP_2)
                    && param1 != null && param2 != null
                    && m2 > 0
                    && (paramSets.size() == (long) param1.length * (long) param2.length);

            for (int k = 0; k < estimates.size(); k++) {
                MonteCarloEstimate e = estimates.get(k);
                MonteCarloStats.Stats s = e.ensStats;

                double fuelML = e.meanFuelLiters / 1_000_000.0;
                double motoKh = e.meanMotoHours / 1_000.0;

                Row rr = raw.createRow(r++);
                int cc = 0;

                if (mode == simcore.Main.RunMode.SWEEP_2) {

                    double p1Val;
                    double p2Val;

                    if (canUseRectIndexing) {
                        int i1 = k / m2;
                        int i2 = k % m2;
                        p1Val = param1[i1];
                        p2Val = param2[i2];
                    } else {
                        // Triangular categories: p1=firstCat, p2=secondCat
                        SystemParameters sp = paramSets.get(k);
                        p1Val = sp.getFirstCat();
                        p2Val = sp.getSecondCat();
                    }

                    Cell p1 = rr.createCell(cc++);
                    p1.setCellValue(r2(p1Val));
                    p1.setCellStyle(centeredNumberStyle);

                    Cell p2 = rr.createCell(cc++);
                    p2.setCellValue(r2(p2Val));
                    p2.setCellStyle(centeredNumberStyle);

                } else if (mode == simcore.Main.RunMode.SWEEP_1) {
                    Cell p1 = rr.createCell(cc++);
                    p1.setCellValue(r2(param1[k]));
                    p1.setCellStyle(centeredNumberStyle);
                }

                writeNumber(rr, cc++, s.getMean(), centeredNumberStyle);
                writeNumber(rr, cc++, s.getCiLow(), centeredNumberStyle);
                writeNumber(rr, cc++, s.getCiHigh(), centeredNumberStyle);

                writeInt(rr, cc++, s.getRequiredSampleSize(), centeredIntStyle);

                writeNumber(rr, cc++, e.meanEnsCat1Kwh, centeredNumberStyle);
                writeNumber(rr, cc++, e.meanEnsCat2Kwh, centeredNumberStyle);
                writeNumber(rr, cc++, fuelML, centeredNumberStyle);
                writeNumber(rr, cc++, motoKh, centeredNumberStyle);
                writeNumber(rr, cc++, e.meanWre, centeredNumberStyle);
                writeNumber(rr, cc++, e.meanWtPct, centeredNumberStyle);
                writeNumber(rr, cc++, e.meanDgPct, centeredNumberStyle);
                writeNumber(rr, cc++, e.meanBtPct, centeredNumberStyle);

                writeNumber(rr, cc++, e.meanFailRoom, centeredNumberStyle);
                writeNumber(rr, cc++, e.meanFailBus, centeredNumberStyle);
                writeNumber(rr, cc++, e.meanFailDg, centeredNumberStyle);
                writeNumber(rr, cc++, e.meanFailWt, centeredNumberStyle);
                writeNumber(rr, cc++, e.meanFailBt, centeredNumberStyle);
                writeNumber(rr, cc++, e.meanRepBt, centeredNumberStyle);
                writeNumber(rr, cc++, e.meanFailBrk, centeredNumberStyle);
            }

            // Autosize all RAW columns except A
            int rawCols = hdr.getLastCellNum();
            autosizeFrom(raw, rawCols, 1);

            // ===== SWEEP_2 grid (only for SWEEP_2) =====
            if (mode == simcore.Main.RunMode.SWEEP_2) {
                Sheet grid = wb.createSheet("SWEEP_2");

                boolean isTriangular = (param1 != null && param2 != null)
                        && paramSets.size() < (long) param1.length * (long) param2.length;

                int top = 0;

                if (isTriangular) {
                    top = writeTriangularGridBlock(grid, "ENS_mean", top, param1, param2,
                            "RAW!$C:$C", "RAW!$A:$A", "RAW!$B:$B", centeredNumberStyle, headerStyle);

                    top = writeTriangularGridBlock(grid, "Fuel_ML", top + 2, param1, param2,
                            "RAW!$I:$I", "RAW!$A:$A", "RAW!$B:$B", centeredNumberStyle, headerStyle);

                    top = writeTriangularGridBlock(grid, "Moto_kh", top + 2, param1, param2,
                            "RAW!$J:$J", "RAW!$A:$A", "RAW!$B:$B", centeredNumberStyle, headerStyle);

                    top = writeTriangularGridBlock(grid, "ENS1_mean", top + 2, param1, param2,
                            "RAW!$G:$G", "RAW!$A:$A", "RAW!$B:$B", centeredNumberStyle, headerStyle);

                    top = writeTriangularGridBlock(grid, "ENS2_mean", top + 2, param1, param2,
                            "RAW!$H:$H", "RAW!$A:$A", "RAW!$B:$B", centeredNumberStyle, headerStyle);

                    top = writeTriangularGridBlock(grid, "FailRoom", top + 2, param1, param2,
                            "RAW!$O:$O", "RAW!$A:$A", "RAW!$B:$B", centeredNumberStyle, headerStyle);

                    top = writeTriangularGridBlock(grid, "FailBus", top + 2, param1, param2,
                            "RAW!$P:$P", "RAW!$A:$A", "RAW!$B:$B", centeredNumberStyle, headerStyle);

                    top = writeTriangularGridBlock(grid, "FailDg", top + 2, param1, param2,
                            "RAW!$Q:$Q", "RAW!$A:$A", "RAW!$B:$B", centeredNumberStyle, headerStyle);

                    top = writeTriangularGridBlock(grid, "FailWt", top + 2, param1, param2,
                            "RAW!$R:$R", "RAW!$A:$A", "RAW!$B:$B", centeredNumberStyle, headerStyle);

                    top = writeTriangularGridBlock(grid, "FailBt", top + 2, param1, param2,
                            "RAW!$S:$S", "RAW!$A:$A", "RAW!$B:$B", centeredNumberStyle, headerStyle);

                    top = writeTriangularGridBlock(grid, "BtRepl", top + 2, param1, param2,
                            "RAW!$T:$T", "RAW!$A:$A", "RAW!$B:$B", centeredNumberStyle, headerStyle);

                    top = writeTriangularGridBlock(grid, "FailBrk", top + 2, param1, param2,
                            "RAW!$U:$U", "RAW!$A:$A", "RAW!$B:$B", centeredNumberStyle, headerStyle);

                } else {
                    top = writeGridBlock(grid, "ENS_mean", top, param1, param2,
                            "RAW!$C:$C", "RAW!$A:$A", "RAW!$B:$B", centeredNumberStyle, headerStyle);

                    top = writeGridBlock(grid, "Fuel_ML", top + 2, param1, param2,
                            "RAW!$I:$I", "RAW!$A:$A", "RAW!$B:$B", centeredNumberStyle, headerStyle);

                    top = writeGridBlock(grid, "Moto_kh", top + 2, param1, param2,
                            "RAW!$J:$J", "RAW!$A:$A", "RAW!$B:$B", centeredNumberStyle, headerStyle);

                    top = writeGridBlock(grid, "ENS1_mean", top + 2, param1, param2,
                            "RAW!$G:$G", "RAW!$A:$A", "RAW!$B:$B", centeredNumberStyle, headerStyle);

                    top = writeGridBlock(grid, "ENS2_mean", top + 2, param1, param2,
                            "RAW!$H:$H", "RAW!$A:$A", "RAW!$B:$B", centeredNumberStyle, headerStyle);

                    top = writeGridBlock(grid, "FailRoom", top + 2, param1, param2,
                            "RAW!$O:$O", "RAW!$A:$A", "RAW!$B:$B", centeredNumberStyle, headerStyle);

                    top = writeGridBlock(grid, "FailBus", top + 2, param1, param2,
                            "RAW!$P:$P", "RAW!$A:$A", "RAW!$B:$B", centeredNumberStyle, headerStyle);

                    top = writeGridBlock(grid, "FailDg", top + 2, param1, param2,
                            "RAW!$Q:$Q", "RAW!$A:$A", "RAW!$B:$B", centeredNumberStyle, headerStyle);

                    top = writeGridBlock(grid, "FailWt", top + 2, param1, param2,
                            "RAW!$R:$R", "RAW!$A:$A", "RAW!$B:$B", centeredNumberStyle, headerStyle);

                    top = writeGridBlock(grid, "FailBt", top + 2, param1, param2,
                            "RAW!$S:$S", "RAW!$A:$A", "RAW!$B:$B", centeredNumberStyle, headerStyle);

                    top = writeGridBlock(grid, "BtRepl", top + 2, param1, param2,
                            "RAW!$T:$T", "RAW!$A:$A", "RAW!$B:$B", centeredNumberStyle, headerStyle);

                    top = writeGridBlock(grid, "FailBrk", top + 2, param1, param2,
                            "RAW!$U:$U", "RAW!$A:$A", "RAW!$B:$B", centeredNumberStyle, headerStyle);
                }

                autosizeFrom(grid, Math.max(2, param2.length + 1), 0);
            }

            try (FileOutputStream out = new FileOutputStream(path)) {
                wb.write(out);
            }
        }
    }

    // ===== Helpers =====

    private static int writeHeader(Row hdr, int col, String text, CellStyle headerStyle) {
        Cell cell = hdr.createCell(col);
        cell.setCellValue(text);
        cell.setCellStyle(headerStyle);
        return col + 1;
    }

    private static void writeNumber(Row row, int col, double value, CellStyle numStyle) {
        Cell cell = row.createCell(col);
        cell.setCellValue(r2(value));
        cell.setCellStyle(numStyle);
    }

    private static void writeInt(Row row, int col, long value, CellStyle intStyle) {
        Cell cell = row.createCell(col);
        cell.setCellValue(value);
        cell.setCellStyle(intStyle);
    }

    private static int writeGridBlock(Sheet sh,
                                      String title,
                                      int topRow,
                                      double[] param1,
                                      double[] param2,
                                      String valueRange,
                                      String critRangeP1,
                                      String critRangeP2,
                                      CellStyle numStyle,
                                      CellStyle headerStyle) {

        // Title
        Row t = sh.createRow(topRow++);
        Cell titleCell = t.createCell(0);
        titleCell.setCellValue(title);
        titleCell.setCellStyle(headerStyle);

        // Header row: param2 across (TEXT, already rounded)
        Row hdr = sh.createRow(topRow++);
        Cell corner = hdr.createCell(0, CellType.STRING);
        corner.setCellValue("");
        corner.setCellStyle(headerStyle);

        for (int j = 0; j < param2.length; j++) {
            Cell cell = hdr.createCell(1 + j, CellType.STRING);
            cell.setCellValue(fmt2(param2[j])); // "0,15"
            cell.setCellStyle(headerStyle);
        }

        // Data rows: param1 down (TEXT, already rounded) + formulas
        for (int i = 0; i < param1.length; i++) {
            Row r = sh.createRow(topRow + i);

            Cell p1 = r.createCell(0, CellType.STRING);
            p1.setCellValue(fmt2(param1[i])); // "0,15"
            p1.setCellStyle(headerStyle);

            int rowExcel = (topRow + i) + 1;
            int hdrExcel = (topRow - 1) + 1;

            for (int j = 0; j < param2.length; j++) {
                String colParam2 = colLetter(1 + j + 1); // grid starts at B
                String f = "AVERAGEIFS(" + valueRange
                        + "," + critRangeP1 + ",VALUE($A" + rowExcel + ")"
                        + "," + critRangeP2 + ",VALUE(" + colParam2 + "$" + hdrExcel + ")"
                        + ")";

                Cell cell = r.createCell(1 + j);
                cell.setCellFormula(f);
                cell.setCellStyle(numStyle);
            }
        }

        return topRow + param1.length;
    }

    private static int writeTriangularGridBlock(Sheet sh,
                                                String title,
                                                int topRow,
                                                double[] param1,
                                                double[] param2,
                                                String valueRange,
                                                String critRangeP1,
                                                String critRangeP2,
                                                CellStyle numStyle,
                                                CellStyle headerStyle) {

        // Title
        Row t = sh.createRow(topRow++);
        Cell titleCell = t.createCell(0);
        titleCell.setCellValue(title);
        titleCell.setCellStyle(headerStyle);

        // Header row: param2 across (TEXT, rounded)
        Row hdr = sh.createRow(topRow++);
        Cell corner = hdr.createCell(0, CellType.STRING);
        corner.setCellValue("");
        corner.setCellStyle(headerStyle);

        for (int j = 0; j < param2.length; j++) {
            Cell cell = hdr.createCell(1 + j, CellType.STRING);
            cell.setCellValue(fmt2(param2[j]));
            cell.setCellStyle(headerStyle);
        }

        // Data rows: param1 down (TEXT, rounded) + formulas, blank above k1+k2>1
        for (int i = 0; i < param1.length; i++) {
            Row r = sh.createRow(topRow + i);

            Cell p1 = r.createCell(0, CellType.STRING);
            p1.setCellValue(fmt2(param1[i]));
            p1.setCellStyle(headerStyle);

            int rowExcel = (topRow + i) + 1;
            int hdrExcel = (topRow - 1) + 1;

            for (int j = 0; j < param2.length; j++) {
                String colParam2 = colLetter(1 + j + 1); // B.. columns

                String k1 = "VALUE($A" + rowExcel + ")";
                String k2 = "VALUE(" + colParam2 + "$" + hdrExcel + ")";

                String avg = "AVERAGEIFS(" + valueRange
                        + "," + critRangeP1 + "," + k1
                        + "," + critRangeP2 + "," + k2
                        + ")";

                String f = "IF((" + k1 + "+" + k2 + ")<=1," + avg + ",\"\")";

                Cell cell = r.createCell(1 + j);
                cell.setCellFormula(f);
                cell.setCellStyle(numStyle);
            }
        }

        return topRow + param1.length;
    }

    private static String colLetter(int col1Based) {
        int col = col1Based;
        StringBuilder sb = new StringBuilder();
        while (col > 0) {
            int rem = (col - 1) % 26;
            sb.insert(0, (char) ('A' + rem));
            col = (col - 1) / 26;
        }
        return sb.toString();
    }

    private static void autosizeFrom(Sheet sh, int cols, int fromCol) {
        for (int i = fromCol; i < cols; i++) sh.autoSizeColumn(i);
    }

    private static double r2(double v) {
        return Math.rint(v * 100.0) / 100.0;
    }

    private static String fmt2(double v) {
        // Use dot, then replace to comma to match your Excel locale expectations in VALUE(...)
        return String.format(Locale.US, "%.2f", r2(v)).replace('.', ',');
    }

    private static String buildPassport(SimulationConfig cfg, SystemParameters sp) {
        return String.format(
                "bus=%s; I=%.2f; II=%.2f; III=%.2f; MC=%d; fail=%b; deg=%b; chargeDg=%b; WT=%dx%.0f; DG=%dx%.0f; BT_base=%.1f; Ib=%.2f/%.2f; nonRes=%.2f",
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
}
