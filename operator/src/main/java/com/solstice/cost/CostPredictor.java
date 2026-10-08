package com.solstice.cost;

public interface CostPredictor {

    String name();

    CostEstimate estimateSavepointDuration(JobFeatures features);

    CostEstimate estimateRestartDuration(JobFeatures features);
}
