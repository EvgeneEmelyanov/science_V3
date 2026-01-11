package simcore.io;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import simcore.config.BusSystemType;
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
            // Economics (must be the first output column)
            c = writeHeader(hdr, c, "Econ", headerStyle);

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
            // Economics inputs block is written BELOW the results.
            // Row indexes here are 0-based in POI; Excel formulas use 1-based.
            final int econBlockStartRow0 = 3 + estimates.size(); // one blank row after the last data row

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

                // --- Econ formula cell (references inputs below + outputs in this row) ---
                final int econColIdx = cc;
                Cell econCell = rr.createCell(cc++);
                econCell.setCellStyle(centeredNumberStyle);

                // Column indexes (0-based) for the outputs in this row, relative to Econ column.
                final int ensMeanColIdx = econColIdx + 1;
                final int ens1ColIdx = econColIdx + 5;
                final int ens2ColIdx = econColIdx + 6;
                final int fuelMlColIdx = econColIdx + 7;
                final int motoKhColIdx = econColIdx + 8;
                // BtRepl column index (0-based): in SWEEP_2 it is U; in SWEEP_1 it will still be fixed offset from Econ
                // Order after Econ: ... BT_% (12), FailRoom (13), FailBus (14), FailDg (15), FailWt (16), FailBt (17), BtRepl (18), FailBrk (19)
                final int btReplColIdx = econColIdx + 18;

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

                // Fill the Econ formula AFTER creating all required cells in the row.
                // Inputs table layout (A=quantity for DG/WT/BT; B=unit price/value; C=label):
                // 0 RU price
                // 1 DG price (A=DG total power)
                // 2 DG motoh cost
                // 3 Fuel price
                // 4 WT price (A=WT total power)
                // 5 WT maintenance per kW/year
                // 6 BT price (A=BT total capacity)
                // 7 BT maintenance per kWh/year
                // 8 Damage1
                // 9 Damage2
                // 10 Damage3
                int baseExcel = econBlockStartRow0 + 1; // 1-based

                String ruPrice = "RAW!$B$" + (baseExcel + 0);

                String dgPower = "RAW!$A$" + (baseExcel + 1);
                String dgPricePerKw = "RAW!$B$" + (baseExcel + 1);
                String dgMotoCost = "RAW!$B$" + (baseExcel + 2);

                String fuelPrice = "RAW!$B$" + (baseExcel + 3);

                String wtPower = "RAW!$A$" + (baseExcel + 4);
                String wtPricePerKw = "RAW!$B$" + (baseExcel + 4);
                String wtMaint = "RAW!$B$" + (baseExcel + 5);

                String btCap = "RAW!$A$" + (baseExcel + 6);
                String btPricePerKwh = "RAW!$B$" + (baseExcel + 6);
                String btMaint = "RAW!$B$" + (baseExcel + 7);

                String dmg1 = "RAW!$B$" + (baseExcel + 8);
                String dmg2 = "RAW!$B$" + (baseExcel + 9);
                String dmg3 = "RAW!$B$" + (baseExcel + 10);

                int rowExcel = rr.getRowNum() + 1;
                String ensMean = colLetter(ensMeanColIdx + 1) + rowExcel;
                String ens1 = colLetter(ens1ColIdx + 1) + rowExcel;
                String ens2 = colLetter(ens2ColIdx + 1) + rowExcel;
                String fuelMlCell = colLetter(fuelMlColIdx + 1) + rowExcel;
                String motoKhCell = colLetter(motoKhColIdx + 1) + rowExcel;
                String btReplCell = colLetter(btReplColIdx + 1) + rowExcel;

                // Total cost formula:
                // - WT/BT yearly maintenance multiplied by 20 years
                // - DG motoh cost multiplied by Moto_kh
                // - Fuel price multiplied by Fuel_ML
                // - Battery CAPEX is multiplied by (1 + BtRepl)
                String f = "(" + ruPrice
                        + "+(" + dgPricePerKw + "*" + dgPower + ")"
                        + "+(" + wtPricePerKw + "*" + wtPower + ")"
                        + "+(" + btPricePerKwh + "*" + btCap + "*(1+" + btReplCell + "))"
                        + "+((" + wtMaint + "*" + wtPower + "+" + btMaint + "*" + btCap + ")*20)"
                        + "+(" + fuelPrice + "*" + fuelMlCell + ")"
                        + "+(" + dgMotoCost + "*" + motoKhCell + ")"
                        + "+(" + dmg1 + "*" + ens1 + ")"
                        + "+(" + dmg2 + "*" + ens2 + ")"
                        + "+(" + dmg3 + "*(" + ensMean + "-(" + ens1 + "+" + ens2 + "))" + "))/1000000";

                econCell.setCellFormula(f);
            }

            // ===== Economics inputs table (RAW, below results) =====
            // One blank row after results
            r++;
            r = writeEconomicsInputsBlock(raw, r, baseParams, centeredNumberStyle, headerStyle);

            // Autosize all RAW columns except A (but note: A is used; keep as requested earlier)
            int rawCols = hdr.getLastCellNum();
            autosizeFrom(raw, rawCols, 1);

            // ===== SWEEP_2 grid (only for SWEEP_2) =====
            if (mode == simcore.Main.RunMode.SWEEP_2) {
                Sheet grid = wb.createSheet("SWEEP_2");

                boolean isTriangular = (param1 != null && param2 != null)
                        && paramSets.size() < (long) param1.length * (long) param2.length;

                // Limit AVERAGEIFS ranges strictly to the results table rows (avoid picking up econ-inputs block below)
                final int firstDataExcelRow = 3; // header is row 2, first data is row 3
                final int lastDataExcelRow = 2 + estimates.size();

                String p1Range = "RAW!$A$" + firstDataExcelRow + ":$A$" + lastDataExcelRow;
                String p2Range = "RAW!$B$" + firstDataExcelRow + ":$B$" + lastDataExcelRow;

                String econRange = "RAW!$C$" + firstDataExcelRow + ":$C$" + lastDataExcelRow;
                String ensMeanRange = "RAW!$D$" + firstDataExcelRow + ":$D$" + lastDataExcelRow;
                String fuelRange = "RAW!$J$" + firstDataExcelRow + ":$J$" + lastDataExcelRow;
                String motoRange = "RAW!$K$" + firstDataExcelRow + ":$K$" + lastDataExcelRow;
                String ens1Range = "RAW!$H$" + firstDataExcelRow + ":$H$" + lastDataExcelRow;
                String ens2Range = "RAW!$I$" + firstDataExcelRow + ":$I$" + lastDataExcelRow;

                String failRoomRange = "RAW!$P$" + firstDataExcelRow + ":$P$" + lastDataExcelRow;
                String failBusRange = "RAW!$Q$" + firstDataExcelRow + ":$Q$" + lastDataExcelRow;
                String failDgRange = "RAW!$R$" + firstDataExcelRow + ":$R$" + lastDataExcelRow;
                String failWtRange = "RAW!$S$" + firstDataExcelRow + ":$S$" + lastDataExcelRow;
                String failBtRange = "RAW!$T$" + firstDataExcelRow + ":$T$" + lastDataExcelRow;
                String btReplRange = "RAW!$U$" + firstDataExcelRow + ":$U$" + lastDataExcelRow;
                String failBrkRange = "RAW!$V$" + firstDataExcelRow + ":$V$" + lastDataExcelRow;

                int top = 0;

                if (isTriangular) {
                    top = writeTriangularGridBlock(grid, "Econ", top, param1, param2,
                            econRange, p1Range, p2Range, centeredNumberStyle, headerStyle);

                    top = writeTriangularGridBlock(grid, "ENS_mean", top + 2, param1, param2,
                            ensMeanRange, p1Range, p2Range, centeredNumberStyle, headerStyle);

                    top = writeTriangularGridBlock(grid, "Fuel_ML", top + 2, param1, param2,
                            fuelRange, p1Range, p2Range, centeredNumberStyle, headerStyle);

                    top = writeTriangularGridBlock(grid, "Moto_kh", top + 2, param1, param2,
                            motoRange, p1Range, p2Range, centeredNumberStyle, headerStyle);

                    top = writeTriangularGridBlock(grid, "ENS1_mean", top + 2, param1, param2,
                            ens1Range, p1Range, p2Range, centeredNumberStyle, headerStyle);

                    top = writeTriangularGridBlock(grid, "ENS2_mean", top + 2, param1, param2,
                            ens2Range, p1Range, p2Range, centeredNumberStyle, headerStyle);

                    top = writeTriangularGridBlock(grid, "FailRoom", top + 2, param1, param2,
                            failRoomRange, p1Range, p2Range, centeredNumberStyle, headerStyle);

                    top = writeTriangularGridBlock(grid, "FailBus", top + 2, param1, param2,
                            failBusRange, p1Range, p2Range, centeredNumberStyle, headerStyle);

                    top = writeTriangularGridBlock(grid, "FailDg", top + 2, param1, param2,
                            failDgRange, p1Range, p2Range, centeredNumberStyle, headerStyle);

                    top = writeTriangularGridBlock(grid, "FailWt", top + 2, param1, param2,
                            failWtRange, p1Range, p2Range, centeredNumberStyle, headerStyle);

                    top = writeTriangularGridBlock(grid, "FailBt", top + 2, param1, param2,
                            failBtRange, p1Range, p2Range, centeredNumberStyle, headerStyle);

                    top = writeTriangularGridBlock(grid, "BtRepl", top + 2, param1, param2,
                            btReplRange, p1Range, p2Range, centeredNumberStyle, headerStyle);

                    top = writeTriangularGridBlock(grid, "FailBrk", top + 2, param1, param2,
                            failBrkRange, p1Range, p2Range, centeredNumberStyle, headerStyle);

                } else {
                    top = writeGridBlock(grid, "Econ", top, param1, param2,
                            econRange, p1Range, p2Range, centeredNumberStyle, headerStyle);

                    top = writeGridBlock(grid, "ENS_mean", top + 2, param1, param2,
                            ensMeanRange, p1Range, p2Range, centeredNumberStyle, headerStyle);

                    top = writeGridBlock(grid, "Fuel_ML", top + 2, param1, param2,
                            fuelRange, p1Range, p2Range, centeredNumberStyle, headerStyle);

                    top = writeGridBlock(grid, "Moto_kh", top + 2, param1, param2,
                            motoRange, p1Range, p2Range, centeredNumberStyle, headerStyle);

                    top = writeGridBlock(grid, "ENS1_mean", top + 2, param1, param2,
                            ens1Range, p1Range, p2Range, centeredNumberStyle, headerStyle);

                    top = writeGridBlock(grid, "ENS2_mean", top + 2, param1, param2,
                            ens2Range, p1Range, p2Range, centeredNumberStyle, headerStyle);

                    top = writeGridBlock(grid, "FailRoom", top + 2, param1, param2,
                            failRoomRange, p1Range, p2Range, centeredNumberStyle, headerStyle);

                    top = writeGridBlock(grid, "FailBus", top + 2, param1, param2,
                            failBusRange, p1Range, p2Range, centeredNumberStyle, headerStyle);

                    top = writeGridBlock(grid, "FailDg", top + 2, param1, param2,
                            failDgRange, p1Range, p2Range, centeredNumberStyle, headerStyle);

                    top = writeGridBlock(grid, "FailWt", top + 2, param1, param2,
                            failWtRange, p1Range, p2Range, centeredNumberStyle, headerStyle);

                    top = writeGridBlock(grid, "FailBt", top + 2, param1, param2,
                            failBtRange, p1Range, p2Range, centeredNumberStyle, headerStyle);

                    top = writeGridBlock(grid, "BtRepl", top + 2, param1, param2,
                            btReplRange, p1Range, p2Range, centeredNumberStyle, headerStyle);

                    top = writeGridBlock(grid, "FailBrk", top + 2, param1, param2,
                            failBrkRange, p1Range, p2Range, centeredNumberStyle, headerStyle);
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

    /**
     * Writes the economics inputs block to RAW sheet.
     * Layout (columns):
     *  A: quantity (kW/kWh) - only for DG/WT/BT rows; empty for others
     *  B: unit price / value
     *  C: label
     *
     * @param startRow0 first row index (0-based) to write the block
     * @return next free row index after the block
     */
    private static int writeEconomicsInputsBlock(Sheet raw,
                                                 int startRow0,
                                                 SystemParameters baseParams,
                                                 CellStyle numStyle,
                                                 CellStyle headerStyle) {

        int busCount = (baseParams.getBusSystemType() == BusSystemType.SINGLE_NOT_SECTIONAL_BUS) ? 1 : 2;

        double dgTotalPower = baseParams.getDieselGeneratorPowerKw() * baseParams.getTotalDieselGeneratorCount();
        double wtTotalPower = baseParams.getWindTurbinePowerKw() * baseParams.getTotalWindTurbineCount();
        double btTotalCap = baseParams.getBatteryCapacityKwhPerBus() * busCount;

        // 0) RU price
        startRow0 = writeEconRow(raw, startRow0, null, 0.0, "РУ", numStyle, headerStyle);

        // 1) DG price per kW (A=DG total power)
        startRow0 = writeEconRow(raw, startRow0, dgTotalPower, 0.0, "ДГУ 1кВт", numStyle, headerStyle);

        // 2) DG motoh cost
        startRow0 = writeEconRow(raw, startRow0, null, 0.0, "ДГУ 1 тыс.мчт", numStyle, headerStyle);

        // 3) Fuel price
        startRow0 = writeEconRow(raw, startRow0, null, 0.0, "топливо 1 кт", numStyle, headerStyle);

        // 4) WT price per kW (A=WT total power)
        startRow0 = writeEconRow(raw, startRow0, wtTotalPower, 0.0, "ВЭУ 1 кВт", numStyle, headerStyle);

        // 5) WT maintenance per kW/year
        startRow0 = writeEconRow(raw, startRow0, null, 0.0, "ВЭУ 1 кВт/год", numStyle, headerStyle);

        // 6) Battery price per kWh (A=BT total capacity)
        startRow0 = writeEconRow(raw, startRow0, btTotalCap, 0.0, "АКБ 1 кВт*ч", numStyle, headerStyle);

        // 7) Battery maintenance per kWh/year
        startRow0 = writeEconRow(raw, startRow0, null, 0.0, "АКБ 1 кВт*ч/год", numStyle, headerStyle);

        // 8-10) Damages
        startRow0 = writeEconRow(raw, startRow0, null, 0.0, "ущерб 1 кат за 1 кВт*ч", numStyle, headerStyle);
        startRow0 = writeEconRow(raw, startRow0, null, 0.0, "ущерб 2 кат за 1 кВт*ч", numStyle, headerStyle);
        startRow0 = writeEconRow(raw, startRow0, null, 0.0, "ущерб 3 кат за 1 кВт*ч", numStyle, headerStyle);

        // Make sure the columns are visible enough for the inputs block.
        raw.setColumnWidth(0, Math.max(raw.getColumnWidth(0), 12 * 256)); // qty
        raw.setColumnWidth(1, Math.max(raw.getColumnWidth(1), 14 * 256)); // unit value
        raw.setColumnWidth(2, Math.max(raw.getColumnWidth(2), 42 * 256)); // label

        return startRow0;
    }

    private static int writeEconRow(Sheet raw,
                                    int row0,
                                    Double quantity,
                                    double unitValue,
                                    String label,
                                    CellStyle numStyle,
                                    CellStyle headerStyle) {

        Row r = raw.createRow(row0);

        // A: quantity
        if (quantity != null) {
            Cell a = r.createCell(0);
            a.setCellValue(r2(quantity));
            a.setCellStyle(numStyle);
        }

        // B: unit value
        Cell b = r.createCell(1);
        b.setCellValue(r2(unitValue));
        b.setCellStyle(numStyle);

        // C: label
        Cell c = r.createCell(2);
        c.setCellValue(label);
        c.setCellStyle(headerStyle);

        return row0 + 1;
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
