package com.solstice.cost;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegressionCostPredictorTest {

    private static final String JOB = "orders";
    private static final Instant T0 = Instant.parse("2026-08-13T09:00:00Z");

    private final CostPredictor fallback = new StaticCostPredictor(
            Duration.ofSeconds(60), Duration.ofSeconds(120));

    private JobFeatures features(long megabytes, int parallelism) {
        return JobFeatures.builder()
                .jobName(JOB)
                .stateSizeBytes(megabytes * 1024 * 1024)
                .parallelism(parallelism)
                .build();
    }

    private CostHistory historyWhereSavepointScalesWithState(int samples) {
        CostHistory history = new CostHistory(100, null);
        for (int i = 0; i < samples; i++) {
            long megabytes = 10L * (i + 1);
            long seconds = 20 + 2 * megabytes;
            history.add(CostObservation.savepoint(JOB, T0,
                    Duration.ofSeconds(seconds), features(megabytes, 2)));
        }
        return history;
    }

    @Test
    @DisplayName("too little history means falling back rather than fitting noise")
    void fallsBackBelowTheSampleFloor() {
        CostEstimate estimate = new RegressionCostPredictor(
                historyWhereSavepointScalesWithState(4), fallback)
                .estimateSavepointDuration(features(50, 2));

        assertEquals("static", estimate.getSource());
    }

    @Test
    @DisplayName("a savepoint that grows with state size is learned")
    void learnsTheRelationshipBetweenStateAndDuration() {
        RegressionCostPredictor predictor =
                new RegressionCostPredictor(historyWhereSavepointScalesWithState(20), fallback);

        CostEstimate small = predictor.estimateSavepointDuration(features(10, 2));
        CostEstimate large = predictor.estimateSavepointDuration(features(200, 2));

        assertEquals("regression", small.getSource());
        assertTrue(large.getValue().compareTo(small.getValue()) > 0,
                "a bigger state should predict a longer savepoint");
    }

    @Test
    @DisplayName("the learned slope is close to the one the data was built with")
    void recoversTheUnderlyingSlope() {
        RegressionCostPredictor predictor =
                new RegressionCostPredictor(historyWhereSavepointScalesWithState(30), fallback);

        long at100 = predictor.estimateSavepointDuration(features(100, 2)).getValue().toSeconds();
        long at200 = predictor.estimateSavepointDuration(features(200, 2)).getValue().toSeconds();

        double slope = (at200 - at100) / 100.0;
        assertTrue(slope > 1.5 && slope < 2.5,
                "expected roughly 2 seconds per megabyte, got " + slope);
    }

    @Test
    @DisplayName("the prediction interval widens when the data is noisy")
    void noisyDataProducesAWiderInterval() {
        CostHistory tight = new CostHistory(100, null);
        CostHistory noisy = new CostHistory(100, null);
        for (int i = 0; i < 20; i++) {
            long megabytes = 10L * (i + 1);
            tight.add(CostObservation.savepoint(JOB, T0,
                    Duration.ofSeconds(20 + 2 * megabytes), features(megabytes, 2)));
            long jitter = (i % 2 == 0 ? 60 : -60);
            noisy.add(CostObservation.savepoint(JOB, T0,
                    Duration.ofSeconds(Math.max(1, 20 + 2 * megabytes + jitter)), features(megabytes, 2)));
        }

        CostEstimate tightEstimate = new RegressionCostPredictor(tight, fallback)
                .estimateSavepointDuration(features(100, 2));
        CostEstimate noisyEstimate = new RegressionCostPredictor(noisy, fallback)
                .estimateSavepointDuration(features(100, 2));

        long tightSpread = tightEstimate.getHigh().toSeconds() - tightEstimate.getLow().toSeconds();
        long noisySpread = noisyEstimate.getHigh().toSeconds() - noisyEstimate.getLow().toSeconds();

        assertTrue(noisySpread > tightSpread,
                "noisy data should not claim the same confidence as clean data");
    }

    @Test
    @DisplayName("savepoint history is not used to predict restarts")
    void savepointAndRestartAreLearnedSeparately() {
        RegressionCostPredictor predictor =
                new RegressionCostPredictor(historyWhereSavepointScalesWithState(20), fallback);

        assertEquals("regression", predictor.estimateSavepointDuration(features(50, 2)).getSource());
        assertEquals("static", predictor.estimateRestartDuration(features(50, 2)).getSource());
    }

    @Test
    @DisplayName("data with no variation cannot be fitted and falls back cleanly")
    void degenerateDataFallsBack() {
        CostHistory identical = new CostHistory(100, null);
        for (int i = 0; i < 20; i++) {
            identical.add(CostObservation.savepoint(JOB, T0,
                    Duration.ofSeconds(45), features(50, 2)));
        }

        CostEstimate estimate = new RegressionCostPredictor(identical, fallback)
                .estimateSavepointDuration(features(50, 2));

        assertTrue(estimate.getValue().toSeconds() > 0);
    }

    @Test
    void theSolverRecoversAKnownLine() {
        List<double[]> inputs = new ArrayList<>();
        List<Double> targets = new ArrayList<>();
        for (int x = 1; x <= 20; x++) {
            inputs.add(new double[]{x});
            targets.add(3.0 * x + 7.0);
        }

        Optional<RidgeRegression> fitted = RidgeRegression.fit(inputs, targets, 0.0001);

        assertTrue(fitted.isPresent());
        assertEquals(37.0, fitted.get().predict(new double[]{10}), 0.5);
    }

    @Test
    void anEmptyDatasetCannotBeFitted() {
        assertTrue(RidgeRegression.fit(List.of(), List.of(), 1.0).isEmpty());
    }
}
