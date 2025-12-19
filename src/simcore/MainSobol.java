package simcore;

import simcore.config.BusSystemType;
import simcore.config.SimulationConfig;
import simcore.config.SystemParameters;
import simcore.engine.*;
import simcore.io.InputData;
import simcore.io.InputDataLoader;
import simcore.sobol.*;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainSobol {

    public static void main(String[] args) {

        String loadFilePath = "D:/01_Load.txt";
        String windFilePath = "D:/02_Wind.txt";

        try {
            // 1) load data
            InputData inputData = new InputDataLoader().load(loadFilePath, windFilePath);
            double[] totalLoadKw = inputData.getLoadKw();
            double[] windMs = inputData.getWindMs();

            // 2) base SystemParameters
            SystemParameters baseParams = new SystemParameters(
                    BusSystemType.SINGLE_SECTIONAL_BUS,
                    8, 330.0,
                    8, 340.0,
                    336.5,
                    1.0, 2.0, 0.8,
                    1.94, 46,
                    4.75, 50,
                    0.575, 44,
                    0.016, 12,
                    0.05, 10
            );

            // 3) base SimulationConfig (важно: iterations тут НЕ используются для соболя напрямую,
            //    потому что MC iterations берём из SobolConfig)
            SimulationConfig baseCfg = new SimulationConfig(
                    windMs,
                    1, // можно оставить 1; trace всё равно выключен в Соболе
                    Runtime.getRuntime().availableProcessors(),
                    true,   // failures
                    true,   // degradation
                    false,
                    false,
                    true,
                    true
            );

            // 4) choose parameters to vary (ids)
            List<TunableParamId> ids = List.of(
                    TunableParamId.WT_FAILURE_RATE,
                    TunableParamId.DG_FAILURE_RATE,
                    TunableParamId.BT_FAILURE_RATE
            );

            // 5) SobolConfig from ids (диапазоны берутся из TunableParameterPool)
            SobolConfig sobolCfg = SobolConfig.fromIds(
                    50,            // Sobol N
                    5,             // MC iterations per point
                    1_000_000L,      // MC base seed (важно: общий для всех точек Соболя)
                    Runtime.getRuntime().availableProcessors(),
                    ids
            );

            // 6) shared executor for MC across the whole Sobol experiment
            ExecutorService ex = Executors.newFixedThreadPool(sobolCfg.getThreads());

            // 7) build base SimInput
            SimInput baseInput = new SimInput(baseCfg, baseParams, totalLoadKw);

            // 8) MC runner + Sobol analyzer
            SingleRunSimulator sim = new SingleRunSimulator();
            MonteCarloRunner mc = new MonteCarloRunner(ex, sim, false, 1.96, 0.10);

            SobolAnalyzer analyzer = new SobolAnalyzer(mc);

            // 9) run
            SobolResult res = analyzer.run(baseInput, sobolCfg);

            // 10) print (пример)
            System.out.println("Sobol done. dim=" + sobolCfg.dim());
            System.out.println("S(ENS):  " + Arrays.toString(res.getS_ens()));
            System.out.println("ST(ENS): " + Arrays.toString(res.getSt_ens()));
            System.out.println("S(Fuel): " + Arrays.toString(res.getS_fuel()));
            System.out.println("ST(Fuel):" + Arrays.toString(res.getSt_fuel()));
            System.out.println("S(Moto): " + Arrays.toString(res.getS_moto()));
            System.out.println("ST(Moto):" + Arrays.toString(res.getSt_moto()));


            ex.shutdown();

        } catch (Exception e) {
            System.err.println("Ошибка в MainSobol: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
