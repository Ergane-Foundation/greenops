package com.solstice.cost;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SuspensionCostTest {

    @Test
    void addsSavepointAndRestartTogether() {
        SuspensionCost cost = new SuspensionCost(
                CostEstimate.certain(Duration.ofSeconds(45), "test"),
                CostEstimate.certain(Duration.ofSeconds(90), "test"));

        assertEquals(Duration.ofSeconds(135), cost.total());
    }

    @Test
    @DisplayName("the conservative total uses the upper bound of each estimate")
    void conservativeTotalUsesUpperBounds() {
        SuspensionCost cost = new SuspensionCost(
                CostEstimate.of(Duration.ofSeconds(45), Duration.ofSeconds(30), Duration.ofSeconds(80), 10, "test"),
                CostEstimate.of(Duration.ofSeconds(90), Duration.ofSeconds(70), Duration.ofSeconds(140), 10, "test"));

        assertEquals(Duration.ofSeconds(135), cost.total());
        assertEquals(Duration.ofSeconds(220), cost.conservativeTotal());
    }

    @Test
    @DisplayName("break even scales the pessimistic cost, not the average")
    void breakEvenUsesTheConservativeTotal() {
        SuspensionCost cost = new SuspensionCost(
                CostEstimate.of(Duration.ofSeconds(60), Duration.ofSeconds(50), Duration.ofSeconds(100), 8, "test"),
                CostEstimate.of(Duration.ofSeconds(120), Duration.ofSeconds(100), Duration.ofSeconds(200), 8, "test"));

        assertEquals(Duration.ofSeconds(600), cost.breakEvenWindow(2.0));
    }

    @Test
    @DisplayName("an estimate from too few samples is not treated as confident")
    void confidenceNeedsEnoughSamples() {
        CostEstimate thin = CostEstimate.of(Duration.ofSeconds(60), Duration.ofSeconds(50),
                Duration.ofSeconds(70), 2, "observed");
        CostEstimate solid = CostEstimate.of(Duration.ofSeconds(60), Duration.ofSeconds(50),
                Duration.ofSeconds(70), 12, "observed");

        assertFalse(thin.isConfident());
        assertTrue(solid.isConfident());
    }

    @Test
    void staticPredictorReturnsItsConfiguredValues() {
        StaticCostPredictor predictor = new StaticCostPredictor(
                Duration.ofSeconds(30), Duration.ofSeconds(75));
        JobFeatures features = JobFeatures.unknown("solstice-flink");

        assertEquals(Duration.ofSeconds(30), predictor.estimateSavepointDuration(features).getValue());
        assertEquals(Duration.ofSeconds(75), predictor.estimateRestartDuration(features).getValue());
        assertEquals("static", predictor.name());
    }

    @Test
    void featuresReportStateSizeInMegabytes() {
        JobFeatures features = JobFeatures.builder()
                .jobName("solstice-flink")
                .stateSizeBytes(52428800)
                .parallelism(2)
                .build();

        assertEquals(50.0, features.getStateSizeMegabytes(), 0.001);
        assertTrue(features.hasStateSize());
        assertFalse(JobFeatures.unknown("x").hasStateSize());
    }
}
