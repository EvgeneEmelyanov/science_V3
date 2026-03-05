package simcore.io;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFColorScaleFormatting;
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
        writeXlsx(path, mode, cfg, baseParams, paramSets, estimates, param1, param2, false);
    }

    public static void writeXlsx(String path,
                                 simcore.Main.RunMode mode,
                                 SimulationConfig cfg,
                                 SystemParameters baseParams,
                                 List<SystemParameters> paramSets,
                                 List<MonteCarloEstimate> estimates,
                                 double[] param1,
                                 double[] param2,
                                 boolean fullOutput) throws IOException {

        if (paramSets.size() != estimates.size()) {
            throw new IllegalArgumentException("paramSets.size != estimates.size");
        }

        try (Workbook wb = new XSSFWorkbook()) {

            // чтобы Excel пересчитал формулы (и условное форматирование на их основе)
            wb.setForceFormulaRecalculation(true);

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

            // small numbers: centered, scientific notation
            CellStyle centeredSciStyle = wb.createCellStyle();
            centeredSciStyle.setAlignment(HorizontalAlignment.CENTER);
            centeredSciStyle.setVerticalAlignment(VerticalAlignment.CENTER);
            centeredSciStyle.setDataFormat(df.getFormat("0.000E+00"));

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

            // Reliability-of-supply metrics derived from ENS (mean over MC)
            c = writeHeader(hdr, c, "LOLH", headerStyle);
            c = writeHeader(hdr, c, "LOLP", headerStyle);
            c = writeHeader(hdr, c, "LPSP", headerStyle);

            // ENS event statistics (mean counts over the horizon)
            c = writeHeader(hdr, c, "ENS_evtN", headerStyle);
            c = writeHeader(hdr, c, "ENS_evtStart_lt1h", headerStyle);
            c = writeHeader(hdr, c, "ENS_evt1h", headerStyle);
            c = writeHeader(hdr, c, "ENS_evt2_4h", headerStyle);
            c = writeHeader(hdr, c, "ENS_evt5_12h", headerStyle);
            c = writeHeader(hdr, c, "ENS_evt13_24h", headerStyle);
            c = writeHeader(hdr, c, "ENS_evtGt24h", headerStyle);
            c = writeHeader(hdr, c, "ENS_evtMaxH", headerStyle);

            // Failures + replacements
            c = writeHeader(hdr, c, "FailRoom", headerStyle);
            c = writeHeader(hdr, c, "FailBus", headerStyle);
            c = writeHeader(hdr, c, "FailDg", headerStyle);
            c = writeHeader(hdr, c, "FailWt", headerStyle);
            c = writeHeader(hdr, c, "FailBt", headerStyle);
            c = writeHeader(hdr, c, "BtRepl", headerStyle);
            c = writeHeader(hdr, c, "FailBrk", headerStyle);

            final int m2 = (param2 == null) ? 0 : param2.length;

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
                writeNumber(rr, cc++, dgTotalKw, centeredNumberStyle);
                writeNumber(rr, cc++, dg1Kw, centeredNumberStyle);
                writeNumber(rr, cc++, wtTotalKw, centeredNumberStyle);
                writeNumber(rr, cc++, btTotalKwh, centeredNumberStyle);

                // ---- LCOE ----
                writeNumber(rr, cc++, e.meanLcoeRubPerKwh, centeredNumberStyle);

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

                // Reliability-of-supply metrics derived from ENS
                writeNumber(rr, cc++, e.meanLoleHours, centeredNumberStyle);
                writeNumber(rr, cc++, e.meanLolp, centeredSciStyle);
                writeNumber(rr, cc++, e.meanLpsp, centeredSciStyle);

                // ENS event statistics
                writeNumber(rr, cc++, e.meanEnsEventsTotal, centeredNumberStyle);
                writeNumber(rr, cc++, e.meanEnsEventsStartOnly, centeredNumberStyle);
                writeNumber(rr, cc++, e.meanEnsEvents1H, centeredNumberStyle);
                writeNumber(rr, cc++, e.meanEnsEvents2to4H, centeredNumberStyle);
                writeNumber(rr, cc++, e.meanEnsEvents5to12H, centeredNumberStyle);
                writeNumber(rr, cc++, e.meanEnsEvents13to24H, centeredNumberStyle);
                writeNumber(rr, cc++, e.meanEnsEventsGt24H, centeredNumberStyle);
                writeNumber(rr, cc++, e.meanEnsEventsMaxHours, centeredNumberStyle);

                // Failures + replacements
                writeNumber(rr, cc++, e.meanFailRoom, centeredNumberStyle);
                writeNumber(rr, cc++, e.meanFailBus, centeredNumberStyle);
                writeNumber(rr, cc++, e.meanFailDg, centeredNumberStyle);
                writeNumber(rr, cc++, e.meanFailWt, centeredNumberStyle);
                writeNumber(rr, cc++, e.meanFailBt, centeredNumberStyle);
                writeNumber(rr, cc++, e.meanRepBt, centeredNumberStyle);
                writeNumber(rr, cc++, e.meanFailBrk, centeredNumberStyle);
            }

            // Autosize RAW columns except A (keep narrow A)
            int rawCols = hdr.getLastCellNum();
            autosizeFrom(raw, rawCols, 1);

            // ===== SWEEP_2 sheet (for SWEEP_2 and SWEEP_1) =====
            if (mode == simcore.Main.RunMode.SWEEP_2 || mode == simcore.Main.RunMode.SWEEP_1) {

                Sheet grid = wb.createSheet("SWEEP_2");

                final int firstDataExcelRow = 3; // header is row 2, first data is row 3
                final int lastDataExcelRow = 2 + estimates.size();

                // Always have param1 in RAW
                int colP1 = findHeaderColIdx(hdr, "param1");
                String p1Range = rangeInSheet("RAW", colP1, firstDataExcelRow, lastDataExcelRow);

                // Metrics ranges
                String lcoeRange    = rangeInSheet("RAW", findHeaderColIdx(hdr, "LCOE, руб/кВт∙ч"), firstDataExcelRow, lastDataExcelRow);
                String ensMeanRange = rangeInSheet("RAW", findHeaderColIdx(hdr, "ENS,кВт∙ч"), firstDataExcelRow, lastDataExcelRow);
                String fuelRange    = rangeInSheet("RAW", findHeaderColIdx(hdr, "Расход топлива"), firstDataExcelRow, lastDataExcelRow);
                String motoRange    = rangeInSheet("RAW", findHeaderColIdx(hdr, "Моточасы"), firstDataExcelRow, lastDataExcelRow);
                String ens1Range    = rangeInSheet("RAW", findHeaderColIdx(hdr, "ENS1_mean"), firstDataExcelRow, lastDataExcelRow);
                String ens2Range    = rangeInSheet("RAW", findHeaderColIdx(hdr, "ENS2_mean"), firstDataExcelRow, lastDataExcelRow);

                String loleHRange   = rangeInSheet("RAW", findHeaderColIdx(hdr, "LOLH"), firstDataExcelRow, lastDataExcelRow);
                String lolpRange    = rangeInSheet("RAW", findHeaderColIdx(hdr, "LOLP"), firstDataExcelRow, lastDataExcelRow);
                String lpspRange    = rangeInSheet("RAW", findHeaderColIdx(hdr, "LPSP"), firstDataExcelRow, lastDataExcelRow);

                String ensEvtNRange         = rangeInSheet("RAW", findHeaderColIdx(hdr, "ENS_evtN"), firstDataExcelRow, lastDataExcelRow);
                String ensEvtStartLt1hRange = rangeInSheet("RAW", findHeaderColIdx(hdr, "ENS_evtStart_lt1h"), firstDataExcelRow, lastDataExcelRow);
                String ensEvt1hRange        = rangeInSheet("RAW", findHeaderColIdx(hdr, "ENS_evt1h"), firstDataExcelRow, lastDataExcelRow);
                String ensEvt2_4hRange      = rangeInSheet("RAW", findHeaderColIdx(hdr, "ENS_evt2_4h"), firstDataExcelRow, lastDataExcelRow);
                String ensEvt5_12hRange     = rangeInSheet("RAW", findHeaderColIdx(hdr, "ENS_evt5_12h"), firstDataExcelRow, lastDataExcelRow);
                String ensEvt13_24hRange    = rangeInSheet("RAW", findHeaderColIdx(hdr, "ENS_evt13_24h"), firstDataExcelRow, lastDataExcelRow);
                String ensEvtGt24hRange     = rangeInSheet("RAW", findHeaderColIdx(hdr, "ENS_evtGt24h"), firstDataExcelRow, lastDataExcelRow);
                String ensEvtMaxHRange      = rangeInSheet("RAW", findHeaderColIdx(hdr, "ENS_evtMaxH"), firstDataExcelRow, lastDataExcelRow);

                String failRoomRange = rangeInSheet("RAW", findHeaderColIdx(hdr, "FailRoom"), firstDataExcelRow, lastDataExcelRow);
                String failBusRange  = rangeInSheet("RAW", findHeaderColIdx(hdr, "FailBus"), firstDataExcelRow, lastDataExcelRow);
                String failDgRange   = rangeInSheet("RAW", findHeaderColIdx(hdr, "FailDg"), firstDataExcelRow, lastDataExcelRow);
                String failWtRange   = rangeInSheet("RAW", findHeaderColIdx(hdr, "FailWt"), firstDataExcelRow, lastDataExcelRow);
                String failBtRange   = rangeInSheet("RAW", findHeaderColIdx(hdr, "FailBt"), firstDataExcelRow, lastDataExcelRow);
                String btReplRange   = rangeInSheet("RAW", findHeaderColIdx(hdr, "BtRepl"), firstDataExcelRow, lastDataExcelRow);
                String failBrkRange  = rangeInSheet("RAW", findHeaderColIdx(hdr, "FailBrk"), firstDataExcelRow, lastDataExcelRow);

                int top = 0;

                if (mode == simcore.Main.RunMode.SWEEP_2) {

                    // Need param2 ranges only in SWEEP_2
                    int colP2 = findHeaderColIdx(hdr, "param2");
                    String p2Range = rangeInSheet("RAW", colP2, firstDataExcelRow, lastDataExcelRow);

                    boolean isTriangular = (param1 != null && param2 != null)
                            && paramSets.size() < (long) param1.length * (long) param2.length;

                    if (isTriangular) {
                        top = writeTriangularGridBlock(grid, "LCOE, руб/кВт∙ч", top, param1, param2,
                                lcoeRange, p1Range, p2Range, centeredNumberStyle, headerStyle);
                        top = writeTriangularGridBlock(grid, "Расход топлива, тыс.тонн", top + 2, param1, param2,
                                fuelRange, p1Range, p2Range, centeredNumberStyle, headerStyle);
                        top = writeTriangularGridBlock(grid, "Моточасы, тыс.мч", top + 2, param1, param2,
                                motoRange, p1Range, p2Range, centeredNumberStyle, headerStyle);
                        top = writeTriangularGridBlock(grid, "ENS,кВт∙ч", top + 2, param1, param2,
                                ensMeanRange, p1Range, p2Range, centeredNumberStyle, headerStyle);
                        top = writeTriangularGridBlock(grid, "LOLH", top + 2, param1, param2,
                                loleHRange, p1Range, p2Range, centeredNumberStyle, headerStyle);

                        top = writeTriangularGridBlock(grid, "ENS_evtN", top + 2, param1, param2,
                                ensEvtNRange, p1Range, p2Range, centeredNumberStyle, headerStyle);
                        top = writeTriangularGridBlock(grid, "ENS_evtMaxH", top + 2, param1, param2,
                                ensEvtMaxHRange, p1Range, p2Range, centeredNumberStyle, headerStyle);

                        top = writeTriangularGridBlock(grid, "LOLP", top + 2, param1, param2,
                                lolpRange, p1Range, p2Range, centeredSciStyle, headerStyle);
                        top = writeTriangularGridBlock(grid, "LPSP", top + 2, param1, param2,
                                lpspRange, p1Range, p2Range, centeredSciStyle, headerStyle);

                        top = writeTriangularGridBlock(grid, "ENS1_mean", top + 2, param1, param2,
                                ens1Range, p1Range, p2Range, centeredNumberStyle, headerStyle);
                        top = writeTriangularGridBlock(grid, "ENS2_mean", top + 2, param1, param2,
                                ens2Range, p1Range, p2Range, centeredNumberStyle, headerStyle);

                        if (fullOutput) {
                            top = writeTriangularGridBlock(grid, "ENS_evtStart_lt1h", top + 2, param1, param2,
                                    ensEvtStartLt1hRange, p1Range, p2Range, centeredNumberStyle, headerStyle);
                            top = writeTriangularGridBlock(grid, "ENS_evt1h", top + 2, param1, param2,
                                    ensEvt1hRange, p1Range, p2Range, centeredNumberStyle, headerStyle);
                            top = writeTriangularGridBlock(grid, "ENS_evt2_4h", top + 2, param1, param2,
                                    ensEvt2_4hRange, p1Range, p2Range, centeredNumberStyle, headerStyle);
                            top = writeTriangularGridBlock(grid, "ENS_evt5_12h", top + 2, param1, param2,
                                    ensEvt5_12hRange, p1Range, p2Range, centeredNumberStyle, headerStyle);
                            top = writeTriangularGridBlock(grid, "ENS_evt13_24h", top + 2, param1, param2,
                                    ensEvt13_24hRange, p1Range, p2Range, centeredNumberStyle, headerStyle);
                            top = writeTriangularGridBlock(grid, "ENS_evtGt24h", top + 2, param1, param2,
                                    ensEvtGt24hRange, p1Range, p2Range, centeredNumberStyle, headerStyle);
                        }

                        if (fullOutput) {
                            top = writeTriangularGridBlock(grid, "FailRoom", top + 2, param1, param2,
                                    failRoomRange, p1Range, p2Range, centeredNumberStyle, headerStyle);
                            top = writeTriangularGridBlock(grid, "FailBus", top + 2, param1, param2,
                                    failBusRange, p1Range, p2Range, centeredNumberStyle, headerStyle);
                        }
                        top = writeTriangularGridBlock(grid, "FailDg", top + 2, param1, param2,
                                failDgRange, p1Range, p2Range, centeredNumberStyle, headerStyle);
                        if (fullOutput) {
                            top = writeTriangularGridBlock(grid, "FailWt", top + 2, param1, param2,
                                    failWtRange, p1Range, p2Range, centeredNumberStyle, headerStyle);
                        }
                        top = writeTriangularGridBlock(grid, "FailBt", top + 2, param1, param2,
                                failBtRange, p1Range, p2Range, centeredNumberStyle, headerStyle);
                        top = writeTriangularGridBlock(grid, "BtRepl", top + 2, param1, param2,
                                btReplRange, p1Range, p2Range, centeredNumberStyle, headerStyle);
                        if (fullOutput) {
                            top = writeTriangularGridBlock(grid, "FailBrk", top + 2, param1, param2,
                                    failBrkRange, p1Range, p2Range, centeredNumberStyle, headerStyle);
                        }

                    } else {
                        top = writeGridBlock(grid, "LCOE, руб/кВт∙ч", top, param1, param2,
                                lcoeRange, p1Range, p2Range, centeredNumberStyle, headerStyle);
                        top = writeGridBlock(grid, "Расход топлива, тыс.тонн", top + 2, param1, param2,
                                fuelRange, p1Range, p2Range, centeredNumberStyle, headerStyle);
                        top = writeGridBlock(grid, "Моточасы, тыс.мч", top + 2, param1, param2,
                                motoRange, p1Range, p2Range, centeredNumberStyle, headerStyle);
                        top = writeGridBlock(grid, "ENS,кВт∙ч", top + 2, param1, param2,
                                ensMeanRange, p1Range, p2Range, centeredNumberStyle, headerStyle);
                        top = writeGridBlock(grid, "LOLH", top + 2, param1, param2,
                                loleHRange, p1Range, p2Range, centeredNumberStyle, headerStyle);

                        top = writeGridBlock(grid, "ENS_evtN", top + 2, param1, param2,
                                ensEvtNRange, p1Range, p2Range, centeredNumberStyle, headerStyle);
                        top = writeGridBlock(grid, "ENS_evtMaxH", top + 2, param1, param2,
                                ensEvtMaxHRange, p1Range, p2Range, centeredNumberStyle, headerStyle);

                        top = writeGridBlock(grid, "LOLP", top + 2, param1, param2,
                                lolpRange, p1Range, p2Range, centeredSciStyle, headerStyle);
                        top = writeGridBlock(grid, "LPSP", top + 2, param1, param2,
                                lpspRange, p1Range, p2Range, centeredSciStyle, headerStyle);

                        top = writeGridBlock(grid, "ENS1_mean", top + 2, param1, param2,
                                ens1Range, p1Range, p2Range, centeredNumberStyle, headerStyle);
                        top = writeGridBlock(grid, "ENS2_mean", top + 2, param1, param2,
                                ens2Range, p1Range, p2Range, centeredNumberStyle, headerStyle);

                        if (fullOutput) {
                            top = writeGridBlock(grid, "ENS_evtStart_lt1h", top + 2, param1, param2,
                                    ensEvtStartLt1hRange, p1Range, p2Range, centeredNumberStyle, headerStyle);
                            top = writeGridBlock(grid, "ENS_evt1h", top + 2, param1, param2,
                                    ensEvt1hRange, p1Range, p2Range, centeredNumberStyle, headerStyle);
                            top = writeGridBlock(grid, "ENS_evt2_4h", top + 2, param1, param2,
                                    ensEvt2_4hRange, p1Range, p2Range, centeredNumberStyle, headerStyle);
                            top = writeGridBlock(grid, "ENS_evt5_12h", top + 2, param1, param2,
                                    ensEvt5_12hRange, p1Range, p2Range, centeredNumberStyle, headerStyle);
                            top = writeGridBlock(grid, "ENS_evt13_24h", top + 2, param1, param2,
                                    ensEvt13_24hRange, p1Range, p2Range, centeredNumberStyle, headerStyle);
                            top = writeGridBlock(grid, "ENS_evtGt24h", top + 2, param1, param2,
                                    ensEvtGt24hRange, p1Range, p2Range, centeredNumberStyle, headerStyle);
                        }

                        if (fullOutput) {
                            top = writeGridBlock(grid, "FailRoom", top + 2, param1, param2,
                                    failRoomRange, p1Range, p2Range, centeredNumberStyle, headerStyle);
                            top = writeGridBlock(grid, "FailBus", top + 2, param1, param2,
                                    failBusRange, p1Range, p2Range, centeredNumberStyle, headerStyle);
                        }
                        top = writeGridBlock(grid, "FailDg", top + 2, param1, param2,
                                failDgRange, p1Range, p2Range, centeredNumberStyle, headerStyle);
                        if (fullOutput) {
                            top = writeGridBlock(grid, "FailWt", top + 2, param1, param2,
                                    failWtRange, p1Range, p2Range, centeredNumberStyle, headerStyle);
                        }
                        top = writeGridBlock(grid, "FailBt", top + 2, param1, param2,
                                failBtRange, p1Range, p2Range, centeredNumberStyle, headerStyle);
                        top = writeGridBlock(grid, "BtRepl", top + 2, param1, param2,
                                btReplRange, p1Range, p2Range, centeredNumberStyle, headerStyle);
                        if (fullOutput) {
                            top = writeGridBlock(grid, "FailBrk", top + 2, param1, param2,
                                    failBrkRange, p1Range, p2Range, centeredNumberStyle, headerStyle);
                        }
                    }

                    autosizeFrom(grid, Math.max(2, (param2 != null ? param2.length : 0) + 1), 0);

                } else {
                    // SWEEP_1: 1D blocks (one row of values; columns are param1)
                    if (param1 != null && param1.length > 0) {
                        top = writeRowBlock1D(grid, "LCOE, руб/кВт∙ч", top, param1,
                                lcoeRange, p1Range, centeredNumberStyle, headerStyle);
                        top = writeRowBlock1D(grid, "Расход топлива, тыс.тонн", top + 2, param1,
                                fuelRange, p1Range, centeredNumberStyle, headerStyle);
                        top = writeRowBlock1D(grid, "Моточасы, тыс.мч", top + 2, param1,
                                motoRange, p1Range, centeredNumberStyle, headerStyle);
                        top = writeRowBlock1D(grid, "ENS,кВт∙ч", top + 2, param1,
                                ensMeanRange, p1Range, centeredNumberStyle, headerStyle);
                        top = writeRowBlock1D(grid, "LOLH", top + 2, param1,
                                loleHRange, p1Range, centeredNumberStyle, headerStyle);

                        top = writeRowBlock1D(grid, "ENS_evtN", top + 2, param1,
                                ensEvtNRange, p1Range, centeredNumberStyle, headerStyle);
                        top = writeRowBlock1D(grid, "ENS_evtMaxH", top + 2, param1,
                                ensEvtMaxHRange, p1Range, centeredNumberStyle, headerStyle);

                        top = writeRowBlock1D(grid, "LOLP", top + 2, param1,
                                lolpRange, p1Range, centeredSciStyle, headerStyle);
                        top = writeRowBlock1D(grid, "LPSP", top + 2, param1,
                                lpspRange, p1Range, centeredSciStyle, headerStyle);

                        top = writeRowBlock1D(grid, "ENS1_mean", top + 2, param1,
                                ens1Range, p1Range, centeredNumberStyle, headerStyle);
                        top = writeRowBlock1D(grid, "ENS2_mean", top + 2, param1,
                                ens2Range, p1Range, centeredNumberStyle, headerStyle);

                        if (fullOutput) {
                            top = writeRowBlock1D(grid, "ENS_evtStart_lt1h", top + 2, param1,
                                    ensEvtStartLt1hRange, p1Range, centeredNumberStyle, headerStyle);
                            top = writeRowBlock1D(grid, "ENS_evt1h", top + 2, param1,
                                    ensEvt1hRange, p1Range, centeredNumberStyle, headerStyle);
                            top = writeRowBlock1D(grid, "ENS_evt2_4h", top + 2, param1,
                                    ensEvt2_4hRange, p1Range, centeredNumberStyle, headerStyle);
                            top = writeRowBlock1D(grid, "ENS_evt5_12h", top + 2, param1,
                                    ensEvt5_12hRange, p1Range, centeredNumberStyle, headerStyle);
                            top = writeRowBlock1D(grid, "ENS_evt13_24h", top + 2, param1,
                                    ensEvt13_24hRange, p1Range, centeredNumberStyle, headerStyle);
                            top = writeRowBlock1D(grid, "ENS_evtGt24h", top + 2, param1,
                                    ensEvtGt24hRange, p1Range, centeredNumberStyle, headerStyle);
                        }

                        if (fullOutput) {
                            top = writeRowBlock1D(grid, "FailRoom", top + 2, param1,
                                    failRoomRange, p1Range, centeredNumberStyle, headerStyle);
                            top = writeRowBlock1D(grid, "FailBus", top + 2, param1,
                                    failBusRange, p1Range, centeredNumberStyle, headerStyle);
                        }
                        top = writeRowBlock1D(grid, "FailDg", top + 2, param1,
                                failDgRange, p1Range, centeredNumberStyle, headerStyle);
                        if (fullOutput) {
                            top = writeRowBlock1D(grid, "FailWt", top + 2, param1,
                                    failWtRange, p1Range, centeredNumberStyle, headerStyle);
                        }
                        top = writeRowBlock1D(grid, "FailBt", top + 2, param1,
                                failBtRange, p1Range, centeredNumberStyle, headerStyle);
                        top = writeRowBlock1D(grid, "BtRepl", top + 2, param1,
                                btReplRange, p1Range, centeredNumberStyle, headerStyle);
                        if (fullOutput) {
                            top = writeRowBlock1D(grid, "FailBrk", top + 2, param1,
                                    failBrkRange, p1Range, centeredNumberStyle, headerStyle);
                        }

                        autosizeFrom(grid, Math.max(2, param1.length + 1), 0);
                    } else {
                        autosizeFrom(grid, 1, 0);
                    }
                }
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

    private static int findHeaderColIdx(Row hdr, String headerText) {
        for (int i = 0; i < hdr.getLastCellNum(); i++) {
            Cell c = hdr.getCell(i);
            if (c == null) continue;
            if (headerText.equals(c.getStringCellValue())) {
                return i;
            }
        }
        throw new IllegalArgumentException("Header not found in RAW: " + headerText);
    }

    private static String rangeInSheet(String sheetName, int col0, int firstRow1Based, int lastRow1Based) {
        String col = colLetter(col0 + 1);
        return sheetName + "!$" + col + "$" + firstRow1Based + ":$" + col + "$" + lastRow1Based;
    }

    private static void writeNumber(Row row, int col, double value, CellStyle numStyle) {
        Cell cell = row.createCell(col);
        // Store full value; display is controlled by Excel format (0.00, 0, 0.000E+00, etc.)
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

        // values matrix is: rows [topRow .. topRow+param1.length-1], cols [1 .. param2.length]
        if (param1.length > 0 && param2.length > 0) {
            applyGreenYellowRedScale(
                    sh,
                    topRow, topRow + param1.length - 1,
                    1, param2.length
            );
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

        if (param1.length > 0 && param2.length > 0) {
            applyGreenYellowRedScale(
                    sh,
                    topRow, topRow + param1.length - 1,
                    1, param2.length
            );
        }

        return topRow + param1.length;
    }

    private static int writeRowBlock1D(Sheet sh,
                                       String title,
                                       int topRow,
                                       double[] param1,
                                       String valueRange,
                                       String critRangeP1,
                                       CellStyle numStyle,
                                       CellStyle headerStyle) {

        Row t = sh.createRow(topRow++);
        Cell titleCell = t.createCell(0);
        titleCell.setCellValue(title);
        titleCell.setCellStyle(headerStyle);

        // header row: param1 values across columns
        Row hdr = sh.createRow(topRow++);
        Cell corner = hdr.createCell(0, CellType.STRING);
        corner.setCellValue("");
        corner.setCellStyle(headerStyle);

        for (int j = 0; j < param1.length; j++) {
            Cell cell = hdr.createCell(1 + j, CellType.STRING);
            cell.setCellValue(fmt2(param1[j]));
            cell.setCellStyle(headerStyle);
        }

        // single values row
        Row r = sh.createRow(topRow);
        Cell rowName = r.createCell(0, CellType.STRING);
        rowName.setCellValue("");
        rowName.setCellStyle(headerStyle);

        int hdrExcelRow0 = (topRow - 1); // header row is (topRow-1) in 0-based
        int hdrExcel1 = hdrExcelRow0 + 1;

        for (int j = 0; j < param1.length; j++) {
            String colParam = colLetter(1 + j + 1); // 1-based col index
            // AVERAGEIF(criteria_range, criteria, average_range)
            String f = "AVERAGEIF(" + critRangeP1 + ",VALUE(" + colParam + "$" + hdrExcel1 + ")," + valueRange + ")";
            Cell cell = r.createCell(1 + j);
            cell.setCellFormula(f);
            cell.setCellStyle(numStyle);
        }

        // conditional formatting for the single row
        if (param1.length > 0) {
            applyGreenYellowRedScale(
                    sh,
                    topRow, topRow,     // single row
                    1, param1.length    // columns 1..param1.length
            );
        }

        return topRow + 1; // next row after the values row
    }

    /**
     * ОДНО правило: Color Scale (градиент).
     * MIN -> green, 50 percentile -> yellow, MAX -> red.
     */
    private static void applyGreenYellowRedScale(Sheet sheet,
                                                 int firstRow0, int lastRow0,
                                                 int firstCol0, int lastCol0) {

        SheetConditionalFormatting scf = sheet.getSheetConditionalFormatting();

        ConditionalFormattingRule rule = scf.createConditionalFormattingColorScaleRule();
        ColorScaleFormatting base = rule.getColorScaleFormatting();
        base.setNumControlPoints(3);

        base.getThresholds()[0].setRangeType(ConditionalFormattingThreshold.RangeType.MIN);

        base.getThresholds()[1].setRangeType(ConditionalFormattingThreshold.RangeType.PERCENTILE);
        base.getThresholds()[1].setValue(50d);

        base.getThresholds()[2].setRangeType(ConditionalFormattingThreshold.RangeType.MAX);

        // XSSF: задаём цвета корректно через setColors + XSSFColorScaleFormatting.createColor()
        XSSFColorScaleFormatting csf = (XSSFColorScaleFormatting) base;

        Color cMin = csf.createColor(); // green
        ((XSSFColor) cMin).setARGBHex("FF63BE7B");

        Color cMid = csf.createColor(); // yellow
        ((XSSFColor) cMid).setARGBHex("FFFFEB84");

        Color cMax = csf.createColor(); // red
        ((XSSFColor) cMax).setARGBHex("FFF8696B");

        csf.setColors(new Color[]{cMin, cMid, cMax});

        CellRangeAddress region = new CellRangeAddress(firstRow0, lastRow0, firstCol0, lastCol0);
        scf.addConditionalFormatting(new CellRangeAddress[]{region}, new ConditionalFormattingRule[]{rule});
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
