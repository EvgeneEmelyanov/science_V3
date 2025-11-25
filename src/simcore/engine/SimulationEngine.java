package simcore.engine;

import simcore.config.BusSystemType;
import simcore.config.SimulationConfig;
import simcore.config.SystemParameters;
import simcore.model.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.*;

/**
 * Движок многопоточного Monte Carlo.
 * Считает:
 *  - ВЭУ + ДГУ против нагрузки на всех шинах,
 *  - учитывает отказы шин, автомата и самих ВЭУ/ДГУ/АКБ.
 *
 * ВАЖНО:
 *  - каждая итерация Monte Carlo строит СВОЙ экземпляр PowerSystem;
 *  - никакого общего изменяемого состояния между итерациями нет;
 *  - если iterations == 1, дополнительно формируется CSV-трейс по часам.
 */
public class SimulationEngine {

    private final SimulationConfig config;
    private final SystemParameters systemParameters;
    private final double[] totalLoadKw; // общий ряд нагрузки (как в файле)

    public SimulationEngine(SimulationConfig config,
                            SystemParameters systemParameters,
                            double[] totalLoadKw) {
        this.config = config;
        this.systemParameters = systemParameters;
        this.totalLoadKw = totalLoadKw;
    }

    /**
     * Запуск Monte Carlo-симуляции.
     *
     * @return сводка по результатам (средний дефицит уже после обработки выборки)
     */
    public SimulationSummary runMonteCarlo()
            throws InterruptedException, ExecutionException, IOException {

        int iterations = config.getIterations();
        int threads = config.getThreads();

        // параметры обработки выборки (можно вынести в конфиг, если захочешь)
        boolean removeOutliers = false;  // пока без удаления выбросов
        double tScore = 1.96;            // 95% доверительный уровень
        double relativeError = 0.10;     // 10% относительная допустимая ошибка

        // Особый режим: iterations == 1 → детальный трейс в CSV
        if (iterations == 1) {

            List<SimulationStepRecord> trace = new ArrayList<>();
            SimulationResult result = runSingleSimulation(0, trace);

            int busCount = (systemParameters.getBusSystemType() == BusSystemType.SINGLE_NOT_SECTIONAL_BUS)
                    ? 1 : 2;

            // путь к CSV-файлу
            String tracePath = "D:/simulation_trace.csv";
            SimulationTraceExporter.exportToCsv(tracePath, trace, busCount);

            // выборка из одного значения дефицита
            double[] deficits = new double[]{result.getTotalDeficit()};
            MonteCarloStats.Stats stats =
                    MonteCarloStats.compute(deficits, removeOutliers, tScore, relativeError);

            double processedMean = stats.getMean(); // по факту то же самое значение

            return new SimulationSummary(
                    result.getTotalDeficit(),
                    processedMean,
                    1,
                    result.getSupplyFromWT(),
                    result.getSupplyFromDG()
            );
        }

        // Обычный режим Monte Carlo (iterations > 1) — без трейсинга
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        List<Future<SimulationResult>> futures = new ArrayList<>(iterations);

        for (int i = 0; i < iterations; i++) {
            final int iterationIndex = i;
            Callable<SimulationResult> task = () -> runSingleSimulation(iterationIndex, null);
            futures.add(executor.submit(task));
        }

        double totalDeficitSum = 0.0;
        double totalSupplyFromWtSum = 0.0;
        double totalSupplyFromDgSum = 0.0;

        // выборка дефицита по итерациям
        double[] deficits = new double[iterations];
        int idx = 0;

        for (Future<SimulationResult> future : futures) {
            SimulationResult result = future.get();

            double deficit = result.getTotalDeficit();
            deficits[idx++] = deficit;

            totalDeficitSum += deficit;
            totalSupplyFromWtSum += result.getSupplyFromWT();
            totalSupplyFromDgSum += result.getSupplyFromDG();
        }

        executor.shutdown();

        // обработка выборки Монте-Карло
        MonteCarloStats.Stats stats =
                MonteCarloStats.compute(deficits, removeOutliers, tScore, relativeError);

        // «сырое» среднее можно, если нужно, оставить для отладки:
        // double rawAverageDeficit = totalDeficitSum / iterations;

        double processedMeanDeficit = stats.getMean();

        return new SimulationSummary(
                totalDeficitSum,
                processedMeanDeficit,
                iterations,
                totalSupplyFromWtSum,
                totalSupplyFromDgSum
        );
    }

    /**
     * Один прогон Monte Carlo (одна реализация по всему времяному ряду).
     * Если trace != null, пишем пошаговые данные по времени в trace.
     */
    private SimulationResult runSingleSimulation(int iterationIndex,
                                                 List<SimulationStepRecord> trace) {

        double[] wind = config.getWindMs();
        int n = wind.length;

        boolean considerFailures = config.isConsiderFailures();

        // --- Строим "свою" энергосистему для этой итерации ---
        PowerSystemBuilder builder = new PowerSystemBuilder();
        PowerSystem powerSystem = builder.build(systemParameters, totalLoadKw);

        List<PowerBus> buses = powerSystem.getBuses();
        int busCount = buses.size();
        Breaker breaker = powerSystem.getTieBreaker();

        double totalDeficit = 0.0;

        // суммарно по всей итерации:
        double totalSupplyFromWT = 0.0; // сколько нагрузки (кВт·ч) покрыто ВЭУ
        double totalSupplyFromDG = 0.0; // сколько нагрузки покрыто ДГУ
        // батарею добавим позже

        // --- Детерминированные генераторы случайных чисел по типам оборудования ---
        long baseSeed = 1_000_000L + iterationIndex * 10_000L;

        Random rndWT  = new Random(baseSeed + 1); // ВЭУ
        Random rndDG  = new Random(baseSeed + 2); // ДГУ
        Random rndBT  = new Random(baseSeed + 3); // АКБ
        Random rndBUS = new Random(baseSeed + 4); // Шины
        Random rndBRK = new Random(baseSeed + 5); // Автомат

        // --- Инициализация моделей отказов перед ОДНОЙ итерацией ---

        if (breaker != null) {
            breaker.initFailureModel(rndBRK, considerFailures);
        }

        for (PowerBus bus : buses) {
            bus.initFailureModel(rndBUS, considerFailures);

            for (WindTurbine wt : bus.getWindTurbines()) {
                wt.initFailureModel(rndWT, considerFailures);
            }
            for (DieselGenerator dg : bus.getDieselGenerators()) {
                dg.initFailureModel(rndDG, considerFailures);
            }
            Battery bt = bus.getBattery();
            if (bt != null) {
                bt.initFailureModel(rndBT, considerFailures);
            }
        }

        // ====== ЧАСОВОЙ ЦИКЛ ======
        for (int t = 0; t < n; t++) {

            double v = wind[t];

            // ---------- 1. ОБНОВЛЕНИЕ ОТКАЗОВ ШИН И АВТОМАТА С УЧЁТОМ ПРАВИЛ ----------

            boolean[] busAvailableBefore = new boolean[busCount];
            for (int b = 0; b < busCount; b++) {
                busAvailableBefore[b] = buses.get(b).isAvailable();
            }

            boolean breakerAvailableBefore = (breaker != null) && breaker.isAvailable();
            boolean breakerClosedBefore    = (breaker != null) && breaker.isClosed();

            if (breaker != null) {
                breaker.updateFailureOneHour(considerFailures);
            }

            for (PowerBus bus : buses) {
                bus.updateFailureOneHour(considerFailures);
            }

            boolean[] busAvailableAfter = new boolean[busCount];
            boolean[] busFailedThisHour = new boolean[busCount];
            boolean anyBusFailedThisHour = false;

            for (int b = 0; b < busCount; b++) {
                PowerBus bus = buses.get(b);
                busAvailableAfter[b] = bus.isAvailable();
                busFailedThisHour[b] = busAvailableBefore[b] && !busAvailableAfter[b];
                anyBusFailedThisHour |= busFailedThisHour[b];
            }

            boolean breakerAvailableAfter = (breaker != null) && breaker.isAvailable();
            boolean breakerFailedThisHour =
                    (breaker != null) && breakerAvailableBefore && !breakerAvailableAfter;

            // Правило 1: автомат был замкнут, и в этот час отказала шина и автомат → отказ всех шин
            if (breaker != null &&
                    breakerClosedBefore &&
                    breakerFailedThisHour &&
                    anyBusFailedThisHour) {

                for (PowerBus bus : buses) {
                    if (bus.isAvailable()) {
                        bus.forceFailNow();
                    }
                }
            }
            // Правило 2: шина отказала, автомат НЕ отказал и был замкнут → автомат размыкается
            else if (breaker != null &&
                    breakerClosedBefore &&
                    anyBusFailedThisHour &&
                    !breakerFailedThisHour) {

                breaker.setClosed(false);
            }

            boolean[] busAlive = new boolean[busCount];
            for (int b = 0; b < busCount; b++) {
                busAlive[b] = buses.get(b).isAvailable();
            }

            // ---------- 2. ОБНОВЛЕНИЕ ОТКАЗОВ ВЭУ/ДГУ/АКБ НА ЖИВЫХ ШИНАХ ----------

            for (int b = 0; b < busCount; b++) {
                if (!busAlive[b]) {
                    continue;
                }
                PowerBus bus = buses.get(b);

                for (WindTurbine wt : bus.getWindTurbines()) {
                    wt.updateFailureOneHour(considerFailures);
                }
                for (DieselGenerator dg : bus.getDieselGenerators()) {
                    dg.updateFailureOneHour(considerFailures);
                }
                Battery bt = bus.getBattery();
                if (bt != null) {
                    bt.updateFailureOneHour(considerFailures);
                }
            }

            // ---------- 3. РАСЧЁТ ГЕНЕРАЦИИ, НАРАБОТКИ И ДЕФИЦИТА + ТРЕЙС ----------

            double totalLoadAtTime = 0.0;
            double totalGenAtTime = 0.0;

            double bus1Load = 0.0;
            double bus1Gen  = 0.0;
            double bus1Bal  = 0.0;

            double bus2Load = 0.0;
            double bus2Gen  = 0.0;
            double bus2Bal  = 0.0;

            for (int b = 0; b < busCount; b++) {

                PowerBus bus = buses.get(b);
                double[] busLoadArr = bus.getLoadKw();
                double loadKw = busLoadArr[t];
                totalLoadAtTime += loadKw;

                double busWindGenKw = 0.0;

                if (busAlive[b]) {
                    // ВЭУ: суммарная потенциальная генерация всех ВЭУ на шине
                    for (WindTurbine wt : bus.getWindTurbines()) {
                        double g = wt.getPotentialGenerationKw(v);
                        busWindGenKw += g;

                        if (wt.isAvailable()) {
                            wt.addWorkTime(1.0);
                        }
                    }

                    // сколько нагрузки покрыто ветром
                    double wtSupply = Math.min(busWindGenKw, loadKw);

                    // остаток нагрузки
                    double residualLoad = loadKw - wtSupply;

                    // пока считаем, что остаток закрывают ДГУ (если они есть)
                    double dgSupply = residualLoad;
                    // TODO: сюда добавится реальная логика ДГУ и АКБ

                    totalSupplyFromWT += wtSupply;
                    totalSupplyFromDG += dgSupply;

                    double busGenKw = wtSupply + dgSupply;

                    // Наработка шины
                    bus.addWorkTime(1.0);

                    totalGenAtTime += busGenKw;

                    double balance = busGenKw - loadKw;

                    if (b == 0) {
                        bus1Load = loadKw;
                        bus1Gen  = busGenKw;
                        bus1Bal  = balance;
                    } else if (b == 1) {
                        bus2Load = loadKw;
                        bus2Gen  = busGenKw;
                        bus2Bal  = balance;
                    }
                } else {
                    // шина мертва — генерации нет
                    double busGenKw = 0.0;
                    totalGenAtTime += busGenKw;

                    double balance = busGenKw - loadKw;

                    if (b == 0) {
                        bus1Load = loadKw;
                        bus1Gen  = busGenKw;
                        bus1Bal  = balance;
                    } else if (b == 1) {
                        bus2Load = loadKw;
                        bus2Gen  = busGenKw;
                        bus2Bal  = balance;
                    }
                }
            }

            // Наработка автомата: если исправен — "работает" этот час
            if (breaker != null && breaker.isAvailable()) {
                breaker.addWorkTime(1.0);
            }

            double deficit = Math.max(0.0, totalLoadAtTime - totalGenAtTime);
            totalDeficit += deficit;

            // Запись трейса для Excel (если запрошен)
            if (trace != null) {
                int tIndex = t; // можно сделать (t + 1), если удобнее с единицы
                double totalLoadFromFile = totalLoadKw[t];

                trace.add(new SimulationStepRecord(
                        tIndex,
                        totalLoadFromFile,
                        bus1Load,
                        bus1Gen,
                        bus1Bal,
                        bus2Load,
                        bus2Gen,
                        bus2Bal
                ));
            }
        }

        return new SimulationResult(totalDeficit, totalSupplyFromWT, totalSupplyFromDG);
    }

    // -----------------------------
    // РЕЗУЛЬТАТЫ ОДНОЙ И МНОГИХ ИТЕРАЦИЙ
    // -----------------------------

    /** Результат одной итерации (одного прогона по всем часам). */
    public static final class SimulationResult {
        private final double totalDeficit;
        private final double supplyFromWT;
        private final double supplyFromDG;

        public SimulationResult(double totalDeficit,
                                double supplyFromWT,
                                double supplyFromDG) {
            this.totalDeficit = totalDeficit;
            this.supplyFromWT = supplyFromWT;
            this.supplyFromDG = supplyFromDG;
        }

        public double getTotalDeficit() {
            return totalDeficit;
        }

        /** Сколько нагрузки (кВт·ч) было обеспечено ВЭУ за одну итерацию. */
        public double getSupplyFromWT() {
            return supplyFromWT;
        }

        /** Сколько нагрузки (кВт·ч) было обеспечено ДГУ за одну итерацию. */
        public double getSupplyFromDG() {
            return supplyFromDG;
        }
    }

    /** Сводка по всем итерациям Monte Carlo. */
    public static final class SimulationSummary {
        private final double totalDeficitSum;
        private final double averageDeficit;
        private final int iterations;

        /** Суммарная нагрузка, покрытая ВЭУ (по всем итерациям), кВт·ч. */
        private final double totalSupplyFromWT;

        /** Суммарная нагрузка, покрытая ДГУ (по всем итерациям), кВт·ч. */
        private final double totalSupplyFromDG;

        public SimulationSummary(double totalDeficitSum,
                                 double averageDeficit,
                                 int iterations,
                                 double totalSupplyFromWT,
                                 double totalSupplyFromDG) {
            this.totalDeficitSum = totalDeficitSum;
            this.averageDeficit = averageDeficit;
            this.iterations = iterations;
            this.totalSupplyFromWT = totalSupplyFromWT;
            this.totalSupplyFromDG = totalSupplyFromDG;
        }

        public double getTotalDeficitSum() {
            return totalDeficitSum;
        }

        /** Средний дефицит по выборке Monte Carlo (уже после обработки). */
        public double getAverageDeficit() {
            return averageDeficit;
        }

        public int getIterations() {
            return iterations;
        }

        public double getTotalSupplyFromWT() {
            return totalSupplyFromWT;
        }

        public double getTotalSupplyFromDG() {
            return totalSupplyFromDG;
        }
    }
}
