// File: simcore/engine/PerBusDispatcher.java
package simcore.engine;

import simcore.engine.metrics.EnsAllocator;
import simcore.config.SimulationConstants;
import simcore.model.*;

/**
 * Per-bus dispatch logic extracted from SingleRunSimulator.
 * Kept 1:1 to preserve behavior; further cleanup should be done after regression is green.
 */
final class PerBusDispatcher {
    private PerBusDispatcher() {}

    // ===== New logic constants (engine-level, not parameters) =====
    private static final double BESS_GRID_FORMING_SOC_MIN = 0.20;
    private static final double UFLS_STEP = 0.10;

    private static double uflsEnsRounded(double loadKw, double deficitKw) {
        if (deficitKw <= SimulationConstants.EPSILON) return 0.0;
        if (loadKw <= SimulationConstants.EPSILON) return deficitKw;
        double stepKw = UFLS_STEP * loadKw;
        if (stepKw <= SimulationConstants.EPSILON) return Math.min(loadKw, deficitKw);
        long steps = (long) Math.ceil(deficitKw / stepKw);
        double shed = steps * stepKw;
        if (shed > loadKw) shed = loadKw;
        return shed;
    }

    private static boolean batteryCanGridForming(HourContext ctx, Battery battery, double loadKw) {
        if (battery == null || !battery.isAvailable()) return false;
        if (battery.getStateOfCharge() < BESS_GRID_FORMING_SOC_MIN) return false;
        // Must be able to support at least Cat I load (if categories used).
        double loadCat1Kw = loadKw * ctx.cat1;
        double disCapKw = battery.getDischargeCapacity(ctx.sp);
        return disCapKw >= (loadCat1Kw - SimulationConstants.EPSILON);
    }

    /**
     * Dispatch for a bus with optional DG transfer.
     *
     * <p>Rules:
     * <ul>
     *   <li>{@code extraDgs} are treated as additional DGs available on this bus for this hour (same physical DG objects).</li>
     *   <li>{@code excludeLocalDgs} are ignored when dispatching this bus for this hour (used when those DGs are transferred away).</li>
     * </ul>
     * Wind transfer is NOT handled here; for WT transfer use {@link #dispatchOneBusOneHourWithExtraSources}.
     */
    static void dispatchOneBusOneHourWithDgTransfer(
            HourContext ctx,
            PowerBus bus,
            boolean busAlive,
            int b,
            double loadKw,
            java.util.List<DieselGenerator> extraDgs,
            java.util.Set<DieselGenerator> excludeLocalDgs
    ) {
        dispatchOneBusOneHourInternal(ctx, bus, null, busAlive, b, loadKw, extraDgs, excludeLocalDgs);
    }


    static void dispatchOneBusOneHour(
            HourContext ctx,
            PowerBus bus,
            boolean busAlive,
            int b,
            double loadKw
    ) {
        dispatchOneBusOneHourWithExtraSources(ctx, bus, null, busAlive, b, loadKw);
    }

    /**
     * Dispatch for a bus with optional extra generation sources (used for DOUBLE_BUS resource transfer logic).
     * Only wind turbines and diesel generators from extraSourceBus are considered.
     */
    static void dispatchOneBusOneHourWithExtraSources(
            HourContext ctx,
            PowerBus bus,
            PowerBus extraSourceBus,
            boolean busAlive,
            int b,
            double loadKw
    ) {
        dispatchOneBusOneHourInternal(ctx, bus, extraSourceBus, busAlive, b, loadKw, null, null);
    }

    private static DieselGenerator[] collectDgs(
            PowerBus bus,
            PowerBus extraSourceBus,
            java.util.List<DieselGenerator> extraDgs,
            java.util.Set<DieselGenerator> excludeLocalDgs
    ) {
        if (extraSourceBus == null && (extraDgs == null || extraDgs.isEmpty()) && (excludeLocalDgs == null || excludeLocalDgs.isEmpty())) {
            return DieselGenerator.getSortedDgs(bus);
        }

        java.util.ArrayList<DieselGenerator> all = new java.util.ArrayList<>();
        for (DieselGenerator dg : bus.getDieselGenerators()) {
            if (excludeLocalDgs != null && excludeLocalDgs.contains(dg)) continue;
            all.add(dg);
        }
        if (extraSourceBus != null) {
            all.addAll(extraSourceBus.getDieselGenerators());
        }
        if (extraDgs != null && !extraDgs.isEmpty()) {
            all.addAll(extraDgs);
        }
        return DieselGenerator.getSortedDgs(all);
    }

    private static void dispatchOneBusOneHourInternal(
            HourContext ctx,
            PowerBus bus,
            PowerBus extraSourceBus,
            boolean busAlive,
            int b,
            double loadKw,
            java.util.List<DieselGenerator> extraDgs,
            java.util.Set<DieselGenerator> excludeLocalDgs
    ) {

        ctx.totals.loadKwh += loadKw;

        if (!busAlive) {
            DieselGenerator.stopAllDieselsOnBus(bus);

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

        // ===== A1: "bus energised" requires a grid-forming source =====
        final Battery battery = bus.getBattery();
        final boolean btGridForming = batteryCanGridForming(ctx, battery, loadKw);

        final DieselGenerator[] dgsForGridForming = collectDgs(bus, extraSourceBus, extraDgs, excludeLocalDgs);
        boolean dgGridForming = false;
        for (DieselGenerator dg : dgsForGridForming) {
            if (dg.isAvailable() && dg.isWorking()) {
                dgGridForming = true;
                break;
            }
        }

        if (!dgGridForming && !btGridForming) {
            // Blackout on this bus (even if the physical bus didn't fail).
            DieselGenerator.stopAllDieselsOnBus(bus);
            // WT are grid-following: without grid-forming they cannot operate.
            double defKw = loadKw;
            ctx.totals.ensKwh += defKw;
            EnsAllocator.addEnsByCategoryPriority321(ctx.totals, loadKw, defKw, ctx.cat1, ctx.cat2);
            ctx.status.set(HourContext.StatusCollector.PRI_BLACKOUT, "BUS_BLACKOUT_NO_GRID_FORMING");

            if (ctx.trace.enabled()) {
                ctx.trace.setBusDown(b, loadKw, defKw);
                ctx.trace.fillDgState(b, bus);
                ctx.trace.fillBatteryState(b, battery);
            }
            return;
        }

        bus.addWorkTime(1);

        // WT work only if there is grid-forming somewhere on the bus.
        double windPotentialKw = SingleRunSimulator.computeWindPotential(bus, ctx.windV);
        if (extraSourceBus != null) {
            windPotentialKw += SingleRunSimulator.computeWindPotential(extraSourceBus, ctx.windV);
        }

        final boolean btAvail = battery != null && battery.isAvailable();

        double windToLoadKw = 0.0;
        double dgProducedKw = 0.0;
        double dgToLoadKwLocal = 0.0;
        double btNetKw = 0.0; // >0 discharge, <0 charge
        double wreLocal = 0.0;
        double startDelayEnsEstimateKwh = 0.0;
        // эффективная задержка запуска ДГУ за этот час
        double tauEff = 0.0;

        if (windPotentialKw >= loadKw - SimulationConstants.EPSILON) {
            // ===== Wind surplus case =====
            // A2: if WT >= Load and BESS can be grid-forming -> DG off, surplus to charge, then curtail.
            // If BESS cannot be grid-forming -> keep DG as grid-forming at min loading, curtail WT if needed.

            DieselGenerator[] dgsFinal = collectDgs(bus, extraSourceBus, extraDgs, excludeLocalDgs);

            if (btGridForming) {
                // DGs off
                DieselGenerator.stopAllDieselsOnBus(bus);
                for (DieselGenerator dg : dgsFinal) {
                    dg.stopWork();
                    dg.setCurrentLoad(0.0);
                }

                windToLoadKw = loadKw;
                double surplusKw = Math.max(0.0, windPotentialKw - windToLoadKw);

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

            } else {
                // Keep at least one DG running as grid-forming at min load.
                DieselGenerator chosen = null;
                for (DieselGenerator dg : dgsFinal) {
                    if (!dg.isAvailable()) continue;
                    chosen = dg;
                    break;
                }

                if (chosen != null) {
                    chosen.startWork();
                    chosen.setCurrentLoad(ctx.dgMinKw);
                    dgProducedKw = ctx.dgMinKw;
                    dgToLoadKwLocal = Math.min(loadKw, dgProducedKw);

                    // Stop all other DGs (for this hour) to keep minimal load only.
                    for (DieselGenerator dg : dgsFinal) {
                        if (dg == chosen) continue;
                        dg.stopWork();
                        dg.setCurrentLoad(0.0);
                    }

                    double remainingLoadKw = Math.max(0.0, loadKw - dgToLoadKwLocal);
                    windToLoadKw = remainingLoadKw;
                    double surplusKw = Math.max(0.0, windPotentialKw - windToLoadKw);

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
                    ctx.status.set(HourContext.StatusCollector.PRI_NORMAL, "WT_GE_LOAD_DG_MIN_30%");
                } else {
                    // No available DG (should be rare here because A1 already ensured some grid-forming), fallback:
                    windToLoadKw = loadKw;
                    wreLocal = Math.max(0.0, windPotentialKw - windToLoadKw);
                }
            }

            // finalize diesel states + fuel burn
            SingleRunSimulator.finalizeIdleAndBurn(ctx, dgsFinal, ctx.dgMinKw);
            SingleRunSimulator.finalizeStoppedDgs(dgsFinal);

        } else {
            // ===== Wind deficit case =====
            windToLoadKw = windPotentialKw;
            final double deficitAfterWindKw = loadKw - windToLoadKw;

            final double btDisCapKw = btAvail ? battery.getDischargeCapacity(ctx.sp) : 0.0;

            final DieselGenerator[] dgs = collectDgs(bus, extraSourceBus, extraDgs, excludeLocalDgs);
            final int dgCountAll = dgs.length;

            final boolean maintenanceStartedThisHour = DieselGenerator.isMaintenanceStartedThisHour(dgs);

            tauEff = maintenanceStartedThisHour ? 0.0 : ctx.dgStartDelayHours;

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

                            // Available discharge energy must respect BATTERY_MIN_SOC.
                            // Here we work in "to-load" kWh units (same convention as Battery.getDischargeCapacity).
                            double soc = battery.getStateOfCharge();
                            double availEnergyKwh = Math.max(
                                    0.0,
                                    (soc - SimulationConstants.BATTERY_MIN_SOC)
                                            * battery.getMaxCapacityKwh()
                                            * SimulationConstants.BATTERY_EFFICIENCY
                            );

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
                boolean dgUsedForLoad = sumDieselKw > SimulationConstants.EPSILON;

                if (!dgUsedForLoad && windToLoadKw > SimulationConstants.EPSILON) {
                    SingleRunSimulator.applyIdleReserveInWindDeficit(
                            dgs,
                            loadKw,
                            windToLoadKw,
                            ctx.cat1,
                            ctx.cat2,
                            ctx.reserveThirdCategory,
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

                if (ctx.considerRotationReserve && dgUsedForLoad) {
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
                            sumDieselKw,
                            ctx.cat1,
                            ctx.cat2,
                            ctx.reserveThirdCategory
                    );
                }
                double firstsumFinalDieselKw = 0.0;
                for (DieselGenerator dg : dgs) {
                    if (!dg.isAvailable()) continue;
                    double p = dg.getCurrentLoad();
                    if (p > SimulationConstants.EPSILON) firstsumFinalDieselKw += p;
                }

                boolean anyBurnThisHour = SingleRunSimulator.finalizeIdleAndBurn(ctx, dgs, ctx.dgMinKw);

                SingleRunSimulator.finalizeStoppedDgs(dgs);

                double sumFinalDieselKw = 0.0;
                for (DieselGenerator dg : dgs) {
                    if (!dg.isAvailable()) continue;
                    double p = dg.getCurrentLoad();
                    if (p > SimulationConstants.EPSILON) sumFinalDieselKw += p;
                }
                dgProducedKw = Math.min(firstsumFinalDieselKw, sumFinalDieselKw);

                if (tauEff > SimulationConstants.EPSILON && dgToUse > readyWorking) {
                    double startDefKw = Math.max(0.0, deficitAfterWindKw - readyLoadStartKw);
                    startDelayEnsEstimateKwh = startDefKw * tauEff;
                }

                boolean allowChargeNow = canCharge && anyBurnThisHour;

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

        double totalGenForLoad = windToLoadKw + dgToLoadKwLocal + btDisToLoad;
        double defKw = loadKw - totalGenForLoad;
        if (defKw < 0.0) defKw = 0.0;

        if (tauEff > SimulationConstants.EPSILON && startDelayEnsEstimateKwh > SimulationConstants.EPSILON) {
            defKw = Math.max(defKw, startDelayEnsEstimateKwh);
        }

        if (defKw > SimulationConstants.EPSILON) {
            // ===== A3: UFLS stepwise shedding (10% steps, round up) =====
            double uflsEns = uflsEnsRounded(loadKw, defKw);
            if (uflsEns > SimulationConstants.EPSILON) {
                ctx.totals.ensKwh += uflsEns;
                EnsAllocator.addEnsByCategoryPriority321(ctx.totals, loadKw, uflsEns, ctx.cat1, ctx.cat2);

                int pct = (int) Math.round(100.0 * (uflsEns / Math.max(loadKw, SimulationConstants.EPSILON)));
                ctx.status.set(HourContext.StatusCollector.PRI_UFLS, "UFLS_SHED_" + pct + "%");
            }
            defKw = uflsEns;
        }

        if (ctx.trace.enabled()) {
            ctx.trace.setBusValues(b, true, loadKw, windToLoadKw, dgToLoadKwLocal, btNetKw, defKw);
            ctx.trace.fillDgState(b, bus);
            ctx.trace.fillBatteryState(b, battery);
        }
    }
}
