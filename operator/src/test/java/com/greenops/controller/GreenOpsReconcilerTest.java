package com.greenops.controller;

import com.greenops.model.GreenOpsResource;
import com.greenops.model.GreenOpsSpec;
import com.greenops.forecast.ForecastService;
import com.greenops.scheduling.PlanExecutor;
import com.greenops.scheduling.SchedulingContext;
import com.greenops.scheduling.SchedulingPolicy;
import com.greenops.scheduling.SuspensionPlan;
import com.greenops.service.FlinkDeploymentService;
import com.greenops.service.LegacySavepointSuspender;
import com.greenops.service.FlinkService;
import com.greenops.service.SavepointResult;
import com.greenops.service.TelemetryService;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GreenOpsReconcilerTest {

    private static final String NS = "greenops";
    private static final String JOB = "greenops-flink";
    private static final String REST = "http://flink-rest:8081";
    private static final String TELEMETRY = "http://telemetry:8080/telemetry/status";

    private TelemetryService telemetry;
    private FlinkService flink;
    private FlinkDeploymentService flinkDeployment;
    private GreenOpsReconciler reconciler;

    @BeforeEach
    void setUp() {
        telemetry = mock(TelemetryService.class);
        flink = mock(FlinkService.class);
        flinkDeployment = mock(FlinkDeploymentService.class);
        reconciler = new GreenOpsReconciler(telemetry, flink, flinkDeployment);
    }

    private GreenOpsResource resource(GreenOpsSpec spec) {
        GreenOpsResource resource = new GreenOpsResource();
        ObjectMeta meta = new ObjectMeta();
        meta.setName("greenops-flink-controller");
        meta.setNamespace(NS);
        resource.setMetadata(meta);
        resource.setSpec(spec);
        return resource;
    }

    private GreenOpsSpec directModeSpec() {
        GreenOpsSpec spec = new GreenOpsSpec();
        spec.setCooperativeSuspension(false);
        spec.setCarbonThreshold(400);
        spec.setFlinkJobName(JOB);
        spec.setFlinkNamespace(NS);
        spec.setTelemetryEndpoint(TELEMETRY);
        spec.setFlinkRestEndpoint(REST);
        spec.setSavepointDirectory("s3://greenops/savepoints");
        spec.setSavepointTimeoutSeconds(300);
        return spec;
    }

    private GreenOpsSpec cooperativeSpec() {
        GreenOpsSpec spec = directModeSpec();
        spec.setCooperativeSuspension(true);
        return spec;
    }

    private void gridReturns(String status, int intensity) {
        when(telemetry.getCurrentStatus(anyString()))
                .thenReturn(new TelemetryService.GridStatus(status, intensity));
    }

    @Nested
    @DisplayName("a suspension must never follow an unsuccessful savepoint")
    class SafetyRule {

        @Test
        void scalesDownOnlyAfterASavepointCompletes() throws Exception {
            gridReturns("DIRTY", 850);
            when(flink.getRunningJobId(REST)).thenReturn(Optional.of("job-1"));
            when(flink.triggerSavepoint(eq(REST), eq("job-1"), anyString())).thenReturn("trigger-1");
            when(flink.pollSavepointCompletion(eq(REST), eq("job-1"), eq("trigger-1"), anyInt()))
                    .thenReturn(SavepointResult.success("s3://greenops/savepoints/savepoint-abc"));

            GreenOpsResource resource = resource(directModeSpec());
            reconciler.reconcile(resource, null);

            verify(flink).scaleJobManager(NS, JOB, 0);
            assertEquals("SCALE_DOWN_AFTER_SAVEPOINT", resource.getStatus().getLastAction());
            assertEquals("COMPLETED", resource.getStatus().getSavepointPhase());
            assertEquals("s3://greenops/savepoints/savepoint-abc",
                    resource.getStatus().getLastSavepointPath());
            assertNull(resource.getStatus().getLastError());
        }

        @Test
        void doesNotScaleDownWhenTheSavepointFails() throws Exception {
            gridReturns("DIRTY", 850);
            when(flink.getRunningJobId(REST)).thenReturn(Optional.of("job-1"));
            when(flink.triggerSavepoint(eq(REST), eq("job-1"), anyString())).thenReturn("trigger-1");
            when(flink.pollSavepointCompletion(eq(REST), eq("job-1"), eq("trigger-1"), anyInt()))
                    .thenReturn(SavepointResult.failure("java.io.IOException: bucket unreachable"));

            GreenOpsResource resource = resource(directModeSpec());
            reconciler.reconcile(resource, null);

            verify(flink, never()).scaleJobManager(anyString(), anyString(), eq(0));
            assertEquals("SAVEPOINT_FAILED", resource.getStatus().getLastAction());
            assertEquals("FAILED", resource.getStatus().getSavepointPhase());
            assertNotNull(resource.getStatus().getLastError());
        }

        @Test
        void doesNotScaleDownWhenTheSavepointTimesOut() throws Exception {
            gridReturns("DIRTY", 850);
            when(flink.getRunningJobId(REST)).thenReturn(Optional.of("job-1"));
            when(flink.triggerSavepoint(eq(REST), eq("job-1"), anyString())).thenReturn("trigger-1");
            when(flink.pollSavepointCompletion(eq(REST), eq("job-1"), eq("trigger-1"), anyInt()))
                    .thenReturn(SavepointResult.timeout());

            GreenOpsResource resource = resource(directModeSpec());
            reconciler.reconcile(resource, null);

            verify(flink, never()).scaleJobManager(anyString(), anyString(), eq(0));
            assertEquals("SAVEPOINT_FAILED", resource.getStatus().getLastAction());
            assertEquals("TIMEOUT", resource.getStatus().getSavepointPhase());
        }

        @Test
        void doesNotScaleDownWhenTheSavepointCallThrows() throws Exception {
            gridReturns("DIRTY", 850);
            when(flink.getRunningJobId(REST)).thenReturn(Optional.of("job-1"));
            when(flink.triggerSavepoint(eq(REST), eq("job-1"), anyString()))
                    .thenThrow(new IllegalStateException("connection refused"));

            GreenOpsResource resource = resource(directModeSpec());
            reconciler.reconcile(resource, null);

            verify(flink, never()).scaleJobManager(anyString(), anyString(), eq(0));
            assertEquals("SAVEPOINT_ERROR", resource.getStatus().getLastAction());
            assertEquals("FAILED", resource.getStatus().getSavepointPhase());
            assertNotNull(resource.getStatus().getLastError());
        }
    }

    @Test
    @DisplayName("a job that is already gone has no state to lose")
    void scalesDownWithoutSavepointWhenNoJobIsRunning() throws Exception {
        gridReturns("DIRTY", 850);
        when(flink.getRunningJobId(REST)).thenReturn(Optional.empty());

        GreenOpsResource resource = resource(directModeSpec());
        reconciler.reconcile(resource, null);

        verify(flink).scaleJobManager(NS, JOB, 0);
        assertEquals("SCALE_DOWN_NO_JOB", resource.getStatus().getLastAction());
        assertEquals("SKIPPED", resource.getStatus().getSavepointPhase());
    }

    @Test
    @DisplayName("without a REST endpoint there is no way to take a savepoint")
    void fallsBackToDirectScaleDownWithoutRestEndpoint() {
        gridReturns("DIRTY", 850);
        GreenOpsSpec spec = directModeSpec();
        spec.setFlinkRestEndpoint(null);

        GreenOpsResource resource = resource(spec);
        reconciler.reconcile(resource, null);

        verify(flink).scaleJobManager(NS, JOB, 0);
        assertEquals("SCALE_DOWN_NO_SAVEPOINT", resource.getStatus().getLastAction());
    }

    @Test
    void scalesUpOnACleanGrid() {
        gridReturns("GREEN", 120);

        GreenOpsResource resource = resource(directModeSpec());
        reconciler.reconcile(resource, null);

        verify(flink).scaleJobManager(NS, JOB, 1);
        assertEquals("SCALE_UP", resource.getStatus().getLastAction());
    }

    @Test
    @DisplayName("an unreachable telemetry service must not trigger a suspension")
    void treatsUnknownGridBelowThresholdAsClean() {
        gridReturns("UNKNOWN", 0);

        GreenOpsResource resource = resource(directModeSpec());
        reconciler.reconcile(resource, null);

        verify(flink, never()).scaleJobManager(anyString(), anyString(), eq(0));
        assertEquals("SCALE_UP", resource.getStatus().getLastAction());
    }

    @Test
    void skipsReconcileWhenTelemetryEndpointIsMissing() {
        GreenOpsSpec spec = directModeSpec();
        spec.setTelemetryEndpoint(null);

        GreenOpsResource resource = resource(spec);
        reconciler.reconcile(resource, null);

        verify(flink, never()).scaleJobManager(anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("the reconciler asks the policy rather than deciding for itself")
    void delegatesTheDecisionToThePolicy() {
        gridReturns("DIRTY", 850);
        when(flinkDeployment.getJobState(NS, JOB)).thenReturn(Optional.of("running"));
        when(flinkDeployment.suspend(NS, JOB)).thenReturn(true);

        SchedulingPolicy alwaysHold = new SchedulingPolicy() {
            @Override
            public String name() {
                return "always-hold";
            }

            @Override
            public SuspensionPlan decide(SchedulingContext context) {
                return SuspensionPlan.hold("policy said so");
            }
        };

        GreenOpsReconciler holding = new GreenOpsReconciler(
                telemetry, new ForecastService(), flinkDeployment,
                new PlanExecutor(flink, flinkDeployment),
                new LegacySavepointSuspender(flink),
                alwaysHold);

        GreenOpsResource resource = resource(cooperativeSpec());
        holding.reconcile(resource, null);

        verify(flinkDeployment, never()).suspend(anyString(), anyString());
        assertEquals("HOLD", resource.getStatus().getLastAction());
        assertEquals("policy said so", resource.getStatus().getDecisionReason());
    }

    @Test
    void recordsWhyTheDecisionWasMade() {
        gridReturns("DIRTY", 850);
        when(flinkDeployment.getJobState(NS, JOB)).thenReturn(Optional.of("running"));
        when(flinkDeployment.suspend(NS, JOB)).thenReturn(true);

        GreenOpsResource resource = resource(cooperativeSpec());
        reconciler.reconcile(resource, null);

        assertNotNull(resource.getStatus().getDecisionReason());
        assertTrue(resource.getStatus().getDecisionReason().contains("850"));
    }

    @Nested
    @DisplayName("cooperative mode hands the lifecycle to the Flink operator")
    class Cooperative {

        @Test
        void requestsSuspendOnADirtyGrid() {
            gridReturns("DIRTY", 850);
            when(flinkDeployment.getJobState(NS, JOB)).thenReturn(Optional.of("running"));
            when(flinkDeployment.suspend(NS, JOB)).thenReturn(true);

            GreenOpsResource resource = resource(cooperativeSpec());
            reconciler.reconcile(resource, null);

            verify(flinkDeployment).suspend(NS, JOB);
            verify(flink, never()).scaleJobManager(anyString(), anyString(), anyInt());
            assertEquals("SUSPEND_REQUESTED", resource.getStatus().getLastAction());
        }

        @Test
        @DisplayName("resume seeds the job from the savepoint written on the way down")
        void resumesFromTheRecordedSavepoint() {
            gridReturns("GREEN", 120);
            when(flinkDeployment.getJobState(NS, JOB)).thenReturn(Optional.of("suspended"));
            when(flinkDeployment.getLastSavepointPath(NS, JOB))
                    .thenReturn(Optional.of("s3://greenops/savepoints/savepoint-xyz"));
            when(flinkDeployment.resume(eq(NS), eq(JOB), anyString())).thenReturn(true);

            GreenOpsResource resource = resource(cooperativeSpec());
            reconciler.reconcile(resource, null);

            verify(flinkDeployment).resume(NS, JOB, "s3://greenops/savepoints/savepoint-xyz");
            assertEquals("RESUME_FROM_SAVEPOINT", resource.getStatus().getLastAction());
            assertEquals("RESTORED", resource.getStatus().getSavepointPhase());
        }


        @Test
        @DisplayName("a Deployment left at zero by the old scaler is scaled back up")
        void scalesUpWhenReplicasAreStaleAtZero() {
            gridReturns("GREEN", 120);
            when(flinkDeployment.getJobState(NS, JOB)).thenReturn(Optional.of("running"));
            when(flink.isScaledToZero(NS, JOB)).thenReturn(true);

            GreenOpsResource resource = resource(cooperativeSpec());
            reconciler.reconcile(resource, null);

            verify(flink).scaleJobManager(NS, JOB, 1);
            assertEquals("SCALE_UP_STALE_REPLICAS", resource.getStatus().getLastAction());
        }

        @Test
        void leavesAHealthyRunningJobAlone() {
            gridReturns("GREEN", 120);
            when(flinkDeployment.getJobState(NS, JOB)).thenReturn(Optional.of("running"));
            when(flink.isScaledToZero(NS, JOB)).thenReturn(false);

            GreenOpsResource resource = resource(cooperativeSpec());
            reconciler.reconcile(resource, null);

            verify(flink, never()).scaleJobManager(anyString(), anyString(), anyInt());
            assertEquals("ALREADY_RUNNING", resource.getStatus().getLastAction());
        }

        @Test
        void doesNotResuspendAnAlreadySuspendedJob() {
            gridReturns("DIRTY", 850);
            when(flinkDeployment.getJobState(NS, JOB)).thenReturn(Optional.of("suspended"));

            GreenOpsResource resource = resource(cooperativeSpec());
            reconciler.reconcile(resource, null);

            verify(flinkDeployment, never()).suspend(anyString(), anyString());
            assertEquals("ALREADY_SUSPENDED", resource.getStatus().getLastAction());
        }

        @Test
        void reportsAFailedSuspendPatch() {
            gridReturns("DIRTY", 850);
            when(flinkDeployment.getJobState(NS, JOB)).thenReturn(Optional.of("running"));
            when(flinkDeployment.suspend(NS, JOB)).thenReturn(false);

            GreenOpsResource resource = resource(cooperativeSpec());
            reconciler.reconcile(resource, null);

            assertEquals("SUSPEND_FAILED", resource.getStatus().getLastAction());
            assertNotNull(resource.getStatus().getLastError());
        }
    }
}
