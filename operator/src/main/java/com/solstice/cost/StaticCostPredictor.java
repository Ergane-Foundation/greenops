package com.solstice.cost;

import java.time.Duration;

public class StaticCostPredictor implements CostPredictor {

    public static final String NAME = "static";

    private final Duration savepointDuration;
    private final Duration restartDuration;

    public StaticCostPredictor(Duration savepointDuration, Duration restartDuration) {
        this.savepointDuration = savepointDuration;
        this.restartDuration = restartDuration;
    }

    public static StaticCostPredictor withDefaults() {
        return new StaticCostPredictor(Duration.ofSeconds(60), Duration.ofSeconds(120));
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public CostEstimate estimateSavepointDuration(JobFeatures features) {
        return CostEstimate.certain(savepointDuration, NAME);
    }

    @Override
    public CostEstimate estimateRestartDuration(JobFeatures features) {
        return CostEstimate.certain(restartDuration, NAME);
    }
}
