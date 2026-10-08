package com.solstice.cost;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class RegressionCostPredictor implements CostPredictor {

    public static final String NAME = "regression";
    private static final int MINIMUM_SAMPLES = 8;
    private static final double LAMBDA = 1.0;

    private static final Logger log = LoggerFactory.getLogger(RegressionCostPredictor.class);

    private final CostHistory history;
    private final CostPredictor fallback;

    public RegressionCostPredictor(CostHistory history, CostPredictor fallback) {
        this.history = history == null ? new CostHistory() : history;
        this.fallback = fallback;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public CostEstimate estimateSavepointDuration(JobFeatures features) {
        return estimate(features, true);
    }

    @Override
    public CostEstimate estimateRestartDuration(JobFeatures features) {
        return estimate(features, false);
    }

    private CostEstimate estimate(JobFeatures features, boolean savepoint) {
        List<CostObservation> relevant = new ArrayList<>();
        for (CostObservation observation : history.forJob(features.getJobName())) {
            if (savepoint ? observation.hasSavepoint() : observation.hasRestart()) {
                relevant.add(observation);
            }
        }

        if (relevant.size() < MINIMUM_SAMPLES) {
            return savepoint
                    ? fallback.estimateSavepointDuration(features)
                    : fallback.estimateRestartDuration(features);
        }

        List<double[]> inputs = new ArrayList<>();
        List<Double> targets = new ArrayList<>();
        for (CostObservation observation : relevant) {
            inputs.add(featureVector(observation.toFeatures()));
            targets.add((double) (savepoint
                    ? observation.getSavepointSeconds()
                    : observation.getRestartSeconds()));
        }

        Optional<RidgeRegression> model = RidgeRegression.fit(inputs, targets, LAMBDA);
        if (model.isEmpty()) {
            log.debug("Could not fit a {} model for {}, falling back",
                    savepoint ? "savepoint" : "restart", features.getJobName());
            return savepoint
                    ? fallback.estimateSavepointDuration(features)
                    : fallback.estimateRestartDuration(features);
        }

        RidgeRegression fitted = model.get();
        double predicted = fitted.predict(featureVector(features));
        if (predicted <= 0 || Double.isNaN(predicted)) {
            return savepoint
                    ? fallback.estimateSavepointDuration(features)
                    : fallback.estimateRestartDuration(features);
        }

        double spread = Math.max(1, fitted.getResidualStandardDeviation());
        long value = Math.round(predicted);
        long low = Math.max(1, Math.round(predicted - spread));
        long high = Math.round(predicted + spread);

        return CostEstimate.of(
                Duration.ofSeconds(value),
                Duration.ofSeconds(low),
                Duration.ofSeconds(high),
                relevant.size(),
                NAME);
    }

    private double[] featureVector(JobFeatures features) {
        return new double[]{
                features.getStateSizeMegabytes(),
                features.getParallelism()
        };
    }
}
