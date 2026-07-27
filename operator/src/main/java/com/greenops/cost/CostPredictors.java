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
        StaticCostPredictor fallback = new StaticCostPredictor(
                Duration.ofSeconds(spec.getAssumedSavepointSeconds()),
                Duration.ofSeconds(spec.getAssumedRestartSeconds()));

        String requested = spec.getCostPredictor() == null ? StaticCostPredictor.NAME : spec.getCostPredictor();
        if (!StaticCostPredictor.NAME.equalsIgnoreCase(requested)) {
            log.debug("Cost predictor {} not available yet, using {}", requested, StaticCostPredictor.NAME);
        }
        return fallback;
    }
}
