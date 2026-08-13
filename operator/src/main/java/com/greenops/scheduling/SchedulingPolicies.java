package com.greenops.scheduling;

import com.greenops.cost.CostModel;
import com.greenops.cost.CostPredictor;
import com.greenops.cost.CostHistory;
import com.greenops.cost.CostPredictors;
import com.greenops.model.GreenOpsSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SchedulingPolicies {

    private static final Logger log = LoggerFactory.getLogger(SchedulingPolicies.class);

    private SchedulingPolicies() {
    }

    public static SchedulingPolicy fromSpec(GreenOpsSpec spec) {
        return fromSpec(spec, new CostHistory());
    }

    public static SchedulingPolicy fromSpec(GreenOpsSpec spec, CostHistory history) {
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

    public static boolean isOptimising(GreenOpsSpec spec) {
        return spec.getSchedulingPolicy() != null
                && OptimisingPolicy.NAME.equals(spec.getSchedulingPolicy().trim().toLowerCase());
    }

    public static OptimisingPolicy optimiserFor(GreenOpsSpec spec) {
        return optimiserFor(spec, new CostHistory());
    }

    public static OptimisingPolicy optimiserFor(GreenOpsSpec spec, CostHistory history) {
        return new OptimisingPolicy(
                costModelFor(spec, history),
                new ForecastAwarePolicy(costModelFor(spec, history), new ThresholdPolicy()),
                spec.getNodePowerWatts(),
                spec.getMaxConcurrentSuspensions());
    }

    public static CostModel costModelFor(GreenOpsSpec spec) {
        return costModelFor(spec, new CostHistory());
    }

    public static CostModel costModelFor(GreenOpsSpec spec, CostHistory history) {
        CostPredictor predictor = CostPredictors.fromSpec(spec, history);
        return new CostModel(predictor, spec.getBreakEvenMultiplier());
    }
}
