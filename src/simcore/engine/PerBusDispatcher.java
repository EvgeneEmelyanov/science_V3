// File: simcore/engine/PerBusDispatcher.java
package simcore.engine;

import simcore.engine.metrics.EnsAllocator;
import simcore.config.SimulationConstants;
import simcore.model.*;

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
        double loadCat1Kw = loadKw * ctx.cat1;
        double disCapKw = battery.getDischargeCapacity(ctx.sp);
        return disCapKw >= (loadCat1Kw - SimulationConstants.EPSILON);
    }

    private static boolean batteryCanBridgeForStart(HourContext ctx, Battery battery, boolean btAvail,
                                                    double btDisCapKw, double startDefKw, double tauEff) {
        if (!btAvail) return false;
        if (startDefKw <= SimulationConstants.EPSILON) return true;
        if (tauEff <= SimulationConstants.EPSILON) return true;

        // Проверяем и ограничение по току (canBatteryBridge), и энергию (useBattery).
        boolean canBridgeByPower = SingleRunSimulator.canBatteryBridge(battery, ctx.sp, startDefKw, tauEff, btDisCapKw);
        if (!canBridgeByPower) return false;

        double needEnergyKwh = startDefKw * tauEff;
        return Battery.useBattery(ctx.sp, battery, needEnergyKwh, btDisCapKw);
    }

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
            DieselGenerator.stopAllDieselsOnBus(bus);

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

        double windPotentialKw = SingleRunSimulator.computeWindPotential(bus, ctx.windV);
        if (extraSourceBus != null) {
            windPotentialKw += SingleRunSimulator.computeWindPotential(extraSourceBus, ctx.windV);
        }

        final boolean btAvail = (battery != null && battery.isAvailable());

        double windToLoadKw = 0.0;
        double dgProducedKw = 0.0;
        double dgToLoadKwLocal = 0.0;
        double btNetKw = 0.0; // >0 discharge, <0 charge (kW ~ kWh for 1 hour)
        double wreLocal = 0.0;

        double startDelayEnsEstimateKwh = 0.0;
        double tauEff = 0.0;

        if (windPotentialKw >= loadKw - SimulationConstants.EPSILON) {
            // ===== Wind surplus case =====
            DieselGenerator[] dgsFinal = collectDgs(bus, extraSourceBus, extraDgs, excludeLocalDgs);

            if (btGridForming) {
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
                    windToLoadKw = loadKw;
                    wreLocal = Math.max(0.0, windPotentialKw - windToLoadKw);
                }
            }

            SingleRunSimulator.finalizeIdleAndBurn(ctx, dgsFinal, ctx.dgMinKw);
            SingleRunSimulator.finalizeStoppedDgs(dgsFinal);

        } else {
            // ===== Wind deficit case =====
            windToLoadKw = windPotentialKw;
            final double deficitAfterWindKw = loadKw - windToLoadKw;

            final double btDisCapKw = btAvail ? battery.getDischargeCapacity(ctx.sp) : 0.0;
            final DieselGenerator[] dgs = collectDgs(bus, extraSourceBus, extraDgs, excludeLocalDgs);

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
                // Физический максимум 1 ДГУ берём от rated, чтобы не было "внезапно 90 кВт".
                final double dgUnitMaxKw = ctx.dgRatedKw;

                // 1) сколько ДГУ нужно в этом часу
                int dgNeeded = (int) Math.ceil(deficitAfterWindKw / dgUnitMaxKw);
                if (dgNeeded < 1) dgNeeded = 1;
                if (dgNeeded > available) dgNeeded = available;

                int dgToUse = dgNeeded;

                // 2) проверка: есть ли незапущенная ДГУ и может ли АКБ обеспечить пуск
                // Распределяем равномерно по текущему dgToUse
                double perDgKw = deficitAfterWindKw / dgToUse;
                if (perDgKw > dgUnitMaxKw) perDgKw = dgUnitMaxKw;

                if (tauEff > SimulationConstants.EPSILON && readyWorking < dgToUse && btAvail) {
                    double startDefKw = Math.max(0.0, deficitAfterWindKw - readyWorking * perDgKw);

                    boolean canBridgeStart = batteryCanBridgeForStart(ctx, battery, btAvail, btDisCapKw, startDefKw, tauEff);

                    if (!canBridgeStart && dgToUse < available) {
                        dgToUse++;
                        perDgKw = deficitAfterWindKw / dgToUse;
                        if (perDgKw > dgUnitMaxKw) perDgKw = dgUnitMaxKw;
                    }
                }

                // 3) N-1: если при отказе одной ДГУ будет дефицит и АКБ не может "перемостить" пуск следующей — +1
                if (tauEff > SimulationConstants.EPSILON && dgToUse < available && btAvail) {
                    double gapIfOneFailsKw = Math.max(0.0, deficitAfterWindKw - Math.max(0, dgToUse - 1) * dgUnitMaxKw);
                    if (gapIfOneFailsKw > SimulationConstants.EPSILON) {
                        boolean canBridgeGap = batteryCanBridgeForStart(ctx, battery, btAvail, btDisCapKw, gapIfOneFailsKw, tauEff);
                        if (!canBridgeGap) {
                            dgToUse++;
                            perDgKw = deficitAfterWindKw / dgToUse;
                            if (perDgKw > dgUnitMaxKw) perDgKw = dgUnitMaxKw;
                        }
                    }
                }

                // 4) диспетчеризация: РАВНОМЕРНО между dgToUse
                int used = 0;
                double sumDieselKw = 0.0;

                for (DieselGenerator dg : dgs) {
                    if (!dg.isAvailable() || used >= dgToUse) {
                        dg.setCurrentLoad(0.0);
                        dg.stopWork();
                        continue;
                    }

                    boolean wasWorking = dg.isWorking();

                    // В течение tauEff новые ДГУ "не дают мощности" (в среднем за час это учтём множителем).
                    double genKw = wasWorking ? perDgKw : perDgKw * (1.0 - tauEff);

                    if (genKw > dgUnitMaxKw) genKw = dgUnitMaxKw;
                    if (genKw < 0.0) genKw = 0.0;

                    dg.setCurrentLoad(genKw);
                    dg.addWorkTime(1, wasWorking ? 1 : 1 + SimulationConstants.DG_MAX_START_FACTOR);
                    dg.startWork();

                    sumDieselKw += genKw;
                    used++;
                }

                // 5) дефицит на период пуска (если часть ДГУ стартует) — пытаемся закрыть АКБ, иначе уйдёт в UFLS/ENS
                if (tauEff > SimulationConstants.EPSILON && readyWorking < dgToUse) {
                    double startDefKw = Math.max(0.0, deficitAfterWindKw - readyWorking * perDgKw);
                    if (startDefKw > SimulationConstants.EPSILON && btAvail) {
                        boolean canBridgeStart = SingleRunSimulator.canBatteryBridge(battery, ctx.sp, startDefKw, tauEff, btDisCapKw);
                        if (canBridgeStart) {
                            double needEnergyKwh = startDefKw * tauEff;
                            if (Battery.useBattery(ctx.sp, battery, needEnergyKwh, btDisCapKw)) {
                                battery.adjustCapacity(battery, -needEnergyKwh, startDefKw, false, ctx.considerDegradation);
                                btNetKw += needEnergyKwh; // в trace это как "кВт", но в модели 1 час => kWh==kW
                            }
                        }
                        // если не смогли — оценим дефицит на старт (для последующего UFLS)
                        if (startDefKw > SimulationConstants.EPSILON && btNetKw <= SimulationConstants.EPSILON) {
                            startDelayEnsEstimateKwh = startDefKw * tauEff;
                        }
                    } else if (startDefKw > SimulationConstants.EPSILON) {
                        startDelayEnsEstimateKwh = startDefKw * tauEff;
                    }
                }

                // burn + stop not used
                boolean anyBurnThisHour = SingleRunSimulator.finalizeIdleAndBurn(ctx, dgs, ctx.dgMinKw);
                SingleRunSimulator.finalizeStoppedDgs(dgs);

                // суммарная выработка ДГУ по итогам (после возможного поднятия до min/idle)
                double sumFinalDieselKw = 0.0;
                for (DieselGenerator dg : dgs) {
                    if (!dg.isAvailable()) continue;
                    double p = dg.getCurrentLoad();
                    if (p > SimulationConstants.EPSILON) sumFinalDieselKw += p;
                }
                dgProducedKw = sumFinalDieselKw;

                // заряд АКБ из профицита ДГУ (если есть) — как было раньше
                boolean canCharge = btAvail
                        && battery.getStateOfCharge() < SimulationConstants.BATTERY_MAX_SOC - SimulationConstants.EPSILON;

                double chargeCapKw = 0.0;
                if (canCharge) {
                    chargeCapKw = battery.getChargeCapacity(ctx.sp);
                    canCharge = chargeCapKw > SimulationConstants.EPSILON;
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
            // стартовый дефицит на долю часа (энергия) приводим к "кВт эквиваленту часа"
            defKw = Math.max(defKw, startDelayEnsEstimateKwh);
            ctx.status.set(HourContext.StatusCollector.PRI_BLACKOUT, "BLACKOUT_PARTIAL_DG_START_DELAY");
        }

        if (defKw > SimulationConstants.EPSILON) {
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
