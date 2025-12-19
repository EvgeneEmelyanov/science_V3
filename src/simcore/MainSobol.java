package simcore;

import simcore.config.SimulationConfig;
import simcore.config.SystemParameters;
import simcore.engine.MonteCarloRunner;
import simcore.engine.SingleRunSimulator;
import simcore.engine.SimInput;
import simcore.sobol.*;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainSobol {

    public static void main(String[] args) {

        String loadFilePath = "D:/01_Load.txt";
        String windFilePath = "D:/02_Wind.txt";
        String sobolCsvPath = "D:/sobol_results.csv"; // если у вас есть writer — используйте

        // Sobol settings
        int sobolN = 50; // размер A/B
        int mcIterations = 5;
        long mcBaseSeed = 1_000_000L;
        int threads = Runtime.getRuntime().availableProcessors();

        ExecutorService ex = null;

        try {
            // 1) входные данные
            ScenarioFactory.LoadedInput li = ScenarioFactory.load(loadFilePath, windFilePath);

            // 2) базовые параметры — единый источник
            SystemParameters baseParams = ScenarioFactory.defaultParams();

            // 3) параметры Соболя (через пул диапазонов)
            List<TunableParamId> ids = List.of(
                    TunableParamId.WT_FAILURE_RATE,
                    TunableParamId.DG_FAILURE_RATE,
                    TunableParamId.BT_FAILURE_RATE
            );

            SobolConfig sobolCfg = SobolConfig.fromIds(
                    sobolN,
                    mcIterations,
                    mcBaseSeed,
                    threads,
                    ids
            );

            // 4) SimulationConfig делаем согласованным с sobolCfg (чтобы не путаться)
            SimulationConfig cfg = ScenarioFactory.defaultConfig(li.windMs(), sobolCfg.getMcIterations(), sobolCfg.getThreads());
            SimInput baseInput = new SimInput(cfg, baseParams, li.totalLoadKw());

            // 5) shared executor for MC across the whole Sobol experiment
            ex = Executors.newFixedThreadPool(sobolCfg.getThreads());

            // 6) MC runner + Sobol analyzer
            SingleRunSimulator sim = new SingleRunSimulator();
            MonteCarloRunner mc = new MonteCarloRunner(ex, sim, false, 1.96, 0.10);
            SobolAnalyzer analyzer = new SobolAnalyzer(mc);

            // 7) run
            SobolResult res = analyzer.run(baseInput, sobolCfg);

            // 8) print
            System.out.println("Sobol done. dim=" + sobolCfg.dim());
            System.out.println("S(ENS):  " + Arrays.toString(res.getS_ens()));
            System.out.println("ST(ENS): " + Arrays.toString(res.getSt_ens()));
            System.out.println("S(Fuel): " + Arrays.toString(res.getS_fuel()));
            System.out.println("ST(Fuel):" + Arrays.toString(res.getSt_fuel()));
            System.out.println("S(Moto): " + Arrays.toString(res.getS_moto()));
            System.out.println("ST(Moto):" + Arrays.toString(res.getSt_moto()));

            // 9) если у вас есть writer — тут:
            // SobolResultsCsvWriter.write(sobolCsvPath, sobolCfg, ids, res);

        } catch (Exception e) {
            System.err.println("Ошибка в MainSobol: " + e.getMessage());
            e.printStackTrace();
        } finally {
            if (ex != null) ex.shutdown();
        }
    }
}
