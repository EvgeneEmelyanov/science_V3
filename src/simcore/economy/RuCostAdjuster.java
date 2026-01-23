package simcore.economy;

import simcore.config.BusSystemType;

/**
 * Helper for adjusting RU (switchgear room / bus system) capex depending on busbar scheme.
 */
public final class RuCostAdjuster {

    private RuCostAdjuster() {}

    public static double multiplier(BusSystemType type) {
        if (type == null) return 1.0;
        return switch (type) {
            case SINGLE_NOT_SECTIONAL_BUS -> 1.0;
            case SINGLE_SECTIONAL_BUS -> 1.15;
            case DOUBLE_BUS -> 1.5;
        };
    }

    public static double effectiveRuCost(BusSystemType type, double baseCostRuRub) {
        return baseCostRuRub * multiplier(type);
    }
}
