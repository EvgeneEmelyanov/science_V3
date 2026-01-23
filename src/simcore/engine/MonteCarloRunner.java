// File: simcore/engine/MonteCarloRunner.java
package simcore.engine;

import simcore.config.SimulationConstants;
import simcore.config.SystemParameters;
import simcore.sobol.ParameterSet;
import simcore.sobol.SobolConfig;
import simcore.economy.EconomyDrivers;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

public final class MonteCarloRunner {

    private static final long MC_SEED_STRIDE = 10_000L;
    private static final long SOBOL_ROW_SEED_STRIDE = 10_000_000_000L; // 1e10

    private final ExecutorService executor;
    private final SingleRunSimulator simulator;

    private final boolean removeOutliers;
    private final double tScore;
    private final double relativeError;

    public MonteCarloRunner(ExecutorService executor,
                            SingleRunSimulator simulator,
                            boolean removeOutliers,
                            double tScore,
                            double relativeError) {
        this.executor = executor;
        this.simulator = simulator;
        this.removeOutliers = removeOutliers;
        this.tScore = tScore;
        this.relativeError = relativeError;
    }

    public MonteCarloEstimate evaluateForTheta(SimInput baseInput,
                                               ParameterSet theta,
                                               SobolConfig sobolCfg,
                                               int mcIterations,
                                               long mcBaseSeed,
                                               boolean traceIfSingle)
            throws InterruptedException, ExecutionException {

        return evaluateForTheta(
                baseInput, theta, sobolCfg,
                mcIterations, mcBaseSeed,
                0L, traceIfSingle
        );
    }

    public MonteCarloEstimate evaluateForTheta(SimInput baseInput,
                                               ParameterSet theta,
                                               SobolConfig sobolCfg,
                                               int mcIterations,
                                               long mcBaseSeed,
                                               long sobolRowIdx,
                                               boolean traceIfSingle)
            throws InterruptedException, ExecutionException {

        // apply theta
        SimInput input = baseInput;
        if (theta != null && sobolCfg != null) {
            SystemParameters baseParams = baseInput.getSystemParameters();
            SystemParameters tuned = theta.applyTo(baseParams, sobolCfg);
            input = baseInput.withSystemParameters(tuned);
        }

        if (mcIterations <= 0) {
            throw new IllegalArgumentException("mcIterations must be > 0");
        }

        if (mcIterations == 1) {
            long seed = seedFor(mcBaseSeed, sobolRowIdx, 0);
            SimulationMetrics m = simulator.simulate(input, seed, traceIfSingle);

            double[] ensArr = new double[]{m.ensKwh};
            MonteCarloStats.Stats ensStats = MonteCarloStats.compute(ensArr, removeOutliers, tScore, relativeError);

            double wtPct = pct(m.wtToLoadKwh, m.loadKwh);
            double dgPct = pct(m.dgToLoadKwh, m.loadKwh);
            double btPct = pct(m.btToLoadKwh, m.loadKwh);
            double wrePct = pct(m.wreKwh, m.loadKwh);

            SingleRunMetrics singleRun = (m.trace != null) ? new SingleRunMetrics(m.trace) : null;

            return new MonteCarloEstimate(
                    theta,
                    m.economyDrivers,
                    ensStats,
                    m.ensCat1Kwh,
                    m.ensCat2Kwh,
                    m.fuelLiters,
                    (double) m.totalMotoHours,
                    wrePct,
                    m.lcoeRubPerKwh,
                    wtPct,
                    dgPct,
                    btPct,
                    singleRun,
                    (double) m.failRoom,
                    (double) m.failBus,
                    (double) m.failDg,
                    (double) m.failWt,
                    (double) m.failBt,
                    (double) m.failBrk,
                    (double) m.repBt,
                    (double) m.ensEventsTotal,
                    (double) m.ensEventsStartOnly,
                    (double) m.ensEvents1H,
                    (double) m.ensEvents2H,
                    (double) m.ensEvents3H,
                    (double) m.ensEvents4H,
                    (double) m.ensEvents5to8H,
                    (double) m.ensEvents9to12H,
                    (double) m.ensEvents13to24H,
                    (double) m.ensEventsGt24H,
                    (double) m.ensEventsMaxHours
            );
        }

        final SimInput inputFinal = input;

        int parallelism = estimateParallelism(executor);
        int chunks = Math.min(mcIterations, Math.max(1, parallelism * 2));
        int chunkSize = (int) Math.ceil(mcIterations / (double) chunks);

        List<Future<ChunkAgg>> futures = new ArrayList<>(chunks);

        for (int c = 0; c < chunks; c++) {
            int from = c * chunkSize;
            int to = Math.min(mcIterations, from + chunkSize);
            if (from >= to) break;

            futures.add(executor.submit(() -> runChunk(inputFinal, mcBaseSeed, sobolRowIdx, from, to)));
        }

        double[] ens = new double[mcIterations];
        double ens1Sum = 0.0;
        double ens2Sum = 0.0;
        double fuelSum = 0.0;
        double motoSum = 0.0;
        double lcoeSum = 0.0;

        double wrePctSum = 0.0;
        double wtPctSum = 0.0;
        double dgPctSum = 0.0;
        double btPctSum = 0.0;

        // Optional: accumulate discounted LCOE drivers (per-year arrays) for fast post-processing.
        double[] servedSumByYear = null;
        double[] fuelSumByYear = null;
        double[] motoSumByYear = null;
        double[] btReplSumByYear = null; // keep as double to average; later rounded to long
        EconomyDrivers firstDrivers = null;

        double failRoomSum = 0.0;
        double failBusSum = 0.0;
        double failDgSum = 0.0;
        double failWtSum = 0.0;
        double failBtSum = 0.0;
        double failBrkSum = 0.0;
        double repBtSum = 0.0;

        double ensEvtTotalSum = 0.0;
        double ensEvtStartOnlySum = 0.0;
        double ensEvt1HSum = 0.0;
        double ensEvt2HSum = 0.0;
        double ensEvt3HSum = 0.0;
        double ensEvt4HSum = 0.0;
        double ensEvt5to8HSum = 0.0;
        double ensEvt9to12HSum = 0.0;
        double ensEvt13to24HSum = 0.0;
        double ensEvtGt24HSum = 0.0;
        double ensEvtMaxHoursSum = 0.0;

        for (Future<ChunkAgg> f : futures) {
            ChunkAgg a = f.get();
            fuelSum += a.fuelSum;
            motoSum += a.motoSum;
            lcoeSum += a.lcoeSum;
            ens1Sum += a.ens1Sum;
            ens2Sum += a.ens2Sum;
            wrePctSum += a.wrePctSum;
            wtPctSum += a.wtPctSum;
            dgPctSum += a.dgPctSum;
            btPctSum += a.btPctSum;
            failRoomSum += a.failRoomSum;
            failBusSum  += a.failBusSum;
            failDgSum   += a.failDgSum;
            failWtSum   += a.failWtSum;
            failBtSum   += a.failBtSum;
            failBrkSum  += a.failBrkSum;
            repBtSum += a.repBtSum;

            // economy drivers
            if (a.firstDrivers != null) {
                if (firstDrivers == null) {
                    firstDrivers = a.firstDrivers;
                    int years = firstDrivers.years();
                    servedSumByYear = new double[years];
                    fuelSumByYear = new double[years];
                    motoSumByYear = new double[years];
                    btReplSumByYear = new double[years];
                }
                int years = firstDrivers.years();
                for (int yy = 0; yy < years; yy++) {
                    servedSumByYear[yy] += a.servedSumByYear[yy];
                    fuelSumByYear[yy] += a.fuelSumByYear[yy];
                    motoSumByYear[yy] += a.motoSumByYear[yy];
                    btReplSumByYear[yy] += a.btReplSumByYear[yy];
                }
            }


            ensEvtTotalSum += a.ensEvtTotalSum;
            ensEvtStartOnlySum += a.ensEvtStartOnlySum;
            ensEvt1HSum += a.ensEvt1HSum;
            ensEvt2HSum += a.ensEvt2HSum;
            ensEvt3HSum += a.ensEvt3HSum;
            ensEvt4HSum += a.ensEvt4HSum;
            ensEvt5to8HSum += a.ensEvt5to8HSum;
            ensEvt9to12HSum += a.ensEvt9to12HSum;
            ensEvt13to24HSum += a.ensEvt13to24HSum;
            ensEvtGt24HSum += a.ensEvtGt24HSum;
            ensEvtMaxHoursSum += a.ensEvtMaxHoursSum;


            System.arraycopy(a.ens, 0, ens, a.ensOffset, a.ens.length);
        }

        MonteCarloStats.Stats ensStats = MonteCarloStats.compute(ens, removeOutliers, tScore, relativeError);

        double inv = 1.0 / mcIterations;

        EconomyDrivers meanEconomyDrivers = null;
        if (firstDrivers != null && servedSumByYear != null) {
            int years = firstDrivers.years();
            double[] servedMean = new double[years];
            double[] fuelMean = new double[years];
            double[] motoMean = new double[years];
            long[] replMean = new long[years];
            for (int yy = 0; yy < years; yy++) {
                servedMean[yy] = servedSumByYear[yy] * inv;
                fuelMean[yy] = fuelSumByYear[yy] * inv;
                motoMean[yy] = motoSumByYear[yy] * inv;
                // replacements: average then round to nearest long for post-processing
                replMean[yy] = Math.round(btReplSumByYear[yy] * inv);
            }
            meanEconomyDrivers = new EconomyDrivers(
                    servedMean, fuelMean, motoMean, replMean,
                    firstDrivers.dgTotalKw, firstDrivers.wtTotalKw, firstDrivers.btTotalKwh,
                    firstDrivers.discountRatePerYear
            );
        }


        return new MonteCarloEstimate(
                theta,
                meanEconomyDrivers,
                ensStats,
                ens1Sum * inv,
                ens2Sum * inv,
                fuelSum * inv,
                motoSum * inv,
                wrePctSum * inv,
                lcoeSum * inv,
                wtPctSum * inv,
                dgPctSum * inv,
                btPctSum * inv,
                null,
                failRoomSum * inv,
                failBusSum * inv,
                failDgSum * inv,
                failWtSum * inv,
                failBtSum * inv,
                failBrkSum * inv,
                repBtSum * inv,
                ensEvtTotalSum * inv,
                ensEvtStartOnlySum * inv,
                ensEvt1HSum * inv,
                ensEvt2HSum * inv,
                ensEvt3HSum * inv,
                ensEvt4HSum * inv,
                ensEvt5to8HSum * inv,
                ensEvt9to12HSum * inv,
                ensEvt13to24HSum * inv,
                ensEvtGt24HSum * inv,
                ensEvtMaxHoursSum * inv
        );

    }

    private ChunkAgg runChunk(SimInput input,
                              long mcBaseSeed,
                              long sobolRowIdx,
                              int fromInclusive,
                              int toExclusive) {

        int n = toExclusive - fromInclusive;
        double[] ens = new double[n];

        double fuelSum = 0.0;
        double motoSum = 0.0;
        double lcoeSum = 0.0;   // <<< ВОТ ЭТОГО У ВАС НЕ ХВАТАЛО


        EconomyDrivers firstDriversLocal = null;
        double[] servedSumByYearLocal = null;
        double[] fuelSumByYearLocal = null;
        double[] motoSumByYearLocal = null;
        double[] btReplSumByYearLocal = null; // double for averaging

        double ens1Sum = 0.0;
        double ens2Sum = 0.0;

        double wrePctSum = 0.0;
        double wtPctSum = 0.0;
        double dgPctSum = 0.0;
        double btPctSum = 0.0;

        // Optional: accumulate discounted LCOE drivers (per-year arrays) for fast post-processing.
        double[] servedSumByYear = null;
        double[] fuelSumByYear = null;
        double[] motoSumByYear = null;
        double[] btReplSumByYear = null; // keep as double to average; later rounded to long
        EconomyDrivers firstDrivers = null;

        double failRoomSum = 0.0;
        double failBusSum = 0.0;
        double failDgSum = 0.0;
        double failWtSum = 0.0;
        double failBtSum = 0.0;
        double failBrkSum = 0.0;
        double repBtSum = 0.0;

        double ensEvtTotalSum = 0.0;
        double ensEvtStartOnlySum = 0.0;
        double ensEvt1HSum = 0.0;
        double ensEvt2HSum = 0.0;
        double ensEvt3HSum = 0.0;
        double ensEvt4HSum = 0.0;
        double ensEvt5to8HSum = 0.0;
        double ensEvt9to12HSum = 0.0;
        double ensEvt13to24HSum = 0.0;
        double ensEvtGt24HSum = 0.0;
        double ensEvtMaxHoursSum = 0.0;

        for (int mcIdx = fromInclusive; mcIdx < toExclusive; mcIdx++) {
            long seed = seedFor(mcBaseSeed, sobolRowIdx, mcIdx);
            SimulationMetrics m = simulator.simulate(input, seed, false);

            // accumulate economy drivers (if present)
            if (m.economyDrivers != null) {
                if (firstDriversLocal == null) {
                    firstDriversLocal = m.economyDrivers;
                    int years = firstDriversLocal.years();
                    servedSumByYearLocal = new double[years];
                    fuelSumByYearLocal = new double[years];
                    motoSumByYearLocal = new double[years];
                    btReplSumByYearLocal = new double[years];
                }
                // basic sanity: assume same years for this theta
                int years = firstDriversLocal.years();
                for (int yy = 0; yy < years; yy++) {
                    servedSumByYearLocal[yy] += m.economyDrivers.servedKwhByYear[yy];
                    fuelSumByYearLocal[yy] += m.economyDrivers.fuelLitersByYear[yy];
                    motoSumByYearLocal[yy] += m.economyDrivers.motoHoursByYear[yy];
                    btReplSumByYearLocal[yy] += m.economyDrivers.btReplByYear[yy];
                }
            }

            int k = mcIdx - fromInclusive;
            ens[k] = m.ensKwh;

            ens1Sum += m.ensCat1Kwh;
            ens2Sum += m.ensCat2Kwh;

            fuelSum += m.fuelLiters;
            motoSum += (double) m.totalMotoHours;
            lcoeSum += m.lcoeRubPerKwh;

            wrePctSum += pct(m.wreKwh, m.loadKwh);
            wtPctSum += pct(m.wtToLoadKwh, m.loadKwh);
            dgPctSum += pct(m.dgToLoadKwh, m.loadKwh);
            btPctSum += pct(m.btToLoadKwh, m.loadKwh);

            failRoomSum += m.failRoom;
            failBusSum  += m.failBus;
            failDgSum   += m.failDg;
            failWtSum   += m.failWt;
            failBtSum   += m.failBt;
            failBrkSum  += m.failBrk;
            repBtSum    += m.repBt;

            ensEvtTotalSum      += m.ensEventsTotal;
            ensEvtStartOnlySum  += m.ensEventsStartOnly;
            ensEvt1HSum         += m.ensEvents1H;
            ensEvt2HSum         += m.ensEvents2H;
            ensEvt3HSum         += m.ensEvents3H;
            ensEvt4HSum         += m.ensEvents4H;
            ensEvt5to8HSum      += m.ensEvents5to8H;
            ensEvt9to12HSum     += m.ensEvents9to12H;
            ensEvt13to24HSum    += m.ensEvents13to24H;
            ensEvtGt24HSum      += m.ensEventsGt24H;
            ensEvtMaxHoursSum   += m.ensEventsMaxHours;
        }

        return new ChunkAgg(
                fromInclusive,
                ens,
                ens1Sum,
                ens2Sum,
                fuelSum,
                motoSum,
                lcoeSum,                // <<< теперь переменная существует
                wrePctSum,
                wtPctSum,
                dgPctSum,
                btPctSum,
                failRoomSum,
                failBusSum,
                failDgSum,
                failWtSum,
                failBtSum,
                failBrkSum,
                repBtSum,
                ensEvtTotalSum,
                ensEvtStartOnlySum,
                ensEvt1HSum,
                ensEvt2HSum,
                ensEvt3HSum,
                ensEvt4HSum,
                ensEvt5to8HSum,
                ensEvt9to12HSum,
                ensEvt13to24HSum,
                ensEvtGt24HSum,
                ensEvtMaxHoursSum
        );
    }


    private static final class ChunkAgg {
        final int ensOffset;
        final double[] ens;
        final double ens1Sum;
        final double ens2Sum;
        final double fuelSum;
        final double motoSum;
        final double lcoeSum;
        final double wrePctSum;
        final double wtPctSum;
        final double dgPctSum;
        final double btPctSum;
        final double failRoomSum;
        final double failBusSum;
        final double failDgSum;
        final double failWtSum;
        final double failBtSum;
        final double failBrkSum;
        final double repBtSum;


        final EconomyDrivers firstDrivers;
        final double[] servedSumByYear;
        final double[] fuelSumByYear;
        final double[] motoSumByYear;
        final double[] btReplSumByYear;

        final double ensEvtTotalSum;
        final double ensEvtStartOnlySum;
        final double ensEvt1HSum;
        final double ensEvt2HSum;
        final double ensEvt3HSum;
        final double ensEvt4HSum;
        final double ensEvt5to8HSum;
        final double ensEvt9to12HSum;
        final double ensEvt13to24HSum;
        final double ensEvtGt24HSum;
        final double ensEvtMaxHoursSum;

        ChunkAgg(int ensOffset,
                 double[] ens,
                 double ens1Sum,
                 double ens2Sum,
                 double fuelSum,
                 double motoSum,
                 double lcoeSum,
                 double wrePctSum,
                 double wtPctSum,
                 double dgPctSum,
                 double btPctSum,
                 double failRoomSum,
                 double failBusSum,
                 double failDgSum,
                 double failWtSum,
                 double failBtSum,
                 double failBrkSum,
                 double repBtSum,
                 double ensEvtTotalSum,
                 double ensEvtStartOnlySum,
                 double ensEvt1HSum,
                 double ensEvt2HSum,
                 double ensEvt3HSum,
                 double ensEvt4HSum,
                 double ensEvt5to8HSum,
                 double ensEvt9to12HSum,
                 double ensEvt13to24HSum,
                 double ensEvtGt24HSum,
                 double ensEvtMaxHoursSum,
                 EconomyDrivers firstDrivers,
                 double[] servedSumByYear,
                 double[] fuelSumByYear,
                 double[] motoSumByYear,
                 double[] btReplSumByYear) {
            this.ensOffset = ensOffset;
            this.ens = ens;
            this.ens1Sum = ens1Sum;
            this.ens2Sum = ens2Sum;
            this.fuelSum = fuelSum;
            this.motoSum = motoSum;
            this.lcoeSum = lcoeSum;
            this.wrePctSum = wrePctSum;
            this.wtPctSum = wtPctSum;
            this.dgPctSum = dgPctSum;
            this.btPctSum = btPctSum;
            this.failRoomSum = failRoomSum;
            this.failBusSum = failBusSum;
            this.failDgSum = failDgSum;
            this.failWtSum = failWtSum;
            this.failBtSum = failBtSum;
            this.failBrkSum = failBrkSum;
            this.repBtSum = repBtSum;

            this.firstDrivers = firstDrivers;
            this.servedSumByYear = servedSumByYear;
            this.fuelSumByYear = fuelSumByYear;
            this.motoSumByYear = motoSumByYear;
            this.btReplSumByYear = btReplSumByYear;

            this.ensEvtTotalSum = ensEvtTotalSum;
            this.ensEvtStartOnlySum = ensEvtStartOnlySum;
            this.ensEvt1HSum = ensEvt1HSum;
            this.ensEvt2HSum = ensEvt2HSum;
            this.ensEvt3HSum = ensEvt3HSum;
            this.ensEvt4HSum = ensEvt4HSum;
            this.ensEvt5to8HSum = ensEvt5to8HSum;
            this.ensEvt9to12HSum = ensEvt9to12HSum;
            this.ensEvt13to24HSum = ensEvt13to24HSum;
            this.ensEvtGt24HSum = ensEvtGt24HSum;
            this.ensEvtMaxHoursSum = ensEvtMaxHoursSum;
        }
    }

    private static int estimateParallelism(ExecutorService executor) {
        if (executor instanceof ForkJoinPool fjp) return Math.max(1, fjp.getParallelism());
        return Math.max(1, Runtime.getRuntime().availableProcessors());
    }

    private static long seedFor(long mcBaseSeed, long sobolRowIdx, int mcIdx) {
        return mcBaseSeed
                + sobolRowIdx * SOBOL_ROW_SEED_STRIDE
                + (long) mcIdx * MC_SEED_STRIDE;
    }

    private static double pct(double part, double total) {
        if (total <= SimulationConstants.EPSILON) return 0.0;
        return (part / total) * 100.0;
    }
}
