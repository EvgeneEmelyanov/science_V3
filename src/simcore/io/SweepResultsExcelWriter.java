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
            Sheet raw = wb.createSheet("RAW");

            // ---- RAW: passport in A1
            int r = 0;
            Row row0 = raw.createRow(r++);
            row0.createCell(0).setCellValue(buildPassport(cfg, baseParams));

            // ---- RAW headers
            Row hdr = raw.createRow(r++);
            int c = 0;
            hdr.createCell(c++).setCellValue("k");
            if (mode == simcore.Main.RunMode.SWEEP_2) {
                hdr.createCell(c++).setCellValue("param1");
                hdr.createCell(c++).setCellValue("param2");
            } else if (mode == simcore.Main.RunMode.SWEEP_1) {
                hdr.createCell(c++).setCellValue("param1");
            }
            hdr.createCell(c++).setCellValue("ENS_mean");
            hdr.createCell(c++).setCellValue("ENS_ciLo");
            hdr.createCell(c++).setCellValue("ENS_ciHi");
            hdr.createCell(c++).setCellValue("ENS_reqN");
            hdr.createCell(c++).setCellValue("Fuel_ML");
            hdr.createCell(c++).setCellValue("Moto_kh");
            hdr.createCell(c++).setCellValue("WRE_%");
            hdr.createCell(c++).setCellValue("WT_%");
            hdr.createCell(c++).setCellValue("DG_%");
            hdr.createCell(c++).setCellValue("BT_%");

            final int m2 = (param2 != null) ? param2.length : 0;

            // ---- RAW rows
            for (int k = 0; k < estimates.size(); k++) {
                MonteCarloEstimate e = estimates.get(k);
                MonteCarloStats.Stats s = e.ensStats;

                double fuelML = e.meanFuelLiters / 1_000_000.0;
                double motoKh = e.meanMotoHours / 1_000.0;

                Row rr = raw.createRow(r++);
                int cc = 0;
                rr.createCell(cc++).setCellValue(k);

                if (mode == simcore.Main.RunMode.SWEEP_2) {
                    int i1 = k / m2;
                    int i2 = k % m2;
                    rr.createCell(cc++).setCellValue(param1[i1]);
                    rr.createCell(cc++).setCellValue(param2[i2]);
                } else if (mode == simcore.Main.RunMode.SWEEP_1) {
                    rr.createCell(cc++).setCellValue(param1[k]);
                }

                rr.createCell(cc++).setCellValue(s.getMean());
                rr.createCell(cc++).setCellValue(s.getCiLow());
                rr.createCell(cc++).setCellValue(s.getCiHigh());
                rr.createCell(cc++).setCellValue(s.getRequiredSampleSize());
                rr.createCell(cc++).setCellValue(fuelML);
                rr.createCell(cc++).setCellValue(motoKh);
                rr.createCell(cc++).setCellValue(e.meanWre);
                rr.createCell(cc++).setCellValue(e.meanWtPct);
                rr.createCell(cc++).setCellValue(e.meanDgPct);
                rr.createCell(cc++).setCellValue(e.meanBtPct);
            }

            autosize(raw, 14);

            // ---- Only for SWEEP_2: build grids with formulas
            if (mode == simcore.Main.RunMode.SWEEP_2) {
                Sheet grid = wb.createSheet("SWEEP_2");

                // blocks: ENS, Fuel, Moto
                int top = 0;
                top = writeGridBlock(grid, "ENS_mean", top, param1, param2, "RAW!$D:$D");
                top = writeGridBlock(grid, "Fuel_ML",  top + 2, param1, param2, "RAW!$H:$H");
                top = writeGridBlock(grid, "Moto_kh",  top + 2, param1, param2, "RAW!$I:$I");

                autosize(grid, Math.max(2, param2.length + 1));
            }

            try (FileOutputStream out = new FileOutputStream(path)) {
                wb.write(out);
            }
        }
    }

    private static int writeGridBlock(Sheet sh,
                                      String title,
                                      int topRow,
                                      double[] param1,
                                      double[] param2,
                                      String valueRange /* e.g. RAW!$D:$D */) {

        // Title
        Row t = sh.createRow(topRow++);
        t.createCell(0).setCellValue(title);

        // Header row: param2 across
        Row hdr = sh.createRow(topRow++);
//        hdr.createCell(0).setCellValue("param1 \\ param2");
        for (int j = 0; j < param2.length; j++) {
            hdr.createCell(1 + j).setCellValue(param2[j]);
        }

        // Data rows: param1 down + formulas
        for (int i = 0; i < param1.length; i++) {
            Row r = sh.createRow(topRow + i);
            r.createCell(0).setCellValue(param1[i]);

            // Excel cell refs: $A(row) and (col)(rowHeader)
            int rowExcel = (topRow + i) + 1;         // 1-based
            int hdrExcel = (topRow - 1) + 1;         // header row (param2) 1-based

            for (int j = 0; j < param2.length; j++) {
                String colParam2 = colLetter(1 + j + 1); // +1 because first col is A, grid starts at B
                // AVERAGEIFS(valueRange, RAW!$B:$B, $A{row}, RAW!$C:$C, {col}{hdrRow})
                String f = "AVERAGEIFS(" + valueRange
                        + ",RAW!$B:$B,$A" + rowExcel
                        + ",RAW!$C:$C," + colParam2 + "$" + hdrExcel
                        + ")";
                r.createCell(1 + j).setCellFormula(f);
            }
        }

        return topRow + param1.length;
    }

    private static String colLetter(int col1Based) {
        // 1->A, 2->B ...
        int col = col1Based;
        StringBuilder sb = new StringBuilder();
        while (col > 0) {
            int rem = (col - 1) % 26;
            sb.insert(0, (char) ('A' + rem));
            col = (col - 1) / 26;
        }
        return sb.toString();
    }

    private static void autosize(Sheet sh, int cols) {
        for (int i = 0; i < cols; i++) sh.autoSizeColumn(i);
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
