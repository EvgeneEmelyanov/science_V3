package simcore.engine;

import simcore.engine.diesel.DieselFleetController;
import simcore.config.SimulationConstants;
import simcore.model.*;
import java.util.List;
import java.util.ArrayList;

/**
 * Dispatching logic for one hour when the sectional tie breaker is closed.
 * Extracted from {@link SingleRunSimulator} to reduce file size and improve testability.
 */
final class SectionalClosedDispatcher {
    private SectionalClosedDispatcher() {}


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

        double[] windToLoad = new double[2];
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

        double bt0DisCap = bt0Avail ? bt0.getDischargeCapacity(ctx.sp) : 0.0;
        double bt1DisCap = bt1Avail ? bt1.getDischargeCapacity(ctx.sp) : 0.0;

        double[] btNet = new double[2]; // >0 discharge, <0 charge

        double dis0 = bt0Avail ? Math.min(rem0, bt0DisCap) : 0.0;
        if (dis0 > SimulationConstants.EPSILON && bt0Avail) {
            bt0.adjustCapacity(bt0, -dis0, dis0, false, ctx.considerDegradation);
            btNet[0] += dis0;
            rem0 -= dis0;
            bt0DisCap -= dis0;
        }
        double dis1 = bt1Avail ? Math.min(rem1, bt1DisCap) : 0.0;
        if (dis1 > SimulationConstants.EPSILON && bt1Avail) {
            bt1.adjustCapacity(bt1, -dis1, dis1, false, ctx.considerDegradation);
            btNet[1] += dis1;
            rem1 -= dis1;
            bt1DisCap -= dis1;
        }

        if (rem0 > SimulationConstants.EPSILON && bt1Avail && bt1DisCap > SimulationConstants.EPSILON) {
            double x = Math.min(rem0, bt1DisCap);
            bt1.adjustCapacity(bt1, -x, x, false, ctx.considerDegradation);
            btNet[1] += x;
            rem0 -= x;
            bt1DisCap -= x;
        }
        if (rem1 > SimulationConstants.EPSILON && bt0Avail && bt0DisCap > SimulationConstants.EPSILON) {
            double x = Math.min(rem1, bt0DisCap);
            bt0.adjustCapacity(bt0, -x, x, false, ctx.considerDegradation);
            btNet[0] += x;
            rem1 -= x;
            bt0DisCap -= x;
        }

        double btDisToLoadTotal = Math.max(0.0, btNet[0]) + Math.max(0.0, btNet[1]);
        double deficitAfterWindBt = rem0 + rem1;

        List<DieselGenerator> allDgs = new ArrayList<>();
        allDgs.addAll(b0.getDieselGenerators());
        allDgs.addAll(b1.getDieselGenerators());
        DieselGenerator[] dgs = DieselFleetController.getSortedDgs(allDgs);

        int available = 0;
        int readyWorking = 0;
        for (DieselGenerator dg : dgs) {
            if (dg.isAvailable()) available++;
            if (dg.isWorking()) readyWorking++;
        }

        double dgProducedKw = 0.0;
        double dgToLoadTotal = 0.0;
        double wre = 0.0;
        double startDelayEnsEstimateKwh = 0.0;

        if (windPot >= totalLoad - SimulationConstants.EPSILON) {
            double surplusKw = Math.max(0.0, windPot - totalLoad);

            if (bt0Avail && bt0.getStateOfCharge() < SimulationConstants.BATTERY_MAX_SOC - SimulationConstants.EPSILON) {
                double cap = bt0.getChargeCapacity(ctx.sp);
                double ch = Math.min(surplusKw, cap);
                if (ch > SimulationConstants.EPSILON) {
                    bt0.adjustCapacity(bt0, +ch, ch, false, ctx.considerDegradation);
                    btNet[0] -= ch;
                    surplusKw -= ch;
                }
            }
            if (bt1Avail && bt1.getStateOfCharge() < SimulationConstants.BATTERY_MAX_SOC - SimulationConstants.EPSILON) {
                double cap = bt1.getChargeCapacity(ctx.sp);
                double ch = Math.min(surplusKw, cap);
                if (ch > SimulationConstants.EPSILON) {
                    bt1.adjustCapacity(bt1, +ch, ch, false, ctx.considerDegradation);
                    btNet[1] -= ch;
                    surplusKw -= ch;
                }
            }

            wre = Math.max(0.0, surplusKw);

            if (SingleRunSimulator.ENABLE_ZERO_LOAD_ALL_DG_READY && totalLoad <= SimulationConstants.EPSILON) {
                DieselFleetController.keepAllDieselsReadyHotStandby(b0);
                DieselFleetController.keepAllDieselsReadyHotStandby(b1);
            } else {
                SingleRunSimulator.applyIdleReserveInWindSurplus(b0, ctx.sp, load0, windToLoad[0], ctx.cat1, ctx.cat2, bt0Avail, bt0, ctx.dgRatedKw, ctx.dgMinKw, ctx.dgStartDelayHours);
                SingleRunSimulator.applyIdleReserveInWindSurplus(b1, ctx.sp, load1, windToLoad[1], ctx.cat1, ctx.cat2, bt1Avail, bt1, ctx.dgRatedKw, ctx.dgMinKw, ctx.dgStartDelayHours);
            }

        } else {
            if (available > 0 && deficitAfterWindBt > SimulationConstants.EPSILON) {

                final boolean canUseOptimal = (ctx.perDgOptimalKw * available >= deficitAfterWindBt);
                final int needed = canUseOptimal
                        ? (int) Math.ceil(deficitAfterWindBt / ctx.perDgOptimalKw)
                        : (int) Math.ceil(deficitAfterWindBt / ctx.dgMaxKw);

                final int dgCountPlanned = Math.min(needed, available);
                int dgToUse = dgCountPlanned;

                final boolean maintenanceStartedThisHour = DieselFleetController.isMaintenanceStartedThisHour(dgs);
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
                    startDelayEnsEstimateKwh = startDefKw * tauEff;
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

                if (usedWind > SimulationConstants.EPSILON) {
                    SingleRunSimulator.applyIdleReserveInWindDeficit(
                            dgs,
                            totalLoad,
                            usedWind,
                            ctx.cat1,
                            ctx.cat2,
                            false,
                            null,
                            ctx.sp,
                            tauEff,
                            0.0,
                            ctx.dgRatedKw,
                            ctx.dgMinKw,
                            ctx.dgMaxKw
                    );
                }

                // N-1 may add DG and redistribute loads
                if (ctx.considerRotationReserve) {
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
                            sumDieselKw
                    );
                }

                // FINAL: low-load/idle/burn based on FINAL currentLoad
                boolean anyBurnThisHour = false;

                for (DieselGenerator dg : dgs) {
                    if (!dg.isAvailable()) continue;

                    double p = dg.getCurrentLoad();
                    if (Math.abs(p) <= SimulationConstants.EPSILON) {
                        dg.setIdle(false);
//                        dg.resetIdleTime();
                        continue;
                    }

                    if (Math.abs(p) + SimulationConstants.EPSILON < ctx.dgMinKw) {
                        if (dg.getIdleTime() >= SimulationConstants.DG_MAX_IDLE_HOURS) {
                            dg.setCurrentLoad(Math.max(ctx.dgMinKw, 0.0));
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
                }

                SingleRunSimulator.finalizeStoppedDgs(dgs);

                // recompute diesel produced after possible burn
                double sumFinalDieselKw = 0.0;
                for (DieselGenerator dg : dgs) {
                    if (!dg.isAvailable()) continue;
                    double p = dg.getCurrentLoad();
                    if (p > SimulationConstants.EPSILON) sumFinalDieselKw += p;
                }
                dgProducedKw = sumFinalDieselKw;

                // distribute diesel to remaining load
                double needDieselToLoad = deficitAfterWindBt;
                dgToLoadTotal = Math.min(needDieselToLoad, dgProducedKw);

                // charge from DG surplus (to both batteries, own first)
                double dieselSurplus = Math.max(0.0, dgProducedKw - dgToLoadTotal);
                boolean allowChargeNow = ctx.considerChargeByDg || anyBurnThisHour;

                if (allowChargeNow && dieselSurplus > SimulationConstants.EPSILON) {
                    if (bt0Avail && bt0.getStateOfCharge() < SimulationConstants.BATTERY_MAX_SOC - SimulationConstants.EPSILON) {
                        double cap = bt0.getChargeCapacity(ctx.sp);
                        double ch = Math.min(dieselSurplus, cap);
                        if (ch > SimulationConstants.EPSILON) {
                            bt0.adjustCapacity(bt0, +ch, ch, true, ctx.considerDegradation);
                            btNet[0] -= ch;
                            dieselSurplus -= ch;
                        }
                    }
                    if (bt1Avail && bt1.getStateOfCharge() < SimulationConstants.BATTERY_MAX_SOC - SimulationConstants.EPSILON) {
                        double cap = bt1.getChargeCapacity(ctx.sp);
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

        double[] dgToLoad = new double[2];
        double need0 = Math.max(0.0, load0 - windToLoad[0] - Math.max(0.0, btNet[0]));
        double need1 = Math.max(0.0, load1 - windToLoad[1] - Math.max(0.0, btNet[1]));
        double needSum = need0 + need1;
        if (needSum > SimulationConstants.EPSILON) {
            dgToLoad[0] = dgToLoadTotal * (need0 / needSum);
            dgToLoad[1] = dgToLoadTotal * (need1 / needSum);
        }

        double[] def = new double[2];
        double supplied0 = windToLoad[0] + dgToLoad[0] + Math.max(0.0, btNet[0]);
        double supplied1 = windToLoad[1] + dgToLoad[1] + Math.max(0.0, btNet[1]);
        def[0] = Math.max(0.0, load0 - supplied0);
        def[1] = Math.max(0.0, load1 - supplied1);

        double ens = def[0] + def[1];

        double[] startEnsByBus = new double[2];
        if (ens > SimulationConstants.EPSILON && startDelayEnsEstimateKwh > SimulationConstants.EPSILON) {
            double startEnsTotal = Math.min(ens, startDelayEnsEstimateKwh);
            startEnsByBus[0] = startEnsTotal * (def[0] / ens);
            startEnsByBus[1] = startEnsTotal * (def[1] / ens);
        }

        double fuel = SingleRunSimulator.FUEL_MODEL.computeFuelLitersOneHour(b0.getDieselGenerators(), ctx.dgRatedKw)
                + SingleRunSimulator.FUEL_MODEL.computeFuelLitersOneHour(b1.getDieselGenerators(), ctx.dgRatedKw);

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