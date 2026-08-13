package com.greenops.cost;

import com.greenops.model.GreenOpsSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

public final class CostPredictors {

    private static final Logger log = LoggerFactory.getLogger(CostPredictors.class);

    private CostPredictors() {
    }

    public static CostPredictor fromSpec(GreenOpsSpec spec) {
        return fromSpec(spec, new CostHistory());
    }

    public static CostPredictor fromSpec(GreenOpsSpec spec, CostHistory history) {
        StaticCostPredictor fallback = new StaticCostPredictor(
                Duration.ofSeconds(spec.getAssumedSavepointSeconds()),
                Duration.ofSeconds(spec.getAssumedRestartSeconds()));

        String requested = spec.getCostPredictor() == null
                ? StaticCostPredictor.NAME
                : spec.getCostPredictor().trim().toLowerCase();

        if (ObservedCostPredictor.NAME.equals(requested)) {
            return new ObservedCostPredictor(history, fallback);
        }
        if (RegressionCostPredictor.NAME.equals(requested)) {
            return new RegressionCostPredictor(history,
                    new ObservedCostPredictor(history, fallback));
        }
        if (!StaticCostPredictor.NAME.equals(requested)) {
            log.warn("Unknown cost predictor {}, using {}", requested, StaticCostPredictor.NAME);
        }
        return fallback;
    }
}
