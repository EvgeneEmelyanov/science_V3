package simcore.engine;

import simcore.engine.diesel.DieselFleetController;
import simcore.engine.metrics.EnsAllocator;
import simcore.config.SimulationConstants;
import simcore.model.*;

/**
 * Per-bus dispatch logic extracted from SingleRunSimulator.
 * Kept 1:1 to preserve behavior; further cleanup should be done after regression is green.
 */
final class PerBusDispatcher {
    private PerBusDispatcher() {}


    static void dispatchOneBusOneHour(
            HourContext ctx,
            PowerBus bus,
            boolean busAlive,
            int b,
            double loadKw
    ) {

        ctx.totals.loadKwh += loadKw;

        if (!busAlive) {
            DieselFleetController.stopAllDieselsOnBus(bus);

            final double defKw = loadKw;
            ctx.totals.ensKwh += defKw;
            EnsAllocator.addEnsByCategoryProportional(ctx.totals, loadKw, defKw, ctx.cat1, ctx.cat2);

            if (ctx.trace.enabled()) {
                ctx.trace.setBusDown(b, loadKw, defKw);
                ctx.trace.fillDgState(b, bus);
                ctx.trace.fillBatteryState(b, bus.getBattery());
            }

            return;
        }

        bus.addWorkTime(1);

        final double windPotentialKw = SingleRunSimulator.computeWindPotential(bus, ctx.windV);

        final Battery battery = bus.getBattery();
        final boolean btAvail = battery != null && battery.isAvailable();

        double windToLoadKw = 0.0;
        double dgProducedKw = 0.0;
        double dgToLoadKwLocal = 0.0;
        double btNetKw = 0.0; // >0 discharge, <0 charge
        double wreLocal = 0.0;
        double startDelayEnsEstimateKwh = 0.0;

        if (windPotentialKw >= loadKw - SimulationConstants.EPSILON) {
            // ===== Wind surplus case =====
            windToLoadKw = loadKw;

            double surplusKw = Math.max(0.0, windPotentialKw - loadKw);

            if (btAvail && battery.getStateOfCharge() < SimulationConstants.BATTERY_MAX_SOC) {
                double chargeCapKw = battery.getChargeCapacity(ctx.sp);
                double chargeKw = Math.min(surplusKw, chargeCapKw);
                if (chargeKw > SimulationConstants.EPSILON) {
                    battery.adjustCapacity(battery, chargeKw, chargeKw, false, ctx.considerDegradation);
                    btNetKw -= chargeKw;
                    surplusKw -= chargeKw;
                }
            }

            wreLocal = Math.max(0.0, surplusKw);

            if (SingleRunSimulator.ENABLE_ZERO_LOAD_ALL_DG_READY && loadKw <= SimulationConstants.EPSILON) {
                DieselFleetController.keepAllDieselsReadyHotStandby(bus);
            } else {
                SingleRunSimulator.applyIdleReserveInWindSurplus(
                        bus,
                        ctx.sp,
                        loadKw,
                        windToLoadKw,
                        ctx.cat1,
                        ctx.cat2,
                        btAvail,
                        battery,
                        ctx.dgRatedKw,
                        ctx.dgMinKw,
                        ctx.dgStartDelayHours
                );
            }
            DieselGenerator[] dgsFinal = DieselFleetController.getSortedDgs(bus);
            SingleRunSimulator.finalizeIdleAndBurn(dgsFinal, ctx.dgMinKw);
            SingleRunSimulator.finalizeStoppedDgs(dgsFinal);

        } else {
            // ===== Wind deficit case =====
            windToLoadKw = windPotentialKw;
            final double deficitAfterWindKw = loadKw - windToLoadKw;

            final double btDisCapKw = btAvail ? battery.getDischargeCapacity(ctx.sp) : 0.0;

            final DieselGenerator[] dgs = DieselFleetController.getSortedDgs(bus);
            final int dgCountAll = dgs.length;

            final boolean maintenanceStartedThisHour = DieselFleetController.isMaintenanceStartedThisHour(dgs);
            final double tauEff = maintenanceStartedThisHour ? 0.0 : ctx.dgStartDelayHours;

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
                    battery.adjustCapacity(battery, -btDisKw, btDisKw, false, ctx.considerDegradation);
                    btNetKw += btDisKw;
                }
            } else {

                final boolean canUseOptimal = (ctx.perDgOptimalKw * available >= deficitAfterWindKw);

                final int needed = canUseOptimal
                        ? (int) Math.ceil(deficitAfterWindKw / ctx.perDgOptimalKw)
                        : (int) Math.ceil(deficitAfterWindKw / ctx.dgMaxKw);

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
                        double dgPowerReadyStartKw = readyUsed * ctx.dgMaxKw;

                        startDefKw = Math.max(0.0, deficitAfterWindKw - dgPowerReadyStartKw);
                        startEnergyKwh = startDefKw * tauEff;

                        double perDgSteadyKw = canUseOptimal
                                ? Math.min(deficitAfterWindKw / i, ctx.perDgOptimalKw)
                                : Math.min(deficitAfterWindKw / i, ctx.dgMaxKw);

                        double totalSteadyKw = perDgSteadyKw * i;
                        steadyDefKw = Math.max(0.0, deficitAfterWindKw - totalSteadyKw);

                        double steadyEnergyKwh = steadyDefKw * (1.0 - tauEff);

                        btEnergyKwh = startEnergyKwh + steadyEnergyKwh;
                        btCurrentKw = Math.max(startDefKw, steadyDefKw);
                    }

                    boolean useBatteryBase =
                            btAvail
                                    && SingleRunSimulator.canBatteryBridge(battery, ctx.sp, btCurrentKw, 1.0, btDisCapKw)
                                    && Battery.useBattery(ctx.sp, battery, btEnergyKwh, btDisCapKw);

                    boolean allowStartBridge =
                            (i > 0)
                                    && btAvail
                                    && (steadyDefKw <= SimulationConstants.EPSILON)
                                    && SingleRunSimulator.canBatteryBridge(battery, ctx.sp, startDefKw, tauEff, btDisCapKw);

                    boolean useBattery = useBatteryBase || allowStartBridge;

                    if (useBattery) {
                        double dischargeEnergyKwh = btEnergyKwh;
                        double dischargeCurrentKw = btCurrentKw;

                        if (allowStartBridge && !useBatteryBase) {
                            dischargeEnergyKwh = startEnergyKwh;
                            dischargeCurrentKw = startDefKw;
                        }

                        if (dischargeEnergyKwh > SimulationConstants.EPSILON) {
                            battery.adjustCapacity(battery, -dischargeEnergyKwh, dischargeCurrentKw, false, ctx.considerDegradation);
                            btNetKw += dischargeEnergyKwh;
                        }

                        dgToUse = i;
                        break;
                    }

                    if (i == dgCountPlanned) {

                        dgToUse = dgCountPlanned;

                        if (btAvail) {

                            final double maxByCurrentKw =
                                    battery.getMaxCapacityKwh() * ctx.sp.getMaxDischargeCurrent();

                            double soc = battery.getStateOfCharge();
                            double availEnergyKwh = Math.max(0.0, soc) * battery.getMaxCapacityKwh();

                            int R = Math.min(readyWorking, dgToUse);
                            double readyMaxStartKw = R * ctx.dgMaxKw;

                            startDefKw = Math.max(0.0, deficitAfterWindKw - readyMaxStartKw);
                            startEnergyKwh = startDefKw * tauEff;

                            if (startEnergyKwh > SimulationConstants.EPSILON && tauEff > SimulationConstants.EPSILON) {

                                double maxStartEnergyByCurrentKwh = maxByCurrentKw * tauEff;

                                double dischargeStartKwh = Math.min(startEnergyKwh, maxStartEnergyByCurrentKwh);
                                dischargeStartKwh = Math.min(dischargeStartKwh, availEnergyKwh);

                                if (dischargeStartKwh > SimulationConstants.EPSILON) {
                                    double dischargeStartKw = dischargeStartKwh / tauEff;

                                    battery.adjustCapacity(
                                            battery,
                                            -dischargeStartKwh,
                                            dischargeStartKw,
                                            false,
                                            ctx.considerDegradation
                                    );

                                    btNetKw += dischargeStartKwh;
                                    availEnergyKwh -= dischargeStartKwh;
                                }
                            }

                            double steadyDur = 1.0 - tauEff;

                            if (steadyDur > SimulationConstants.EPSILON) {

                                double dgSteadyMaxKw = dgToUse * ctx.dgMaxKw;

                                steadyDefKw = Math.max(0.0, deficitAfterWindKw - dgSteadyMaxKw);

                                double steadyNeedEnergyKwh = steadyDefKw * steadyDur;

                                if (steadyNeedEnergyKwh > SimulationConstants.EPSILON) {

                                    double maxSteadyEnergyByCurrentKwh = maxByCurrentKw * steadyDur;

                                    double dischargeSteadyKwh = Math.min(steadyNeedEnergyKwh, maxSteadyEnergyByCurrentKwh);
                                    dischargeSteadyKwh = Math.min(dischargeSteadyKwh, availEnergyKwh);

                                    if (dischargeSteadyKwh > SimulationConstants.EPSILON) {
                                        double dischargeSteadyKw = dischargeSteadyKwh / steadyDur;

                                        battery.adjustCapacity(
                                                battery,
                                                -dischargeSteadyKwh,
                                                dischargeSteadyKw,
                                                false,
                                                ctx.considerDegradation
                                        );

                                        btNetKw += dischargeSteadyKwh;
                                        availEnergyKwh -= dischargeSteadyKwh;
                                    }
                                }
                            }
                        }

                        break;
                    }
                }

                // ---- распределение по ДГУ (пуск + steady) ----
                int R = Math.min(readyWorking, dgToUse);

                double readyMaxStartKw = R * ctx.dgMaxKw;
                double readyLoadStartKw = Math.min(deficitAfterWindKw, readyMaxStartKw);
                double perReadyStartKw = (R > 0) ? (readyLoadStartKw / R) : 0.0;

                double perDgSteadyKw = 0.0;
                if (dgToUse > 0) {
                    perDgSteadyKw = canUseOptimal
                            ? (deficitAfterWindKw / dgToUse)
                            : Math.min(deficitAfterWindKw / dgToUse, ctx.dgMaxKw);

                    if (canUseOptimal && perDgSteadyKw > ctx.perDgOptimalKw) perDgSteadyKw = ctx.perDgOptimalKw;
                }

                if (tauEff > SimulationConstants.EPSILON && dgToUse > readyWorking) {
                    double startDefKw = Math.max(0.0, deficitAfterWindKw - readyLoadStartKw);
                    startDelayEnsEstimateKwh = startDefKw * tauEff;
                }

                boolean canCharge = btAvail
                        && battery.getStateOfCharge() < SimulationConstants.BATTERY_MAX_SOC - SimulationConstants.EPSILON;

                double chargeCapKw = 0.0;
                if (canCharge) {
                    chargeCapKw = battery.getChargeCapacity(ctx.sp);
                    canCharge = chargeCapKw > SimulationConstants.EPSILON;
                }

                int used = 0;
                double sumDieselKw = 0.0;

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

                    if (genKw > ctx.dgMaxKw) genKw = ctx.dgMaxKw;
                    if (genKw < 0.0) genKw = 0.0;

                    dg.setCurrentLoad(genKw);
                    dg.addWorkTime(1, wasWorking ? 1 : 1 + SimulationConstants.DG_MAX_START_FACTOR);
                    dg.startWork();

                    sumDieselKw += genKw;
                    used++;
                }

                // ===== IDLE reserve in wind deficit =====
                if (windToLoadKw > SimulationConstants.EPSILON) {
                    SingleRunSimulator.applyIdleReserveInWindDeficit(
                            dgs,
                            loadKw,
                            windToLoadKw,
                            ctx.cat1,
                            ctx.cat2,
                            btAvail,
                            battery,
                            ctx.sp,
                            tauEff,
                            btAvail ? battery.getDischargeCapacity(ctx.sp) : 0.0,
                            ctx.dgRatedKw,
                            ctx.dgMinKw,
                            ctx.dgMaxKw
                    );
                }

                // ===== ROTATING RESERVE (N-1) =====
                if (ctx.considerRotationReserve) {
                    sumDieselKw = SingleRunSimulator.applyRotationReserveNminus1(
                            dgs,
                            loadKw,
                            windToLoadKw,
                            btNetKw,
                            btAvail,
                            battery,
                            ctx.sp,
                            tauEff,
                            ctx.dgMaxKw,
                            ctx.dgMinKw,
                            sumDieselKw
                    );
                }

                // ===== FINAL: low-load/idle/burn based on FINAL currentLoad =====
                boolean anyBurnThisHour = SingleRunSimulator.finalizeIdleAndBurn(dgs, ctx.dgMinKw);

                // ===== Финализация статусов ДГУ за час =====
                SingleRunSimulator.finalizeStoppedDgs(dgs);

                // ===== recompute diesel produced after possible burn =====
                double sumFinalDieselKw = 0.0;
                for (DieselGenerator dg : dgs) {
                    if (!dg.isAvailable()) continue;
                    double p = dg.getCurrentLoad();
                    if (p > SimulationConstants.EPSILON) sumFinalDieselKw += p;
                }
                dgProducedKw = sumFinalDieselKw;

                // ===== ENS из-за задержки пуска ДГУ =====
                if (tauEff > SimulationConstants.EPSILON && dgToUse > readyWorking) {
                    double startDefKw = Math.max(0.0, deficitAfterWindKw - readyLoadStartKw);
                    startDelayEnsEstimateKwh = startDefKw * tauEff;
                }

                // ---- заряд от ДГУ ----
                boolean allowChargeNow = canCharge && (ctx.considerChargeByDg || anyBurnThisHour);

                double btDisToLoadKw = Math.max(0.0, btNetKw);
                double needFromDieselToLoadKw = loadKw - windToLoadKw - btDisToLoadKw;
                if (needFromDieselToLoadKw < 0.0) needFromDieselToLoadKw = 0.0;

                double dieselSurplusKw = dgProducedKw - needFromDieselToLoadKw;
                if (dieselSurplusKw < 0.0) dieselSurplusKw = 0.0;

                double extraForChargeKw = 0.0;
                if (allowChargeNow && dieselSurplusKw > SimulationConstants.EPSILON) {
                    double ch = Math.min(dieselSurplusKw, chargeCapKw);
                    if (ch > SimulationConstants.EPSILON) {
                        battery.adjustCapacity(battery, +ch, ch, true, ctx.considerDegradation);
                        btNetKw -= ch;
                        extraForChargeKw = ch;
                    }
                }

                dgToLoadKwLocal = Math.max(0.0, dgProducedKw - extraForChargeKw);
            }
        }

        ctx.totals.fuelLiters += SingleRunSimulator.computeFuelLitersOneHour(bus.getDieselGenerators(), ctx.dgRatedKw);

        double btDisToLoad = Math.max(0.0, btNetKw);

        ctx.totals.wtToLoadKwh += windToLoadKw;
        ctx.totals.dgToLoadKwh += dgToLoadKwLocal;
        ctx.totals.btToLoadKwh += btDisToLoad;
        ctx.totals.wreKwh += wreLocal;

        if (ctx.hourWreRef != null) ctx.hourWreRef[0] += wreLocal;

        double suppliedKw = windToLoadKw + dgToLoadKwLocal + btDisToLoad;
        double defKw = loadKw - suppliedKw;
        if (defKw < 0.0) defKw = 0.0;
        ctx.totals.ensKwh += defKw;

        double startEns = Math.min(defKw, Math.max(0.0, startDelayEnsEstimateKwh));
        if (startEns > SimulationConstants.EPSILON) {
            EnsAllocator.addEnsByCategoryProportional(ctx.totals, loadKw, startEns, ctx.cat1, ctx.cat2);
        }
        double restEns = Math.max(0.0, defKw - startEns);
        if (restEns > SimulationConstants.EPSILON) {
            EnsAllocator.addEnsByCategory(ctx.totals, loadKw, restEns, ctx.cat1, ctx.cat2);
        }

        if (ctx.trace.enabled()) {
            ctx.trace.setBusValues(
                    b,
                    true,
                    loadKw,
                    windToLoadKw,
                    dgToLoadKwLocal,
                    btNetKw,
                    defKw
            );
            ctx.trace.fillDgState(b, bus);
            ctx.trace.fillBatteryState(b, bus.getBattery());
        }
    }
}
