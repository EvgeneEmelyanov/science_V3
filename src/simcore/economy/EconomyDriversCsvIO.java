package simcore.economy;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Simple CSV (actually TSV-ish with compact array encoding) IO for EconomyDrivers.
 * One line per case:
 *
 * New (v2) format:
 * caseId\tyears\tdgUnitKw\tdgTotalKw\twtTotalKw\tbtTotalKwh\tdiscountRate\tserved[]\tfuel[]\tmoto[]\trepl[]\tens1[]\tens2[]\tens3[]
 *
 * Old (v1) format (backward-compatible):
 * caseId\tyears\tdgTotalKw\twtTotalKw\tbtTotalKwh\tdiscountRate\tserved[]\tfuel[]\tmoto[]\trepl[]\t[ens arrays optional]
 *
 * Arrays are encoded as comma-separated values.
 */
public final class EconomyDriversCsvIO {

    private EconomyDriversCsvIO() {}

    public static void append(String path, String caseId, EconomyDrivers d) throws IOException {
        try (Writer w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(path, true), StandardCharsets.UTF_8))) {
            w.write(encodeLine(caseId, d));
            w.write("\n");
        }
    }

    public static Map<String, EconomyDrivers> readAll(String path) throws IOException {
        Map<String, EconomyDrivers> m = new LinkedHashMap<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(path), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                Parsed p = parseLine(line);
                m.put(p.caseId, p.drivers);
            }
        }
        return m;
    }

    private static String encodeLine(String caseId, EconomyDrivers d) {
        return caseId
                + "\t" + d.years()
                + "\t" + d.dgUnitKw
                + "\t" + d.dgTotalKw
                + "\t" + d.wtTotalKw
                + "\t" + d.btTotalKwh
                + "\t" + d.discountRatePerYear
                + "\t" + joinDoubles(d.servedKwhByYear)
                + "\t" + joinDoubles(d.fuelLitersByYear)
                + "\t" + joinDoubles(d.motoHoursByYear)
                + "\t" + joinLongs(d.btReplByYear)
                + "\t" + joinDoubles(d.ensCat1KwhByYear)
                + "\t" + joinDoubles(d.ensCat2KwhByYear)
                + "\t" + joinDoubles(d.ensCat3KwhByYear);
    }

    private static Parsed parseLine(String line) {
        String[] parts = line.split("\\t");
        String caseId = parts[0];
        int years = Integer.parseInt(parts[1]);

        // Detect format.
        // v1: 10 columns base, optional ENS arrays -> 13.
        // v2: adds dgUnitKw column => 11 base, optional ENS arrays -> 14.
        final boolean v2;
        if (parts.length == 10 || parts.length == 13) {
            v2 = false;
        } else if (parts.length >= 11) {
            v2 = true;
        } else {
            throw new IllegalArgumentException("Bad drivers line, expected v1>=10 or v2>=11 columns: " + line);
        }

        final int idxDgUnit = v2 ? 2 : -1;
        final int idxDgTotal = v2 ? 3 : 2;
        final int idxWtTotal = v2 ? 4 : 3;
        final int idxBtTotal = v2 ? 5 : 4;
        final int idxRate = v2 ? 6 : 5;
        final int idxServed = v2 ? 7 : 6;
        final int idxFuel = v2 ? 8 : 7;
        final int idxMoto = v2 ? 9 : 8;
        final int idxRepl = v2 ? 10 : 9;
        final int idxEns1 = v2 ? 11 : 10;
        final int idxEns2 = v2 ? 12 : 11;
        final int idxEns3 = v2 ? 13 : 12;

        double dgTotal = Double.parseDouble(parts[idxDgTotal]);
        double dgUnit = v2 ? Double.parseDouble(parts[idxDgUnit]) : 0.0;
        double wt = Double.parseDouble(parts[idxWtTotal]);
        double bt = Double.parseDouble(parts[idxBtTotal]);
        double r = Double.parseDouble(parts[idxRate]);

        double[] served = parseDoubles(parts[idxServed], years);
        double[] fuel = parseDoubles(parts[idxFuel], years);
        double[] moto = parseDoubles(parts[idxMoto], years);
        long[] repl = parseLongs(parts[idxRepl], years);

        // Backward compatible: ENS arrays may be absent.
        double[] ens1 = (parts.length > idxEns3) ? parseDoubles(parts[idxEns1], years) : new double[years];
        double[] ens2 = (parts.length > idxEns3) ? parseDoubles(parts[idxEns2], years) : new double[years];
        double[] ens3 = (parts.length > idxEns3) ? parseDoubles(parts[idxEns3], years) : new double[years];

        // If reading v1 (no dgUnitKw), infer it if possible assuming identical DG units.
        if (!v2) {
            // If total DG power is known and the model used identical units, dgUnit is unknown here.
            // Keep 0.0; callers that depend on dgUnit should fill it from SystemParameters.
            dgUnit = 0.0;
        }

        EconomyDrivers d = new EconomyDrivers(served, fuel, moto, repl, ens1, ens2, ens3, dgTotal, dgUnit, wt, bt, r);
        return new Parsed(caseId, d);
    }

    private static String joinDoubles(double[] a) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(a[i]);
        }
        return sb.toString();
    }

    private static String joinLongs(long[] a) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(a[i]);
        }
        return sb.toString();
    }

    private static double[] parseDoubles(String s, int years) {
        String[] p = s.isEmpty() ? new String[0] : s.split(",");
        if (p.length != years) {
            throw new IllegalArgumentException("Expected " + years + " doubles, got " + p.length);
        }
        double[] a = new double[years];
        for (int i = 0; i < years; i++) a[i] = Double.parseDouble(p[i]);
        return a;
    }

    private static long[] parseLongs(String s, int years) {
        String[] p = s.isEmpty() ? new String[0] : s.split(",");
        if (p.length != years) {
            throw new IllegalArgumentException("Expected " + years + " longs, got " + p.length);
        }
        long[] a = new long[years];
        for (int i = 0; i < years; i++) a[i] = Long.parseLong(p[i]);
        return a;
    }

    private static final class Parsed {
        final String caseId;
        final EconomyDrivers drivers;
        Parsed(String caseId, EconomyDrivers drivers) {
            this.caseId = caseId;
            this.drivers = drivers;
        }
    }
}
