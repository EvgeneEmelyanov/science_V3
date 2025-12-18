package simcore.sobol;

import simcore.config.SystemParameters;

/**
 * Один фактор чувствительности.
 * ВАЖНО: SystemParameters immutable -> apply() возвращает новый объект.
 */
public interface SobolFactor {

    String getName();

    double getMin();

    double getMax();

    SystemParameters apply(SystemParameters base, double value);

    default double scaleFromUnit(double u01) {
        if (u01 < 0.0) u01 = 0.0;
        if (u01 > 1.0) u01 = 1.0;
        return getMin() + (getMax() - getMin()) * u01;
    }
}
