package simcore.engine;

import simcore.config.SimulationConstants;
import simcore.model.*;

import java.util.Arrays;

/**
 * Dispatching logic for one hour when the sectional tie breaker is closed.
 * Extracted from {@link SingleRunSimulator} to reduce file size and improve testability.
 */
final class SectionalClosedDispatcher {
    private SectionalClosedDispatcher() {}

    private static boolean isStartCapableAtHourStart(DieselGenerator dg) {
        return dg != null && dg.isAvailable() && dg.wasStartCapableAtHourStart();
    }


    // Start-delay-aware DG maximum average power available in the current hour (kW).
// Used to decide whether BESS discharge is needed at all in sectional-closed / coupled modes.
    private static double dgMaxAvgOneHour(PowerBus bus, double dgMaxKw, double dgStartDelayHours) {
        if (bus == null) return 0.0;

        DieselGenerator[] dgs = DieselGenerator.getSortedDgs(bus);

        int available = 0;
        int workingAtStart = 0;
        for (DieselGenerator dg : dgs) {
            if (!dg.isAvailable()) continue;
            available++;
            if (dg.wasStartCapableAtHourStart()) workingAtStart++;
        }

        double tauRaw = DieselGenerator.isMaintenanceStartedThisHour(dgs) ? 0.0 : dgStartDelayHours;
        double tau = (available > workingAtStart) ? tauRaw : 0.0;

        return Math.max(0.0,
                (workingAtStart * dgMaxKw)
                        + ((available - workingAtStart) * dgMaxKw * Math.max(0.0, 1.0 - tau))
        );
    }

    /**
     * Per-thread reusable buffers to avoid per-hour allocations in the hottest dispatching path.
     * SOBOL/MC can run in parallel, so we keep buffers thread-local.
     */
    private static final ThreadLocal<Buffers> BUFFERS = ThreadLocal.withInitial(Buffers::new);

    private static final class Buffers {
        final double[] windToLoad = new double[2];
        final double[] btNet = new double[2];
        final double[] dgToLoad = new double[2];
        final double[] def = new double[2];
        final double[] startEnsByBus = new double[2];
        DieselGenerator[] dgs = new DieselGenerator[0];

        void ensureDgCapacity(int n) {
            if (dgs.length != n) {
                dgs = new DieselGenerator[n];
            }
        }
    }


    static SectionalClosedResult dispatchSectionalClosedOneHour(
            HourContext ctx,
            java.util.List<PowerBus> buses,
            double[] loadByBus
    ) {
        PowerBus b0 = buses.get(0);
        PowerBus b1 = buses.get(1);
        b0.addWorkTime(1);
        b1.addWorkTime(1);

        double load0 = loadByBus[0];
        double load1 = loadByBus[1];
        double totalLoad = load0 + load1;

        double windPot0 = SingleRunSimulator.computeWindPotential(b0, ctx.windV);
        double windPot1 = SingleRunSimulator.computeWindPotential(b1, ctx.windV);
        double windPot = windPot0 + windPot1;

        Buffers buf = BUFFERS.get();

        double[] windToLoad = buf.windToLoad;
        double[] btNet = buf.btNet;
        double[] dgToLoad = buf.dgToLoad;
        double[] def = buf.def;
        double[] startEnsByBus = buf.startEnsByBus;

        // reset per-hour buffers
        windToLoad[0] = windToLoad[1] = 0.0;
        btNet[0] = btNet[1] = 0.0;
        dgToLoad[0] = dgToLoad[1] = 0.0;
        def[0] = def[1] = 0.0;
        startEnsByBus[0] = startEnsByBus[1] = 0.0;
        if (totalLoad > SimulationConstants.EPSILON) {
            windToLoad[0] = Math.min(load0, windPot * (load0 / totalLoad));
            windToLoad[1] = Math.min(load1, windPot * (load1 / totalLoad));
        }
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
        if (bt0 != null) bt0.setCurrentNonReserveDischargeLevelForTrace(bt0.getEffectiveNonReserveDischargeLevel(ctx.sp));
        if (bt1 != null) bt1.setCurrentNonReserveDischargeLevelForTrace(bt1.getEffectiveNonReserveDischargeLevel(ctx.sp));


// ===== Battery discharge (sectional closed): use BESS only for the residual that DGs cannot cover
// (start-delay-aware max average DG supply), and cap discharge by energy above SOC_MIN (1 hour window).
        double bt0DisCap = bt0Avail ? bt0.getDischargePowerCapKw(ctx.sp) : 0.0;
        double bt1DisCap = bt1Avail ? bt1.getDischargePowerCapKw(ctx.sp) : 0.0;

        double dg0MaxAvg = dgMaxAvgOneHour(b0, ctx.dgMaxKw, ctx.dgStartDelayHours);
        double dg1MaxAvg = dgMaxAvgOneHour(b1, ctx.dgMaxKw, ctx.dgStartDelayHours);
        double totalRemAfterWind = Math.max(0.0, rem0) + Math.max(0.0, rem1);
        double totalDgMaxAvg = Math.max(0.0, dg0MaxAvg) + Math.max(0.0, dg1MaxAvg);

        double needBessTotal = Math.max(0.0, totalRemAfterWind - totalDgMaxAvg);

        double disTotal = 0.0;
        if (needBessTotal > SimulationConstants.EPSILON) {
            // discharge BT0 first, then BT1 (can be changed later to another priority policy)
            if (bt0Avail && bt0DisCap > SimulationConstants.EPSILON) {
                double byEnergy0 = bt0.getAvailableDischargeEnergyKwhAbove(SimulationConstants.BATTERY_MIN_SOC);
                double can0 = Math.min(bt0DisCap, byEnergy0); // 1h
                double dis0 = Math.min(can0, needBessTotal - disTotal);
                if (dis0 > SimulationConstants.EPSILON) {
                    bt0.adjustCapacity(bt0, -dis0, dis0, false, ctx.considerDegradation);
                    disTotal += dis0;
                }
            }
            if (bt1Avail && bt1DisCap > SimulationConstants.EPSILON && (needBessTotal - disTotal) > SimulationConstants.EPSILON) {
                double byEnergy1 = bt1.getAvailableDischargeEnergyKwhAbove(SimulationConstants.BATTERY_MIN_SOC);
                double can1 = Math.min(bt1DisCap, byEnergy1); // 1h
                double dis1 = Math.min(can1, needBessTotal - disTotal);
                if (dis1 > SimulationConstants.EPSILON) {
                    bt1.adjustCapacity(bt1, -dis1, dis1, false, ctx.considerDegradation);
                    disTotal += dis1;
                }
            }

            // allocate discharged power to buses proportionally to their remaining demand after wind
            if (totalRemAfterWind > SimulationConstants.EPSILON && disTotal > SimulationConstants.EPSILON) {
                double share0 = Math.max(0.0, rem0) / totalRemAfterWind;
                double share1 = 1.0 - share0;

                double disTo0 = disTotal * share0;
                double disTo1 = disTotal * share1;

                rem0 = Math.max(0.0, rem0 - disTo0);
                rem1 = Math.max(0.0, rem1 - disTo1);

                btNet[0] += disTo0; // positive = discharge to load
                btNet[1] += disTo1;
            }
        }

        double btDisToLoadTotal = Math.max(0.0, btNet[0]) + Math.max(0.0, btNet[1]);
        double deficitAfterWindBt = rem0 + rem1;

        int n0 = b0.getDieselGenerators().size();
        int n1 = b1.getDieselGenerators().size();
        int n = n0 + n1;
        buf.ensureDgCapacity(n);
        DieselGenerator[] dgs = buf.dgs;
        int p = 0;
        for (int i = 0; i < n0; i++) dgs[p++] = b0.getDieselGenerators().get(i);
        for (int i = 0; i < n1; i++) dgs[p++] = b1.getDieselGenerators().get(i);
        Arrays.sort(dgs, DieselGenerator.DISPATCH_COMPARATOR);
        Arrays.sort(dgs, (a, b) -> {
            boolean aReady = isStartCapableAtHourStart(a);
            boolean bReady = isStartCapableAtHourStart(b);
            if (aReady != bReady) return aReady ? -1 : 1;
            if (a.isWorking() != b.isWorking()) return a.isWorking() ? -1 : 1;
            return Integer.compare(a.getTotalTimeWorked(), b.getTotalTimeWorked());
        });

        int available = 0;
        int readyWorking = 0;
        for (DieselGenerator dg : dgs) {
            if (dg.isAvailable()) available++;
            if (dg.wasStartCapableAtHourStart()) readyWorking++;
        }

        double dgProducedKw = 0.0;
        double dgToLoadTotal = 0.0;
        double wre = 0.0;
        double startDelayEnsEstimateKwh = 0.0;

        if (windPot >= totalLoad - SimulationConstants.EPSILON) {
            double surplusKw = Math.max(0.0, windPot - totalLoad);

            if (bt0Avail && bt0.getStateOfCharge() < SimulationConstants.BATTERY_MAX_SOC - SimulationConstants.EPSILON) {
                double cap = bt0.getChargePowerCapKw(ctx.sp);
                double ch = Math.min(surplusKw, cap);
                if (ch > SimulationConstants.EPSILON) {
                    bt0.adjustCapacity(bt0, +ch, ch, false, ctx.considerDegradation);
                    btNet[0] -= ch;
                    surplusKw -= ch;
                }
            }
            if (bt1Avail && bt1.getStateOfCharge() < SimulationConstants.BATTERY_MAX_SOC - SimulationConstants.EPSILON) {
                double cap = bt1.getChargePowerCapKw(ctx.sp);
                double ch = Math.min(surplusKw, cap);
                if (ch > SimulationConstants.EPSILON) {
                    bt1.adjustCapacity(bt1, +ch, ch, false, ctx.considerDegradation);
                    btNet[1] -= ch;
                    surplusKw -= ch;
                }
            }

            wre = Math.max(0.0, surplusKw);

            SingleRunSimulator.applyIdleReserveInWindSurplus(b0, ctx.sp, ctx.hourIndex, load0, windToLoad[0], ctx.cat1, ctx.cat2, bt0Avail, bt0, ctx.dgRatedKw, ctx.dgMinKw, ctx.dgStartDelayHours);
            SingleRunSimulator.applyIdleReserveInWindSurplus(b1, ctx.sp, ctx.hourIndex, load1, windToLoad[1], ctx.cat1, ctx.cat2, bt1Avail, bt1, ctx.dgRatedKw, ctx.dgMinKw, ctx.dgStartDelayHours);

            SingleRunSimulator.finalizeIdleAndBurn(ctx, dgs, ctx.dgMinKw);
            SingleRunSimulator.finalizeStoppedDgs(dgs);

        } else {
            if (available > 0 && deficitAfterWindBt > SimulationConstants.EPSILON) {

                final boolean canUseOptimal = (ctx.perDgOptimalKw * available >= deficitAfterWindBt);
                final int needed = canUseOptimal
                        ? (int) Math.ceil(deficitAfterWindBt / ctx.perDgOptimalKw)
                        : (int) Math.ceil(deficitAfterWindBt / ctx.dgMaxKw);

                final int dgCountPlanned = Math.min(needed, available);
                final int naturalNeedDgCount = dgCountPlanned;
                int dgToUse = dgCountPlanned;

                // ===== Non-reserve BESS use: try to reduce DG count for fuel saving =====
                double socNonReserveFloor = Math.max(
                        SimulationConstants.BATTERY_MIN_SOC,
                        Math.max(
                                bt0Avail ? bt0.previewAdaptiveNonReserveFloorForCandidate(ctx.sp, naturalNeedDgCount, dgToUse) : SimulationConstants.BATTERY_MIN_SOC,
                                bt1Avail ? bt1.previewAdaptiveNonReserveFloorForCandidate(ctx.sp, naturalNeedDgCount, dgToUse) : SimulationConstants.BATTERY_MIN_SOC
                        )
                );

                if ((bt0Avail || bt1Avail) && dgToUse > 1) {
                    while (dgToUse > 1) {
                        int cand = dgToUse - 1;
                        double needFromBtKw = Math.max(0.0, deficitAfterWindBt - cand * ctx.dgMaxKw);
                        if (needFromBtKw <= SimulationConstants.EPSILON) {
                            dgToUse = cand;
                            continue;
                        }

                        double btCapKw = (bt0Avail ? bt0.getDischargePowerCapKw(ctx.sp) : 0.0)
                                + (bt1Avail ? bt1.getDischargePowerCapKw(ctx.sp) : 0.0);
                        if (btCapKw + SimulationConstants.EPSILON < needFromBtKw) break;

                        double candFloor = Math.max(
                                SimulationConstants.BATTERY_MIN_SOC,
                                Math.max(
                                        bt0Avail ? bt0.previewAdaptiveNonReserveFloorForCandidate(ctx.sp, naturalNeedDgCount, cand) : SimulationConstants.BATTERY_MIN_SOC,
                                        bt1Avail ? bt1.previewAdaptiveNonReserveFloorForCandidate(ctx.sp, naturalNeedDgCount, cand) : SimulationConstants.BATTERY_MIN_SOC
                                )
                        );

                        double btEnergyKwh = 0.0;
                        if (bt0Avail) btEnergyKwh += Math.max(0.0, (bt0.getStateOfCharge() - candFloor))
                                * bt0.getMaxCapacityKwh() * SimulationConstants.BATTERY_EFFICIENCY;
                        if (bt1Avail) btEnergyKwh += Math.max(0.0, (bt1.getStateOfCharge() - candFloor))
                                * bt1.getMaxCapacityKwh() * SimulationConstants.BATTERY_EFFICIENCY;

                        if (btEnergyKwh + SimulationConstants.EPSILON < needFromBtKw) break; // duration=1h
                        socNonReserveFloor = candFloor;
                        dgToUse = cand;
                    }
                }

                if (bt0Avail) bt0.commitAdaptiveNonReserveFloorForCandidate(ctx.sp, naturalNeedDgCount, dgToUse);
                if (bt1Avail) bt1.commitAdaptiveNonReserveFloorForCandidate(ctx.sp, naturalNeedDgCount, dgToUse);

                final boolean maintenanceStartedThisHour = DieselGenerator.isMaintenanceStartedThisHour(dgs);

                final double tauEff = maintenanceStartedThisHour ? 0.0 : ctx.dgStartDelayHours;

                int R = Math.min(readyWorking, dgToUse);

                double readyMaxStartKw = R * ctx.dgMaxKw;
                double readyLoadStartKw = Math.min(deficitAfterWindBt, readyMaxStartKw);
                double perReadyStartKw = (R > 0) ? (readyLoadStartKw / R) : 0.0;

                double perDgSteadyKw = 0.0;
                if (dgToUse > 0) {
                    perDgSteadyKw = canUseOptimal
                            ? (deficitAfterWindBt / dgToUse)
                            : Math.min(deficitAfterWindBt / dgToUse, ctx.dgMaxKw);
                    if (canUseOptimal && perDgSteadyKw > ctx.perDgOptimalKw) perDgSteadyKw = ctx.perDgOptimalKw;
                }

                if (tauEff > SimulationConstants.EPSILON && dgToUse > readyWorking) {
                    double startDefKw = Math.max(0.0, deficitAfterWindBt - readyLoadStartKw);

                    // ===== Reserve BESS bridging during tau (NO non-reserve restriction) =====
                    double remainingStartDefKw = startDefKw;

                    // Try BT0
                    if (bt0Avail && remainingStartDefKw > SimulationConstants.EPSILON) {
                        double capKw = bt0.getDischargePowerCapKw(ctx.sp);
                        double pDis0 = Math.min(remainingStartDefKw, capKw);
                        double availKwh = Math.max(0.0, (bt0.getStateOfCharge() - SimulationConstants.BATTERY_MIN_SOC))
                                * bt0.getMaxCapacityKwh() * SimulationConstants.BATTERY_EFFICIENCY;
                        double needKwh = pDis0 * tauEff;
                        if (availKwh + SimulationConstants.EPSILON >= needKwh) {
                            bt0.adjustCapacity(bt0, -pDis0 * tauEff, pDis0, true, ctx.considerDegradation);
                            // btNet stores hour-equivalent contribution; scale partial-hour discharge.
                            btNet[0] += pDis0 * tauEff;
                            remainingStartDefKw -= pDis0;
                        }
                    }

                    // Try BT1
                    if (bt1Avail && remainingStartDefKw > SimulationConstants.EPSILON) {
                        double capKw = bt1.getDischargePowerCapKw(ctx.sp);
                        double pDis1 = Math.min(remainingStartDefKw, capKw);
                        double availKwh = Math.max(0.0, (bt1.getStateOfCharge() - SimulationConstants.BATTERY_MIN_SOC))
                                * bt1.getMaxCapacityKwh() * SimulationConstants.BATTERY_EFFICIENCY;
                        double needKwh = pDis1 * tauEff;
                        if (availKwh + SimulationConstants.EPSILON >= needKwh) {
                            bt1.adjustCapacity(bt1, -pDis1 * tauEff, pDis1, true, ctx.considerDegradation);
                            btNet[1] += pDis1 * tauEff;
                            remainingStartDefKw -= pDis1;
                        }
                    }

                    remainingStartDefKw = Math.max(0.0, remainingStartDefKw);
                    startDelayEnsEstimateKwh = remainingStartDefKw * tauEff;
                }

                double sumDieselKw = 0.0;
                int used = 0;

                // IMPORTANT: do NOT touch idleTime/burn here (loads may change after N-1).
                for (int k = 0; k < dgs.length; k++) {
                    DieselGenerator dg = dgs[k];
                    if (!dg.isAvailable() || used >= dgToUse) {
                        dg.setCurrentLoad(0.0);
                        dg.setIdle(false);
                        continue;
                    }

                    boolean wasWorking = dg.wasStartCapableAtHourStart();
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

                // Выбор между ХХ и ВР:
                // - если ДГУ НЕ используются для обеспечения нагрузки (ветер >= нагрузка, либо ветер закрывает дефицит при отсутствии ДГУ),
                //   держим готовность через ХХ.
                // - если ДГУ используются вместе с ветром, ХХ не нужен; вместо этого используется ВР (N-1).
                boolean dgUsedForLoad = sumDieselKw > SimulationConstants.EPSILON;
                // N-1 may add DG and redistribute loads
                if (ctx.considerRotationReserve && dgUsedForLoad) {
                    sumDieselKw = SingleRunSimulator.applyRotationReserveNminus1(
                            dgs,
                            totalLoad,
                            usedWind,
                            btDisToLoadTotal,
                            false,
                            null,
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

                // ===== FINAL: low-load / idle / burn based on FINAL currentLoad =====
                boolean anyBurnThisHour = SingleRunSimulator.finalizeIdleAndBurn(ctx, dgs, ctx.dgMinKw);


                SingleRunSimulator.finalizeStoppedDgs(dgs);

                // recompute diesel produced after possible burn
                double sumFinalDieselKw = 0.0;
                for (DieselGenerator dg : dgs) {
                    if (!dg.isAvailable()) continue;
                    double pKw = dg.getCurrentLoad();
                    if (pKw > SimulationConstants.EPSILON) sumFinalDieselKw += pKw;
                }
                dgProducedKw = sumFinalDieselKw;

                // distribute diesel to remaining load
                double needDieselToLoad = deficitAfterWindBt;
                dgToLoadTotal = Math.min(needDieselToLoad, dgProducedKw);

                // charge from DG surplus (to both batteries, own first)
                double dieselSurplus = Math.max(0.0, dgProducedKw - dgToLoadTotal);
                boolean allowChargeNow = anyBurnThisHour;

                if (allowChargeNow && dieselSurplus > SimulationConstants.EPSILON) {
                    if (bt0Avail && bt0.getStateOfCharge() < SimulationConstants.BATTERY_MAX_SOC - SimulationConstants.EPSILON) {
                        double cap = bt0.getChargePowerCapKw(ctx.sp);
                        double ch = Math.min(dieselSurplus, cap);
                        if (ch > SimulationConstants.EPSILON) {
                            bt0.adjustCapacity(bt0, +ch, ch, true, ctx.considerDegradation);
                            btNet[0] -= ch;
                            dieselSurplus -= ch;
                        }
                    }
                    if (bt1Avail && bt1.getStateOfCharge() < SimulationConstants.BATTERY_MAX_SOC - SimulationConstants.EPSILON) {
                        double cap = bt1.getChargePowerCapKw(ctx.sp);
                        double ch = Math.min(dieselSurplus, cap);
                        if (ch > SimulationConstants.EPSILON) {
                            bt1.adjustCapacity(bt1, +ch, ch, true, ctx.considerDegradation);
                            btNet[1] -= ch;
                            dieselSurplus -= ch;
                        }
                    }
                }
            }
        }

        double need0 = Math.max(0.0, load0 - windToLoad[0] - Math.max(0.0, btNet[0]));
        double need1 = Math.max(0.0, load1 - windToLoad[1] - Math.max(0.0, btNet[1]));
        double needSum = need0 + need1;
        if (needSum > SimulationConstants.EPSILON) {
            dgToLoad[0] = dgToLoadTotal * (need0 / needSum);
            dgToLoad[1] = dgToLoadTotal * (need1 / needSum);
        }

        double supplied0 = windToLoad[0] + dgToLoad[0] + Math.max(0.0, btNet[0]);
        double supplied1 = windToLoad[1] + dgToLoad[1] + Math.max(0.0, btNet[1]);
        def[0] = Math.max(0.0, load0 - supplied0);
        def[1] = Math.max(0.0, load1 - supplied1);

        double ens = def[0] + def[1];

        if (ens > SimulationConstants.EPSILON && startDelayEnsEstimateKwh > SimulationConstants.EPSILON) {
            double startEnsTotal = Math.min(ens, startDelayEnsEstimateKwh);
            // Track start ENS separately for ENS event statistics.
            ctx.totals.startEnsKwh += startEnsTotal;
            startEnsByBus[0] = startEnsTotal * (def[0] / ens);
            startEnsByBus[1] = startEnsTotal * (def[1] / ens);
        }

        double fuel = SingleRunSimulator.computeFuelLitersOneHour(b0.getDieselGenerators(), ctx.dgRatedKw)
                + SingleRunSimulator.computeFuelLitersOneHour(b1.getDieselGenerators(), ctx.dgRatedKw);

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
                def,
                startEnsByBus
        );
    }
}