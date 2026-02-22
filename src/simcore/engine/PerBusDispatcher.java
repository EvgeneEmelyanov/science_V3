package simcore.engine;

import simcore.config.SimulationConstants;
import simcore.engine.metrics.EnsAllocator;
import simcore.model.Battery;
import simcore.model.DieselGenerator;
import simcore.model.PowerBus;
import simcore.config.ModelDefaults;

import java.util.ArrayList;
import java.util.List;

/**
 * One-bus one-hour dispatch according to the spec:
 * WT -> BESS (reserve/non-reserve) -> DG (with start delay tau) -> UFLS.
 */
final class PerBusDispatcher {
    private PerBusDispatcher() {}

    private static double uflsEnsRounded(double loadKw, double deficitKw) {
        if (deficitKw <= SimulationConstants.EPSILON) return 0.0;
        if (loadKw <= SimulationConstants.EPSILON) return 0.0;
        double stepKw = SimulationConstants.UFLS_STEP * loadKw;
        if (stepKw <= SimulationConstants.EPSILON) return Math.min(loadKw, deficitKw);
        long steps = (long) Math.ceil(deficitKw / stepKw);
        double shed = steps * stepKw;
        return Math.min(loadKw, shed);
    }

    private static int countAvailable(DieselGenerator[] dgs) {
        int c = 0;
        for (DieselGenerator dg : dgs) if (dg.isAvailable()) c++;
        return c;
    }

    private static int countWorkingAtStart(DieselGenerator[] dgs) {
        int c = 0;
        for (DieselGenerator dg : dgs) {
            if (!dg.isAvailable()) continue;
            if (dg.wasWorkingAtHourStart()) c++;
        }
        return c;
    }

    private static List<DieselGenerator> selectDgsToRun(DieselGenerator[] dgs, int nOn) {
        ArrayList<DieselGenerator> out = new ArrayList<>(nOn);

        // Prefer those already working at hour start.
        for (DieselGenerator dg : dgs) {
            if (out.size() >= nOn) break;
            if (!dg.isAvailable()) continue;
            if (dg.wasWorkingAtHourStart()) out.add(dg);
        }
        for (DieselGenerator dg : dgs) {
            if (out.size() >= nOn) break;
            if (!dg.isAvailable()) continue;
            if (!dg.wasWorkingAtHourStart()) out.add(dg);
        }
        return out;
    }

    private static double batteryAvailableEnergyKwhAbove(Battery bt, double socFloor) {
        if (bt == null || !bt.isAvailable()) return 0.0;
        double soc = bt.getStateOfCharge();
        double cap = bt.getMaxCapacityKwh();
        double usableSoc = Math.max(0.0, soc - socFloor);
        return usableSoc * cap * SimulationConstants.BATTERY_EFFICIENCY;
    }

    private static double batteryMaxDischargeKw(Battery bt, HourContext ctx) {
        if (bt == null || !bt.isAvailable()) return 0.0;
        return bt.getDischargePowerCapKw(ctx.sp);
    }

    private static double dischargeBattery(Battery bt, HourContext ctx, double powerKw, double durationHours, boolean bridgeMode) {
        if (bt == null || !bt.isAvailable()) return 0.0;
        if (powerKw <= SimulationConstants.EPSILON || durationHours <= 0.0) return 0.0;

        // Limit by inverter current.
        double capKw = bt.getDischargeCapacity(ctx.sp);

        // Also limit by actually available stored energy (avoid "discharge" at SOC=0).
        // Energy delta passed to Battery.adjustCapacity() is terminal energy (before efficiency).
        double maxCapKwh = bt.getMaxCapacityKwh();
        double soc = bt.getStateOfCharge();
        double storedKwh = Math.max(0.0, soc * maxCapKwh);
        double capByStoredKw = storedKwh <= 0.0
                ? 0.0
                : (storedKwh / durationHours) * SimulationConstants.BATTERY_EFFICIENCY;

        double p = Math.min(powerKw, Math.min(capKw, capByStoredKw));
        if (p <= SimulationConstants.EPSILON) return 0.0;

        bt.adjustCapacity(bt, -p * durationHours, p, bridgeMode, ctx.considerDegradation);
        return p;
    }

    private static double chargeBattery(Battery bt, HourContext ctx, double powerKw, double durationHours) {
        if (bt == null || !bt.isAvailable()) return 0.0;
        if (powerKw <= SimulationConstants.EPSILON || durationHours <= 0.0) return 0.0;

        // If battery is (almost) full, do not "charge" it and do not count work/degradation.
        double soc0 = bt.getStateOfCharge();
        if (soc0 >= 1.0 - 1e-9) return 0.0;

        // Limit by inverter current.
        double capKw = bt.getChargeCapacity(ctx.sp);

        // Also limit by remaining free capacity (avoid "charge" at SOC=1).
        // Energy delta passed to Battery.adjustCapacity() is terminal energy (before efficiency).
        double maxCapKwh = bt.getMaxCapacityKwh();
        double soc = soc0;
        double freeKwh = Math.max(0.0, (1.0 - soc) * maxCapKwh);
        double capByFreeKw = freeKwh <= 0.0
                ? 0.0
                : (freeKwh / durationHours) / SimulationConstants.BATTERY_EFFICIENCY;

        double p = Math.min(powerKw, Math.min(capKw, capByFreeKw));
        if (p <= SimulationConstants.EPSILON) return 0.0;

        bt.adjustCapacity(bt, +p * durationHours, p, false, ctx.considerDegradation);
        return p;
    }

    private static boolean hasAnyGridFormingEquipmentOnBus(PowerBus bus) {
        if (bus == null) return false;
        for (DieselGenerator dg : bus.getDieselGenerators()) {
            if (dg != null && dg.isAvailable()) return true;
        }
        Battery bt = bus.getBattery();
        return bt != null && bt.isAvailable();
    }

    static void dispatchOneBusOneHour(
            HourContext ctx,
            PowerBus bus,
            boolean busEnergised,
            int b,
            double loadKw
    ) {
        dispatchOneBusOneHourWithExtraSources(ctx, bus, null, busEnergised, b, loadKw);
    }

    static void dispatchOneBusOneHourWithExtraSources(
            HourContext ctx,
            PowerBus bus,
            PowerBus extraSourceBus,
            boolean busEnergised,
            int b,
            double loadKw
    ) {
        ctx.totals.loadKwh += loadKw;

        final Battery bt = bus.getBattery();
        final boolean btAvail = bt != null && bt.isAvailable();

        // Collect DGs (local + optional extra source bus) in dispatch order.
        final DieselGenerator[] dgs;
        if (extraSourceBus == null) {
            dgs = DieselGenerator.getSortedDgs(bus);
        } else {
            ArrayList<DieselGenerator> all = new ArrayList<>();
            all.addAll(bus.getDieselGenerators());
            all.addAll(extraSourceBus.getDieselGenerators());
            dgs = DieselGenerator.getSortedDgs(all);
        }

        final int availableDg = countAvailable(dgs);

        // Hard outage: bus not energised -> 100% ENS (scheme rules handle transfers before this call).
        if (!busEnergised || !hasAnyGridFormingEquipmentOnBus(bus) && (extraSourceBus == null || !hasAnyGridFormingEquipmentOnBus(extraSourceBus))) {
            DieselGenerator.stopAllDieselsOnBus(bus);
            if (extraSourceBus != null) DieselGenerator.stopAllDieselsOnBus(extraSourceBus);

            ctx.totals.ensKwh += loadKw;
            EnsAllocator.addEnsByCategoryProportional(ctx.totals, loadKw, loadKw, ctx.cat1, ctx.cat2);
            ctx.status.set(HourContext.StatusCollector.PRI_BLACKOUT, "OUTAGE_FULL_ENS");

            if (ctx.trace.enabled()) {
                ctx.trace.setBusDown(b, loadKw, loadKw);
                ctx.trace.fillDgState(b, bus);
                ctx.trace.fillBatteryState(b, bt);
            }
            return;
        }

        bus.addWorkTime(1);

        // ===== WT potential =====
        double windPotKw = SingleRunSimulator.computeWindPotential(bus, ctx.windV);
        if (extraSourceBus != null) windPotKw += SingleRunSimulator.computeWindPotential(extraSourceBus, ctx.windV);

        double windToLoadKw = 0.0;
        double dgToLoadKw = 0.0;
        double btNetKw = 0.0; // + discharge, - charge (average over the hour)
        double wreKw = 0.0;

        // ===== CASE A: WT >= load =====
        if (windPotKw >= loadKw - SimulationConstants.EPSILON) {

            // Spec: if BESS exists and is available -> wind forms the bus using BESS inverter (regardless of SOC).
            if (btAvail) {
                DieselGenerator.stopAllDieselsOnBus(bus);
                if (extraSourceBus != null) DieselGenerator.stopAllDieselsOnBus(extraSourceBus);

                windToLoadKw = loadKw;
                double surplusKw = Math.max(0.0, windPotKw - windToLoadKw);

                // Charge BESS from surplus.
                double ch = chargeBattery(bt, ctx, surplusKw, 1.0);
                btNetKw -= ch;
                surplusKw -= ch;

                wreKw = Math.max(0.0, surplusKw);

            } else {
                // Spec: no BESS -> keep minimal 1 DG at min load to provide grid-forming.
                if (availableDg > 0) {
                    DieselGenerator chosen = null;
                    for (DieselGenerator dg : dgs) {
                        if (!dg.isAvailable()) continue;
                        chosen = dg;
                        break;
                    }
                    // Defensive: should not happen because availableDg>0.
                    if (chosen != null) {
                        for (DieselGenerator dg : dgs) {
                            if (!dg.isAvailable()) {
                                dg.setCurrentLoad(0.0);
                                dg.stopWork();
                                continue;
                            }
                            if (dg == chosen) {
                                dg.startWork();
                                dg.setCurrentLoad(ctx.dgMinKw);
                            } else {
                                dg.setCurrentLoad(0.0);
                                dg.stopWork();
                            }
                        }

                        double dgMin = ctx.dgMinKw;
                        double wtToLoad = Math.max(0.0, loadKw - dgMin);
                        windToLoadKw = wtToLoad;
                        dgToLoadKw = Math.min(loadKw, dgMin);

                        wreKw = Math.max(0.0, windPotKw - windToLoadKw);
                        ctx.status.set(HourContext.StatusCollector.PRI_NORMAL, "WT_GE_LOAD_NO_BESS_DG_MIN");
                    }
                } else {
                    // No DG and no BESS (should have been caught by busEnergised), but stay safe.
                    windToLoadKw = loadKw;
                    wreKw = Math.max(0.0, windPotKw - windToLoadKw);
                }
            }

            // Fuel/moto accounting for DG.
            SingleRunSimulator.finalizeIdleAndBurn(ctx, dgs, ctx.dgMinKw);
            SingleRunSimulator.finalizeStoppedDgs(dgs);

        } else {
            // ===== CASE B: WT < load =====
            windToLoadKw = windPotKw;
            double deficitAfterWindKw = Math.max(0.0, loadKw - windToLoadKw);

            // ===== 0) Special case: BESS can cover the entire deficit for the whole hour (fuel-saving) =====
            // Non-reserve floor is an ABSOLUTE SOC floor (>= max(SOC_MIN, nonReserveLevel)).
            if (btAvail && deficitAfterWindKw > SimulationConstants.EPSILON) {
                double nonReserveFloor = Math.max(SimulationConstants.BATTERY_MIN_SOC, ctx.sp.getNonReserveDischargeLevel());
                double btCapKw0 = batteryMaxDischargeKw(bt, ctx);
                double needKw0 = deficitAfterWindKw;
                double needKwh0 = needKw0; // 1 hour

                double availKwh0 = bt.getAvailableDischargeEnergyKwhAbove(nonReserveFloor);

                if (needKw0 <= btCapKw0 + SimulationConstants.EPSILON
                        && availKwh0 + SimulationConstants.EPSILON >= needKwh0) {

                    DieselGenerator.stopAllDieselsOnBus(bus);
                    if (extraSourceBus != null) DieselGenerator.stopAllDieselsOnBus(extraSourceBus);

                    double actualDisKw0 = dischargeBattery(bt, ctx, needKw0, 1.0, false);
                    btNetKw += actualDisKw0; // full hour average
                    dgToLoadKw = 0.0;

                    ctx.status.set(HourContext.StatusCollector.PRI_NORMAL, "BESS_SUPPLIES_DEFICIT_NO_DG");

                    // totals / ENS at the end will see zero deficit
                    ctx.totals.wtToLoadKwh += windToLoadKw;
                    ctx.totals.dgToLoadKwh += 0.0;
                    ctx.totals.btToLoadKwh += Math.max(0.0, btNetKw);
                    ctx.totals.wreKwh += 0.0;
                    ctx.totals.fuelLiters += SingleRunSimulator.computeFuelLitersOneHour(bus.getDieselGenerators(), ctx.dgRatedKw);

                    if (ctx.trace.enabled()) {
                        ctx.trace.setBusValues(b, true, loadKw, windToLoadKw, 0.0, btNetKw, 0.0);
                        ctx.trace.fillDgState(b, bus);
                        ctx.trace.fillBatteryState(b, bt);
                    }
                    return;
                }
            }

            final double tau = DieselGenerator.isMaintenanceStartedThisHour(dgs) ? 0.0 : ctx.dgStartDelayHours;

            // ===== 1) DG count by max power =====
            int nAvail = availableDg;
            int nNeededByMax = (ctx.dgMaxKw > SimulationConstants.EPSILON)
                    ? (int) Math.ceil(deficitAfterWindKw / ctx.dgMaxKw)
                    : nAvail;
            int nPlanned = Math.min(Math.max(0, nNeededByMax), Math.max(0, nAvail));

            // ===== 2) Non-reserve BESS use: reduce DG count if possible =====
            // Threshold: SOC >= SOC_MIN + nonReserveAdd (from system parameters).
            // This applies ONLY to the part of discharge that is used to *reduce* DG count.
            // Non-reserve floor is ABSOLUTE (>= max(SOC_MIN, nonReserveLevel)).
            double socNonReserveFloor = Math.max(SimulationConstants.BATTERY_MIN_SOC, ctx.sp.getNonReserveDischargeLevel());

            int nPlannedReduced = nPlanned;
            if (btAvail && nAvail > 0) {
                while (nPlannedReduced > 1) {
                    int cand = nPlannedReduced - 1;
                    double needFromBtKw = Math.max(0.0, deficitAfterWindKw - cand * ctx.dgMaxKw);
                    if (needFromBtKw <= SimulationConstants.EPSILON) {
                        nPlannedReduced = cand;
                        continue;
                    }
                    double btCapKw = batteryMaxDischargeKw(bt, ctx);
                    if (btCapKw + SimulationConstants.EPSILON < needFromBtKw) break;

                    double needEnergyKwh = needFromBtKw * 1.0;
                    double availEnergyKwh = bt.getAvailableDischargeEnergyKwhAbove(socNonReserveFloor);
                    if (availEnergyKwh + SimulationConstants.EPSILON < needEnergyKwh) break;

                    nPlannedReduced = cand;
                }
            }
            nPlanned = nPlannedReduced;

            // ===== 3) Rotation reserve (N-1) =====
            int nOn = nPlanned;
            if (ctx.considerRotationReserve && nOn < nAvail) {
                double criticalRatio = ctx.cat1 + ctx.cat2;
                if (ctx.reserveThirdCategory) criticalRatio = 1.0;
                criticalRatio = Math.max(0.0, Math.min(1.0, criticalRatio));

                double criticalLoadKw = loadKw * criticalRatio;
                double criticalDefAfterWind = Math.max(0.0, criticalLoadKw - windToLoadKw);

                // If one DG is lost: remaining DG max.
                double remainingDgMaxKw = Math.max(0.0, (nOn - 1) * ctx.dgMaxKw);
                double gapKw;
                if (ModelDefaults.CFG_USE_AVG_LOAD_RESERVE_POLICY) {
                    gapKw = SingleRunSimulator.getAvgLoadPerBusKw() * ModelDefaults.CFG_ROTATION_RESERVE_COEFF;
                } else {
                    gapKw = Math.max(0.0, criticalDefAfterWind - remainingDgMaxKw);
                }

                if (gapKw > SimulationConstants.EPSILON) {
                    boolean btCanBridge = btAvail && SingleRunSimulator.canBatteryBridge(bt, ctx.sp, gapKw, tau, batteryMaxDischargeKw(bt, ctx));
                    if (!btCanBridge) {
                        nOn = Math.min(nAvail, nOn + 1);
                        ctx.status.set(HourContext.StatusCollector.PRI_RESERVE, "ROTATION_RESERVE_ADD_DG");
                    }
                }
            }

            // ===== 4) Determine who is already working at hour start =====
            int nWorkingAtStart = countWorkingAtStart(dgs);
            int nAlready = Math.min(nWorkingAtStart, nOn);
            int nNew = Math.max(0, nOn - nAlready);

            // ===== 5) DG dispatch (start interval tau + steady interval 1-tau) =====
            double dgSteadyTotalKw = Math.min(deficitAfterWindKw, nOn * ctx.dgMaxKw);
            double perDgSteadyKw = (nOn > 0) ? Math.min(ctx.dgMaxKw, deficitAfterWindKw / nOn) : 0.0;

            double dgStartTotalKw;
            if (tau <= SimulationConstants.EPSILON || nAlready <= 0) {
                dgStartTotalKw = 0.0;
            } else {
                double perReadyStartKw = Math.min(ctx.dgMaxKw, deficitAfterWindKw / nAlready);
                dgStartTotalKw = perReadyStartKw * nAlready;
            }

            // ===== 6) Reserve BESS bridging during tau for DG start delay =====
            double deficitTauKw = 0.0;
            if (tau > SimulationConstants.EPSILON && nNew > 0) {
                deficitTauKw = Math.max(0.0, deficitAfterWindKw - dgStartTotalKw);
                if (deficitTauKw > SimulationConstants.EPSILON) {
                    // Reserve discharge: SOC floor is BATTERY_MIN_SOC (no non-reserve add)
                    double btCapKw = batteryMaxDischargeKw(bt, ctx);
                    double btDisKw = Math.min(deficitTauKw, btCapKw);

                    // Ensure energy is available above SOC_MIN for tau.
                    double availKwh = btAvail
                            ? bt.getAvailableDischargeEnergyKwhAbove(SimulationConstants.BATTERY_MIN_SOC)
                            : 0.0;

                    double needKwh = btDisKw * tau;
                    if (btAvail && availKwh + SimulationConstants.EPSILON >= needKwh) {
                        double actualDisKw = dischargeBattery(bt, ctx, btDisKw, tau, true);
                        // btNetKw is stored as "hour-equivalent" contribution (kWh for 1h step).
                        // For partial-hour discharge we scale by the duration.
                        btNetKw += actualDisKw * tau;
                        deficitTauKw = Math.max(0.0, deficitTauKw - actualDisKw);
                    }
                }
            }

            // Remaining deficit on tau contributes to ENS (scheme may further re-route before per-bus, but here it is local).
            if (tau > SimulationConstants.EPSILON && deficitTauKw > SimulationConstants.EPSILON) {
                double ensTau = deficitTauKw * tau;
                ctx.totals.ensKwh += ensTau;
                ctx.totals.startEnsKwh += ensTau;
                EnsAllocator.addEnsByCategoryProportional(ctx.totals, loadKw, ensTau, ctx.cat1, ctx.cat2);
                ctx.status.set(HourContext.StatusCollector.PRI_BLACKOUT, "DG_START_DELAY_ENS");
            }

            // ===== 7) Steady interval: discharge BESS if still deficit remains =====
            // Steady deficit after DG at steady power.
            double steadyNeedFromBtKw = Math.max(0.0, deficitAfterWindKw - dgSteadyTotalKw);

            // But if we reduced DG count for fuel saving, the missing power is also a BESS steady need.
            // (Already included in steadyNeedFromBtKw because dgSteadyTotalKw uses nOn.)

            // Decide how much can be treated as non-reserve (SOC floor with add) and how much as reserve.
            if (steadyNeedFromBtKw > SimulationConstants.EPSILON && btAvail) {
                double btCapKw = batteryMaxDischargeKw(bt, ctx);
                double reqKw = Math.min(steadyNeedFromBtKw, btCapKw);

                // Non-reserve allowance energy (above SOC_MIN+add).
                double nonResAvailKwh = bt.getAvailableDischargeEnergyKwhAbove(socNonReserveFloor);
                double nonResMaxKw = Math.min(reqKw, nonResAvailKwh); // because duration=1h for this phase

                double disNonResKw = 0.0;
                if (nonResMaxKw > SimulationConstants.EPSILON) {
                    disNonResKw = dischargeBattery(bt, ctx, nonResMaxKw, 1.0 - tau, false);
                    btNetKw += disNonResKw * (1.0 - tau);
                    reqKw = Math.max(0.0, reqKw - disNonResKw);
                }

                // Reserve part down to SOC_MIN.
                if (reqKw > SimulationConstants.EPSILON) {
                    double resAvailKwh = bt.getAvailableDischargeEnergyKwhAbove(SimulationConstants.BATTERY_MIN_SOC);
                    double resMaxKw = Math.min(reqKw, resAvailKwh / Math.max(1e-9, (1.0 - tau)));
                    double disResKw = dischargeBattery(bt, ctx, resMaxKw, 1.0 - tau, false);
                    btNetKw += disResKw * (1.0 - tau);
                }
            }

            // ===== 8) Write DG average loads for the hour (tau + steady) =====
            List<DieselGenerator> toRun = selectDgsToRun(dgs, nOn);
            for (DieselGenerator dg : dgs) {
                dg.setCurrentLoad(0.0);
                dg.stopWork();
                dg.setIdle(false);
            }

            int used = 0;
            for (DieselGenerator dg : toRun) {
                boolean wasWorking = dg.wasWorkingAtHourStart();
                double avgKw;
                if (tau <= SimulationConstants.EPSILON) {
                    avgKw = perDgSteadyKw;
                } else {
                    if (wasWorking) {
                        double perReadyStartKw = (nAlready > 0)
                                ? Math.min(ctx.dgMaxKw, deficitAfterWindKw / nAlready)
                                : 0.0;
                        avgKw = perReadyStartKw * tau + perDgSteadyKw * (1.0 - tau);
                    } else {
                        avgKw = perDgSteadyKw * (1.0 - tau);
                    }
                }

                if (avgKw > ctx.dgMaxKw) avgKw = ctx.dgMaxKw;
                if (avgKw < 0.0) avgKw = 0.0;

                dg.setCurrentLoad(avgKw);
                dg.addWorkTime(1, wasWorking ? 1 : 1 + SimulationConstants.DG_MAX_START_FACTOR);
                dg.startWork();
                used++;
                if (used >= nOn) break;
            }

            SingleRunSimulator.finalizeIdleAndBurn(ctx, dgs, ctx.dgMinKw);
            SingleRunSimulator.finalizeStoppedDgs(dgs);

            // Total diesel to load is the part not used to charge (we do not charge from diesel in this dispatcher).
            double sumDieselKw = 0.0;
            for (DieselGenerator dg : dgs) {
                if (!dg.isAvailable()) continue;
                double p = dg.getCurrentLoad();
                if (p > SimulationConstants.EPSILON) sumDieselKw += p;
            }
            dgToLoadKw = Math.min(deficitAfterWindKw, sumDieselKw);
        }

        // ===== Totals / ENS / UFLS =====
        ctx.totals.wtToLoadKwh += windToLoadKw;
        ctx.totals.dgToLoadKwh += dgToLoadKw;
        ctx.totals.btToLoadKwh += Math.max(0.0, btNetKw);
        ctx.totals.wreKwh += wreKw;
        if (ctx.hourWreRef != null) ctx.hourWreRef[0] += wreKw;

        ctx.totals.fuelLiters += SingleRunSimulator.computeFuelLitersOneHour(bus.getDieselGenerators(), ctx.dgRatedKw);

        double genForLoadKw = windToLoadKw + dgToLoadKw + Math.max(0.0, btNetKw);
        double deficitKw = Math.max(0.0, loadKw - genForLoadKw);

        if (deficitKw > SimulationConstants.EPSILON) {
            double shedKw = uflsEnsRounded(loadKw, deficitKw);
            if (shedKw > SimulationConstants.EPSILON) {
                ctx.totals.ensKwh += shedKw;
                EnsAllocator.addEnsByCategoryPriority321(ctx.totals, loadKw, shedKw, ctx.cat1, ctx.cat2);
                int pct = (int) Math.round(100.0 * (shedKw / Math.max(loadKw, SimulationConstants.EPSILON)));
                ctx.status.set(HourContext.StatusCollector.PRI_UFLS, "UFLS_SHED_" + pct + "%");
            }
            deficitKw = shedKw;
        }

        if (ctx.trace.enabled()) {
            ctx.trace.setBusValues(b, true, loadKw, windToLoadKw, dgToLoadKw, btNetKw, deficitKw);
            ctx.trace.fillDgState(b, bus);
            ctx.trace.fillBatteryState(b, bt);
        }
    }
}
