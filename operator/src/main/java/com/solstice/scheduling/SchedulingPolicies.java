package com.solstice.scheduling;

import com.solstice.cost.CostModel;
import com.solstice.cost.CostPredictor;
import com.solstice.cost.CostHistory;
import com.solstice.cost.CostPredictors;
import com.solstice.model.SolsticeSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SchedulingPolicies {

    private static final Logger log = LoggerFactory.getLogger(SchedulingPolicies.class);

    private SchedulingPolicies() {
    }

    public static SchedulingPolicy fromSpec(SolsticeSpec spec) {
        return fromSpec(spec, new CostHistory());
    }

    public static SchedulingPolicy fromSpec(SolsticeSpec spec, CostHistory history) {
        String requested = spec.getSchedulingPolicy() == null
                ? ThresholdPolicy.NAME
                : spec.getSchedulingPolicy().trim().toLowerCase();

        ThresholdPolicy threshold = new ThresholdPolicy();

        if (ThresholdPolicy.NAME.equals(requested)) {
            return threshold;
        }

        if (ForecastAwarePolicy.NAME.equals(requested) || OptimisingPolicy.NAME.equals(requested)) {
            return new ForecastAwarePolicy(costModelFor(spec, history), threshold);
        }

        log.warn("Unknown scheduling policy {}, falling back to {}", requested, ThresholdPolicy.NAME);
        return threshold;
    }

    public static boolean isOptimising(SolsticeSpec spec) {
        return spec.getSchedulingPolicy() != null
                && OptimisingPolicy.NAME.equals(spec.getSchedulingPolicy().trim().toLowerCase());
    }

    public static OptimisingPolicy optimiserFor(SolsticeSpec spec) {
        return optimiserFor(spec, new CostHistory());
    }

    public static OptimisingPolicy optimiserFor(SolsticeSpec spec, CostHistory history) {
        return new OptimisingPolicy(
                costModelFor(spec, history),
                new ForecastAwarePolicy(costModelFor(spec, history), new ThresholdPolicy()),
                spec.getNodePowerWatts(),
                spec.getMaxConcurrentSuspensions());
    }

    public static CostModel costModelFor(SolsticeSpec spec) {
        return costModelFor(spec, new CostHistory());
    }

    public static CostModel costModelFor(SolsticeSpec spec, CostHistory history) {
        CostPredictor predictor = CostPredictors.fromSpec(spec, history);
        return new CostModel(predictor, spec.getBreakEvenMultiplier());
    }
}
