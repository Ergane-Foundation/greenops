package com.solstice.cost;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObservedCostPredictorTest {

    private static final String JOB = "solstice-flink";
    private static final Instant T0 = Instant.parse("2026-07-28T09:00:00Z");

    private final CostPredictor fallback = new StaticCostPredictor(
            Duration.ofSeconds(60), Duration.ofSeconds(120));

    private CostHistory historyWithSavepoints(long... seconds) {
        CostHistory history = new CostHistory();
        for (long s : seconds) {
            history.add(CostObservation.savepoint(JOB, T0, Duration.ofSeconds(s), JobFeatures.unknown(JOB)));
        }
        return history;
    }

    @Test
    @DisplayName("too little history means falling back rather than guessing from noise")
    void fallsBackWhenHistoryIsThin() {
        ObservedCostPredictor predictor = new ObservedCostPredictor(historyWithSavepoints(30, 40), fallback);

        CostEstimate estimate = predictor.estimateSavepointDuration(JobFeatures.unknown(JOB));

        assertEquals(Duration.ofSeconds(60), estimate.getValue());
        assertEquals("static", estimate.getSource());
    }

    @Test
    void usesTheMedianOnceThereIsEnoughHistory() {
        ObservedCostPredictor predictor = new ObservedCostPredictor(
                historyWithSavepoints(20, 30, 40, 50, 60), fallback);

        CostEstimate estimate = predictor.estimateSavepointDuration(JobFeatures.unknown(JOB));

        assertEquals(Duration.ofSeconds(40), estimate.getValue());
        assertEquals("observed", estimate.getSource());
        assertEquals(5, estimate.getSampleCount());
    }

    @Test
    @DisplayName("one pathological savepoint does not drag the estimate with it")
    void anOutlierDoesNotMoveTheMedian() {
        ObservedCostPredictor predictor = new ObservedCostPredictor(
                historyWithSavepoints(30, 32, 31, 33, 900), fallback);

        CostEstimate estimate = predictor.estimateSavepointDuration(JobFeatures.unknown(JOB));

        assertEquals(Duration.ofSeconds(32), estimate.getValue());
        assertTrue(estimate.getHigh().toSeconds() >= 33);
    }

    @Test
    void reportsARangeAroundTheMedian() {
        ObservedCostPredictor predictor = new ObservedCostPredictor(
                historyWithSavepoints(10, 20, 30, 40, 50, 60, 70, 80, 90, 100), fallback);

        CostEstimate estimate = predictor.estimateSavepointDuration(JobFeatures.unknown(JOB));

        assertTrue(estimate.getLow().compareTo(estimate.getValue()) <= 0);
        assertTrue(estimate.getHigh().compareTo(estimate.getValue()) >= 0);
        assertTrue(estimate.isConfident());
    }

    @Test
    @DisplayName("history from another job is not borrowed")
    void keepsJobsSeparate() {
        CostHistory history = new CostHistory();
        for (long s : new long[]{10, 11, 12, 13}) {
            history.add(CostObservation.savepoint("other-job", T0, Duration.ofSeconds(s), JobFeatures.unknown("other-job")));
        }
        ObservedCostPredictor predictor = new ObservedCostPredictor(history, fallback);

        CostEstimate estimate = predictor.estimateSavepointDuration(JobFeatures.unknown(JOB));

        assertEquals(Duration.ofSeconds(60), estimate.getValue());
        assertEquals("static", estimate.getSource());
    }

    @Test
    void savepointAndRestartHistoriesDoNotMix() {
        CostHistory history = new CostHistory();
        for (long s : new long[]{20, 25, 30, 35}) {
            history.add(CostObservation.savepoint(JOB, T0, Duration.ofSeconds(s), JobFeatures.unknown(JOB)));
        }
        ObservedCostPredictor predictor = new ObservedCostPredictor(history, fallback);

        assertEquals("observed", predictor.estimateSavepointDuration(JobFeatures.unknown(JOB)).getSource());
        assertEquals("static", predictor.estimateRestartDuration(JobFeatures.unknown(JOB)).getSource());
    }

    @Test
    @DisplayName("the ring buffer keeps the most recent observations and drops the oldest")
    void historyIsBounded() {
        CostHistory history = new CostHistory(3, null);
        for (long s : new long[]{10, 20, 30, 40, 50}) {
            history.add(CostObservation.savepoint(JOB, T0, Duration.ofSeconds(s), JobFeatures.unknown(JOB)));
        }

        assertEquals(3, history.size());
        assertEquals(java.util.List.of(30L, 40L, 50L), history.savepointSeconds(JOB));
    }

    @Test
    void anEmptyHistoryIsUsable() {
        ObservedCostPredictor predictor = new ObservedCostPredictor(new CostHistory(), fallback);
        assertTrue(new CostHistory().isEmpty());
        assertFalse(predictor.estimateSavepointDuration(JobFeatures.unknown(JOB)).isConfident());
    }
}
