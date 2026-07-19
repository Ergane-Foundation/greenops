package com.greenops.scheduling;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThresholdPolicyTest {

    private final ThresholdPolicy policy = new ThresholdPolicy();

    private SchedulingContext context(String gridStatus, int intensity) {
        return SchedulingContext.builder()
                .jobName("greenops-flink")
                .carbonThreshold(400)
                .gridStatus(gridStatus)
                .carbonIntensity(intensity)
                .build();
    }

    @Test
    void suspendsOnADirtyGrid() {
        SuspensionPlan plan = policy.decide(context("DIRTY", 850));
        assertEquals(SuspensionAction.SUSPEND, plan.getAction());
    }

    @Test
    void resumesOnACleanGrid() {
        SuspensionPlan plan = policy.decide(context("GREEN", 120));
        assertEquals(SuspensionAction.RESUME, plan.getAction());
    }

    @Test
    @DisplayName("the threshold boundary resumes rather than suspends")
    void exactlyAtThresholdIsNotDirty() {
        assertEquals(SuspensionAction.RESUME, policy.decide(context("UNKNOWN", 400)).getAction());
        assertEquals(SuspensionAction.SUSPEND, policy.decide(context("UNKNOWN", 401)).getAction());
    }

    @Test
    @DisplayName("an unreachable telemetry service never triggers a suspension")
    void unknownGridAtZeroResumes() {
        assertEquals(SuspensionAction.RESUME, policy.decide(context("UNKNOWN", 0)).getAction());
    }

    @Test
    void everyDecisionExplainsItself() {
        assertNotNull(policy.decide(context("DIRTY", 850)).getReason());
        assertTrue(policy.decide(context("DIRTY", 850)).getReason().contains("850"));
        assertNotNull(policy.decide(context("GREEN", 120)).getReason());
    }

    @Test
    void isNamed() {
        assertEquals("threshold", policy.name());
    }
}
