package simcore.model;

import simcore.config.BusSystemType;
import simcore.config.SystemParameters;

import java.util.ArrayList;
import java.util.List;

/**
 * Строит PowerSystem на основе SystemParameters и общего профиля нагрузки.
 */
public class PowerSystemBuilder {

    /**
     * @param params      параметры энергосистемы (тип шин, мощности, надёжность и т.д.)
     * @param totalLoadKw общий профиль нагрузки (как в исходном файле), кВт
     */
    public PowerSystem build(SystemParameters params, double[] totalLoadKw) {

        BusSystemType busType = params.getBusSystemType();
        int busCount = (busType == BusSystemType.SINGLE_NOT_SECTIONAL_BUS) ? 1 : 2;

        // Распределение нагрузки по шинам
        double[][] busLoads = splitLoad(totalLoadKw, busCount);

        List<PowerBus> buses = new ArrayList<>(busCount);

        int wtIdCounter = 1;
        int dgIdCounter = 1;
        int btIdCounter = 1;
        int busIdCounter = 1;

        int totalWt = params.getTotalWindTurbineCount();
        int totalDg = params.getTotalDieselGeneratorCount();

        int wtPerBus = totalWt / busCount;
        int dgPerBus = totalDg / busCount;

        double batteryCapacityPerBus = params.getBatteryCapacityKwhPerBus();

        for (int b = 0; b < busCount; b++) {
            double[] loadForBus = busLoads[b];

            PowerBus bus = new PowerBus(
                    busIdCounter++,
                    loadForBus,
                    params.getBusFailureRatePerYear(),
                    params.getBusRepairTimeHours()
            );

            // ВЭУ
            for (int i = 0; i < wtPerBus; i++) {
                WindTurbine wt = new WindTurbine(
                        wtIdCounter++,
                        params.getWindTurbinePowerKw(),
                        params.getWindTurbineFailureRatePerYear(),
                        params.getWindTurbineRepairTimeHours()
                );
                bus.addWindTurbine(wt);
            }

            // ДГУ
            for (int i = 0; i < dgPerBus; i++) {
                DieselGenerator dg = new DieselGenerator(
                        dgIdCounter++,
                        params.getDieselGeneratorPowerKw(),
                        params.getDieselGeneratorFailureRatePerYear(),
                        params.getDieselGeneratorRepairTimeHours()
                );
                bus.addDieselGenerator(dg);
            }

            // АКБ
            if (batteryCapacityPerBus > 0.0) {
                Battery bt = new Battery(
                        btIdCounter++,
                        batteryCapacityPerBus,
                        params.getBatteryFailureRatePerYear(),
                        params.getBatteryRepairTimeHours()
                );
                bus.setBattery(bt);
            }

            buses.add(bus);
        }

        // Автомат между шинами:
        // для SINGLE_SECTIONAL_BUS и DOUBLE_BUS он есть;
        // для SINGLE_NOT_SECTIONAL_BUS — нет.
        Breaker breaker = null;
        if (busType == BusSystemType.SINGLE_SECTIONAL_BUS
                || busType == BusSystemType.DOUBLE_BUS) {

            breaker = new Breaker(
                    1,
                    false, // по умолчанию разомкнут, логику замыкания допишем позже
                    params.getBreakerFailureRatePerYear(),
                    params.getBreakerRepairTimeHours()
            );
        }

        return new PowerSystem(buses, breaker);
    }

    /**
     * Простейшее равномерное деление нагрузки по шинам.
     * Если 1 шина — вся нагрузка на неё.
     * Если 2 — половина на каждую.
     * Больше 2 шин сейчас не используем, но можно расширить при необходимости.
     */
    private double[][] splitLoad(double[] totalLoadKw, int busCount) {
        double[][] result = new double[busCount][totalLoadKw.length];

        if (busCount == 1) {
            System.arraycopy(totalLoadKw, 0, result[0], 0, totalLoadKw.length);
        } else if (busCount == 2) {
            for (int t = 0; t < totalLoadKw.length; t++) {
                double half = totalLoadKw[t] / 2.0;
                result[0][t] = half;
                result[1][t] = half;
            }
        } else {
            for (int b = 0; b < busCount; b++) {
                for (int t = 0; t < totalLoadKw.length; t++) {
                    result[b][t] = totalLoadKw[t] / busCount;
                }
            }
        }

        return result;
    }
}
