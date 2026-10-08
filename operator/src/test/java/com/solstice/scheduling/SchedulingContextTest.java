package com.solstice.scheduling;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchedulingContextTest {

    private SchedulingContext.Builder base() {
        return SchedulingContext.builder()
                .jobName("solstice-flink")
                .carbonThreshold(400);
    }

    @Test
    void anExplicitDirtyLabelWins() {
        SchedulingContext c = base().gridStatus("DIRTY").carbonIntensity(10).build();
        assertTrue(c.isGridDirty());
    }

    @Test
    void anExplicitGreenLabelWins() {
        SchedulingContext c = base().gridStatus("GREEN").carbonIntensity(900).build();
        assertFalse(c.isGridDirty());
    }

    @Test
    @DisplayName("an unknown grid falls back to the threshold comparison")
    void unknownGridComparesAgainstThreshold() {
        assertTrue(base().gridStatus("UNKNOWN").carbonIntensity(401).build().isGridDirty());
        assertFalse(base().gridStatus("UNKNOWN").carbonIntensity(400).build().isGridDirty());
    }

    @Test
    @DisplayName("an unreachable telemetry service reads as clean rather than dirty")
    void unreachableTelemetryIsNotDirty() {
        SchedulingContext c = base().gridStatus("UNKNOWN").carbonIntensity(0).build();
        assertFalse(c.isGridDirty());
    }

    @Test
    void plansCarryTheirReason() {
        SuspensionPlan plan = SuspensionPlan.suspend("carbon above threshold");
        assertEquals(SuspensionAction.SUSPEND, plan.getAction());
        assertEquals("carbon above threshold", plan.getReason());
        assertTrue(plan.isSuspend());
        assertFalse(plan.isResume());
    }

    @Test
    void holdIsNeitherSuspendNorResume() {
        SuspensionPlan plan = SuspensionPlan.hold("nothing to do");
        assertFalse(plan.isSuspend());
        assertFalse(plan.isResume());
    }
}
