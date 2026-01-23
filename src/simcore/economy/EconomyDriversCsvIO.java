package simcore.economy;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Simple CSV (actually TSV-ish with compact array encoding) IO for EconomyDrivers.
 * One line per case:
 *
 * caseId\tyears\tdgTotalKw\twtTotalKw\tbtTotalKwh\tdiscountRate\tserved[]\tfuel[]\tmoto[]\trepl[]
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
                + "\t" + d.dgTotalKw
                + "\t" + d.wtTotalKw
                + "\t" + d.btTotalKwh
                + "\t" + d.discountRatePerYear
                + "\t" + joinDoubles(d.servedKwhByYear)
                + "\t" + joinDoubles(d.fuelLitersByYear)
                + "\t" + joinDoubles(d.motoHoursByYear)
                + "\t" + joinLongs(d.btReplByYear);
    }

    private static Parsed parseLine(String line) {
        String[] parts = line.split("\\t");
        if (parts.length < 10) {
            throw new IllegalArgumentException("Bad drivers line, expected >=10 columns: " + line);
        }
        String caseId = parts[0];
        int years = Integer.parseInt(parts[1]);

        double dg = Double.parseDouble(parts[2]);
        double wt = Double.parseDouble(parts[3]);
        double bt = Double.parseDouble(parts[4]);
        double r = Double.parseDouble(parts[5]);

        double[] served = parseDoubles(parts[6], years);
        double[] fuel = parseDoubles(parts[7], years);
        double[] moto = parseDoubles(parts[8], years);
        long[] repl = parseLongs(parts[9], years);

        EconomyDrivers d = new EconomyDrivers(served, fuel, moto, repl, dg, wt, bt, r);
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
