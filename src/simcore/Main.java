package simcore;

import simcore.config.SimulationConfig;
import simcore.config.SystemParameters;
import simcore.engine.MonteCarloEstimate;
import simcore.engine.SimulationEngine;
import simcore.engine.SimulationTraceExporter;
import simcore.io.ResultsCsvWriter;

import java.util.List;

public final class Main {

    public static void main(String[] args) {

        String loadFilePath = "D:/01_Load.txt";
        String windFilePath = "D:/02_Wind.txt";

        String resultsCsvPath = "D:/simulation_results.csv";
        String traceCsvPath = "D:/simulation_trace.csv";

        int threads = Runtime.getRuntime().availableProcessors();
        int mcIterations = 411;          // iterations==1 -> будет trace
        long mcBaseSeed = 1_000_000L;

        try {
            // 1) Данные
            SimcoreRunner.LoadedScenario sc = SimcoreRunner.loadScenario(loadFilePath, windFilePath);

            // 2) Параметры / конфиг
            SystemParameters params = SimcoreRunner.buildDefaultParams(sc.windMs());
            SimulationConfig cfg = SimcoreRunner.buildDefaultConfig(sc.windMs(), mcIterations, threads);

            // 3) Контекст (единый executor)
            SimcoreRunner.EngineContext ctx = SimcoreRunner.buildEngineContext(cfg, params, sc.totalLoadKw(), threads);

            // 4) MC
            boolean traceIfSingle = true;
            SimulationEngine engine = ctx.engine();
            MonteCarloEstimate est = engine.runMonteCarlo(ctx.input(), mcIterations, mcBaseSeed, traceIfSingle);

            // 5) Trace (только при iterations==1)
            if (mcIterations == 1 && est.singleRun != null && est.singleRun.trace != null && !est.singleRun.trace.isEmpty()) {
                SimulationTraceExporter.exportToCsv(traceCsvPath, est.singleRun.trace);
            }

            // 6) Консоль
            System.out.println("ENS mean = " + round2(est.ensStats.getMean())
                    + "  CI=[" + round2(est.ensStats.getCiLow()) + ", " + round2(est.ensStats.getCiHigh()) + "]"
                    + "  reqN=" + est.ensStats.getRequiredSampleSize());

            System.out.println("Fuel mean (ML) = " + round2(est.meanFuelLiters / 1_000_000.0));
            System.out.println("Moto mean (kh) = " + round2(est.meanMotoHours / 1_000.0));
            System.out.println("WRE mean % = " + round2(est.meanWre));

            System.out.println("WT/DG/BT % = "
                    + round2(est.meanWtPct) + " / "
                    + round2(est.meanDgPct) + " / "
                    + round2(est.meanBtPct));


            // 7) CSV результатов (одна строка)
            ResultsCsvWriter.writeSobolEstimates(resultsCsvPath, cfg, params, List.of(est));

            // 8) Закрыть executor
            ctx.executor().shutdown();

        } catch (Exception e) {
            System.err.println("Ошибка в Main: " + e.getMessage());
            e.printStackTrace();
        }
    }
    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

}
