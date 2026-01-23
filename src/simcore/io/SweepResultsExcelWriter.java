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

            // numbers: centered, 2 decimals (for results)
            CellStyle centeredNumberStyle = wb.createCellStyle();
            centeredNumberStyle.setAlignment(HorizontalAlignment.CENTER);
            centeredNumberStyle.setVerticalAlignment(VerticalAlignment.CENTER);
            centeredNumberStyle.setDataFormat(df.getFormat("0.00"));

            // integers: centered, no decimals
            CellStyle centeredIntStyle = wb.createCellStyle();
            centeredIntStyle.setAlignment(HorizontalAlignment.CENTER);
            centeredIntStyle.setVerticalAlignment(VerticalAlignment.CENTER);
            centeredIntStyle.setDataFormat(df.getFormat("0"));

            // money/prices: centered, integer with thousands separator (no decimals)
            CellStyle econMoneyStyle = wb.createCellStyle();
            econMoneyStyle.setAlignment(HorizontalAlignment.CENTER);
            econMoneyStyle.setVerticalAlignment(VerticalAlignment.CENTER);
            econMoneyStyle.setDataFormat(df.getFormat("#,##0"));

            // ===== RAW sheet =====
            Sheet raw = wb.createSheet("RAW");

            int r = 0;

            // A1: passport
            Row row0 = raw.createRow(r++);
            Cell passportCell = row0.createCell(0);
            passportCell.setCellValue(buildPassport(cfg, baseParams));
            passportCell.setCellStyle(passportStyle);

            // Keep narrow column A
            raw.setColumnWidth(0, 10 * 256);
            row0.setHeightInPoints(14);

            // ===== Headers =====
            Row hdr = raw.createRow(r++);
            int c = 0;

            // Inputs (params)
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

            // Per-run equipment sizes (must be per-row, not from baseParams)
            c = writeHeader(hdr, c, "DG_kW", headerStyle);      // суммарная мощность ДГУ
            c = writeHeader(hdr, c, "DG1_kW", headerStyle);     // мощность одной ДГУ (нужно для моточасных затрат)
            c = writeHeader(hdr, c, "WT_kW", headerStyle);
            c = writeHeader(hdr, c, "BT_kWh", headerStyle);

            // Outputs
            c = writeHeader(hdr, c, "LCOE, руб/кВт∙ч", headerStyle);
            c = writeHeader(hdr, c, "Затраты млн.руб.", headerStyle);

            c = writeHeader(hdr, c, "ENS,кВт∙ч", headerStyle);
            c = writeHeader(hdr, c, "ENS_ciLo", headerStyle);
            c = writeHeader(hdr, c, "ENS_ciHi", headerStyle);
            c = writeHeader(hdr, c, "ENS_reqN", headerStyle);
            c = writeHeader(hdr, c, "ENS1_mean", headerStyle);
            c = writeHeader(hdr, c, "ENS2_mean", headerStyle);
            c = writeHeader(hdr, c, "Расход топлива", headerStyle);
            c = writeHeader(hdr, c, "Моточасы", headerStyle);
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

            // ENS event statistics (mean counts over the horizon)
            c = writeHeader(hdr, c, "ENS_evtN", headerStyle);
            c = writeHeader(hdr, c, "ENS_evtStart_lt1h", headerStyle);
            c = writeHeader(hdr, c, "ENS_evt1h", headerStyle);
            c = writeHeader(hdr, c, "ENS_evt2h", headerStyle);
            c = writeHeader(hdr, c, "ENS_evt3h", headerStyle);
            c = writeHeader(hdr, c, "ENS_evt4h", headerStyle);
            c = writeHeader(hdr, c, "ENS_evt5_8h", headerStyle);
            c = writeHeader(hdr, c, "ENS_evt9_12h", headerStyle);
            c = writeHeader(hdr, c, "ENS_evt13_24h", headerStyle);
            c = writeHeader(hdr, c, "ENS_evtGt24h", headerStyle);
            c = writeHeader(hdr, c, "ENS_evtMaxH", headerStyle);

            // ===== RAW rows =====
            // Economics inputs block is written BELOW the results (prices only).
            final int econBlockStartRow0 = 3 + estimates.size(); // one blank row after last data row

            final int m2 = (param2 != null) ? param2.length : 0;
            final boolean canUseRectIndexing = (mode == simcore.Main.RunMode.SWEEP_2)
                    && param1 != null && param2 != null
                    && m2 > 0
                    && (paramSets.size() == (long) param1.length * (long) param2.length);

            for (int k = 0; k < estimates.size(); k++) {
                MonteCarloEstimate e = estimates.get(k);
                MonteCarloStats.Stats s = e.ensStats;

                double fuelML = e.meanFuelLiters / 1_000_000.0;  // Fuel_ML
                double motoKh = e.meanMotoHours / 1_000.0;       // Moto_kh (тыс. моточасов)

                SystemParameters sp = paramSets.get(k);

                double dg1Kw = sp.getDieselGeneratorPowerKw(); // мощность одной ДГУ
                double dgTotalKw = dg1Kw * sp.getTotalDieselGeneratorCount(); // суммарная мощность всех ДГУ

                double wtTotalKw = sp.getWindTurbinePowerKw() * sp.getTotalWindTurbineCount();
                int busCount = (sp.getBusSystemType() == BusSystemType.SINGLE_NOT_SECTIONAL_BUS) ? 1 : 2;
                double btTotalKwh = sp.getBatteryCapacityKwhPerBus() * busCount;

                Row rr = raw.createRow(r++);
                int cc = 0;

                // ---- params ----
                if (mode == simcore.Main.RunMode.SWEEP_2) {

                    double p1Val;
                    double p2Val;

                    if (canUseRectIndexing) {
                        int i1 = k / m2;
                        int i2 = k % m2;
                        p1Val = param1[i1];
                        p2Val = param2[i2];
                    } else {
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

                // ---- per-run sizes ----
                final int dgTotKwColIdx = cc;
                writeNumber(rr, cc++, dgTotalKw, centeredNumberStyle);

                final int dg1KwColIdx = cc;
                writeNumber(rr, cc++, dg1Kw, centeredNumberStyle);

                final int wtKwColIdx = cc;
                writeNumber(rr, cc++, wtTotalKw, centeredNumberStyle);

                final int btKwhColIdx = cc;
                writeNumber(rr, cc++, btTotalKwh, centeredNumberStyle);

                // ---- LCOE ----
                writeNumber(rr, cc++, e.meanLcoeRubPerKwh, centeredNumberStyle);

                // ---- Econ cell ----
                final int econColIdx = cc;
                Cell econCell = rr.createCell(cc++);
                econCell.setCellStyle(centeredNumberStyle);

                // Column indexes (0-based) for the outputs in this row, relative to Econ column.
                final int ensMeanColIdx = econColIdx + 1;
                final int ens1ColIdx = econColIdx + 5;
                final int ens2ColIdx = econColIdx + 6;
                final int fuelMlColIdx = econColIdx + 7;
                final int motoKhColIdx = econColIdx + 8;
                final int btReplColIdx = econColIdx + 18; // fixed by header order after Econ

                // ---- outputs ----
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

                // ENS event statistics
                writeNumber(rr, cc++, e.meanEnsEventsTotal, centeredNumberStyle);
                writeNumber(rr, cc++, e.meanEnsEventsStartOnly, centeredNumberStyle);
                writeNumber(rr, cc++, e.meanEnsEvents1H, centeredNumberStyle);
                writeNumber(rr, cc++, e.meanEnsEvents2H, centeredNumberStyle);
                writeNumber(rr, cc++, e.meanEnsEvents3H, centeredNumberStyle);
                writeNumber(rr, cc++, e.meanEnsEvents4H, centeredNumberStyle);
                writeNumber(rr, cc++, e.meanEnsEvents5to8H, centeredNumberStyle);
                writeNumber(rr, cc++, e.meanEnsEvents9to12H, centeredNumberStyle);
                writeNumber(rr, cc++, e.meanEnsEvents13to24H, centeredNumberStyle);
                writeNumber(rr, cc++, e.meanEnsEventsGt24H, centeredNumberStyle);
                writeNumber(rr, cc++, e.meanEnsEventsMaxHours, centeredNumberStyle);

                // ---- Econ formula ----
                int baseExcel = econBlockStartRow0 + 1; // 1-based

                String ruPrice = "RAW!$A$" + (baseExcel + 0);

                String dgPricePerKw = "RAW!$A$" + (baseExcel + 1);
                // затраты ДГУ: ₽ за 1 кВт за 1 тыс. мчт
                String dgMotoCostPerKwPerKh = "RAW!$A$" + (baseExcel + 2);

                String fuelPrice = "RAW!$A$" + (baseExcel + 3);

                String wtPricePerKw = "RAW!$A$" + (baseExcel + 4);
                String wtMaint = "RAW!$A$" + (baseExcel + 5);

                String btPricePerKwh = "RAW!$A$" + (baseExcel + 6);
                String btMaint = "RAW!$A$" + (baseExcel + 7);

                String dmg1 = "RAW!$A$" + (baseExcel + 8);
                String dmg2 = "RAW!$A$" + (baseExcel + 9);
                String dmg3 = "RAW!$A$" + (baseExcel + 10);

                int rowExcel = rr.getRowNum() + 1;

                String dgTotKwCell = colLetter(dgTotKwColIdx + 1) + rowExcel;
                String dg1KwCell = colLetter(dg1KwColIdx + 1) + rowExcel;
                String wtKwCell = colLetter(wtKwColIdx + 1) + rowExcel;
                String btKwhCell = colLetter(btKwhColIdx + 1) + rowExcel;

                String ensMean = colLetter(ensMeanColIdx + 1) + rowExcel;
                String ens1 = colLetter(ens1ColIdx + 1) + rowExcel;
                String ens2 = colLetter(ens2ColIdx + 1) + rowExcel;

                String fuelMlCell = colLetter(fuelMlColIdx + 1) + rowExcel;
                String motoKhCell = colLetter(motoKhColIdx + 1) + rowExcel;

                String btReplCell = colLetter(btReplColIdx + 1) + rowExcel;

                // Total cost formula:
                // - Battery CAPEX multiplied by (1 + BtRepl)
                // - WT/BT yearly maintenance multiplied by 20 years
                // - DG "моточасные" затраты: (₽/(кВт·тыс.мчт)) * (кВт одной ДГУ) * (тыс.мчт)
                String f = "("
                        + ruPrice
                        + "+(" + dgPricePerKw + "*" + dgTotKwCell + ")"
                        + "+(" + wtPricePerKw + "*" + wtKwCell + ")"
                        + "+(" + btPricePerKwh + "*" + btKwhCell + "*(1+" + btReplCell + "))"
                        + "+((" + wtMaint + "*" + wtKwCell + "+" + btMaint + "*" + btKwhCell + ")*20)"
                        + "+(" + fuelPrice + "*" + fuelMlCell + ")"
                        + "+(" + dgMotoCostPerKwPerKh + "*" + dg1KwCell + "*" + motoKhCell + ")"
                        + "+(" + dmg1 + "*" + ens1 + ")"
                        + "+(" + dmg2 + "*" + ens2 + ")"
                        + "+(" + dmg3 + "*(" + ensMean + "-(" + ens1 + "+" + ens2 + "))" + ")"
                        + ")/1000000";

                econCell.setCellFormula(f);
            }

            // ===== Economics inputs table (RAW, below results) =====
            r++; // one blank row
            r = writeEconomicsInputsBlock(raw, r, baseParams, econMoneyStyle, headerStyle);

            // Autosize RAW columns except A (keep narrow A)
            int rawCols = hdr.getLastCellNum();
            autosizeFrom(raw, rawCols, 1);

            // ===== SWEEP_2 grid (only for SWEEP_2) =====
            if (mode == simcore.Main.RunMode.SWEEP_2) {
                Sheet grid = wb.createSheet("SWEEP_2");

                boolean isTriangular = (param1 != null && param2 != null)
                        && paramSets.size() < (long) param1.length * (long) param2.length;

                final int firstDataExcelRow = 3; // header is row 2, first data is row 3
                final int lastDataExcelRow = 2 + estimates.size();

                String p1Range = "RAW!$A$" + firstDataExcelRow + ":$A$" + lastDataExcelRow;
                String p2Range = "RAW!$B$" + firstDataExcelRow + ":$B$" + lastDataExcelRow;

                // Columns:
                // param1(A), param2(B), DG_kW(C), DG1_kW(D), WT_kW(E), BT_kWh(F), LCOE(G), Econ(H), ENS_mean(I), ...
                String lcoeRange = "RAW!$G$" + firstDataExcelRow + ":$G$" + lastDataExcelRow;
                String econRange = "RAW!$H$" + firstDataExcelRow + ":$H$" + lastDataExcelRow;
                String ensMeanRange = "RAW!$I$" + firstDataExcelRow + ":$I$" + lastDataExcelRow;
                String fuelRange = "RAW!$O$" + firstDataExcelRow + ":$O$" + lastDataExcelRow;
                String motoRange = "RAW!$P$" + firstDataExcelRow + ":$P$" + lastDataExcelRow;
                String ens1Range = "RAW!$M$" + firstDataExcelRow + ":$M$" + lastDataExcelRow;
                String ens2Range = "RAW!$N$" + firstDataExcelRow + ":$N$" + lastDataExcelRow;

                String failRoomRange = "RAW!$U$"  + firstDataExcelRow + ":$U$"  + lastDataExcelRow;
                String failBusRange  = "RAW!$V$"  + firstDataExcelRow + ":$V$"  + lastDataExcelRow;
                String failDgRange   = "RAW!$W$"  + firstDataExcelRow + ":$W$"  + lastDataExcelRow;
                String failWtRange   = "RAW!$X$"  + firstDataExcelRow + ":$X$"  + lastDataExcelRow;
                String failBtRange   = "RAW!$Y$"  + firstDataExcelRow + ":$Y$"  + lastDataExcelRow;
                String btReplRange   = "RAW!$Z$"  + firstDataExcelRow + ":$Z$"  + lastDataExcelRow;
                String failBrkRange  = "RAW!$AA$" + firstDataExcelRow + ":$AA$" + lastDataExcelRow;

                int top = 0;

                if (isTriangular) {
                    top = writeTriangularGridBlock(grid, "LCOE, руб/кВт∙ч", top, param1, param2,
                            lcoeRange, p1Range, p2Range, centeredNumberStyle, headerStyle);
                    top = writeTriangularGridBlock(grid, "Затраты млн.руб", top + 2, param1, param2,
                            econRange, p1Range, p2Range, centeredNumberStyle, headerStyle);
                    top = writeTriangularGridBlock(grid, "ENS,кВт∙ч", top + 2, param1, param2,
                            ensMeanRange, p1Range, p2Range, centeredNumberStyle, headerStyle);
                    top = writeTriangularGridBlock(grid, "Расход топлива, тыс.тонн", top + 2, param1, param2,
                            fuelRange, p1Range, p2Range, centeredNumberStyle, headerStyle);
                    top = writeTriangularGridBlock(grid, "Моточасы, тыс.мч", top + 2, param1, param2,
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
                    top = writeGridBlock(grid, "LCOE, руб/кВт∙ч", top, param1, param2,
                            lcoeRange, p1Range, p2Range, centeredNumberStyle, headerStyle);
                    top = writeGridBlock(grid, "Затраты млн.руб.", top + 2, param1, param2,
                            econRange, p1Range, p2Range, centeredNumberStyle, headerStyle);
                    top = writeGridBlock(grid, "ENS,кВт∙ч", top + 2, param1, param2,
                            ensMeanRange, p1Range, p2Range, centeredNumberStyle, headerStyle);
                    top = writeGridBlock(grid, "Расход топлива, тыс.тонн", top + 2, param1, param2,
                            fuelRange, p1Range, p2Range, centeredNumberStyle, headerStyle);
                    top = writeGridBlock(grid, "Моточасы, тыс.мч", top + 2, param1, param2,
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

    private static int writeEconomicsInputsBlock(Sheet raw,
                                                 int startRow0,
                                                 SystemParameters baseParams,
                                                 CellStyle moneyStyle,
                                                 CellStyle headerStyle) {

        // базовые стоимости (берём из baseParams, чтобы совпадало с Defaults/SystemParameters)
        startRow0 = writeEconRow(raw, startRow0, baseParams.getCostRuRub(),               "РУ", moneyStyle, headerStyle);
        startRow0 = writeEconRow(raw, startRow0, baseParams.getCostDgRubPerKw(),          "ДГУ 1кВт", moneyStyle, headerStyle);
        startRow0 = writeEconRow(raw, startRow0, baseParams.getCostDgRubPerKwPerKmh(),    "ДГУ 1 тыс.мчт/1 кВт", moneyStyle, headerStyle);
        startRow0 = writeEconRow(raw, startRow0, baseParams.getCostFuelRubPerKt(),        "топливо 1 кт", moneyStyle, headerStyle);
        startRow0 = writeEconRow(raw, startRow0, baseParams.getCostWtRubPerKw(),          "ВЭУ 1 кВт", moneyStyle, headerStyle);
        startRow0 = writeEconRow(raw, startRow0, baseParams.getCostWtRubPerKwPerYear(),   "ВЭУ 1 кВт/год", moneyStyle, headerStyle);
        startRow0 = writeEconRow(raw, startRow0, baseParams.getCostBtRubPerKwh(),         "АКБ 1 кВт*ч", moneyStyle, headerStyle);
        startRow0 = writeEconRow(raw, startRow0, baseParams.getCostBtRubPerKwhPerYear(),  "АКБ 1 кВт*ч/год", moneyStyle, headerStyle);
        startRow0 = writeEconRow(raw, startRow0, baseParams.getDamageRubPerKwhCat1(),     "ущерб 1 кат за 1 кВт*ч", moneyStyle, headerStyle);
        startRow0 = writeEconRow(raw, startRow0, baseParams.getDamageRubPerKwhCat2(),     "ущерб 2 кат за 1 кВт*ч", moneyStyle, headerStyle);
        startRow0 = writeEconRow(raw, startRow0, baseParams.getDamageRubPerKwhCat3(),     "ущерб 3 кат за 1 кВт*ч", moneyStyle, headerStyle);

        raw.setColumnWidth(1, Math.max(raw.getColumnWidth(1), 42 * 256)); // label
        raw.setColumnWidth(0, Math.max(raw.getColumnWidth(0), 16 * 256)); // price/value

        return startRow0;
    }

    private static int writeEconRow(Sheet raw,
                                    int row0,
                                    double unitValue,
                                    String label,
                                    CellStyle moneyStyle,
                                    CellStyle headerStyle) {

        Row r = raw.createRow(row0);

        // A: unit value (integer)
        Cell a = r.createCell(0);
        a.setCellValue(unitValue);
        a.setCellStyle(moneyStyle);

        // B: label
        Cell b = r.createCell(1);
        b.setCellValue(label);
        b.setCellStyle(headerStyle);

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

        Row t = sh.createRow(topRow++);
        Cell titleCell = t.createCell(0);
        titleCell.setCellValue(title);
        titleCell.setCellStyle(headerStyle);

        Row hdr = sh.createRow(topRow++);
        Cell corner = hdr.createCell(0, CellType.STRING);
        corner.setCellValue("");
        corner.setCellStyle(headerStyle);

        for (int j = 0; j < param2.length; j++) {
            Cell cell = hdr.createCell(1 + j, CellType.STRING);
            cell.setCellValue(fmt2(param2[j]));
            cell.setCellStyle(headerStyle);
        }

        for (int i = 0; i < param1.length; i++) {
            Row r = sh.createRow(topRow + i);

            Cell p1 = r.createCell(0, CellType.STRING);
            p1.setCellValue(fmt2(param1[i]));
            p1.setCellStyle(headerStyle);

            int rowExcel = (topRow + i) + 1;
            int hdrExcel = (topRow - 1) + 1;

            for (int j = 0; j < param2.length; j++) {
                String colParam2 = colLetter(1 + j + 1);
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

        Row t = sh.createRow(topRow++);
        Cell titleCell = t.createCell(0);
        titleCell.setCellValue(title);
        titleCell.setCellStyle(headerStyle);

        Row hdr = sh.createRow(topRow++);
        Cell corner = hdr.createCell(0, CellType.STRING);
        corner.setCellValue("");
        corner.setCellStyle(headerStyle);

        for (int j = 0; j < param2.length; j++) {
            Cell cell = hdr.createCell(1 + j, CellType.STRING);
            cell.setCellValue(fmt2(param2[j]));
            cell.setCellStyle(headerStyle);
        }

        for (int i = 0; i < param1.length; i++) {
            Row r = sh.createRow(topRow + i);

            Cell p1 = r.createCell(0, CellType.STRING);
            p1.setCellValue(fmt2(param1[i]));
            p1.setCellStyle(headerStyle);

            int rowExcel = (topRow + i) + 1;
            int hdrExcel = (topRow - 1) + 1;

            for (int j = 0; j < param2.length; j++) {
                String colParam2 = colLetter(1 + j + 1);

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
        return String.format(Locale.US, "%.2f", r2(v)).replace('.', ',');
    }

    private static String buildPassport(SimulationConfig cfg, SystemParameters sp) {
        return String.format(
                "bus=%s; I=%.2f; II=%.2f; MC=%d; fail=%b; deg=%b; reserveIII=%b; WT=%dx%.0f; DG=%dx%.0f; BT_base=%.1f; Ib=%.2f/%.2f; nonRes=%.2f",
                sp.getBusSystemType(),
                sp.getFirstCat(),
                sp.getSecondCat(),
                cfg.getIterations(),
                cfg.isConsiderFailures(),
                cfg.isConsiderBatteryDegradation(),
                cfg.isReserveThirdCategory(),
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
