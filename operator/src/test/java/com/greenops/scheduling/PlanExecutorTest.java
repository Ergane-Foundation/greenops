package com.greenops.scheduling;

import com.greenops.model.GreenOpsSpec;
import com.greenops.model.GreenOpsStatus;
import com.greenops.service.FlinkDeploymentService;
import com.greenops.service.FlinkService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        when(flinkDeployment.getUpgradeMode(anyString(), anyString())).thenReturn(Optional.of("savepoint"));

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
    @DisplayName("a requested savepoint is marked completed once Flink reports the job suspended")
    void suspendConfirmsTheSavepoint() {
        status.setSavepointPhase("REQUESTED");
        when(flinkDeployment.getJobState(NS, JOB)).thenReturn(Optional.of("suspended"));
        when(flinkDeployment.getLifecycleState(NS, JOB)).thenReturn(Optional.of("SUSPENDED"));
        when(flinkDeployment.getLastSavepointPath(NS, JOB))
                .thenReturn(Optional.of("s3://greenops/savepoints/savepoint-01852e"));

        executor.apply(SuspensionPlan.suspend("dirty"), spec, status);

        assertEquals("ALREADY_SUSPENDED", status.getLastAction());
        assertEquals("COMPLETED", status.getSavepointPhase());
        assertEquals("s3://greenops/savepoints/savepoint-01852e", status.getLastSavepointPath());
        assertNotNull(status.getLastSavepointAt());
    }

    @Test
    @DisplayName("a savepoint still in progress stays requested")
    void suspendInProgressStaysRequested() {
        status.setSavepointPhase("REQUESTED");
        when(flinkDeployment.getJobState(NS, JOB)).thenReturn(Optional.of("suspended"));
        when(flinkDeployment.getLifecycleState(NS, JOB)).thenReturn(Optional.of("UPGRADING"));
        when(flinkDeployment.getError(NS, JOB)).thenReturn(Optional.empty());

        executor.apply(SuspensionPlan.suspend("dirty"), spec, status);

        assertEquals("REQUESTED", status.getSavepointPhase());
    }

    @Test
    @DisplayName("an error from Flink while suspending is surfaced as a failed savepoint")
    void suspendErrorMarksTheSavepointFailed() {
        status.setSavepointPhase("REQUESTED");
        when(flinkDeployment.getJobState(NS, JOB)).thenReturn(Optional.of("suspended"));
        when(flinkDeployment.getLifecycleState(NS, JOB)).thenReturn(Optional.of("DEPLOYED"));
        when(flinkDeployment.getError(NS, JOB)).thenReturn(Optional.of("Savepoint failed: bucket unreachable"));

        executor.apply(SuspensionPlan.suspend("dirty"), spec, status);

        assertEquals("FAILED", status.getSavepointPhase());
        assertNotNull(status.getLastError());
    }

    @Test
    @DisplayName("a failed savepoint that Flink later completes is marked completed")
    void suspendRecoversFromAnEarlierFailure() {
        status.setSavepointPhase("FAILED");
        status.setLastError("Savepoint failed: bucket unreachable");
        when(flinkDeployment.getJobState(NS, JOB)).thenReturn(Optional.of("suspended"));
        when(flinkDeployment.getLifecycleState(NS, JOB)).thenReturn(Optional.of("SUSPENDED"));
        when(flinkDeployment.getLastSavepointPath(NS, JOB))
                .thenReturn(Optional.of("s3://greenops/savepoints/savepoint-retry"));

        executor.apply(SuspensionPlan.suspend("dirty"), spec, status);

        assertEquals("COMPLETED", status.getSavepointPhase());
        assertNull(status.getLastError());
    }

    @Test
    void aFailedSuspendPatchIsReported() {
        when(flinkDeployment.getJobState(NS, JOB)).thenReturn(Optional.of("running"));
        when(flinkDeployment.suspend(NS, JOB)).thenReturn(false);

        executor.apply(SuspensionPlan.suspend("dirty"), spec, status);

        assertEquals("SUSPEND_FAILED", status.getLastAction());
        assertNotNull(status.getLastError());
    }

    @ParameterizedTest
    @ValueSource(strings = {"stateless", "last-state"})
    @DisplayName("a job whose upgrade mode takes no savepoint is never suspended")
    void suspendIsBlockedWithoutSavepointUpgradeMode(String upgradeMode) {
        when(flinkDeployment.getJobState(NS, JOB)).thenReturn(Optional.of("running"));
        when(flinkDeployment.getUpgradeMode(NS, JOB)).thenReturn(Optional.of(upgradeMode));

        executor.apply(SuspensionPlan.suspend("dirty"), spec, status);

        verify(flinkDeployment, never()).suspend(anyString(), anyString());
        assertEquals("SUSPEND_BLOCKED_UPGRADE_MODE", status.getLastAction());
        assertNotNull(status.getLastError());
    }

    @Test
    @DisplayName("an unset upgrade mode is Flink's default, stateless, and blocks suspension")
    void suspendIsBlockedWhenUpgradeModeIsUnset() {
        when(flinkDeployment.getJobState(NS, JOB)).thenReturn(Optional.of("running"));
        when(flinkDeployment.getUpgradeMode(NS, JOB)).thenReturn(Optional.empty());

        executor.apply(SuspensionPlan.suspend("dirty"), spec, status);

        verify(flinkDeployment, never()).suspend(anyString(), anyString());
        assertEquals("SUSPEND_BLOCKED_UPGRADE_MODE", status.getLastAction());
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
