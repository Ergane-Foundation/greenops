package com.solstice.cost;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CostRecorderTest {

    private static final String JOB = "orders";
    private static final Instant T0 = Instant.parse("2026-08-12T09:00:00Z");

    private CostRecorder recorder;

    @BeforeEach
    void setUp() {
        recorder = new CostRecorder();
    }

    @Test
    @DisplayName("a suspend that lands is measured from when it was asked for")
    void measuresASuspend() {
        recorder.suspendRequested(JOB, T0);

        Optional<CostObservation> observed =
                recorder.suspendObserved(JOB, T0.plus(Duration.ofSeconds(47)), JobFeatures.unknown(JOB));

        assertTrue(observed.isPresent());
        assertEquals(47, observed.get().getSavepointSeconds());
        assertTrue(observed.get().hasSavepoint());
        assertFalse(observed.get().hasRestart());
    }

    @Test
    void measuresAResume() {
        recorder.resumeRequested(JOB, T0);

        Optional<CostObservation> observed =
                recorder.resumeObserved(JOB, T0.plus(Duration.ofSeconds(130)), JobFeatures.unknown(JOB));

        assertTrue(observed.isPresent());
        assertEquals(130, observed.get().getRestartSeconds());
    }

    @Test
    @DisplayName("nothing is recorded for a transition that was never requested")
    void ignoresAnUnrequestedTransition() {
        assertTrue(recorder.suspendObserved(JOB, T0, JobFeatures.unknown(JOB)).isEmpty());
        assertTrue(recorder.resumeObserved(JOB, T0, JobFeatures.unknown(JOB)).isEmpty());
    }

    @Test
    @DisplayName("an implausibly long gap is discarded rather than poisoning the history")
    void discardsImplausibleDurations() {
        recorder.suspendRequested(JOB, T0);

        Optional<CostObservation> observed =
                recorder.suspendObserved(JOB, T0.plus(Duration.ofHours(9)), JobFeatures.unknown(JOB));

        assertTrue(observed.isEmpty());
    }

    @Test
    @DisplayName("a clock going backwards is discarded too")
    void discardsNegativeDurations() {
        recorder.suspendRequested(JOB, T0);

        assertTrue(recorder.suspendObserved(JOB, T0.minusSeconds(30), JobFeatures.unknown(JOB)).isEmpty());
    }

    @Test
    void anObservationIsOnlyCountedOnce() {
        recorder.suspendRequested(JOB, T0);

        assertTrue(recorder.suspendObserved(JOB, T0.plusSeconds(40), JobFeatures.unknown(JOB)).isPresent());
        assertTrue(recorder.suspendObserved(JOB, T0.plusSeconds(80), JobFeatures.unknown(JOB)).isEmpty());
    }

    @Test
    @DisplayName("jobs are timed independently")
    void jobsDoNotShareTimers() {
        recorder.suspendRequested("a", T0);
        recorder.suspendRequested("b", T0.plusSeconds(10));

        assertEquals(30, recorder.suspendObserved("a", T0.plusSeconds(30), JobFeatures.unknown("a"))
                .orElseThrow().getSavepointSeconds());
        assertEquals(50, recorder.suspendObserved("b", T0.plusSeconds(60), JobFeatures.unknown("b"))
                .orElseThrow().getSavepointSeconds());
    }

    @Test
    void reportsWhatItIsWaitingFor() {
        recorder.suspendRequested(JOB, T0);

        assertTrue(recorder.isAwaitingSuspend(JOB));
        assertFalse(recorder.isAwaitingResume(JOB));

        recorder.forget(JOB);
        assertFalse(recorder.isAwaitingSuspend(JOB));
    }

    @Test
    @DisplayName("the features at the time of measurement travel with the observation")
    void carriesFeaturesAlongside() {
        recorder.suspendRequested(JOB, T0);
        JobFeatures features = JobFeatures.builder()
                .jobName(JOB)
                .stateSizeBytes(104857600)
                .parallelism(4)
                .build();

        CostObservation observed =
                recorder.suspendObserved(JOB, T0.plusSeconds(60), features).orElseThrow();

        assertEquals(104857600, observed.getStateSizeBytes());
        assertEquals(4, observed.getParallelism());
    }
}
