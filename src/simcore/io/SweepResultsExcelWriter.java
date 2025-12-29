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

            // numbers for RAW: keep numeric cells for charts
            CellStyle centeredNumberStyle = wb.createCellStyle();
            centeredNumberStyle.setAlignment(HorizontalAlignment.CENTER);
            centeredNumberStyle.setVerticalAlignment(VerticalAlignment.CENTER);
            centeredNumberStyle.setDataFormat(df.getFormat("0.00"));

            // integers: no decimals ever
            CellStyle centeredIntStyle = wb.createCellStyle();
            centeredIntStyle.setAlignment(HorizontalAlignment.CENTER);
            centeredIntStyle.setVerticalAlignment(VerticalAlignment.CENTER);
            centeredIntStyle.setDataFormat(df.getFormat("0"));

            // ===== RAW sheet =====
            Sheet raw = wb.createSheet("RAW");

            int r = 0;

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

            final int m2 = (param2 != null) ? param2.length : 0;

            // RAW rows
            for (int k = 0; k < estimates.size(); k++) {
                MonteCarloEstimate e = estimates.get(k);
                MonteCarloStats.Stats s = e.ensStats;

                double fuelML = e.meanFuelLiters / 1_000_000.0;
                double motoKh = e.meanMotoHours / 1_000.0;

                Row rr = raw.createRow(r++);
                int cc = 0;

                if (mode == simcore.Main.RunMode.SWEEP_2) {
                    int i1 = k / m2;
                    int i2 = k % m2;

                    Cell p1 = rr.createCell(cc++);
                    p1.setCellValue(param1[i1]);
                    p1.setCellStyle(centeredNumberStyle);

                    Cell p2 = rr.createCell(cc++);
                    p2.setCellValue(param2[i2]);
                    p2.setCellStyle(centeredNumberStyle);

                } else if (mode == simcore.Main.RunMode.SWEEP_1) {
                    Cell p1 = rr.createCell(cc++);
                    p1.setCellValue(param1[k]);
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

            // Autosize all RAW columns except A (passport/param1)
            int rawCols = hdr.getLastCellNum();
            autosizeFrom(raw, rawCols, 1);

            // ===== SWEEP_2 grid (only for SWEEP_2) =====
            if (mode == simcore.Main.RunMode.SWEEP_2) {
                Sheet grid = wb.createSheet("SWEEP_2");

                int top = 0;

                top = writeGridBlock(grid, "ENS_mean", top, param1, param2,
                        "RAW!$C:$C",
                        "RAW!$A:$A",
                        "RAW!$B:$B",
                        centeredNumberStyle,
                        headerStyle);

                top = writeGridBlock(grid, "Fuel_ML", top + 2, param1, param2,
                        "RAW!$I:$I",
                        "RAW!$A:$A",
                        "RAW!$B:$B",
                        centeredNumberStyle,
                        headerStyle);

                top = writeGridBlock(grid, "Moto_kh", top + 2, param1, param2,
                        "RAW!$J:$J",
                        "RAW!$A:$A",
                        "RAW!$B:$B",
                        centeredNumberStyle,
                        headerStyle);

                top = writeGridBlock(grid, "ENS1_mean", top + 2, param1, param2,
                        "RAW!$G:$G",
                        "RAW!$A:$A",
                        "RAW!$B:$B",
                        centeredNumberStyle,
                        headerStyle);

                top = writeGridBlock(grid, "ENS2_mean", top + 2, param1, param2,
                        "RAW!$H:$H",
                        "RAW!$A:$A",
                        "RAW!$B:$B",
                        centeredNumberStyle,
                        headerStyle);

                top = writeGridBlock(grid, "FailRoom", top + 2, param1, param2,
                        "RAW!$O:$O",
                        "RAW!$A:$A",
                        "RAW!$B:$B",
                        centeredNumberStyle,
                        headerStyle);

                top = writeGridBlock(grid, "FailBus", top + 2, param1, param2,
                        "RAW!$P:$P",
                        "RAW!$A:$A",
                        "RAW!$B:$B",
                        centeredNumberStyle,
                        headerStyle);

                top = writeGridBlock(grid, "FailDg", top + 2, param1, param2,
                        "RAW!$Q:$Q",
                        "RAW!$A:$A",
                        "RAW!$B:$B",
                        centeredNumberStyle,
                        headerStyle);

                top = writeGridBlock(grid, "FailWt", top + 2, param1, param2,
                        "RAW!$R:$R",
                        "RAW!$A:$A",
                        "RAW!$B:$B",
                        centeredNumberStyle,
                        headerStyle);

                top = writeGridBlock(grid, "FailBt", top + 2, param1, param2,
                        "RAW!$S:$S",
                        "RAW!$A:$A",
                        "RAW!$B:$B",
                        centeredNumberStyle,
                        headerStyle);

                top = writeGridBlock(grid, "BtRepl", top + 2, param1, param2,
                        "RAW!$T:$T",
                        "RAW!$A:$A",
                        "RAW!$B:$B",
                        centeredNumberStyle,
                        headerStyle);

                top = writeGridBlock(grid, "FailBrk", top + 2, param1, param2,
                        "RAW!$U:$U",
                        "RAW!$A:$A",
                        "RAW!$B:$B",
                        centeredNumberStyle,
                        headerStyle);

                autosizeFrom(grid, Math.max(2, param2.length + 1), 0);
            }

            try (FileOutputStream out = new FileOutputStream(path)) {
                wb.write(out);
            }
        }
    }

    private static int writeHeader(Row hdr, int col, String text, CellStyle headerStyle) {
        Cell cell = hdr.createCell(col);
        cell.setCellValue(text);
        cell.setCellStyle(headerStyle);
        return col + 1;
    }

    private static void writeNumber(Row row, int col, double value, CellStyle numStyle) {
        Cell cell = row.createCell(col);
        cell.setCellValue(value);
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

        // Header row: param2 across (WRITE AS TEXT)
        Row hdr = sh.createRow(topRow++);
        Cell corner = hdr.createCell(0, CellType.STRING);
        corner.setCellValue("");
        corner.setCellStyle(headerStyle);

        for (int j = 0; j < param2.length; j++) {
            Cell cell = hdr.createCell(1 + j, CellType.STRING);
            cell.setCellValue(Double.toString(param2[j]).replace('.', ','));

            cell.setCellStyle(headerStyle);
        }

        // Data rows: param1 down (WRITE AS TEXT) + formulas
        for (int i = 0; i < param1.length; i++) {
            Row r = sh.createRow(topRow + i);

            Cell p1 = r.createCell(0, CellType.STRING);
            p1.setCellValue(Double.toString(param1[i]).replace('.', ','));  // ВАЖНО: тоже запятая
            p1.setCellStyle(headerStyle);


            // Excel row numbers (1-based)
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
