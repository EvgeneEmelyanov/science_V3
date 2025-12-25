package simcore.engine;

import simcore.config.SimulationConfig;
import simcore.config.SimulationConstants;
import simcore.config.SystemParameters;
import simcore.config.BusSystemType;
import simcore.model.*;
import simcore.engine.failures.FailureStepper;
import simcore.engine.trace.ArrayTraceSession;
import simcore.engine.trace.NoTraceSession;
import simcore.engine.trace.TraceSession;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;


public final class SingleRunSimulator {

    // ===== Fuel model constants (из старого кода) =====
    private static final double K11 = 0.0185;
    private static final double K21 = -0.0361;
    private static final double K31 = 0.2745;
    private static final double K12 = 5.3978;
    private static final double K22 = -11.4831;
    private static final double K32 = 11.6284;

    private static final ThreadLocal<DieselGenerator[]> DG_SORT_BUF = new ThreadLocal<>();

    public SimulationMetrics simulate(SimInput input, long seed, boolean traceEnabled) {

        final SimulationConfig config = input.getConfig();
        final SystemParameters sp = input.getSystemParameters();

        final double[] windMs = config.getWindMs();
        final int hours = windMs.length;

        final boolean considerFailures = config.isConsiderFailures();
        final boolean considerDegradation = config.isConsiderBatteryDegradation();
        final boolean considerChargeByDg = config.isConsiderChargeByDg();
        final boolean considerRotationReserve = config.isConsiderRotationReserve();

        final double cat1 = sp.getFirstCat();
        final double cat2 = sp.getSecondCat();
        @SuppressWarnings("unused")
        final double cat3 = sp.getThirdCat();

        final PowerSystem system = new PowerSystemBuilder().build(sp, input.getTotalLoadKw());
        final List<PowerBus> buses = system.getBuses();
        final int busCount = buses.size();
        final Breaker breaker = system.getTieBreaker();

        FailureStepper.initFailureModels(seed, considerFailures, buses, breaker);

        final Totals totals = new Totals();
        final TraceSession trace = traceEnabled ? new ArrayTraceSession() : new NoTraceSession();

        final boolean[] busAvailBefore = new boolean[busCount];
        final boolean[] busAvailAfter = new boolean[busCount];
        final boolean[] busFailedThisHour = new boolean[busCount];
        final boolean[] busAlive = new boolean[busCount];

        // часто используемые параметры ДГУ
        final double dgRatedKw = sp.getDieselGeneratorPowerKw();
        final double dgMaxKw = dgRatedKw * SimulationConstants.DG_MAX_POWER;
        final double dgMinKw = dgRatedKw * SimulationConstants.DG_MIN_POWER;
        final double perDgOptimalKw = dgRatedKw * SimulationConstants.DG_OPTIMAL_POWER;
        final double dgStartDelayHours = SimulationConstants.DG_START_DELAY_HOURS;

        for (int t = 0; t < hours; t++) {

            final double windV = windMs[t];
            final boolean doTrace = trace.enabled();
            trace.startHour(busCount);

            double totalLoadAtTime = 0.0;
            double totalDefAtTime = 0.0;
            double totalWreAtTime = 0.0;
            final double[] hourWreRef = doTrace ? new double[]{0.0} : null;

            final boolean tieWasClosedAtHourStart = (breaker != null && breaker.isClosed());

            FailureStepper.updateNetworkFailuresOneHour(
                    considerFailures,
                    buses,
                    breaker,
                    busAvailBefore,
                    busAvailAfter,
                    busFailedThisHour,
                    busAlive
            );

            FailureStepper.updateEquipmentFailuresOneHour(considerFailures, buses, busAlive);

            if (tieWasClosedAtHourStart && breaker != null) {

                boolean breakerFailed = !breaker.isAvailable();
                boolean anyBusFailed = false;
                for (int i = 0; i < busCount; i++) {
                    if (!busAlive[i]) { anyBusFailed = true; break; }
                }

                if (breakerFailed && anyBusFailed) {
                    // отказал автомат + отказала шина => падают обе шины
                    for (int i = 0; i < busCount; i++) busAlive[i] = false;

                    // (желательно) отметить отказ в массивах, чтобы trace/статистика видели событие
                    for (int i = 0; i < busCount; i++) busFailedThisHour[i] = true;

                    breaker.setClosed(false);

                } else if (breakerFailed) {
                    // отказал только автомат => разомкнуть, шины остаются как есть
                    breaker.setClosed(false);

                } else if (anyBusFailed) {
                    // отказала шина, автомат жив => разомкнуть, шины остаются как есть (одна уже dead)
                    breaker.setClosed(false);
                }
            }

            if (breaker != null && breaker.isAvailable()) breaker.addWorkTime(1);

            // ===== Bus system logic (SINGLE_SECTIONAL_BUS) =====
            final BusSystemType busType = sp.getBusSystemType();

            final double[] effectiveLoadKw = (busType == BusSystemType.SINGLE_SECTIONAL_BUS && busCount == 2)
                    ? computeEffectiveLoadsForSectional(sp, buses, busAlive, t, cat1, cat2)
                    : null;

            boolean sectionalClosedThisHour = false;
            if (busType == BusSystemType.SINGLE_SECTIONAL_BUS
                    && busCount == 2
                    && breaker != null
                    && breaker.isAvailable()
                    && busAlive[0] && busAlive[1]) {

                double[] loadsForDecision = (effectiveLoadKw != null)
                        ? effectiveLoadKw
                        : new double[]{buses.get(0).getLoadKw()[t], buses.get(1).getLoadKw()[t]};

                sectionalClosedThisHour = shouldCloseTieBreakerThisHour(
                        sp, buses, loadsForDecision, windV, dgMaxKw
                );

                breaker.setClosed(sectionalClosedThisHour);
            } else {
                if (breaker != null) breaker.setClosed(false);
            }
            // ===== Sectional-closed dispatch (если секционник закрыт) =====
            if (sectionalClosedThisHour) {

                final double[] loads = (effectiveLoadKw != null)
                        ? effectiveLoadKw
                        : new double[]{buses.get(0).getLoadKw()[t], buses.get(1).getLoadKw()[t]};

                SectionalClosedResult r = dispatchSectionalClosedOneHour(
                        sp,
                        buses,
                        loads,
                        windV,
                        considerDegradation,
                        considerChargeByDg,
                        considerRotationReserve,
                        cat1,
                        cat2,
                        dgRatedKw,
                        dgMaxKw,
                        dgMinKw,
                        perDgOptimalKw,
                        dgStartDelayHours
                );

                // totals: полностью за час (в этом режиме per-bus dispatch ниже НЕ выполняем)
                totals.loadKwh += r.loadKwh;
                totals.ensKwh += r.ensKwh;
                totals.wreKwh += r.wreKwh;
                totals.wtToLoadKwh += r.wtToLoadKwh;
                totals.dgToLoadKwh += r.dgToLoadKwh;
                totals.btToLoadKwh += r.btToLoadKwh;
                totals.fuelLiters += r.fuelLiters;

                if (doTrace) {
                    totalLoadAtTime = r.loadKwh;
                    totalDefAtTime = r.ensKwh;
                    totalWreAtTime = r.wreKwh;

                    for (int b = 0; b < busCount; b++) {
                        trace.setBusValues(
                                b,
                                true,
                                loads[b],
                                r.windToLoadByBus[b],
                                r.dgToLoadByBus[b],
                                r.btNetByBus[b],
                                r.defByBus[b]
                        );
                        trace.fillDgState(b, buses.get(b));
                        trace.fillBatteryState(b, buses.get(b).getBattery());
                    }

                    Boolean brkClosed = (breaker == null) ? null : breaker.isClosed();
                    trace.addHourRecord(t, totalLoadAtTime, totalDefAtTime, totalWreAtTime, brkClosed);

                }
                continue;
            }

            // ===== Standard per-bus dispatch =====
            for (int b = 0; b < busCount; b++) {
                final PowerBus bus = buses.get(b);
                final double loadKw = (effectiveLoadKw != null) ? effectiveLoadKw[b] : bus.getLoadKw()[t];

                dispatchOneBusOneHour(
                        sp,
                        bus,
                        busAlive[b],
                        b,
                        loadKw,
                        windV,
                        cat1,
                        cat2,
                        considerDegradation,
                        considerChargeByDg,
                        considerRotationReserve,
                        dgRatedKw,
                        dgMaxKw,
                        dgMinKw,
                        perDgOptimalKw,
                        dgStartDelayHours,
                        totals,
                        hourWreRef,
                        trace
                );
            }

            if (doTrace) {
                for (int b = 0; b < busCount; b++) {
                    totalLoadAtTime += buses.get(b).getLoadKw()[t]; // или effectiveLoadKw[b] если он не null — ниже см.
                }
                totalWreAtTime = hourWreRef[0];
                Boolean brkClosed = (breaker == null) ? null : breaker.isClosed();
                trace.addHourRecord(t, totalLoadAtTime, totalDefAtTime, totalWreAtTime, brkClosed);

            }

        }

        // ===== total failures by internal counters =====
        long failBus = 0;
        long failDg = 0;
        long failWt = 0;
        long failBt = 0;
        long failBrk = 0;

        for (PowerBus bus : buses) {
            failBus += bus.getFailureCount();
            for (WindTurbine wt : bus.getWindTurbines()) failWt += wt.getFailureCount();
            for (DieselGenerator dg : bus.getDieselGenerators()) failDg += dg.getFailureCount();
            Battery bt = bus.getBattery();
            if (bt != null) failBt += bt.getFailureCount();
        }
        if (breaker != null) failBrk += breaker.getFailureCount();

        long moto = 0;
        for (PowerBus bus : buses) {
            for (DieselGenerator dg : bus.getDieselGenerators()) moto += dg.getTotalTimeWorked();
        }

        return new SimulationMetrics(
                totals.loadKwh,
                totals.ensKwh,
                totals.wreKwh,
                totals.wtToLoadKwh,
                totals.dgToLoadKwh,
                totals.btToLoadKwh,
                totals.fuelLiters,
                moto,
                trace.records(),
                failBus,
                failDg,
                failWt,
                failBt,
                failBrk
        );
    }

    // ======================================================================
    // Helpers
    // ======================================================================

    private static final class Totals {
        double loadKwh;
        double ensKwh;
        double wreKwh;
        double wtToLoadKwh;
        double dgToLoadKwh;
        double btToLoadKwh;
        double fuelLiters;
    }

    private static final class SectionalClosedResult {
        final double loadKwh;
        final double ensKwh;
        final double wreKwh;
        final double wtToLoadKwh;
        final double dgToLoadKwh;
        final double btToLoadKwh;
        final double fuelLiters;

        final double[] windToLoadByBus;
        final double[] dgToLoadByBus;
        final double[] btNetByBus;
        final double[] defByBus;

        SectionalClosedResult(double loadKwh,
                              double ensKwh,
                              double wreKwh,
                              double wtToLoadKwh,
                              double dgToLoadKwh,
                              double btToLoadKwh,
                              double fuelLiters,
                              double[] windToLoadByBus,
                              double[] dgToLoadByBus,
                              double[] btNetByBus,
                              double[] defByBus) {
            this.loadKwh = loadKwh;
            this.ensKwh = ensKwh;
            this.wreKwh = wreKwh;
            this.wtToLoadKwh = wtToLoadKwh;
            this.dgToLoadKwh = dgToLoadKwh;
            this.btToLoadKwh = btToLoadKwh;
            this.fuelLiters = fuelLiters;
            this.windToLoadByBus = windToLoadByBus;
            this.dgToLoadByBus = dgToLoadByBus;
            this.btNetByBus = btNetByBus;
            this.defByBus = defByBus;
        }
    }

    private static void dispatchOneBusOneHour(
            SystemParameters sp,
            PowerBus bus,
            boolean busAlive,
            int b,
            double loadKw,
            double windV,
            double cat1,
            double cat2,
            boolean considerDegradation,
            boolean considerChargeByDg,
            boolean considerRotationReserve,
            double dgRatedKw,
            double dgMaxKw,
            double dgMinKw,
            double perDgOptimalKw,
            double dgStartDelayHours,
            Totals totals,
            double[] hourWreRef,
            TraceSession trace
    ) {

        totals.loadKwh += loadKw;

        if (!busAlive) {
            stopAllDieselsOnBus(bus);

            final double defKw = loadKw;
            totals.ensKwh += defKw;

            if (trace.enabled()) {
                trace.setBusDown(b, loadKw, defKw);
                trace.fillDgState(b, bus);
                trace.fillBatteryState(b, bus.getBattery());
            }

            return;
        }

        bus.addWorkTime(1);

        // wind potential
        final double windPotentialKw = computeWindPotential(bus, windV);

        final Battery battery = bus.getBattery();
        final boolean btAvail = battery != null && battery.isAvailable();

        // per-bus hour variables
        double windToLoadKw = 0.0;
        double dgProducedKw = 0.0;
        double dgToLoadKwLocal = 0.0;
        double btNetKw = 0.0; // >0 discharge, <0 charge
        double wreLocal = 0.0;

        if (windPotentialKw >= loadKw - SimulationConstants.EPSILON) {
            // ===== Wind surplus case =====
            windToLoadKw = loadKw;

            double surplusKw = Math.max(0.0, windPotentialKw - loadKw);

            // charge battery from wind
            if (btAvail && battery.getStateOfCharge() < SimulationConstants.BATTERY_MAX_SOC) {
                double chargeCapKw = battery.getChargeCapacity(sp);
                double chargeKw = Math.min(surplusKw, chargeCapKw);
                if (chargeKw > SimulationConstants.EPSILON) {
                    battery.adjustCapacity(battery, chargeKw, chargeKw, false, considerDegradation);
                    btNetKw -= chargeKw;
                    surplusKw -= chargeKw;
                }
            }

            wreLocal = Math.max(0.0, surplusKw);

            // DG idle reserve in wind surplus
            applyIdleReserveInWindSurplus(
                    bus,
                    sp,
                    loadKw,
                    windToLoadKw,
                    cat1,
                    cat2,
                    btAvail,
                    battery,
                    dgRatedKw,
                    dgMinKw,
                    dgStartDelayHours
            );

        } else {
            // ===== Wind deficit case =====
            windToLoadKw = windPotentialKw;
            final double deficitAfterWindKw = loadKw - windToLoadKw;

            final double btDisCapKw = btAvail ? battery.getDischargeCapacity(sp) : 0.0;

            final DieselGenerator[] dgs = getSortedDgs(bus);
            final int dgCountAll = dgs.length;

            final boolean maintenanceStartedThisHour = isMaintenanceStartedThisHour(dgs);
            final double tauEff = maintenanceStartedThisHour ? 0.0 : dgStartDelayHours;

            int available = 0;
            int readyWorking = 0;
            for (DieselGenerator dg : dgs) {
                if (dg.isAvailable()) available++;
                if (dg.isWorking()) readyWorking++;
            }

            if (available == 0) {
                // only battery
                double btDisKw = btAvail ? Math.min(deficitAfterWindKw, btDisCapKw) : 0.0;
                if (btDisKw > SimulationConstants.EPSILON && btAvail) {
                    battery.adjustCapacity(battery, -btDisKw, btDisKw, false, considerDegradation);
                    btNetKw += btDisKw;
                }
            } else {

                final boolean canUseOptimal = (perDgOptimalKw * available >= deficitAfterWindKw);

                final int needed = canUseOptimal
                        ? (int) Math.ceil(deficitAfterWindKw / perDgOptimalKw)
                        : (int) Math.ceil(deficitAfterWindKw / dgMaxKw);

                final int dgCountPlanned = Math.min(needed, available);
                int dgToUse = dgCountPlanned;

                // выбрать минимальное i, где АКБ может покрыть старт/дефицит
                for (int i = 0; i <= dgCountPlanned; i++) {

                    double btEnergyKwh;
                    double btCurrentKw;
                    double startDefKw = 0.0;
                    double startEnergyKwh = 0.0;
                    double steadyDefKw;

                    if (i == 0) {
                        btEnergyKwh = deficitAfterWindKw;
                        btCurrentKw = deficitAfterWindKw;
                        steadyDefKw = deficitAfterWindKw;
                    } else {
                        int readyUsed = Math.min(i, readyWorking);
                        double dgPowerReadyStartKw = readyUsed * dgMaxKw;

                        startDefKw = Math.max(0.0, deficitAfterWindKw - dgPowerReadyStartKw);
                        startEnergyKwh = startDefKw * tauEff;

                        double perDgSteadyKw = canUseOptimal
                                ? Math.min(deficitAfterWindKw / i, perDgOptimalKw)
                                : Math.min(deficitAfterWindKw / i, dgMaxKw);

                        double totalSteadyKw = perDgSteadyKw * i;
                        steadyDefKw = Math.max(0.0, deficitAfterWindKw - totalSteadyKw);

                        double steadyEnergyKwh = steadyDefKw * (1.0 - tauEff);

                        btEnergyKwh = startEnergyKwh + steadyEnergyKwh;
                        btCurrentKw = Math.max(startDefKw, steadyDefKw);
                    }

                    boolean useBatteryBase = btAvail
                            && btDisCapKw > btEnergyKwh - SimulationConstants.EPSILON
                            && Battery.useBattery(sp, battery, btEnergyKwh, btDisCapKw);

                    // IMPORTANT: логика как у пользователя (без условия startEnergy > EPSILON)
                    boolean allowStartBridge = (i > 0)
                            && btAvail
                            && (steadyDefKw <= SimulationConstants.EPSILON)
                            && (btDisCapKw > startEnergyKwh - SimulationConstants.EPSILON);

                    boolean useBattery = useBatteryBase || allowStartBridge;

                    if (useBattery) {
                        double dischargeEnergyKwh = btEnergyKwh;
                        double dischargeCurrentKw = btCurrentKw;

                        if (allowStartBridge && !useBatteryBase) {
                            dischargeEnergyKwh = startEnergyKwh;
                            dischargeCurrentKw = startDefKw;
                        }

                        if (dischargeEnergyKwh > SimulationConstants.EPSILON) {
                            battery.adjustCapacity(battery, -dischargeEnergyKwh, dischargeCurrentKw, false, considerDegradation);
                            btNetKw += dischargeEnergyKwh;
                        }

                        dgToUse = i;
                        break;
                    }

                    if (i == dgCountPlanned) {
                        double btDisKw = btAvail ? Math.min(deficitAfterWindKw, btDisCapKw) : 0.0;
                        if (btDisKw > SimulationConstants.EPSILON && btAvail) {
                            battery.adjustCapacity(battery, -btDisKw, btDisKw, false, considerDegradation);
                            btNetKw += btDisKw;
                        }
                        dgToUse = dgCountPlanned;
                    }
                }

                // ---- распределение по ДГУ (пуск + steady) + low-load + прожиг ----
                int R = Math.min(readyWorking, dgToUse);

                double readyMaxStartKw = R * dgMaxKw;
                double readyLoadStartKw = Math.min(deficitAfterWindKw, readyMaxStartKw);
                double perReadyStartKw = (R > 0) ? (readyLoadStartKw / R) : 0.0;

                double perDgSteadyKw = 0.0;
                if (dgToUse > 0) {
                    perDgSteadyKw = canUseOptimal
                            ? (deficitAfterWindKw / dgToUse)
                            : Math.min(deficitAfterWindKw / dgToUse, dgMaxKw);

                    if (canUseOptimal && perDgSteadyKw > perDgOptimalKw) perDgSteadyKw = perDgOptimalKw;
                }

                boolean canCharge = btAvail
                        && battery.getStateOfCharge() < SimulationConstants.BATTERY_MAX_SOC - SimulationConstants.EPSILON;

                double chargeCapKw = 0.0;
                if (canCharge) {
                    chargeCapKw = battery.getChargeCapacity(sp);
                    canCharge = chargeCapKw > SimulationConstants.EPSILON;
                }

                int used = 0;
                double sumDieselKw = 0.0;
                boolean anyBurnThisHour = false;

                for (int k = 0; k < dgCountAll; k++) {
                    DieselGenerator dg = dgs[k];

                    if (!dg.isAvailable() || used >= dgToUse) {
                        dg.setCurrentLoad(0.0);
                        dg.setIdle(false);
                        continue;
                    }

                    boolean wasWorking = dg.isWorking();

                    double genKw = wasWorking
                            ? (perReadyStartKw * tauEff + perDgSteadyKw * (1.0 - tauEff))
                            : (perDgSteadyKw * (1.0 - tauEff));

                    if (genKw + SimulationConstants.EPSILON < dgMinKw) {

                        if (dg.getIdleTime() >= SimulationConstants.DG_MAX_IDLE_HOURS) {
                            genKw = Math.max(genKw, dgMinKw);
                            anyBurnThisHour = true;

                            dg.setIdle(false);
                            dg.resetIdleTime();
                        } else {
                            dg.incrementIdleTime();
                            dg.setIdle(true);
                        }

                    } else {
                        dg.setIdle(false);
                        dg.resetIdleTime();
                    }

                    if (genKw > dgMaxKw) genKw = dgMaxKw;

                    dg.setCurrentLoad(genKw);
                    dg.addWorkTime(1, wasWorking ? 1 : 6);
                    dg.startWork();

                    sumDieselKw += genKw;
                    used++;
                }

                // ===== IDLE в дефиците ветра =====
                if (windToLoadKw > SimulationConstants.EPSILON) {
                    applyIdleReserveInWindDeficit(
                            dgs,
                            loadKw,
                            windToLoadKw,
                            cat1,
                            cat2,
                            btAvail,
                            battery,
                            sp,
                            tauEff,
                            btAvail ? battery.getDischargeCapacity(sp) : 0.0,
                            dgRatedKw,
                            dgMinKw,
                            dgMaxKw
                    );
                }

                // ===== ROTATING RESERVE (N-1) =====
                if (considerRotationReserve) {
                    sumDieselKw = applyRotationReserveNminus1(
                            dgs,
                            loadKw,
                            windToLoadKw,
                            btNetKw,
                            btAvail,
                            battery,
                            sp,
                            tauEff,
                            dgMaxKw,
                            dgMinKw,
                            sumDieselKw
                    );
                }

                // ===== Финализация статусов ДГУ за час =====
                finalizeStoppedDgs(dgs);

                dgProducedKw = sumDieselKw;

                // ---- заряд от ДГУ ----
                boolean allowChargeNow = canCharge && (considerChargeByDg || anyBurnThisHour);

                double btDisToLoadKw = Math.max(0.0, btNetKw);
                double needFromDieselToLoadKw = loadKw - windToLoadKw - btDisToLoadKw;
                if (needFromDieselToLoadKw < 0.0) needFromDieselToLoadKw = 0.0;

                double dieselSurplusKw = dgProducedKw - needFromDieselToLoadKw;
                if (dieselSurplusKw < 0.0) dieselSurplusKw = 0.0;

                double extraForChargeKw = 0.0;
                if (allowChargeNow && dieselSurplusKw > SimulationConstants.EPSILON) {
                    double ch = Math.min(dieselSurplusKw, chargeCapKw);
                    if (ch > SimulationConstants.EPSILON) {
                        battery.adjustCapacity(battery, +ch, ch, true, considerDegradation);
                        btNetKw -= ch;
                        extraForChargeKw = ch;
                    }
                }

                dgToLoadKwLocal = Math.max(0.0, dgProducedKw - extraForChargeKw);
            }
        }

        // ---- Fuel per hour ----
        totals.fuelLiters += computeFuelLitersOneHour(bus.getDieselGenerators(), dgRatedKw);

        // ---- totals ----
        double btDisToLoad = Math.max(0.0, btNetKw);

        totals.wtToLoadKwh += windToLoadKw;
        totals.dgToLoadKwh += dgToLoadKwLocal;
        totals.btToLoadKwh += btDisToLoad;
        totals.wreKwh += wreLocal;

        if (hourWreRef != null) hourWreRef[0] += wreLocal;

        double suppliedKw = windToLoadKw + dgToLoadKwLocal + btDisToLoad;
        double defKw = loadKw - suppliedKw;
        if (defKw < 0.0) defKw = 0.0;
        totals.ensKwh += defKw;

        if (trace.enabled()) {
            trace.setBusValues(
                    b,
                    true,
                    loadKw,
                    windToLoadKw,
                    dgToLoadKwLocal,
                    btNetKw,
                    defKw
            );
            trace.fillDgState(b, bus);
            trace.fillBatteryState(b, bus.getBattery());
        }

    }

    private static double[] computeEffectiveLoadsForSectional(SystemParameters sp,
                                                              List<PowerBus> buses,
                                                              boolean[] busAlive,
                                                              int t,
                                                              double cat1,
                                                              double cat2) {
        double[] out = new double[buses.size()];
        for (int i = 0; i < buses.size(); i++) out[i] = buses.get(i).getLoadKw()[t];

        if (buses.size() != 2) return out;
        if (busAlive[0] == busAlive[1]) return out;

        int dead = busAlive[0] ? 1 : 0;
        int live = busAlive[0] ? 0 : 1;

        PowerBus deadBus = buses.get(dead);
        int busRepairTime = sp.getBusRepairTimeHours();
        boolean firstRepairHour = (deadBus.getRepairDurationHours() == busRepairTime);

        double ratio = firstRepairHour ? cat1 : (cat1 + cat2);
        double transfer = out[dead] * ratio;

        out[dead] = Math.max(0.0, out[dead] - transfer);
        out[live] += transfer;
        return out;
    }

    private static boolean shouldCloseTieBreakerThisHour(SystemParameters sp,
                                                         List<PowerBus> buses,
                                                         double[] loads,
                                                         double windV,
                                                         double dgMaxKw) {
        for (int b = 0; b < 2; b++) {
            PowerBus bus = buses.get(b);
            double load = loads[b];

            double windPot = windPotentialNoSideEffects(bus, windV);
            double dgPot = dieselPotential(bus, dgMaxKw);

            Battery bt = bus.getBattery();
            double btPot = (bt != null && bt.isAvailable()) ? bt.getDischargeCapacity(sp) : 0.0;

            if (windPot + dgPot + btPot + SimulationConstants.EPSILON < load) return true;
        }
        return false;
    }

    private static double windPotentialNoSideEffects(PowerBus bus, double windV) {
        double pot = 0.0;
        for (WindTurbine wt : bus.getWindTurbines()) {
            if (wt.isAvailable()) pot += wt.getPotentialGenerationKw(windV);
        }
        return pot;
    }

    private static double computeWindPotential(PowerBus bus, double windV) {
        double pot = 0.0;
        for (WindTurbine wt : bus.getWindTurbines()) {
            if (!wt.isAvailable()) continue;
            pot += wt.getPotentialGenerationKw(windV);
        }
        return pot;
    }

    private static double dieselPotential(PowerBus bus, double dgMaxKw) {
        double pot = 0.0;
        for (DieselGenerator dg : bus.getDieselGenerators()) {
            if (dg.isAvailable()) pot += dgMaxKw;
        }
        return pot;
    }

    private static DieselGenerator[] getSortedDgs(List<DieselGenerator> dgList) {
        int n = dgList.size();
        DieselGenerator[] arr = new DieselGenerator[n];
        for (int i = 0; i < n; i++) arr[i] = dgList.get(i);
        Arrays.sort(arr, DieselGenerator.DISPATCH_COMPARATOR);
        return arr;
    }

    private static SectionalClosedResult dispatchSectionalClosedOneHour(
            SystemParameters sp,
            List<PowerBus> buses,
            double[] loadByBus,
            double windV,
            boolean considerDegradation,
            boolean considerChargeByDg,
            boolean considerRotationReserve,
            double cat1,
            double cat2,
            double dgRatedKw,
            double dgMaxKw,
            double dgMinKw,
            double perDgOptimalKw,
            double dgStartDelayHours
    ) {
        // Both buses must be alive here.
        PowerBus b0 = buses.get(0);
        PowerBus b1 = buses.get(1);
        b0.addWorkTime(1);
        b1.addWorkTime(1);

        double load0 = loadByBus[0];
        double load1 = loadByBus[1];
        double totalLoad = load0 + load1;

        double windPot0 = computeWindPotential(b0, windV);
        double windPot1 = computeWindPotential(b1, windV);
        double windPot = windPot0 + windPot1;

        double[] windToLoad = new double[2];
        if (totalLoad > SimulationConstants.EPSILON) {
            windToLoad[0] = Math.min(load0, windPot * (load0 / totalLoad));
            windToLoad[1] = Math.min(load1, windPot * (load1 / totalLoad));
        }
        // ensure not exceeding windPot
        double usedWind = windToLoad[0] + windToLoad[1];
        if (usedWind > windPot) {
            double k = windPot / usedWind;
            windToLoad[0] *= k;
            windToLoad[1] *= k;
            usedWind = windPot;
        }

        double rem0 = Math.max(0.0, load0 - windToLoad[0]);
        double rem1 = Math.max(0.0, load1 - windToLoad[1]);

        Battery bt0 = b0.getBattery();
        Battery bt1 = b1.getBattery();
        boolean bt0Avail = bt0 != null && bt0.isAvailable();
        boolean bt1Avail = bt1 != null && bt1.isAvailable();

        double bt0DisCap = bt0Avail ? bt0.getDischargeCapacity(sp) : 0.0;
        double bt1DisCap = bt1Avail ? bt1.getDischargeCapacity(sp) : 0.0;

        double[] btNet = new double[2]; // >0 discharge, <0 charge

        // discharge own first
        double dis0 = bt0Avail ? Math.min(rem0, bt0DisCap) : 0.0;
        if (dis0 > SimulationConstants.EPSILON && bt0Avail) {
            bt0.adjustCapacity(bt0, -dis0, dis0, false, considerDegradation);
            btNet[0] += dis0;
            rem0 -= dis0;
            bt0DisCap -= dis0;
        }
        double dis1 = bt1Avail ? Math.min(rem1, bt1DisCap) : 0.0;
        if (dis1 > SimulationConstants.EPSILON && bt1Avail) {
            bt1.adjustCapacity(bt1, -dis1, dis1, false, considerDegradation);
            btNet[1] += dis1;
            rem1 -= dis1;
            bt1DisCap -= dis1;
        }

        // then cross-feed batteries if still deficit
        if (rem0 > SimulationConstants.EPSILON && bt1Avail && bt1DisCap > SimulationConstants.EPSILON) {
            double x = Math.min(rem0, bt1DisCap);
            bt1.adjustCapacity(bt1, -x, x, false, considerDegradation);
            btNet[1] += x;
            rem0 -= x;
            bt1DisCap -= x;
        }
        if (rem1 > SimulationConstants.EPSILON && bt0Avail && bt0DisCap > SimulationConstants.EPSILON) {
            double x = Math.min(rem1, bt0DisCap);
            bt0.adjustCapacity(bt0, -x, x, false, considerDegradation);
            btNet[0] += x;
            rem1 -= x;
            bt0DisCap -= x;
        }

        double btDisToLoadTotal = Math.max(0.0, btNet[0]) + Math.max(0.0, btNet[1]);

        double deficitAfterWindBt = rem0 + rem1;

        // dispatch DGs on combined list
        List<DieselGenerator> allDgs = new ArrayList<>();
        allDgs.addAll(b0.getDieselGenerators());
        allDgs.addAll(b1.getDieselGenerators());
        DieselGenerator[] dgs = getSortedDgs(allDgs);

        int available = 0;
        int readyWorking = 0;
        for (DieselGenerator dg : dgs) {
            if (dg.isAvailable()) available++;
            if (dg.isWorking()) readyWorking++;
        }

        double dgProducedKw = 0.0;
        double dgToLoadTotal = 0.0;
        double wre = 0.0;

        if (windPot >= totalLoad - SimulationConstants.EPSILON) {
            // wind already covers total load; remaining is WRE after battery charge below
            double surplusKw = Math.max(0.0, windPot - totalLoad);

            // charge own batteries first from wind surplus
            if (bt0Avail && bt0.getStateOfCharge() < SimulationConstants.BATTERY_MAX_SOC - SimulationConstants.EPSILON) {
                double cap = bt0.getChargeCapacity(sp);
                double ch = Math.min(surplusKw, cap);
                if (ch > SimulationConstants.EPSILON) {
                    bt0.adjustCapacity(bt0, +ch, ch, false, considerDegradation);
                    btNet[0] -= ch;
                    surplusKw -= ch;
                }
            }
            if (bt1Avail && bt1.getStateOfCharge() < SimulationConstants.BATTERY_MAX_SOC - SimulationConstants.EPSILON) {
                double cap = bt1.getChargeCapacity(sp);
                double ch = Math.min(surplusKw, cap);
                if (ch > SimulationConstants.EPSILON) {
                    bt1.adjustCapacity(bt1, +ch, ch, false, considerDegradation);
                    btNet[1] -= ch;
                    surplusKw -= ch;
                }
            }

            wre = Math.max(0.0, surplusKw);

            // keep idle reserve (use per-bus helper for both buses)
            applyIdleReserveInWindSurplus(b0, sp, load0, windToLoad[0], cat1, cat2, bt0Avail, bt0, dgRatedKw, dgMinKw, dgStartDelayHours);
            applyIdleReserveInWindSurplus(b1, sp, load1, windToLoad[1], cat1, cat2, bt1Avail, bt1, dgRatedKw, dgMinKw, dgStartDelayHours);

        } else {
            if (available > 0 && deficitAfterWindBt > SimulationConstants.EPSILON) {

                final boolean canUseOptimal = (perDgOptimalKw * available >= deficitAfterWindBt);
                final int needed = canUseOptimal
                        ? (int) Math.ceil(deficitAfterWindBt / perDgOptimalKw)
                        : (int) Math.ceil(deficitAfterWindBt / dgMaxKw);

                final int dgCountPlanned = Math.min(needed, available);
                int dgToUse = dgCountPlanned;

                final boolean maintenanceStartedThisHour = isMaintenanceStartedThisHour(dgs);
                final double tauEff = maintenanceStartedThisHour ? 0.0 : dgStartDelayHours;

                // we do not use batteries here for start bridging: it was already discharged above in a prioritized way.

                int R = Math.min(readyWorking, dgToUse);

                double readyMaxStartKw = R * dgMaxKw;
                double readyLoadStartKw = Math.min(deficitAfterWindBt, readyMaxStartKw);
                double perReadyStartKw = (R > 0) ? (readyLoadStartKw / R) : 0.0;

                double perDgSteadyKw = 0.0;
                if (dgToUse > 0) {
                    perDgSteadyKw = canUseOptimal
                            ? (deficitAfterWindBt / dgToUse)
                            : Math.min(deficitAfterWindBt / dgToUse, dgMaxKw);
                    if (canUseOptimal && perDgSteadyKw > perDgOptimalKw) perDgSteadyKw = perDgOptimalKw;
                }

                boolean anyBurnThisHour = false;
                double sumDieselKw = 0.0;
                int used = 0;

                for (int k = 0; k < dgs.length; k++) {
                    DieselGenerator dg = dgs[k];
                    if (!dg.isAvailable() || used >= dgToUse) {
                        dg.setCurrentLoad(0.0);
                        dg.setIdle(false);
                        continue;
                    }

                    boolean wasWorking = dg.isWorking();
                    double genKw = wasWorking
                            ? (perReadyStartKw * tauEff + perDgSteadyKw * (1.0 - tauEff))
                            : (perDgSteadyKw * (1.0 - tauEff));

                    if (genKw + SimulationConstants.EPSILON < dgMinKw) {
                        if (dg.getIdleTime() >= SimulationConstants.DG_MAX_IDLE_HOURS) {
                            genKw = Math.max(genKw, dgMinKw);
                            anyBurnThisHour = true;
                            dg.setIdle(false);
                            dg.resetIdleTime();
                        } else {
                            dg.incrementIdleTime();
                            dg.setIdle(true);
                        }
                    } else {
                        dg.setIdle(false);
                        dg.resetIdleTime();
                    }

                    if (genKw > dgMaxKw) genKw = dgMaxKw;

                    dg.setCurrentLoad(genKw);
                    dg.addWorkTime(1, wasWorking ? 1 : 6);
                    dg.startWork();

                    sumDieselKw += genKw;
                    used++;
                }

                if (usedWind > SimulationConstants.EPSILON) {
                    // apply idle reserve using aggregated parameters (approx: treat as one bus)
                    // Use total load and usedWind; batteries are already considered via their capacities.
                    // Here we conservatively pass no battery to avoid double counting.
                    applyIdleReserveInWindDeficit(
                            dgs,
                            totalLoad,
                            usedWind,
                            cat1,
                            cat2,
                            false,
                            null,
                            sp,
                            tauEff,
                            0.0,
                            dgRatedKw,
                            dgMinKw,
                            dgMaxKw
                    );
                }

                if (considerRotationReserve) {
                    // rotation reserve: allow batteries as firm via their remaining discharge capacity
                    double btRemainCap = 0.0;
                    if (bt0Avail) btRemainCap += bt0.getDischargeCapacity(sp);
                    if (bt1Avail) btRemainCap += bt1.getDischargeCapacity(sp);

                    // we cannot pass two batteries into existing helper; use a conservative approach: no battery
                    sumDieselKw = applyRotationReserveNminus1(
                            dgs,
                            totalLoad,
                            usedWind,
                            btDisToLoadTotal,
                            false,
                            null,
                            sp,
                            tauEff,
                            dgMaxKw,
                            dgMinKw,
                            sumDieselKw
                    );
                }

                finalizeStoppedDgs(dgs);

                dgProducedKw = sumDieselKw;

                // distribute diesel to remaining load
                double needDieselToLoad = deficitAfterWindBt;
                dgToLoadTotal = Math.min(needDieselToLoad, dgProducedKw);

                // charge from DG surplus (to both batteries, own first)
                double dieselSurplus = Math.max(0.0, dgProducedKw - dgToLoadTotal);
                boolean allowChargeNow = considerChargeByDg || anyBurnThisHour;

                if (allowChargeNow && dieselSurplus > SimulationConstants.EPSILON) {
                    if (bt0Avail && bt0.getStateOfCharge() < SimulationConstants.BATTERY_MAX_SOC - SimulationConstants.EPSILON) {
                        double cap = bt0.getChargeCapacity(sp);
                        double ch = Math.min(dieselSurplus, cap);
                        if (ch > SimulationConstants.EPSILON) {
                            bt0.adjustCapacity(bt0, +ch, ch, true, considerDegradation);
                            btNet[0] -= ch;
                            dieselSurplus -= ch;
                        }
                    }
                    if (bt1Avail && bt1.getStateOfCharge() < SimulationConstants.BATTERY_MAX_SOC - SimulationConstants.EPSILON) {
                        double cap = bt1.getChargeCapacity(sp);
                        double ch = Math.min(dieselSurplus, cap);
                        if (ch > SimulationConstants.EPSILON) {
                            bt1.adjustCapacity(bt1, +ch, ch, true, considerDegradation);
                            btNet[1] -= ch;
                            dieselSurplus -= ch;
                        }
                    }
                }
            }
        }

        // compute per-bus diesel-to-load allocation proportional to remaining needs
        double[] dgToLoad = new double[2];
        double need0 = Math.max(0.0, load0 - windToLoad[0] - Math.max(0.0, btNet[0]));
        double need1 = Math.max(0.0, load1 - windToLoad[1] - Math.max(0.0, btNet[1]));
        double needSum = need0 + need1;
        if (needSum > SimulationConstants.EPSILON) {
            dgToLoad[0] = dgToLoadTotal * (need0 / needSum);
            dgToLoad[1] = dgToLoadTotal * (need1 / needSum);
        }

        // deficits per bus
        double[] def = new double[2];
        double supplied0 = windToLoad[0] + dgToLoad[0] + Math.max(0.0, btNet[0]);
        double supplied1 = windToLoad[1] + dgToLoad[1] + Math.max(0.0, btNet[1]);
        def[0] = Math.max(0.0, load0 - supplied0);
        def[1] = Math.max(0.0, load1 - supplied1);

        double ens = def[0] + def[1];

        double fuel = computeFuelLitersOneHour(b0.getDieselGenerators(), dgRatedKw)
                + computeFuelLitersOneHour(b1.getDieselGenerators(), dgRatedKw);

        return new SectionalClosedResult(
                totalLoad,
                ens,
                wre,
                windToLoad[0] + windToLoad[1],
                dgToLoad[0] + dgToLoad[1],
                Math.max(0.0, btNet[0]) + Math.max(0.0, btNet[1]),
                fuel,
                windToLoad,
                dgToLoad,
                btNet,
                def
        );
    }

    private static DieselGenerator[] getSortedDgs(PowerBus bus) {
        List<DieselGenerator> dgList = bus.getDieselGenerators();
        int n = dgList.size();

        DieselGenerator[] buf = DG_SORT_BUF.get();
        if (buf == null || buf.length != n) {
            buf = new DieselGenerator[n];
            DG_SORT_BUF.set(buf);
        }
        for (int i = 0; i < n; i++) buf[i] = dgList.get(i);

        Arrays.sort(buf, DieselGenerator.DISPATCH_COMPARATOR);
        return buf;
    }

    private static void stopAllDieselsOnBus(PowerBus bus) {
        for (DieselGenerator dg : bus.getDieselGenerators()) {
            if (!dg.isAvailable()) {
                // если недоступен — приводим к безопасному состоянию
                hardStopDg(dg);
                continue;
            }

            dg.stopWork();
            dg.setCurrentLoad(0.0);
            dg.setIdle(false);
            dg.resetIdleTime();
        }
    }


    private static boolean isMaintenanceStartedThisHour(DieselGenerator[] dgs) {
        for (DieselGenerator dg : dgs) {
            if (!dg.isAvailable()) continue;
            if (dg.isInMaintenance() && dg.getRepairTimeHours() == 4) return true;
        }
        return false;
    }

    private static void applyIdleReserveInWindSurplus(
            PowerBus bus,
            SystemParameters sp,
            double loadKw,
            double windToLoadKw,
            double cat1,
            double cat2,
            boolean btAvail,
            Battery battery,
            double dgRatedKw,
            double dgMinKw,
            double tau
    ) {
        DieselGenerator[] dgs = getSortedDgs(bus);
        int dgCountAll = dgs.length;

        int available = 0;
        for (DieselGenerator dg : dgs) if (dg.isAvailable()) available++;

        boolean[] keepOn = new boolean[dgCountAll];

        double pCrit = loadKw * (cat1 + SimulationConstants.DG_IDLE_K2 * cat2);
        double windLoss = Math.min(windToLoadKw, pCrit);

        double btFirm = 0.0;
        if (btAvail) {
            double btDisCap = battery.getDischargeCapacity(sp);
            if (canBatteryBridge(battery, sp, windLoss, tau, btDisCap)) {
                btFirm = windLoss;
            } else {
                btFirm = btDisCap;
            }
        }

        double reserveNeed = windLoss - btFirm;
        reserveNeed += windLoss * SimulationConstants.DG_IDLE_MARGIN_PCT;
        if (reserveNeed < 0.0) reserveNeed = 0.0;

        int idleNeed = (reserveNeed > SimulationConstants.EPSILON)
                ? (int) Math.ceil(reserveNeed / dgRatedKw)
                : 0;
        if (idleNeed > available) idleNeed = available;

        // 1) сначала уже working
        for (int k = 0; k < dgCountAll && idleNeed > 0; k++) {
            DieselGenerator dg = dgs[k];

            if (!dg.isAvailable()) {
                hardStopDg(dg);
                continue;
            }
            if (!dg.isWorking()) continue;

            double genKw = idleOrBurnGenKw(dg, dgRatedKw, dgMinKw);
            dg.setCurrentLoad(genKw);
            dg.addWorkTime(1, 1);
            dg.startWork();

            keepOn[k] = true;
            idleNeed--;
        }

        // 2) запуск новых
        for (int k = 0; k < dgCountAll && idleNeed > 0; k++) {
            DieselGenerator dg = dgs[k];

            if (!dg.isAvailable()) {
                hardStopDg(dg);
                continue;
            }
            if (dg.isWorking()) continue;

            dg.startWork();

            double genKw = idleOrBurnGenKw(dg, dgRatedKw, dgMinKw);
            dg.setCurrentLoad(genKw);
            dg.addWorkTime(1, 6);

            keepOn[k] = true;
            idleNeed--;
        }

        // финализация
        for (int k = 0; k < dgCountAll; k++) {
            DieselGenerator dg = dgs[k];

            if (!dg.isAvailable()) {
                hardStopDg(dg);
                continue;
            }
            if (keepOn[k]) continue;

            dg.setCurrentLoad(0.0);
            dg.stopWork();
            dg.setIdle(false);
            dg.resetIdleTime();
        }
    }

    private static void applyIdleReserveInWindDeficit(
            DieselGenerator[] dgs,
            double loadKw,
            double windToLoadKw,
            double cat1,
            double cat2,
            boolean btAvail,
            Battery battery,
            SystemParameters sp,
            double tauEff,
            double btDisCapKw,
            double dgRatedKw,
            double dgMinKw,
            double dgMaxKw
    ) {
        int dgCountAll = dgs.length;

        double pCrit = loadKw * (cat1 + SimulationConstants.DG_IDLE_K2 * cat2);
        double windLoss = Math.min(windToLoadKw, pCrit);

        double btFirm = 0.0;
        if (btAvail) {
            if (canBatteryBridge(battery, sp, windLoss, tauEff, btDisCapKw)) {
                btFirm = windLoss;
            } else {
                btFirm = btDisCapKw;
            }
        }

        double dgFirm = 0.0;
        for (DieselGenerator dg : dgs) {
            if (!dg.isAvailable()) continue;
            if (!dg.isWorking()) continue;
            if (dg.getCurrentLoad() < -SimulationConstants.EPSILON) continue;
            dgFirm += dgMaxKw;
        }

        double reserveNeed = windLoss - (btFirm + dgFirm);
        reserveNeed += windLoss * SimulationConstants.DG_IDLE_MARGIN_PCT;
        if (reserveNeed < 0.0) reserveNeed = 0.0;

        int idleNeed = (reserveNeed > SimulationConstants.EPSILON)
                ? (int) Math.ceil(reserveNeed / dgRatedKw)
                : 0;

        int idleCapable = 0;
        for (DieselGenerator dg : dgs) {
            if (!dg.isAvailable()) continue;
            if (dg.getCurrentLoad() > SimulationConstants.EPSILON) continue;
            idleCapable++;
        }
        if (idleNeed > idleCapable) idleNeed = idleCapable;

        // 1) working
        for (int k = 0; k < dgCountAll && idleNeed > 0; k++) {
            DieselGenerator dg = dgs[k];
            if (!dg.isAvailable()) continue;
            if (dg.getCurrentLoad() > SimulationConstants.EPSILON) continue;
            if (!dg.isWorking()) continue;

            double genKw = idleOrBurnGenKw(dg, dgRatedKw, dgMinKw);
            dg.setCurrentLoad(genKw);
            dg.addWorkTime(1, 1);
            dg.startWork();

            idleNeed--;
        }

        // 2) start new
        for (int k = 0; k < dgCountAll && idleNeed > 0; k++) {
            DieselGenerator dg = dgs[k];
            if (!dg.isAvailable()) continue;
            if (dg.getCurrentLoad() > SimulationConstants.EPSILON) continue;
            if (dg.isWorking()) continue;

            dg.startWork();

            double genKw = idleOrBurnGenKw(dg, dgRatedKw, dgMinKw);
            dg.setCurrentLoad(genKw);
            dg.addWorkTime(1, 6);

            idleNeed--;
        }
    }

    private static double applyRotationReserveNminus1(
            DieselGenerator[] dgs,
            double loadKw,
            double windToLoadKw,
            double btNetKw,
            boolean btAvail,
            Battery battery,
            SystemParameters sp,
            double tauEff,
            double dgMaxKw,
            double dgMinKw,
            double currentSumDieselKw
    ) {
        int dgCountAll = dgs.length;

        double btDisToLoadRR = Math.max(0.0, btNetKw);
        double needFromDieselNowKw = loadKw - windToLoadKw - btDisToLoadRR;
        if (needFromDieselNowKw < 0.0) needFromDieselNowKw = 0.0;

        int onlineCount = 0;
        for (int k = 0; k < dgCountAll; k++) {
            DieselGenerator dg = dgs[k];
            if (!dg.isAvailable()) continue;
            if (Math.abs(dg.getCurrentLoad()) > SimulationConstants.EPSILON) onlineCount++;
        }

        double deficitAfterOneTripKw = needFromDieselNowKw - Math.max(0, (onlineCount - 1)) * dgMaxKw;
        if (deficitAfterOneTripKw < 0.0) deficitAfterOneTripKw = 0.0;

        boolean batteryCoversNminus1 = false;
        if (btAvail && deficitAfterOneTripKw > SimulationConstants.EPSILON) {
            double btDisCapRR = battery.getDischargeCapacity(sp);
            batteryCoversNminus1 = canBatteryBridge(battery, sp, deficitAfterOneTripKw, tauEff, btDisCapRR);
        }

        boolean nMinusOneOk = (deficitAfterOneTripKw <= SimulationConstants.EPSILON) || batteryCoversNminus1;

        if (!nMinusOneOk && needFromDieselNowKw > SimulationConstants.EPSILON) {

            int needOnline = (int) Math.ceil(needFromDieselNowKw / dgMaxKw) + 1;

            int avail = 0;
            for (int k = 0; k < dgCountAll; k++) if (dgs[k].isAvailable()) avail++;
            if (needOnline > avail) needOnline = avail;

            int add = needOnline - onlineCount;

            if (add > 0) {
                // 1) "горячие" (isWorking==true), p==0
                for (int k = 0; k < dgCountAll && add > 0; k++) {
                    DieselGenerator dg = dgs[k];
                    if (!dg.isAvailable()) continue;
                    if (!dg.isWorking()) continue;
                    if (Math.abs(dg.getCurrentLoad()) > SimulationConstants.EPSILON) continue;

                    dg.setCurrentLoad(dgMinKw);
                    dg.setIdle(false);
                    dg.resetIdleTime();

                    dg.addWorkTime(1, 1);
                    dg.startWork();

                    add--;
                    onlineCount++;
                }

                // 2) запуск новых (isWorking==false), p==0
                for (int k = 0; k < dgCountAll && add > 0; k++) {
                    DieselGenerator dg = dgs[k];
                    if (!dg.isAvailable()) continue;
                    if (dg.isWorking()) continue;
                    if (Math.abs(dg.getCurrentLoad()) > SimulationConstants.EPSILON) continue;

                    dg.startWork();

                    dg.setCurrentLoad(dgMinKw);
                    dg.setIdle(false);
                    dg.resetIdleTime();

                    dg.addWorkTime(1, 6);

                    add--;
                    onlineCount++;
                }
            }

            // перераспределяем нагрузку по всем online ДГУ (включая тех, кто был в ХХ)
            if (onlineCount > 0) {
                double per = needFromDieselNowKw / onlineCount;
                if (per > dgMaxKw) per = dgMaxKw;

                double sum = 0.0;
                int usedRR = 0;

                for (int k = 0; k < dgCountAll; k++) {
                    DieselGenerator dg = dgs[k];
                    if (!dg.isAvailable()) continue;

                    if (Math.abs(dg.getCurrentLoad()) <= SimulationConstants.EPSILON) continue; // не online
                    if (usedRR >= onlineCount) break;

                    double genKw = per;

                    if (genKw + SimulationConstants.EPSILON >= dgMinKw) {
                        dg.setIdle(false);
                        dg.resetIdleTime();
                    }

                    if (genKw > dgMaxKw) genKw = dgMaxKw;

                    dg.setCurrentLoad(genKw);
                    dg.startWork();

                    sum += genKw;
                    usedRR++;
                }

                return sum; // как в исходнике: после перераспределения sumDiesel = sum
            }
        }

        return currentSumDieselKw;
    }

    private static void finalizeStoppedDgs(DieselGenerator[] dgs) {
        for (DieselGenerator dg : dgs) {
            if (!dg.isAvailable()) continue;

            double p = dg.getCurrentLoad();
            if (Math.abs(p) > SimulationConstants.EPSILON) continue;

            dg.stopWork();
            dg.setIdle(false);
            dg.resetIdleTime();
        }
    }

    private static double idleOrBurnGenKw(DieselGenerator dg, double dgRatedKw, double dgMinKw) {
        double genKw = -0.15 * dgRatedKw;

        if (dg.getIdleTime() >= SimulationConstants.DG_MAX_IDLE_HOURS) {
            genKw = Math.max(dgMinKw, 0.0);
            dg.setIdle(false);
            dg.resetIdleTime();
        } else {
            dg.incrementIdleTime();
            dg.setIdle(true);
        }

        return genKw;
    }

    private static void hardStopDg(DieselGenerator dg) {
        dg.stopWork();
        dg.setCurrentLoad(0.0);
        dg.setIdle(false);
        dg.resetIdleTime();
    }

    private static double computeFuelLitersOneHour(List<DieselGenerator> dgs, double dgRatedKw) {
        double liters = 0.0;

        for (DieselGenerator dg : dgs) {
            if (!dg.isAvailable()) continue;

            double pSigned = dg.getCurrentLoad();
            double loadLevel = Math.abs(pSigned) / dgRatedKw;

            if (loadLevel <= SimulationConstants.EPSILON) continue;
            if (loadLevel > 1.0) loadLevel = 1.0;

            liters += fuelLitersOneHour(loadLevel, dgRatedKw);
        }

        return liters;
    }

    // ======================================================================
    // Fuel model
    // ======================================================================

    private static double fuelLitersOneHour(double loadLevel, double powerKw) {
        double k1 = K11 + (K12 / powerKw);
        double k2 = K21 + (K22 / powerKw);
        double k3 = K31 + (K32 / powerKw);

        double unitFuel = k1 * loadLevel * loadLevel + k2 * loadLevel + k3;
        double liters = 0.84 * powerKw * loadLevel * unitFuel;

        return Math.max(0.0, liters);
    }

    private static boolean canBatteryBridge(
            Battery battery,
            SystemParameters sp,
            double requiredPowerKw,
            double durationHours,
            double btDisCap
    ) {
        if (battery == null || !battery.isAvailable()) return false;
        if (requiredPowerKw <= SimulationConstants.EPSILON) return true;
        if (durationHours <= 0.0) return true;

        double requiredEnergyKwh = requiredPowerKw * durationHours;

        double maxByCurrent = battery.getMaxCapacityKwh() * sp.getMaxDischargeCurrent();
        double maxByCapacity = Math.max(
                0.0,
                (battery.getStateOfCharge() - SimulationConstants.BATTERY_MIN_SOC) * battery.getMaxCapacityKwh()
                        * SimulationConstants.BATTERY_EFFICIENCY
        );

        boolean powerOk = maxByCurrent >= requiredPowerKw;
        boolean energyOk = maxByCapacity >= requiredEnergyKwh;

        return powerOk && energyOk;
    }
}
