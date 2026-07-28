package com.greenops.cost;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CostModelTest {

    private static final JobFeatures JOB = JobFeatures.unknown("greenops-flink");

    private CostModel modelWith(long savepointSeconds, long restartSeconds, double multiplier) {
        return new CostModel(
                new StaticCostPredictor(Duration.ofSeconds(savepointSeconds), Duration.ofSeconds(restartSeconds)),
                multiplier);
    }

    @Test
    @DisplayName("a long dirty window is worth the round trip")
    void aLongWindowIsWorthIt() {
        CostModel model = modelWith(60, 120, 2.0);

        SuspensionDecision decision = model.evaluate(JOB, Duration.ofHours(8));

        assertTrue(decision.isWorthIt());
        assertEquals(SuspensionDecision.Verdict.WORTH_IT, decision.getVerdict());
    }

    @Test
    @DisplayName("a short dirty window costs more than it saves")
    void aShortWindowIsNotWorthIt() {
        CostModel model = modelWith(60, 120, 2.0);

        SuspensionDecision decision = model.evaluate(JOB, Duration.ofMinutes(3));

        assertFalse(decision.isWorthIt());
        assertEquals(SuspensionDecision.Verdict.WINDOW_TOO_SHORT, decision.getVerdict());
        assertEquals(Duration.ofSeconds(360), decision.getBreakEven());
    }

    @Test
    @DisplayName("the boundary sits exactly at break even")
    void theBoundaryIsInclusive() {
        CostModel model = modelWith(60, 120, 2.0);

        assertTrue(model.evaluate(JOB, Duration.ofSeconds(360)).isWorthIt());
        assertFalse(model.evaluate(JOB, Duration.ofSeconds(359)).isWorthIt());
    }

    @Test
    @DisplayName("a bigger multiplier demands a longer window before suspending")
    void theMultiplierMovesTheBar() {
        SuspensionDecision lenient = modelWith(60, 120, 1.0).evaluate(JOB, Duration.ofMinutes(5));
        SuspensionDecision strict = modelWith(60, 120, 4.0).evaluate(JOB, Duration.ofMinutes(5));

        assertTrue(lenient.isWorthIt());
        assertFalse(strict.isWorthIt());
    }

    @Test
    @DisplayName("without a forecast there is no window to judge")
    void noWindowIsItsOwnVerdict() {
        SuspensionDecision decision = modelWith(60, 120, 2.0).evaluate(JOB, null);

        assertEquals(SuspensionDecision.Verdict.NO_WINDOW_KNOWN, decision.getVerdict());
        assertFalse(decision.isWorthIt());
    }

    @Test
    @DisplayName("break even uses the pessimistic cost so a slow savepoint does not surprise us")
    void breakEvenUsesTheUpperBound() {
        CostHistory history = new CostHistory();
        for (long s : new long[]{30, 40, 50, 60, 200}) {
            history.add(CostObservation.savepoint("greenops-flink", java.time.Instant.now(),
                    Duration.ofSeconds(s), JOB));
        }
        for (long s : new long[]{100, 110, 120, 130, 140}) {
            history.add(CostObservation.restart("greenops-flink", java.time.Instant.now(),
                    Duration.ofSeconds(s), JOB));
        }

        CostModel model = new CostModel(
                new ObservedCostPredictor(history, StaticCostPredictor.withDefaults()), 2.0);
        SuspensionCost cost = model.costOf(JOB);

        assertTrue(cost.conservativeTotal().compareTo(cost.total()) > 0);
    }

    @Test
    void everyDecisionExplainsItself() {
        CostModel model = modelWith(60, 120, 2.0);

        assertTrue(model.evaluate(JOB, Duration.ofMinutes(3)).getReason().contains("below"));
        assertTrue(model.evaluate(JOB, Duration.ofHours(8)).getReason().contains("covers"));
        assertTrue(model.evaluate(JOB, null).getReason().contains("no forecast"));
    }
}
