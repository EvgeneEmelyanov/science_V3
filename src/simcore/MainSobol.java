package simcore;

import simcore.config.SimulationConfig;
import simcore.config.SystemParameters;
import simcore.engine.MonteCarloRunner;
import simcore.engine.SimInput;
import simcore.io.ResultsCsvWriter;
import simcore.sobol.*;

import java.util.List;

public final class MainSobol {

    public static void main(String[] args) {

        String loadFilePath = "D:/01_Load.txt";
        String windFilePath = "D:/02_Wind.txt";

        String sobolResultsCsvPath = "D:/sobol_points_mc.csv";

        // execution
        int threads = Runtime.getRuntime().availableProcessors();
        int sobolN = 50;        // размер матриц A/B
        int mcIterations = 10;  // MC на каждую точку
        long mcBaseSeed = 1_000_000L;

        try {
            // 1) входные данные
            SimcoreRunner.LoadedScenario sc = SimcoreRunner.loadScenario(loadFilePath, windFilePath);

            // 2) базовые параметры/конфиг
            SystemParameters baseParams = SimcoreRunner.buildDefaultParams(sc.windMs());
            SimulationConfig cfg = SimcoreRunner.buildDefaultConfig(sc.windMs(), mcIterations, threads);

            // 3) общий контекст движка
            SimcoreRunner.EngineContext ctx = SimcoreRunner.buildEngineContext(
                    cfg, baseParams, sc.totalLoadKw(), threads
            );

            SimInput baseInput = ctx.input();
            MonteCarloRunner mcRunner = ctx.mcRunner();

            // 4) факторы (пример: батарейные)
            List<SobolFactor> factors = DefaultSobolFactors.batteryFactors();

            SobolConfig sobolCfg = SimcoreRunner.buildSobolConfig(
                    sobolN,
                    mcIterations,
                    mcBaseSeed,
                    threads,
                    factors
            );

            // 5) Sobol analyzer (инфраструктура)
            SobolAnalyzer sobol = new SobolAnalyzer(mcRunner);
            SobolResult result = sobol.run(baseInput, sobolCfg);

            System.out.println("Sobol indices (ENS):");
            for (int i = 0; i < sobolCfg.dim(); i++) {
                String name = sobolCfg.getFactors().get(i).getName();
                System.out.printf("%-20s  S=%.4f  ST=%.4f%n", name, result.S_ens[i], result.ST_ens[i]);
            }

            System.out.println("Sobol indices (Fuel):");
            for (int i = 0; i < sobolCfg.dim(); i++) {
                String name = sobolCfg.getFactors().get(i).getName();
                System.out.printf("%-20s  S=%.4f  ST=%.4f%n", name, result.S_fuel[i], result.ST_fuel[i]);
            }

            System.out.println("Sobol indices (Moto):");
            for (int i = 0; i < sobolCfg.dim(); i++) {
                String name = sobolCfg.getFactors().get(i).getName();
                System.out.printf("%-20s  S=%.4f  ST=%.4f%n", name, result.S_moto[i], result.ST_moto[i]);
            }


            // 6) Сохраняем “сырые” оценки MC по точкам (A, B, AB)
            // Сейчас writer пишет список estimates. Для удобства сольём в один список:
            List<simcore.engine.MonteCarloEstimate> all = new java.util.ArrayList<>();
            all.addAll(result.yA);
            all.addAll(result.yB);
            for (List<simcore.engine.MonteCarloEstimate> col : result.yAB) all.addAll(col);

            ResultsCsvWriter.writeSobolEstimates(
                    sobolResultsCsvPath,
                    cfg,
                    baseParams,
                    all
            );

            // 7) закрыть executor
            ctx.executor().shutdown();

            System.out.println("Sobol points MC computed. Saved to: " + sobolResultsCsvPath);

        } catch (Exception e) {
            System.err.println("Ошибка в MainSobol: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
