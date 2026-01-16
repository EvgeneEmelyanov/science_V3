package simcore.regression;

import simcore.config.BusSystemType;
import simcore.config.SimulationConfig;
import simcore.config.SystemParameters;
import simcore.config.SystemParametersBuilder;
import simcore.engine.SimInput;
import simcore.engine.SingleRunSimulator;
import simcore.engine.SimulationMetrics;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Минимальный регрессионный прогон без внешних зависимостей (POI/commons-math).
 *
 * Usage:
 *  -generate <file>: сгенерировать базовый эталон
 *  -verify   <file>: проверить текущее поведение относительно эталона
 */
public final class RegressionRunner {

    private RegressionRunner() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: RegressionRunner -generate <baseline.json> | -verify <baseline.json>");
            System.exit(2);
        }

        String mode = args[0];
        Path baselinePath = Path.of(args[1]);

        Map<String, MetricsSnapshot> current = runAll();

        if ("-generate".equals(mode)) {
            writeBaseline(baselinePath, current);
            System.out.println("Baseline written to: " + baselinePath.toAbsolutePath());
            return;
        }

        if ("-verify".equals(mode)) {
            Map<String, MetricsSnapshot> baseline = readBaseline(baselinePath);
            verify(baseline, current);
            System.out.println("OK: all regression checks passed");
            return;
        }

        throw new IllegalArgumentException("Unknown mode: " + mode);
    }

    private static Map<String, MetricsSnapshot> runAll() {
        final int hours = 24 * 7; // 1 week for fast regression
        final double[] wind = new double[hours];
        final double[] load = new double[hours];

        // Детерминированные ряды (не требуют файлов)
        for (int t = 0; t < hours; t++) {
            double dayPhase = 2.0 * Math.PI * (t % 24) / 24.0;
            load[t] = 600.0 + 200.0 * Math.sin(dayPhase) + 50.0 * Math.sin(2.0 * dayPhase);
            // ветер меняется медленнее
            double weekPhase = 2.0 * Math.PI * t / hours;
            wind[t] = 7.0 + 3.0 * Math.sin(weekPhase) + 1.0 * Math.sin(3.0 * weekPhase);
            if (load[t] < 0) load[t] = 0;
            if (wind[t] < 0) wind[t] = 0;
        }

        final long seed = 123456789L;

        Map<String, MetricsSnapshot> out = new LinkedHashMap<>();
        for (BusSystemType type : BusSystemType.values()) {
            for (double btCap : new double[]{0.0, 300.0}) {
                SystemParameters sp = defaultParams(type, btCap);
                SimulationConfig cfg = defaultConfig(wind);
                SimInput input = new SimInput(cfg, sp, load);

                SingleRunSimulator sim = new SingleRunSimulator();
                SimulationMetrics m = sim.simulate(input, seed, false);

                String key = type.name() + "|BT=" + (int) btCap;
                out.put(key, MetricsSnapshot.from(m));
            }
        }
        return out;
    }

    // ---- defaults (скопированы из ScenarioFactory, но без io-зависимостей) ----

    private static SystemParameters defaultParams(BusSystemType busSystemType, double batteryCapacityKwhPerBus) {
        SystemParameters base = new SystemParameters(
                busSystemType,
                0.1, 0.40,
                4, 673,
                6, 340,
                201.9,
                1.0, 2.0, 0.2,
                1.94, 46,
                4.75, 50,
                0.575, 44,
                0.02, 12,
                0.1, 10,
                0.0, 24,
                0.2, 0.2
        );
        return SystemParametersBuilder.from(base)
                .setBatteryCapacityKwhPerBus(batteryCapacityKwhPerBus)
                .build();
    }

    private static SimulationConfig defaultConfig(double[] windMs) {
        return new SimulationConfig(
                windMs,
                1,
                1,
                true,
                true,
                false,
                false,
                true,
                true
        );
    }

    // ---- baseline IO ----

    private static void writeBaseline(Path path, Map<String, MetricsSnapshot> data) throws IOException {
        Files.createDirectories(path.toAbsolutePath().getParent());
        try (Writer w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            w.write("{\n");
            int i = 0;
            for (var e : data.entrySet()) {
                if (i++ > 0) w.write(",\n");
                w.write("  \"" + e.getKey() + "\": " + e.getValue().toJson());
            }
            w.write("\n}\n");
        }
    }

    private static Map<String, MetricsSnapshot> readBaseline(Path path) throws IOException {
        String json = Files.readString(path, StandardCharsets.UTF_8);
        return MetricsSnapshot.parseTopLevelMap(json);
    }

    private static void verify(Map<String, MetricsSnapshot> baseline,
                               Map<String, MetricsSnapshot> current) {
        double tol = 1e-9;

        if (!baseline.keySet().equals(current.keySet())) {
            throw new AssertionError("Scenario keys differ. Baseline keys=" + baseline.keySet() + ", current keys=" + current.keySet());
        }

        for (String key : baseline.keySet()) {
            MetricsSnapshot b = baseline.get(key);
            MetricsSnapshot c = current.get(key);
            String diff = b.diff(c, tol);
            if (diff != null) {
                throw new AssertionError("Mismatch for " + key + ": " + diff);
            }
        }
    }

    // ---- snapshot ----

    private record MetricsSnapshot(
            double loadKwh,
            double ensKwh,
            double ensCat1Kwh,
            double ensCat2Kwh,
            double wreKwh,
            double wtToLoadKwh,
            double dgToLoadKwh,
            double btToLoadKwh,
            double fuelLiters,
            long totalMotoHours,
            long failBus,
            long failDg,
            long failWt,
            long failBt,
            long failBrk,
            long failRoom,
            long repBt
    ) {
        static MetricsSnapshot from(SimulationMetrics m) {
            return new MetricsSnapshot(
                    m.loadKwh,
                    m.ensKwh,
                    m.ensCat1Kwh,
                    m.ensCat2Kwh,
                    m.wreKwh,
                    m.wtToLoadKwh,
                    m.dgToLoadKwh,
                    m.btToLoadKwh,
                    m.fuelLiters,
                    m.totalMotoHours,
                    m.failBus,
                    m.failDg,
                    m.failWt,
                    m.failBt,
                    m.failBrk,
                    m.failRoom,
                    m.repBt
            );
        }

        String toJson() {
            // компактно, но читаемо
            return String.format(Locale.US,
                    "{\"loadKwh\":%.12f,\"ensKwh\":%.12f,\"ensCat1Kwh\":%.12f,\"ensCat2Kwh\":%.12f," +
                            "\"wreKwh\":%.12f,\"wtToLoadKwh\":%.12f,\"dgToLoadKwh\":%.12f,\"btToLoadKwh\":%.12f," +
                            "\"fuelLiters\":%.12f,\"totalMotoHours\":%d," +
                            "\"failBus\":%d,\"failDg\":%d,\"failWt\":%d,\"failBt\":%d,\"failBrk\":%d,\"failRoom\":%d,\"repBt\":%d}",
                    loadKwh, ensKwh, ensCat1Kwh, ensCat2Kwh,
                    wreKwh, wtToLoadKwh, dgToLoadKwh, btToLoadKwh,
                    fuelLiters, totalMotoHours,
                    failBus, failDg, failWt, failBt, failBrk, failRoom, repBt
            );
        }

        String diff(MetricsSnapshot other, double tol) {
            if (other == null) return "other is null";

            String d;
            if ((d = diffDouble("loadKwh", loadKwh, other.loadKwh, tol)) != null) return d;
            if ((d = diffDouble("ensKwh", ensKwh, other.ensKwh, tol)) != null) return d;
            if ((d = diffDouble("ensCat1Kwh", ensCat1Kwh, other.ensCat1Kwh, tol)) != null) return d;
            if ((d = diffDouble("ensCat2Kwh", ensCat2Kwh, other.ensCat2Kwh, tol)) != null) return d;
            if ((d = diffDouble("wreKwh", wreKwh, other.wreKwh, tol)) != null) return d;
            if ((d = diffDouble("wtToLoadKwh", wtToLoadKwh, other.wtToLoadKwh, tol)) != null) return d;
            if ((d = diffDouble("dgToLoadKwh", dgToLoadKwh, other.dgToLoadKwh, tol)) != null) return d;
            if ((d = diffDouble("btToLoadKwh", btToLoadKwh, other.btToLoadKwh, tol)) != null) return d;
            if ((d = diffDouble("fuelLiters", fuelLiters, other.fuelLiters, tol)) != null) return d;

            if (totalMotoHours != other.totalMotoHours) return "totalMotoHours: " + totalMotoHours + " != " + other.totalMotoHours;
            if (failBus != other.failBus) return "failBus: " + failBus + " != " + other.failBus;
            if (failDg != other.failDg) return "failDg: " + failDg + " != " + other.failDg;
            if (failWt != other.failWt) return "failWt: " + failWt + " != " + other.failWt;
            if (failBt != other.failBt) return "failBt: " + failBt + " != " + other.failBt;
            if (failBrk != other.failBrk) return "failBrk: " + failBrk + " != " + other.failBrk;
            if (failRoom != other.failRoom) return "failRoom: " + failRoom + " != " + other.failRoom;
            if (repBt != other.repBt) return "repBt: " + repBt + " != " + other.repBt;

            return null;
        }

        private static String diffDouble(String name, double a, double b, double tol) {
            double da = Math.abs(a - b);
            if (da > tol) return name + ": " + a + " != " + b + " (|diff|=" + da + ")";
            return null;
        }

        // --- ultra-minimal JSON parsing (достаточно для собственного файла) ---

        static Map<String, MetricsSnapshot> parseTopLevelMap(String json) {
            String s = json.trim();
            if (!s.startsWith("{") || !s.endsWith("}")) {
                throw new IllegalArgumentException("Not a JSON object");
            }
            s = s.substring(1, s.length() - 1).trim();
            Map<String, MetricsSnapshot> out = new LinkedHashMap<>();
            if (s.isEmpty()) return out;

            // split by top-level commas
            List<String> entries = splitTopLevel(s);
            for (String e : entries) {
                int colon = e.indexOf(':');
                if (colon < 0) throw new IllegalArgumentException("Bad entry: " + e);
                String key = unquote(e.substring(0, colon).trim());
                String obj = e.substring(colon + 1).trim();
                out.put(key, parseSnapshot(obj));
            }
            return out;
        }

        private static MetricsSnapshot parseSnapshot(String obj) {
            String s = obj.trim();
            if (!s.startsWith("{") || !s.endsWith("}")) {
                throw new IllegalArgumentException("Bad snapshot obj: " + obj);
            }
            s = s.substring(1, s.length() - 1).trim();
            Map<String, String> kv = new HashMap<>();
            for (String part : splitTopLevel(s)) {
                int colon = part.indexOf(':');
                String k = unquote(part.substring(0, colon).trim());
                String v = part.substring(colon + 1).trim();
                kv.put(k, v);
            }

            return new MetricsSnapshot(
                    Double.parseDouble(kv.get("loadKwh")),
                    Double.parseDouble(kv.get("ensKwh")),
                    Double.parseDouble(kv.get("ensCat1Kwh")),
                    Double.parseDouble(kv.get("ensCat2Kwh")),
                    Double.parseDouble(kv.get("wreKwh")),
                    Double.parseDouble(kv.get("wtToLoadKwh")),
                    Double.parseDouble(kv.get("dgToLoadKwh")),
                    Double.parseDouble(kv.get("btToLoadKwh")),
                    Double.parseDouble(kv.get("fuelLiters")),
                    Long.parseLong(kv.get("totalMotoHours")),
                    Long.parseLong(kv.get("failBus")),
                    Long.parseLong(kv.get("failDg")),
                    Long.parseLong(kv.get("failWt")),
                    Long.parseLong(kv.get("failBt")),
                    Long.parseLong(kv.get("failBrk")),
                    Long.parseLong(kv.get("failRoom")),
                    Long.parseLong(kv.get("repBt"))
            );
        }

        private static List<String> splitTopLevel(String s) {
            List<String> out = new ArrayList<>();
            int depth = 0;
            int start = 0;
            boolean inStr = false;
            for (int i = 0; i < s.length(); i++) {
                char ch = s.charAt(i);
                if (ch == '"' && (i == 0 || s.charAt(i - 1) != '\\')) inStr = !inStr;
                if (inStr) continue;
                if (ch == '{') depth++;
                if (ch == '}') depth--;
                if (ch == ',' && depth == 0) {
                    out.add(s.substring(start, i).trim());
                    start = i + 1;
                }
            }
            out.add(s.substring(start).trim());
            return out;
        }

        private static String unquote(String s) {
            String t = s.trim();
            if (t.startsWith("\"") && t.endsWith("\"")) {
                return t.substring(1, t.length() - 1);
            }
            throw new IllegalArgumentException("Expected quoted string: " + s);
        }
    }
}
