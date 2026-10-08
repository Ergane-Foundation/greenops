package com.solstice.cost;

import java.time.Duration;

public class CostModel {

    private final CostPredictor predictor;
    private final double breakEvenMultiplier;

    public CostModel(CostPredictor predictor, double breakEvenMultiplier) {
        this.predictor = predictor;
        this.breakEvenMultiplier = breakEvenMultiplier <= 0 ? 1.0 : breakEvenMultiplier;
    }

    public CostPredictor getPredictor() {
        return predictor;
    }

    public SuspensionCost costOf(JobFeatures features) {
        return new SuspensionCost(
                predictor.estimateSavepointDuration(features),
                predictor.estimateRestartDuration(features));
    }

    public SuspensionDecision evaluate(JobFeatures features, Duration windowDuration) {
        SuspensionCost cost = costOf(features);

        if (windowDuration == null || windowDuration.isZero() || windowDuration.isNegative()) {
            return SuspensionDecision.noWindowKnown(cost);
        }

        Duration breakEven = cost.breakEvenWindow(breakEvenMultiplier);
        if (windowDuration.compareTo(breakEven) < 0) {
            return SuspensionDecision.windowTooShort(windowDuration, breakEven, cost);
        }
        return SuspensionDecision.worthIt(windowDuration, breakEven, cost);
    }
}
