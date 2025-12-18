package simcore.engine;

import simcore.config.BusSystemType;
import simcore.config.SimulationConfig;
import simcore.config.SimulationConstants;
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
 * - ВЭУ + ДГУ против нагрузки на всех шинах,
 * - учитывает отказы шин, автомата и самих ВЭУ/ДГУ/АКБ.
 * <p>
 * ВАЖНО:
 * - каждая итерация Monte Carlo строит СВОЙ экземпляр PowerSystem;
 * - никакого общего изменяемого состояния между итерациями нет;
 * - если iterations == 1, дополнительно формируется CSV-трейс по часам.
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
            SimulationTraceExporter.exportToCsv(tracePath, trace);

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

        // Обычный режим Monte Carlo (iterations > 1) — без вывода часовых результатов
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
        boolean considerDegradation = config.isConsiderBatteryDegradation();

        // --- Строим "свою" энергосистему для этой итерации ---
        PowerSystemBuilder builder = new PowerSystemBuilder();
        PowerSystem powerSystem = builder.build(systemParameters, totalLoadKw);

        List<PowerBus> buses = powerSystem.getBuses();
        int busCount = buses.size();
        Breaker breaker = powerSystem.getTieBreaker();

        // суммарно по всей итерации:
        double totalDeficit = 0.0;
        double totalSupplyFromWT = 0.0;
        double totalSupplyFromDG = 0.0;
        double totalSupplyFromBT = 0.0;
        double totalWre = 0.0;  // суммарная неиспользованная энергия ветра (WRE)
        double totalEns = 0.0;  // суммарный недоотпуск (ENS), если потребуется

        // --- Детерминированные генераторы случайных чисел по типам оборудования ---
        long baseSeed = 1_000_000L + iterationIndex * 10_000L;

        Random rndWT = new Random(baseSeed + 1); // ВЭУ
        Random rndDG = new Random(baseSeed + 2); // ДГУ
        Random rndBT = new Random(baseSeed + 3); // АКБ
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
            boolean breakerClosedBefore = (breaker != null) && breaker.isClosed();

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

            // TODO где-то тут будет распределение нагрузки по шинам

            // ---------- 3. РАСЧЁТ ГЕНЕРАЦИИ, НАРАБОТКИ И ДЕФИЦИТА + ТРЕЙС ----------

            double totalLoadAtTime = 0.0;
            double totalDeficitAtTime = 0.0;
            double totalWreAtTime = 0.0;
            boolean[] busStatus = new boolean[busCount];
            double[] busLoadAtTime = new double[busCount];
            double[] busGenWindAtTime = new double[busCount];
            double[] busGenDgAtTime = new double[busCount];
            double[] busGenBtAtTime = new double[busCount];
            double[] busDeficitAtTime = new double[busCount];


            // Наработка автомата: если исправен — "работает" этот час
            if (breaker != null && breaker.isAvailable()) {
                breaker.addWorkTime(1);
            }

            for (int b = 0; b < busCount; b++) { // TODO ТУТ ДЕЛАЕМ ЧАСОВЫЕ ИНТЕРВАЛЫ

                PowerBus bus = buses.get(b);
                double[] busLoadArr = bus.getLoadKw(); // TODO ПОТОМ УБРАТЬ ОТ СЮДА РАСПРЕДЕЛЕНИЕ НАГРУЗКИ НА 275 СТРОКУ
                double loadKw = busLoadArr[t];
                totalLoadAtTime += loadKw;

                double busWindPotentialKw = 0.0;
                double busWindGenKw = 0.0;
                double busDieselGenKw = 0.0;
                double busBatteryGenKw = 0.0; // >0 разряд, <0 заряд

                int dgCount = 0; // кол-во дгу, которые нужны в текущей итерации

                if (busAlive[b]) {
                    busStatus[b] = true;
                    busLoadAtTime[b] = loadKw;

                    // Наработка шины
                    bus.addWorkTime(1);

                    // ВЭУ: суммарная потенциальная генерация всех ВЭУ на шине
                    for (WindTurbine wt : bus.getWindTurbines()) {
                        double g = wt.getPotentialGenerationKw(v);
                        busWindPotentialKw += g;

                        if (wt.isAvailable()) {
                            wt.addWorkTime(1);
                        }
                    }
                    busGenWindAtTime[b] = busWindPotentialKw;

                    Battery battery = bus.getBattery();

                    // === ПРОФИЦИТ ВЕТРА ===
                    if (busWindPotentialKw >= loadKw - SimulationConstants.EPSILON) {

                        busWindGenKw = loadKw; // ВЭУ полностью закрыли нагрузку

                        double proficit = Math.max(0.0, busWindPotentialKw - loadKw);

                        if (battery != null && battery.isAvailable()
                                && battery.getStateOfCharge() < SimulationConstants.BATTERY_MAX_SOC) {

                            double chargeCap = battery.getChargeCapacity(systemParameters);  // сколько можем принять
                            double chargeToUse = Math.min(proficit, chargeCap);

                            if (chargeToUse > SimulationConstants.EPSILON) {
                                // заряд АКБ: в балансе генерация АКБ отрицательная
                                battery.adjustCapacity(battery, chargeToUse, chargeToUse, false, considerDegradation);
                                busBatteryGenKw = -chargeToUse;
                                proficit -= chargeToUse;
                            }

                            totalWre += Math.max(0.0, proficit);
                        } else {
                            totalWre += Math.max(0.0, busWindPotentialKw - busWindGenKw);
                        }

                        // дизели не нужны
                        busDieselGenKw = 0.0;
                        for (DieselGenerator dg : bus.getDieselGenerators()) {
                            dg.stopWork();
                            dg.setCurrentLoad(0.0);
                        }
                        //todo проверка необходимости холостого хода
                    }
                    // === ДЕФИЦИТ ВЕТРА ===
                    else {
                        busWindGenKw = busWindPotentialKw;  // ВЭУ отдали всё

                        double deficitAfterWind = loadKw - busWindGenKw;

                        double batteryDischargeCap = 0.0;
                        boolean batteryAvailable = false;
                        if (battery != null && battery.isAvailable()) {
                            batteryAvailable = true;
                            batteryDischargeCap = battery.getDischargeCapacity(systemParameters);
                        }

                        double dgPower = systemParameters.getDieselGeneratorPowerKw();
                        double dgMaxLoad = dgPower * SimulationConstants.DG_MAX_POWER;
                        double perDgTarget = dgPower * SimulationConstants.DG_OPTIMAL_POWER;

                        long availableGenerators = bus.getDieselGenerators().stream()
                                .filter(DieselGenerator::isAvailable)
                                .count();


                        int readyDgCount = (int) bus.getDieselGenerators().stream()
                                .filter(DieselGenerator::isWorking)
                                .count();

                        boolean canUse80Percent = (perDgTarget * availableGenerators >= deficitAfterWind); //todo мб умножать на ready

                        int neededGenerators;
                        if (canUse80Percent) {
                            neededGenerators = (int) Math.ceil(deficitAfterWind / perDgTarget);
                        } else {
                            neededGenerators = (int) Math.ceil(deficitAfterWind / dgMaxLoad);
                        }
                        dgCount = (int) Math.min(neededGenerators, availableGenerators);

                        int dgToUse = 0;
                        double x = 0; // todo нормально назвать переменную


                        for (int i = 0; i <= dgCount; i++) {

                            dgToUse = i;

                            double dgEnergy;
                            double btEnergy;
                            double btCurrent;

                            if (i == 0) {
                                dgEnergy = 0.0;
                                btEnergy = deficitAfterWind;
                                btCurrent = deficitAfterWind;
                            } else {
                                int ready = Math.min(i, readyDgCount);

                                // Сколько готовы выдать уже работающие ДГУ
                                double dgPowerReady = ready * dgMaxLoad;
                                double startingDeficit = Math.max(0.0, deficitAfterWind - dgPowerReady);
                                double startingEnergy = startingDeficit * SimulationConstants.DG_START_DELAY_HOURS;

                                // Определяем целевую нагрузку на ДГУ
                                double perDgLoad;
                                if (canUse80Percent) {
                                    perDgLoad = Math.min(deficitAfterWind / i, dgMaxLoad * 0.8); // нагрузка до 100% //todo сделать ссылку на константу
                                } else {
                                    perDgLoad = Math.min(deficitAfterWind / i, dgMaxLoad); // нагрузка до 100%
                                }

                                double totalDgPowerSteady = perDgLoad * i;
                                double steadyDeficit = Math.max(0.0, deficitAfterWind - totalDgPowerSteady);
                                x = steadyDeficit;
                                double steadyEnergy = steadyDeficit * (1.0 - SimulationConstants.DG_START_DELAY_HOURS);

                                btEnergy = startingEnergy + steadyEnergy;
                                btCurrent = Math.max(startingDeficit, steadyDeficit);
                                dgEnergy = deficitAfterWind - btEnergy;
                            }

                            boolean useBattery =
                                    batteryAvailable
                                            && (Battery.useBattery(systemParameters, battery, btEnergy, batteryDischargeCap) ||
                                            (i != 0 && batteryDischargeCap >= btEnergy && x == 0))
                                            && batteryDischargeCap > btEnergy - SimulationConstants.EPSILON;

                            if (useBattery) {
                                // АКБ может добровольно отдать требуемую энергию — решение найдено
                                battery.adjustCapacity(battery, -btEnergy, btCurrent, false, considerDegradation);
                                busBatteryGenKw = btEnergy;
                                busDieselGenKw = dgEnergy;
                                break; // выходим, минимальное i найдено
                            }

                            // если мы дошли до последней итерации и АКБ не может отдать btEnergy
                            if (i == dgCount && !useBattery) {
                                if (batteryAvailable) {
                                    // разряжаем АКБ на максимум, что доступно
                                    btEnergy = batteryDischargeCap;
                                    btCurrent = btEnergy;
                                    battery.adjustCapacity(battery, -btEnergy, btCurrent, false, considerDegradation);
                                } else {
                                    btEnergy = 0.0;
                                }
                                // суммарная генерация ДГУ
                                busDieselGenKw = deficitAfterWind - btEnergy;
                                busBatteryGenKw = btEnergy;

                                break;
                            }

                        }
                        // todo нужно чтобы дгу по возможности работало >30% и <80%
                        List<DieselGenerator> allDg = new ArrayList<>(bus.getDieselGenerators());
                        allDg.sort(DieselGenerator.DISPATCH_COMPARATOR);

                        int R = Math.min(readyDgCount, dgToUse);
                        double tau = SimulationConstants.DG_START_DELAY_HOURS;

                        // Пусковой режим — сколько могут взять готовые ДГУ
                        double readyMaxStart = R * perDgTarget;
                        double readyLoadStart = Math.min(deficitAfterWind, readyMaxStart);
                        double perReadyStart = (R > 0) ? readyLoadStart / R : 0.0;

                        // Установившийся режим
                        double perDgSteady;
                        if (canUse80Percent) {
                            perDgSteady = deficitAfterWind / dgToUse;
                        } else {
                            perDgSteady = Math.min(deficitAfterWind / dgToUse, dgMaxLoad);
                        }


                        // 3. Распределяем по конкретным ДГУ
                        int used = 0;

                        for (DieselGenerator dg : allDg) {

                            if (!dg.isAvailable()) {
                                dg.setCurrentLoad(0.0);
                                dg.stopWork();
                                continue;
                            }

                            if (used >= dgToUse) {
                                dg.setCurrentLoad(0.0);
                                dg.stopWork();
                                continue;
                            }

                            double energy;
                            if (dg.isWorking()) {// ДГУ была готова
                                energy = perReadyStart * tau + perDgSteady * (1.0 - tau);
                            } else {// ДГУ запускалась
                                energy = 0.0 * tau + perDgSteady * (1.0 - tau);
                            }

                            dg.setCurrentLoad(energy);
                            dg.addWorkTime(1, dg.isWorking() ? 1 : 1 + 5); //todo сделать ссылку на константу
                            dg.startWork();

                            used++;
                        }


                    }
                } else {
                    // === ШИНА В ОТКАЗЕ ===
                    busWindGenKw = 0.0;
                    busDieselGenKw = 0.0;
                    busBatteryGenKw = 0.0;

                    for (DieselGenerator dg : bus.getDieselGenerators()) {
                        dg.stopWork();
                        dg.setCurrentLoad(0.0);
                    }
                }


//не выводить в то если в то уже есть дгу с этой шины
//                если нагрузка меньше 30 процентов пробовать догрузить дгу  и зарядить акб
//                        если нагрузка меньше 30 процентов дольше 4 часов то нужно прожеч дгу


                // todo запись результатов
                busGenDgAtTime[b] = busDieselGenKw;
                busGenBtAtTime[b] = busBatteryGenKw;
                busDeficitAtTime[b] = busLoadAtTime[b] - (busDieselGenKw + busWindGenKw + Math.max(0.0, busBatteryGenKw));


                totalSupplyFromWT += busWindGenKw;
                totalSupplyFromDG += busDieselGenKw;
                totalSupplyFromBT += Math.max(0.0, busBatteryGenKw);
                totalDeficit += busDeficitAtTime[b];


            }


            if (trace != null) {
                double[][] busGenDgLoadKw = new double[busCount][];
                double[][] busGenDgHoursSinceMaintenance = new double[busCount][];
                double[][] busGenDgTimeWorked = new double[busCount][];
                double[][] busGenDgTotalTimeWorked = new double[busCount][];
                boolean[][] dgAvailable = new boolean[busCount][];
                boolean[][] dgInMaintenance = new boolean[busCount][];

                for (int b = 0; b < busCount; b++) {
                    List<DieselGenerator> dgList = buses.get(b).getDieselGenerators();
                    int dgCount = dgList.size();

                    busGenDgLoadKw[b] = new double[dgCount];
                    busGenDgHoursSinceMaintenance[b] = new double[dgCount];
                    busGenDgTimeWorked[b] = new double[dgCount];
                    busGenDgTotalTimeWorked[b] = new double[dgCount];
                    dgAvailable[b] = new boolean[dgCount];
                    dgInMaintenance[b] = new boolean[dgCount];

                    for (int i = 0; i < dgCount; i++) {
                        DieselGenerator dg = dgList.get(i);
                        busGenDgLoadKw[b][i] = dg.getCurrentLoad();
                        busGenDgHoursSinceMaintenance[b][i] = dg.getHoursSinceMaintenance();
                        busGenDgTimeWorked[b][i] = dg.getTimeWorked();
                        busGenDgTotalTimeWorked[b][i] = dg.getTotalTimeWorked();
                        dgAvailable[b][i] = dg.isAvailable();
                        dgInMaintenance[b][i] = dg.isInMaintenance();
                    }
                }

                trace.add(new SimulationStepRecord(
                        t,
                        totalLoadAtTime,
                        totalDeficitAtTime,
                        totalWreAtTime,
                        busStatus,
                        busLoadAtTime,
                        busGenWindAtTime,
                        busGenDgAtTime,
                        busGenBtAtTime,
                        busDeficitAtTime,
                        busGenDgLoadKw,
                        busGenDgHoursSinceMaintenance,
                        busGenDgTimeWorked,
                        busGenDgTotalTimeWorked,
                        dgAvailable,
                        dgInMaintenance
                ));
            }




        }

        return new SimulationResult(totalDeficit, totalSupplyFromWT, totalSupplyFromDG);
    }

    // -----------------------------
    // РЕЗУЛЬТАТЫ ОДНОЙ И МНОГИХ ИТЕРАЦИЙ
    // -----------------------------

    /**
     * Результат одной итерации (одного прогона по всем часам).
     */
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

        /**
         * Сколько нагрузки (кВт·ч) было обеспечено ВЭУ за одну итерацию.
         */
        public double getSupplyFromWT() {
            return supplyFromWT;
        }

        /**
         * Сколько нагрузки (кВт·ч) было обеспечено ДГУ за одну итерацию.
         */
        public double getSupplyFromDG() {
            return supplyFromDG;
        }
    }

    /**
     * Сводка по всем итерациям Monte Carlo.
     */
    public static final class SimulationSummary {
        private final double totalDeficitSum;
        private final double averageDeficit;
        private final int iterations;

        /**
         * Суммарная нагрузка, покрытая ВЭУ (по всем итерациям), кВт·ч.
         */
        private final double totalSupplyFromWT;

        /**
         * Суммарная нагрузка, покрытая ДГУ (по всем итерациям), кВт·ч.
         */
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

        /**
         * Средний дефицит по выборке Monte Carlo (уже после обработки).
         */
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
