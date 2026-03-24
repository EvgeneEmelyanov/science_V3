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
            if (dg.wasStartCapableAtHourStart()) c++;
        }
        return c;
    }

    private static void clearInstantStartReadyNextHour(DieselGenerator[] dgs) {
        for (DieselGenerator dg : dgs) {
            dg.clearInstantStartReadyNextHour();
        }
    }

    private static void markExactlyOneDgInstantStartReadyNextHour(DieselGenerator[] dgs) {
        DieselGenerator chosen = null;

        for (DieselGenerator dg : dgs) {
            if (!dg.isAvailable()) continue;
            if (dg.wasWorkingAtHourStart()) {
                chosen = dg;
                break;
            }
        }
        if (chosen == null) {
            for (DieselGenerator dg : dgs) {
                if (!dg.isAvailable()) continue;
                chosen = dg;
                break;
            }
        }

        clearInstantStartReadyNextHour(dgs);
        if (chosen != null) {
            chosen.markInstantStartReadyNextHour();
        }
    }

    private static List<DieselGenerator> selectDgsToRun(DieselGenerator[] dgs, int nOn) {
        ArrayList<DieselGenerator> out = new ArrayList<>(nOn);

        // Prefer those already working or explicitly ready for instant start at hour start.
        for (DieselGenerator dg : dgs) {
            if (out.size() >= nOn) break;
            if (!dg.isAvailable()) continue;
            if (dg.wasStartCapableAtHourStart()) out.add(dg);
        }
        for (DieselGenerator dg : dgs) {
            if (out.size() >= nOn) break;
            if (!dg.isAvailable()) continue;
            if (!dg.wasStartCapableAtHourStart()) out.add(dg);
        }
        return out;
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

    private static boolean canBatteryGridFormWindSurplus(Battery bt, HourContext ctx, double dispatchLoadKw) {
        if (bt == null || !bt.isAvailable()) return false;
        if (dispatchLoadKw <= SimulationConstants.EPSILON) return true;

        double reserveShare = Math.max(0.0, Math.min(1.0, ctx.sp.getBtGridFormingReserveShare()));
        double requiredPowerKw = reserveShare * dispatchLoadKw;
        if (requiredPowerKw <= SimulationConstants.EPSILON) return true;

        double btCapKw = batteryMaxDischargeKw(bt, ctx);
        if (btCapKw + SimulationConstants.EPSILON < requiredPowerKw) return false;

        int requiredDgCount = (int) Math.ceil(requiredPowerKw / ctx.dgRatedKw - SimulationConstants.EPSILON);
        if (requiredDgCount <= 0) requiredDgCount = 1;

        double requiredEnergyKwh = requiredDgCount * ctx.dgRatedKw * ctx.dgStartDelayHours;
        double availKwh = bt.getAvailableDischargeEnergyKwhAbove(SimulationConstants.BATTERY_MIN_SOC);

        return availKwh + SimulationConstants.EPSILON >= requiredEnergyKwh;
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
            double loadKw,
            double p1LoadKw,
            double p2LoadKw,
            double p3LoadKw
    ) {
        dispatchOneBusOneHourWithExtraSources(ctx, bus, null, busEnergised, b, loadKw, p1LoadKw, p2LoadKw, p3LoadKw);
    }

    static void dispatchOneBusOneHourWithExtraSources(
            HourContext ctx,
            PowerBus bus,
            PowerBus extraSourceBus,
            boolean busEnergised,
            int b,
            double loadKw,
            double p1LoadKw,
            double p2LoadKw,
            double p3LoadKw
    ) {
        final double originalLoadKw = loadKw;
        ctx.totals.loadKwh += originalLoadKw;

        final Battery bt = bus.getBattery();
        final boolean btAvail = bt != null && bt.isAvailable();
        if (bt != null) {
            bt.setCurrentNonReserveDischargeLevelForTrace(bt.getEffectiveNonReserveDischargeLevel(ctx.sp));
        }

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

        // Effective category shares for reserve heuristics on this bus this hour.
        double tot = Math.max(0.0, p1LoadKw) + Math.max(0.0, p2LoadKw) + Math.max(0.0, p3LoadKw);
        double effCat1 = (tot > SimulationConstants.EPSILON) ? (Math.max(0.0, p1LoadKw) / tot) : ctx.cat1;
        double effCat2 = (tot > SimulationConstants.EPSILON) ? (Math.max(0.0, p2LoadKw) / tot) : ctx.cat2;

        // Hard outage: bus not energised -> 100% ENS (scheme rules handle transfers before this call).
        if (!busEnergised || (!hasAnyGridFormingEquipmentOnBus(bus) && (extraSourceBus == null || !hasAnyGridFormingEquipmentOnBus(extraSourceBus)))) {
            DieselGenerator.stopAllDieselsOnBus(bus);
            if (extraSourceBus != null) DieselGenerator.stopAllDieselsOnBus(extraSourceBus);

            ctx.totals.ensKwh += loadKw;
            EnsAllocator.addEnsByBucketsProportional(ctx.totals, loadKw, p1LoadKw, p2LoadKw, p3LoadKw);
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

        // ===== UFLS should be decided BEFORE dispatch =====
        double preShedKw = 0.0;
        double dispatchLoadKw = originalLoadKw;
        if (dispatchLoadKw > SimulationConstants.EPSILON
                && windPotKw < dispatchLoadKw - SimulationConstants.EPSILON) {

            int nWorkingAtStartForMax = countWorkingAtStart(dgs);
            double tauRawForMax = DieselGenerator.isMaintenanceStartedThisHour(dgs) ? 0.0 : ctx.dgStartDelayHours;
            double tauMax = (availableDg > nWorkingAtStartForMax) ? tauRawForMax : 0.0;

            // Max average DG power this hour if we start all available DGs.
            double dgMaxAvgKw = Math.max(0.0,
                    (nWorkingAtStartForMax * ctx.dgMaxKw)
                            + ((availableDg - nWorkingAtStartForMax) * ctx.dgMaxKw * Math.max(0.0, 1.0 - tauMax))
            );

            // Max average BESS discharge this hour down to SOC_MIN.
            double btMaxAvgKw = 0.0;
            if (btAvail) {
                double capKw = batteryMaxDischargeKw(bt, ctx);
                double availKwh = bt.getAvailableDischargeEnergyKwhAbove(SimulationConstants.BATTERY_MIN_SOC);
                btMaxAvgKw = Math.min(capKw, availKwh); // duration=1h
            }

            double maxSupplyKw = windPotKw + dgMaxAvgKw + btMaxAvgKw;
            double deficitIfMaxKw = Math.max(0.0, dispatchLoadKw - maxSupplyKw);
            if (deficitIfMaxKw > SimulationConstants.EPSILON) {
                preShedKw = uflsEnsRounded(dispatchLoadKw, deficitIfMaxKw);
                if (preShedKw > SimulationConstants.EPSILON) {
                    dispatchLoadKw = Math.max(0.0, dispatchLoadKw - preShedKw);
                    ctx.totals.ensKwh += preShedKw;
                    EnsAllocator.addEnsByBucketsPriority321(ctx.totals, preShedKw, p1LoadKw, p2LoadKw, p3LoadKw);
                    int pct = (int) Math.round(100.0 * (preShedKw / Math.max(originalLoadKw, SimulationConstants.EPSILON)));
                    ctx.status.set(HourContext.StatusCollector.PRI_UFLS, "UFLS_SHED_" + pct + "%");
                }
            }
        }

        double windToLoadKw = 0.0;
        double dgToLoadKw = 0.0;
        double btNetKw = 0.0; // + discharge, - charge (average over the hour)
        double wreKw = 0.0;

        double startEnsRawKwh = 0.0; // raw ENS energy during DG start delay (tau), attributed later

        // ===== CASE A: WT >= load =====
        if (windPotKw >= dispatchLoadKw - SimulationConstants.EPSILON) {

            // Wind may form the bus via BESS inverter only if the battery can cover
            // the configured reserve share of load by power and by energy for DG start.
            boolean btCanGridFormInWindSurplus = btAvail && canBatteryGridFormWindSurplus(bt, ctx, dispatchLoadKw);
            if (btCanGridFormInWindSurplus) {
                DieselGenerator.stopAllDieselsOnBus(bus);
                if (extraSourceBus != null) DieselGenerator.stopAllDieselsOnBus(extraSourceBus);

                windToLoadKw = dispatchLoadKw;
                double surplusKw = Math.max(0.0, windPotKw - windToLoadKw);

                // Charge BESS from WT surplus.
                double ch = chargeBattery(bt, ctx, surplusKw, 1.0);
                btNetKw -= ch;
                surplusKw -= ch;

                wreKw = Math.max(0.0, surplusKw);

                boolean keepOneInstantReadyNextHour =
                        ctx.sp.isKeepOneDgInstantStartReadyAfterWtBessGridForming()
                                && windPotKw > dispatchLoadKw + SimulationConstants.EPSILON
                                && availableDg > 0;

                if (keepOneInstantReadyNextHour) {
                    markExactlyOneDgInstantStartReadyNextHour(dgs);
                    ctx.status.set(HourContext.StatusCollector.PRI_RESERVE,
                            "WT_BESS_GRID_FORMING_KEEP_1_DG_INSTANT_READY_NEXT_HOUR");
                } else {
                    clearInstantStartReadyNextHour(dgs);
                }

            } else {
                // Battery may be insufficient for grid-forming reserve, but it still may be charged
                // from WT surplus. The reserve check must not disable charging.
                if (availableDg > 0) {

                    SingleRunSimulator.applyIdleReserveInWindSurplus(
                            bus,
                            ctx.sp,
                            ctx.hourIndex,
                            dispatchLoadKw,
                            dispatchLoadKw,
                            effCat1,
                            effCat2,
                            false,
                            bt,
                            ctx.dgRatedKw,
                            ctx.dgMinKw,
                            0.0
                    );

                    double dgSumKw = 0.0;
                    for (DieselGenerator dg : dgs) {
                        if (!dg.isAvailable()) continue;
                        double p = dg.getCurrentLoad();
                        if (p > SimulationConstants.EPSILON) dgSumKw += p;
                    }

                    dgToLoadKw = Math.min(dispatchLoadKw, dgSumKw);
                    windToLoadKw = Math.max(0.0, dispatchLoadKw - dgToLoadKw);

                    double surplusKw = Math.max(0.0, windPotKw - windToLoadKw);
                    if (btAvail && surplusKw > SimulationConstants.EPSILON) {
                        double ch = chargeBattery(bt, ctx, surplusKw, 1.0);
                        btNetKw -= ch;
                        surplusKw -= ch;
                    }

                    wreKw = Math.max(0.0, surplusKw);
                    ctx.status.set(HourContext.StatusCollector.PRI_NORMAL, "WT_GE_LOAD_NO_BESS_DG_RESERVE");
                } else {
                    windToLoadKw = dispatchLoadKw;

                    double surplusKw = Math.max(0.0, windPotKw - windToLoadKw);
                    if (btAvail && surplusKw > SimulationConstants.EPSILON) {
                        double ch = chargeBattery(bt, ctx, surplusKw, 1.0);
                        btNetKw -= ch;
                        surplusKw -= ch;
                    }

                    wreKw = Math.max(0.0, surplusKw);
                    ctx.status.set(HourContext.StatusCollector.PRI_RESERVE,
                            "WT_GE_LOAD_BESS_NOT_ENOUGH_FOR_GRID_FORMING_RESERVE");
                }
            }

            SingleRunSimulator.finalizeIdleAndBurn(ctx, dgs, ctx.dgMinKw);
            SingleRunSimulator.finalizeStoppedDgs(dgs);

        } else {
            // ===== CASE B: WT < load =====
            windToLoadKw = windPotKw;
            double deficitAfterWindKw = Math.max(0.0, dispatchLoadKw - windToLoadKw);

            // ===== 0) Special case: BESS can cover the entire deficit for the whole hour (fuel-saving) =====
            if (btAvail && deficitAfterWindKw > SimulationConstants.EPSILON) {
                int naturalNeed0ByMax = (ctx.dgMaxKw > SimulationConstants.EPSILON)
                        ? (int) Math.ceil(deficitAfterWindKw / ctx.dgMaxKw)
                        : availableDg;
                int naturalNeed0ByOptimal = (ctx.perDgOptimalKw > SimulationConstants.EPSILON)
                        ? (int) Math.ceil(deficitAfterWindKw / ctx.perDgOptimalKw)
                        : naturalNeed0ByMax;
                int naturalNeed0 = Math.min(Math.max(0, Math.max(naturalNeed0ByMax, naturalNeed0ByOptimal)), Math.max(0, availableDg));

                double nonReserveFloor = Math.max(
                        SimulationConstants.BATTERY_MIN_SOC,
                        bt.previewAdaptiveNonReserveFloorForCandidate(ctx.sp, naturalNeed0, 0)
                );
                bt.setCurrentNonReserveDischargeLevelForTrace(nonReserveFloor);
                double btCapKw0 = batteryMaxDischargeKw(bt, ctx);
                double needKw0 = deficitAfterWindKw;
                double needKwh0 = needKw0; // 1 hour

                double availKwh0 = bt.getAvailableDischargeEnergyKwhAbove(nonReserveFloor);

                if (needKw0 <= btCapKw0 + SimulationConstants.EPSILON
                        && availKwh0 + SimulationConstants.EPSILON >= needKwh0) {

                    bt.commitAdaptiveNonReserveFloorForCandidate(ctx.sp, naturalNeed0, 0);
                    DieselGenerator.stopAllDieselsOnBus(bus);
                    if (extraSourceBus != null) DieselGenerator.stopAllDieselsOnBus(extraSourceBus);

                    double actualDisKw0 = dischargeBattery(bt, ctx, needKw0, 1.0, false);
                    btNetKw += actualDisKw0; // full hour average
                    dgToLoadKw = 0.0;

                    ctx.status.set(HourContext.StatusCollector.PRI_NORMAL, "BESS_SUPPLIES_DEFICIT_NO_DG");

                    ctx.totals.wtToLoadKwh += windToLoadKw;
                    ctx.totals.dgToLoadKwh += 0.0;
                    ctx.totals.btToLoadKwh += Math.max(0.0, btNetKw);
                    ctx.totals.wreKwh += 0.0;
                    ctx.totals.fuelLiters += SingleRunSimulator.computeFuelLitersOneHour(bus.getDieselGenerators(), ctx.dgRatedKw);

                    if (ctx.trace.enabled()) {
                        ctx.trace.setBusValues(b, true, originalLoadKw, windToLoadKw, 0.0, btNetKw, preShedKw);
                        ctx.trace.fillDgState(b, bus);
                        ctx.trace.fillBatteryState(b, bt);
                    }
                    return;
                }
            }

            final double tauRaw = DieselGenerator.isMaintenanceStartedThisHour(dgs) ? 0.0 : ctx.dgStartDelayHours;
            double tau = tauRaw;

            // ===== 1) DG count by max power AND by optimal power =====
            int nAvail = availableDg;

            int nNeededByMax = (ctx.dgMaxKw > SimulationConstants.EPSILON)
                    ? (int) Math.ceil(deficitAfterWindKw / ctx.dgMaxKw)
                    : nAvail;

            int nNeededByOptimal = (ctx.perDgOptimalKw > SimulationConstants.EPSILON)
                    ? (int) Math.ceil(deficitAfterWindKw / ctx.perDgOptimalKw)
                    : nNeededByMax;

            // если есть возможность не превышать оптимум — включаем больше ДГУ
            int nPlannedRaw = Math.max(nNeededByMax, nNeededByOptimal);
            int nPlanned = Math.min(Math.max(0, nPlannedRaw), Math.max(0, nAvail));
            final int naturalNeedDgCount = nPlanned;

            // ===== 2) Non-reserve BESS use: reduce DG count if possible =====
            double socNonReserveFloor = btAvail
                    ? Math.max(SimulationConstants.BATTERY_MIN_SOC,
                    bt.previewAdaptiveNonReserveFloorForCandidate(ctx.sp, naturalNeedDgCount, nPlanned))
                    : SimulationConstants.BATTERY_MIN_SOC;

            int nPlannedReduced = nPlanned;
            if (btAvail && nAvail > 0) {
                while (nPlannedReduced > 1) {
                    int cand = nPlannedReduced - 1;
//                    double needFromBtKw = Math.max(0.0, deficitAfterWindKw - cand * ctx.dgMaxKw);
                    double needFromBtKw = Math.max(0.0, deficitAfterWindKw - cand * ctx.perDgOptimalKw);
                    if (needFromBtKw <= SimulationConstants.EPSILON) {
                        nPlannedReduced = cand;
                        continue;
                    }
                    double btCapKw = batteryMaxDischargeKw(bt, ctx);
                    if (btCapKw + SimulationConstants.EPSILON < needFromBtKw) break;

                    double candFloor = Math.max(SimulationConstants.BATTERY_MIN_SOC,
                            bt.previewAdaptiveNonReserveFloorForCandidate(ctx.sp, naturalNeedDgCount, cand));
                    double needEnergyKwh = needFromBtKw * 1.0;
                    double availEnergyKwh = bt.getAvailableDischargeEnergyKwhAbove(candFloor);
                    if (availEnergyKwh + SimulationConstants.EPSILON < needEnergyKwh) break;

                    socNonReserveFloor = candFloor;
                    nPlannedReduced = cand;
                }
            }
            nPlanned = nPlannedReduced;
            if (btAvail) {
                bt.commitAdaptiveNonReserveFloorForCandidate(ctx.sp, naturalNeedDgCount, nPlanned);
            }

            // ===== 3) Rotation reserve (N-1) =====
            int nOn = nPlanned;
            if (ctx.considerRotationReserve && nOn < nAvail) {
                double criticalRatio = effCat1 + effCat2;
                if (ctx.reserveThirdCategory) criticalRatio = 1.0;
                criticalRatio = Math.max(0.0, Math.min(1.0, criticalRatio));

                double criticalLoadKw = dispatchLoadKw * criticalRatio;
                double criticalDefAfterWind = Math.max(0.0, criticalLoadKw - windToLoadKw);

                double remainingDgMaxKw = Math.max(0.0, (nOn - 1) * ctx.dgMaxKw);
                double gapKw;
                if (ModelDefaults.CFG_USE_AVG_LOAD_RESERVE_POLICY) {
                    gapKw = SingleRunSimulator.getAvgLoadPerBusKw() * ctx.sp.getRotationReserveCoeff();
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

            if (nNew == 0) {
                tau = 0.0;
            }

            // ===== 5) DG dispatch (start interval tau + steady interval 1-tau) =====
            double dgSteadyTotalKw = Math.min(deficitAfterWindKw, nOn * ctx.dgMaxKw);
            double perDgSteadyKw = 0.0;
            if (nOn > 0) {
                double target = deficitAfterWindKw / nOn;
                double optimal = ctx.perDgOptimalKw;
                if (target <= optimal) {
                    perDgSteadyKw = target;
                } else {
                    if (optimal * nOn >= deficitAfterWindKw) {
                        perDgSteadyKw = optimal;
                    } else {
                        perDgSteadyKw = Math.min(ctx.dgMaxKw, target);
                    }
                }
            }

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
                    double btCapKw = batteryMaxDischargeKw(bt, ctx);
                    double btDisKw = Math.min(deficitTauKw, btCapKw);

                    double availKwh = btAvail
                            ? bt.getAvailableDischargeEnergyKwhAbove(SimulationConstants.BATTERY_MIN_SOC)
                            : 0.0;

                    double needKwh = btDisKw * tau;
                    if (btAvail && availKwh + SimulationConstants.EPSILON >= needKwh) {
                        double actualDisKw = dischargeBattery(bt, ctx, btDisKw, tau, true);
                        btNetKw += actualDisKw * tau;
                        deficitTauKw = Math.max(0.0, deficitTauKw - actualDisKw);
                    }
                }
            }

            if (tau > SimulationConstants.EPSILON && deficitTauKw > SimulationConstants.EPSILON) {
                startEnsRawKwh = deficitTauKw * tau;
                ctx.status.set(HourContext.StatusCollector.PRI_BLACKOUT, "DG_START_DELAY_ENS");
            }

            // ===== 7) Steady interval: discharge BESS if still deficit remains =====
            double steadyNeedFromBtKw = Math.max(0.0, deficitAfterWindKw - dgSteadyTotalKw);

            if (steadyNeedFromBtKw > SimulationConstants.EPSILON && btAvail) {
                double btCapKw = batteryMaxDischargeKw(bt, ctx);
                double reqKw = Math.min(steadyNeedFromBtKw, btCapKw);

                double nonResAvailKwh = bt.getAvailableDischargeEnergyKwhAbove(socNonReserveFloor);
                double nonResMaxKw = Math.min(reqKw, nonResAvailKwh); // duration ~ 1h

                double disNonResKw = 0.0;
                if (nonResMaxKw > SimulationConstants.EPSILON) {
                    disNonResKw = dischargeBattery(bt, ctx, nonResMaxKw, 1.0 - tau, false);
                    btNetKw += disNonResKw * (1.0 - tau);
                    reqKw = Math.max(0.0, reqKw - disNonResKw);
                }

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
                boolean wasReadyAtStart = dg.wasStartCapableAtHourStart();
                double avgKw;
                if (tau <= SimulationConstants.EPSILON) {
                    avgKw = perDgSteadyKw;
                } else {
                    if (wasReadyAtStart) {
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
                dg.addWorkTime(1, wasReadyAtStart ? 1 : 1 + SimulationConstants.DG_MAX_START_FACTOR);
                dg.startWork();
                used++;
                if (used >= nOn) break;
            }

            SingleRunSimulator.finalizeIdleAndBurn(ctx, dgs, ctx.dgMinKw);
            SingleRunSimulator.finalizeStoppedDgs(dgs);

            double sumDieselKw = 0.0;
            for (DieselGenerator dg : dgs) {
                if (!dg.isAvailable()) continue;
                double p = dg.getCurrentLoad();
                if (p > SimulationConstants.EPSILON) sumDieselKw += p;
            }
            dgToLoadKw = Math.min(deficitAfterWindKw, sumDieselKw);
        }

        double genForLoadKw = windToLoadKw + dgToLoadKw + Math.max(0.0, btNetKw);
        double deficitKw = Math.max(0.0, dispatchLoadKw - genForLoadKw);

        if (deficitKw > SimulationConstants.EPSILON) {
            double rawDeficitKw = deficitKw;
            double shedKw = uflsEnsRounded(dispatchLoadKw, rawDeficitKw);
            if (shedKw > SimulationConstants.EPSILON) {

                double rawDeficitKwh = deficitKw;
                double shedKwh = uflsEnsRounded(dispatchLoadKw, rawDeficitKwh);
                if (shedKwh > SimulationConstants.EPSILON) {

                    if (startEnsRawKwh > SimulationConstants.EPSILON) {
                        double startPartKwh = (rawDeficitKwh <= startEnsRawKwh + SimulationConstants.EPSILON)
                                ? shedKwh
                                : Math.min(shedKwh, startEnsRawKwh);

                        ctx.totals.startEnsKwh += startPartKwh;
                    }

                    ctx.totals.ensKwh += shedKwh;
                    EnsAllocator.addEnsByBucketsPriority321(ctx.totals, shedKwh, p1LoadKw, p2LoadKw, p3LoadKw);
                    int pct = (int) Math.round(100.0 * (shedKwh / Math.max(originalLoadKw, SimulationConstants.EPSILON)));
                    ctx.status.set(HourContext.StatusCollector.PRI_UFLS, "UFLS_SHED_" + pct + "%");
                    deficitKw = shedKwh;
                }

                EnsAllocator.addEnsByBucketsPriority321(ctx.totals, shedKw, p1LoadKw, p2LoadKw, p3LoadKw);
                int pct = (int) Math.round(100.0 * (shedKw / Math.max(originalLoadKw, SimulationConstants.EPSILON)));
                ctx.status.set(HourContext.StatusCollector.PRI_UFLS, "UFLS_SHED_" + pct + "%");
                deficitKw = shedKw;
            }
        }

        // ===== Totals / ENS / UFLS =====
        ctx.totals.wtToLoadKwh += windToLoadKw;
        ctx.totals.dgToLoadKwh += dgToLoadKw;
        ctx.totals.btToLoadKwh += Math.max(0.0, btNetKw);
        ctx.totals.wreKwh += wreKw;
        if (ctx.hourWreRef != null) ctx.hourWreRef[0] += wreKw;

        ctx.totals.fuelLiters += SingleRunSimulator.computeFuelLitersOneHour(bus.getDieselGenerators(), ctx.dgRatedKw);

        if (ctx.trace.enabled()) {
            ctx.trace.setBusValues(b, true, originalLoadKw, windToLoadKw, dgToLoadKw, btNetKw, preShedKw + deficitKw);
            ctx.trace.fillDgState(b, bus);
            ctx.trace.fillBatteryState(b, bt);
        }
    }
}