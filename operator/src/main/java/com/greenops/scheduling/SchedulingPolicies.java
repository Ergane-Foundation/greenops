package com.greenops.scheduling;

import com.greenops.cost.CostModel;
import com.greenops.cost.CostPredictor;
import com.greenops.cost.CostPredictors;
import com.greenops.model.GreenOpsSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SchedulingPolicies {

    private static final Logger log = LoggerFactory.getLogger(SchedulingPolicies.class);

    private SchedulingPolicies() {
    }

    public static SchedulingPolicy fromSpec(GreenOpsSpec spec) {
        String requested = spec.getSchedulingPolicy() == null
                ? ThresholdPolicy.NAME
                : spec.getSchedulingPolicy().trim().toLowerCase();

        ThresholdPolicy threshold = new ThresholdPolicy();

        if (ThresholdPolicy.NAME.equals(requested)) {
            return threshold;
        }

        if (ForecastAwarePolicy.NAME.equals(requested)) {
            return new ForecastAwarePolicy(costModelFor(spec), threshold);
        }

        if (OptimisingPolicy.NAME.equals(requested)) {
            return new ForecastAwarePolicy(costModelFor(spec), threshold);
        }

        log.warn("Unknown scheduling policy {}, falling back to {}", requested, ThresholdPolicy.NAME);
        return threshold;
    }

    public static boolean isOptimising(GreenOpsSpec spec) {
        return spec.getSchedulingPolicy() != null
                && OptimisingPolicy.NAME.equals(spec.getSchedulingPolicy().trim().toLowerCase());
    }

    public static OptimisingPolicy optimiserFor(GreenOpsSpec spec) {
        return new OptimisingPolicy(
                costModelFor(spec),
                new ForecastAwarePolicy(costModelFor(spec), new ThresholdPolicy()),
                spec.getNodePowerWatts(),
                spec.getMaxConcurrentSuspensions());
    }

    public static CostModel costModelFor(GreenOpsSpec spec) {
        CostPredictor predictor = CostPredictors.fromSpec(spec);
        return new CostModel(predictor, spec.getBreakEvenMultiplier());
    }
}
