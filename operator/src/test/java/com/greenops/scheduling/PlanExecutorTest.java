package com.greenops.scheduling;

import com.greenops.model.GreenOpsSpec;
import com.greenops.model.GreenOpsStatus;
import com.greenops.service.FlinkDeploymentService;
import com.greenops.service.FlinkService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlanExecutorTest {

    private static final String NS = "greenops";
    private static final String JOB = "greenops-flink";

    private FlinkService flink;
    private FlinkDeploymentService flinkDeployment;
    private PlanExecutor executor;
    private GreenOpsSpec spec;
    private GreenOpsStatus status;

    @BeforeEach
    void setUp() {
        flink = mock(FlinkService.class);
        flinkDeployment = mock(FlinkDeploymentService.class);
        executor = new PlanExecutor(flink, flinkDeployment);

        spec = new GreenOpsSpec();
        spec.setFlinkJobName(JOB);
        spec.setFlinkNamespace(NS);
        status = new GreenOpsStatus();
    }

    @Test
    void suspendPatchesTheFlinkDeployment() {
        when(flinkDeployment.getJobState(NS, JOB)).thenReturn(Optional.of("running"));
        when(flinkDeployment.suspend(NS, JOB)).thenReturn(true);

        executor.apply(SuspensionPlan.suspend("dirty"), spec, status);

        verify(flinkDeployment).suspend(NS, JOB);
        assertEquals("SUSPEND_REQUESTED", status.getLastAction());
    }

    @Test
    void suspendIsSkippedWhenAlreadySuspended() {
        when(flinkDeployment.getJobState(NS, JOB)).thenReturn(Optional.of("suspended"));

        executor.apply(SuspensionPlan.suspend("dirty"), spec, status);

        verify(flinkDeployment, never()).suspend(anyString(), anyString());
        assertEquals("ALREADY_SUSPENDED", status.getLastAction());
    }

    @Test
    void aFailedSuspendPatchIsReported() {
        when(flinkDeployment.getJobState(NS, JOB)).thenReturn(Optional.of("running"));
        when(flinkDeployment.suspend(NS, JOB)).thenReturn(false);

        executor.apply(SuspensionPlan.suspend("dirty"), spec, status);

        assertEquals("SUSPEND_FAILED", status.getLastAction());
        assertNotNull(status.getLastError());
    }

    @Test
    @DisplayName("resume seeds the job from the recorded savepoint")
    void resumeRestoresFromSavepoint() {
        when(flinkDeployment.getJobState(NS, JOB)).thenReturn(Optional.of("suspended"));
        when(flinkDeployment.getLastSavepointPath(NS, JOB))
                .thenReturn(Optional.of("s3://greenops/savepoints/savepoint-xyz"));
        when(flinkDeployment.resume(eq(NS), eq(JOB), anyString())).thenReturn(true);

        executor.apply(SuspensionPlan.resume("clean"), spec, status);

        verify(flinkDeployment).resume(NS, JOB, "s3://greenops/savepoints/savepoint-xyz");
        assertEquals("RESUME_FROM_SAVEPOINT", status.getLastAction());
        assertEquals("RESTORED", status.getSavepointPhase());
    }

    @Test
    @DisplayName("a job with no savepoint stays suspended rather than starting empty")
    void resumeIsBlockedWithoutASavepoint() {
        when(flinkDeployment.getJobState(NS, JOB)).thenReturn(Optional.of("suspended"));
        when(flinkDeployment.getLastSavepointPath(NS, JOB)).thenReturn(Optional.empty());

        executor.apply(SuspensionPlan.resume("clean"), spec, status);

        verify(flinkDeployment, never()).resume(anyString(), anyString(), any());
        assertEquals("RESUME_BLOCKED_NO_SAVEPOINT", status.getLastAction());
        assertEquals("MISSING", status.getSavepointPhase());
        assertNotNull(status.getLastError());
    }

    @Test
    @DisplayName("a savepoint recorded for another job is never used to resume this one")
    void resumeDoesNotBorrowAnotherJobsSavepoint() {
        status.setLastSavepointPath("s3://greenops/savepoints/savepoint-of-another-job");
        when(flinkDeployment.getJobState(NS, JOB)).thenReturn(Optional.of("suspended"));
        when(flinkDeployment.getLastSavepointPath(NS, JOB)).thenReturn(Optional.empty());

        executor.apply(SuspensionPlan.resume("clean"), spec, status);

        verify(flinkDeployment, never()).resume(anyString(), anyString(), any());
        assertEquals("RESUME_BLOCKED_NO_SAVEPOINT", status.getLastAction());
    }

    @Test
    @DisplayName("a Deployment left at zero by the old scaler is scaled back up")
    void resumeFixesStaleReplicas() {
        when(flinkDeployment.getJobState(NS, JOB)).thenReturn(Optional.of("running"));
        when(flink.isScaledToZero(NS, JOB)).thenReturn(true);

        executor.apply(SuspensionPlan.resume("clean"), spec, status);

        verify(flink).scaleJobManager(NS, JOB, 1);
        assertEquals("SCALE_UP_STALE_REPLICAS", status.getLastAction());
    }

    @Test
    void resumeLeavesAHealthyJobAlone() {
        when(flinkDeployment.getJobState(NS, JOB)).thenReturn(Optional.of("running"));
        when(flink.isScaledToZero(NS, JOB)).thenReturn(false);

        executor.apply(SuspensionPlan.resume("clean"), spec, status);

        verify(flink, never()).scaleJobManager(anyString(), anyString(), anyInt());
        assertEquals("ALREADY_RUNNING", status.getLastAction());
    }

    @Test
    @DisplayName("hold touches nothing at all")
    void holdChangesNothing() {
        executor.apply(SuspensionPlan.hold("window too short"), spec, status);

        verify(flinkDeployment, never()).suspend(anyString(), anyString());
        verify(flinkDeployment, never()).resume(anyString(), anyString(), anyString());
        verify(flink, never()).scaleJobManager(anyString(), anyString(), anyInt());
        assertEquals("HOLD", status.getLastAction());
    }
}
