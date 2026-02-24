// File: simcore/engine/bus/BusPotential.java
package simcore.engine.bus;

import simcore.config.SimulationConstants;
import simcore.config.SystemParameters;
import simcore.model.Battery;
import simcore.model.DieselGenerator;
import simcore.model.PowerBus;
import simcore.model.WindTurbine;

/**
 * Потенциалы мощности по шине без побочных эффектов (не меняет состояние/наработку).
 *
 * ВАЖНО:
 * - Для логики TieBreaker нельзя использовать только "мощностной" предел АКБ (инвертор),
 *   нужно учитывать еще и доступную энергию на интервал (например 1 час),
 *   иначе потенциал завышается и решение о замыкании СВ/МШВ становится неверным.
 */
public final class BusPotential {

    private BusPotential() {}

    public static double windPotentialNoSideEffects(PowerBus bus, double windV) {
        double pot = 0.0;
        for (WindTurbine wt : bus.getWindTurbines()) {
            if (wt != null && wt.isAvailable()) {
                pot += wt.getPotentialGenerationKw(windV);
            }
        }
        return pot;
    }

    /**
     * Максимально возможная мощность ДГУ по шине.
     * Примечание: не учитывает tau (задержку пуска) и "работал ли в начале часа".
     * Для tie-breaker это обычно приемлемая верхняя оценка.
     */
    public static double dieselPotential(PowerBus bus, double dgMaxKw) {
        double pot = 0.0;
        for (DieselGenerator dg : bus.getDieselGenerators()) {
            if (dg != null && dg.isAvailable()) {
                pot += dgMaxKw;
            }
        }
        return pot;
    }

    /**
     * Старый "мощностной" потенциал АКБ (только ограничение инвертора/С-rate),
     * без учета энергии/SOC на длительность интервала.
     *
     * Оставлен для обратной совместимости (если где-то нужен именно ceiling по мощности).
     */
    public static double batteryDischargePotential(PowerBus bus, SystemParameters sp) {
        Battery bt = bus.getBattery();
        if (bt == null || !bt.isAvailable()) return 0.0;
        return bt.getDischargeCapacity(sp);
    }

    /**
     * "Firm" потенциал АКБ на заданную длительность: ограничен и мощностью, и доступной энергией.
     *
     * socFloor: ниже этого SOC разряжать нельзя в рамках данного расчета.
     * durationHours: длительность интервала (для tie-breaker обычно 1.0).
     *
     * Требует, чтобы у Battery был метод:
     *   double getAvailableDischargeEnergyKwhAbove(double socFloor)
     * который возвращает доступную энергию (кВт*ч) выше socFloor с учетом принятой в модели эффективности.
     */
    public static double batteryDischargePotentialFirm(PowerBus bus,
                                                       SystemParameters sp,
                                                       double durationHours,
                                                       double socFloor) {
        Battery bt = bus.getBattery();
        if (bt == null || !bt.isAvailable()) return 0.0;
        if (durationHours <= SimulationConstants.EPSILON) return 0.0;

        // Ограничение по мощности (инвертор/С-rate)
        double pCapKw = bt.getDischargeCapacity(sp);
        if (pCapKw <= SimulationConstants.EPSILON) return 0.0;

        // Ограничение по энергии на интервал
        double eAvailKwh = bt.getAvailableDischargeEnergyKwhAbove(socFloor);
        if (eAvailKwh <= SimulationConstants.EPSILON) return 0.0;

        double pByEnergyKw = eAvailKwh / durationHours;

        double p = Math.min(pCapKw, pByEnergyKw);
        return (p > SimulationConstants.EPSILON) ? p : 0.0;
    }
}