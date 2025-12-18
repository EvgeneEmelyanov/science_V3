package simcore.sobol;

import simcore.config.SystemParameters;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ParameterSet {

    private final Map<String, Double> values;

    public ParameterSet(Map<String, Double> values) {
        this.values = new LinkedHashMap<>(values);
    }

    public Map<String, Double> getValues() {
        return Collections.unmodifiableMap(values);
    }

    public double get(String name) {
        Double v = values.get(name);
        if (v == null) throw new IllegalArgumentException("Parameter not found: " + name);
        return v;
    }

    public SystemParameters applyTo(SystemParameters base, SobolConfig cfg) {
        // immutable: стартуем с копии (или с base — если copy не нужен)
        SystemParameters p = cfg.copyParameters(base);
        for (SobolFactor f : cfg.getFactors()) {
            double v = get(f.getName());
            p = f.apply(p, v);
        }
        return p;
    }

    @Override
    public String toString() {
        return values.toString();
    }
}
