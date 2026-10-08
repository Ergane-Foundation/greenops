package com.solstice.controller;

import com.solstice.model.SolsticeResource;
import com.solstice.model.SolsticeSpec;
import com.solstice.forecast.ForecastService;
import com.solstice.scheduling.PlanExecutor;
import com.solstice.scheduling.SchedulingContext;
import com.solstice.scheduling.SchedulingPolicy;
import com.solstice.scheduling.SuspensionPlan;
import com.solstice.service.FlinkDeploymentService;
import com.solstice.service.LegacySavepointSuspender;
import com.solstice.service.FlinkService;
import com.solstice.service.SavepointResult;
import com.solstice.service.TelemetryService;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import io.prometheus.metrics.model.snapshots.CounterSnapshot;
import io.prometheus.metrics.model.snapshots.DataPointSnapshot;
import io.prometheus.metrics.model.snapshots.GaugeSnapshot;
import io.prometheus.metrics.model.snapshots.Labels;
import io.prometheus.metrics.model.snapshots.MetricSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SolsticeReconcilerTest {

    private static final String NS = "solstice";
    private static final String JOB = "solstice-flink";
    private static final String REST = "http://flink-rest:8081";
    private static final String TELEMETRY = "http://telemetry:8080/telemetry/status";

    private TelemetryService telemetry;
    private FlinkService flink;
    private FlinkDeploymentService flinkDeployment;
    private SolsticeReconciler reconciler;

    @BeforeEach
    void setUp() {
        telemetry = mock(TelemetryService.class);
        flink = mock(FlinkService.class);
        flinkDeployment = mock(FlinkDeploymentService.class);
        reconciler = new SolsticeReconciler(telemetry, flink, flinkDeployment);
        when(flinkDeployment.getUpgradeMode(anyString(), anyString())).thenReturn(Optional.of("savepoint"));
    }

    private SolsticeResource resource(SolsticeSpec spec) {
        SolsticeResource resource = new SolsticeResource();
        ObjectMeta meta = new ObjectMeta();
        meta.setName("solstice-flink-controller");
        meta.setNamespace(NS);
        resource.setMetadata(meta);
        resource.setSpec(spec);
        return resource;
    }

    private SolsticeSpec directModeSpec() {
        SolsticeSpec spec = new SolsticeSpec();
        spec.setCooperativeSuspension(false);
        spec.setCarbonThreshold(400);
        spec.setFlinkJobName(JOB);
        spec.setFlinkNamespace(NS);
        spec.setTelemetryEndpoint(TELEMETRY);
        spec.setFlinkRestEndpoint(REST);
        spec.setSavepointDirectory("s3://solstice/savepoints");
        spec.setSavepointTimeoutSeconds(300);
        return spec;
    }

    private SolsticeSpec cooperativeSpec() {
        SolsticeSpec spec = directModeSpec();
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
                    .thenReturn(SavepointResult.success("s3://solstice/savepoints/savepoint-abc"));

            SolsticeResource resource = resource(directModeSpec());
            reconciler.reconcile(resource, null);

            verify(flink).scaleJobManager(NS, JOB, 0);
            assertEquals("SCALE_DOWN_AFTER_SAVEPOINT", resource.getStatus().getLastAction());
            assertEquals("COMPLETED", resource.getStatus().getSavepointPhase());
            assertEquals("s3://solstice/savepoints/savepoint-abc",
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

            SolsticeResource resource = resource(directModeSpec());
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

            SolsticeResource resource = resource(directModeSpec());
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

            SolsticeResource resource = resource(directModeSpec());
            reconciler.reconcile(resource, null);

            verify(flink, never()).scaleJobManager(anyString(), anyString(), eq(0));
            assertEquals("SAVEPOINT_ERROR", resource.getStatus().getLastAction());
            assertEquals("FAILED", resource.getStatus().getSavepointPhase());
            assertNotNull(resource.getStatus().getLastError());
        }
    }

    @Test
    @DisplayName("a job that is not RUNNING may still hold state, so it is left alone")
    void doesNotScaleDownWhenNoJobIsRunning() throws Exception {
        gridReturns("DIRTY", 850);
        when(flink.getRunningJobId(REST)).thenReturn(Optional.empty());

        SolsticeResource resource = resource(directModeSpec());
        reconciler.reconcile(resource, null);

        verify(flink, never()).scaleJobManager(anyString(), anyString(), anyInt());
        assertEquals("SAVEPOINT_UNAVAILABLE", resource.getStatus().getLastAction());
        assertEquals("SKIPPED", resource.getStatus().getSavepointPhase());
        assertNotNull(resource.getStatus().getLastError());
    }

    @Test
    @DisplayName("without a REST endpoint there is no way to take a savepoint, so nothing is suspended")
    void doesNotScaleDownWithoutRestEndpoint() {
        gridReturns("DIRTY", 850);
        SolsticeSpec spec = directModeSpec();
        spec.setFlinkRestEndpoint(null);

        SolsticeResource resource = resource(spec);
        reconciler.reconcile(resource, null);

        verify(flink, never()).scaleJobManager(anyString(), anyString(), anyInt());
        assertEquals("SAVEPOINT_UNAVAILABLE", resource.getStatus().getLastAction());
        assertNotNull(resource.getStatus().getLastError());
    }

    @Test
    void scalesUpOnACleanGrid() {
        gridReturns("GREEN", 120);

        SolsticeResource resource = resource(directModeSpec());
        reconciler.reconcile(resource, null);

        verify(flink).scaleJobManager(NS, JOB, 1);
        assertEquals("SCALE_UP", resource.getStatus().getLastAction());
    }

    @Test
    @DisplayName("an unreachable telemetry service neither suspends nor resumes")
    void holdsWhenTheGridIsUnknown() {
        gridReturns("UNKNOWN", 0);

        SolsticeResource resource = resource(directModeSpec());
        reconciler.reconcile(resource, null);

        verify(flink, never()).scaleJobManager(anyString(), anyString(), anyInt());
        assertEquals("HOLD", resource.getStatus().getLastAction());
    }

    @Test
    void skipsReconcileWhenTelemetryEndpointIsMissing() {
        SolsticeSpec spec = directModeSpec();
        spec.setTelemetryEndpoint(null);

        SolsticeResource resource = resource(spec);
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

        SolsticeReconciler holding = new SolsticeReconciler(
                telemetry, new ForecastService(), flinkDeployment,
                new PlanExecutor(flink, flinkDeployment),
                new LegacySavepointSuspender(flink),
                alwaysHold);

        SolsticeResource resource = resource(cooperativeSpec());
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

        SolsticeResource resource = resource(cooperativeSpec());
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

            SolsticeResource resource = resource(cooperativeSpec());
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
                    .thenReturn(Optional.of("s3://solstice/savepoints/savepoint-xyz"));
            when(flinkDeployment.resume(eq(NS), eq(JOB), anyString())).thenReturn(true);

            SolsticeResource resource = resource(cooperativeSpec());
            reconciler.reconcile(resource, null);

            verify(flinkDeployment).resume(NS, JOB, "s3://solstice/savepoints/savepoint-xyz");
            assertEquals("RESUME_FROM_SAVEPOINT", resource.getStatus().getLastAction());
            assertEquals("RESTORED", resource.getStatus().getSavepointPhase());
        }


        @Test
        @DisplayName("a Deployment left at zero by the old scaler is scaled back up")
        void scalesUpWhenReplicasAreStaleAtZero() {
            gridReturns("GREEN", 120);
            when(flinkDeployment.getJobState(NS, JOB)).thenReturn(Optional.of("running"));
            when(flink.isScaledToZero(NS, JOB)).thenReturn(true);

            SolsticeResource resource = resource(cooperativeSpec());
            reconciler.reconcile(resource, null);

            verify(flink).scaleJobManager(NS, JOB, 1);
            assertEquals("SCALE_UP_STALE_REPLICAS", resource.getStatus().getLastAction());
        }

        @Test
        void leavesAHealthyRunningJobAlone() {
            gridReturns("GREEN", 120);
            when(flinkDeployment.getJobState(NS, JOB)).thenReturn(Optional.of("running"));
            when(flink.isScaledToZero(NS, JOB)).thenReturn(false);

            SolsticeResource resource = resource(cooperativeSpec());
            reconciler.reconcile(resource, null);

            verify(flink, never()).scaleJobManager(anyString(), anyString(), anyInt());
            assertEquals("ALREADY_RUNNING", resource.getStatus().getLastAction());
        }

        @Test
        void doesNotResuspendAnAlreadySuspendedJob() {
            gridReturns("DIRTY", 850);
            when(flinkDeployment.getJobState(NS, JOB)).thenReturn(Optional.of("suspended"));

            SolsticeResource resource = resource(cooperativeSpec());
            reconciler.reconcile(resource, null);

            verify(flinkDeployment, never()).suspend(anyString(), anyString());
            assertEquals("ALREADY_SUSPENDED", resource.getStatus().getLastAction());
        }

        @Test
        @DisplayName("a suspended job stays suspended while the grid is unknown")
        void doesNotResumeOnAnUnknownGrid() {
            gridReturns("UNKNOWN", 0);
            when(flinkDeployment.getJobState(NS, JOB)).thenReturn(Optional.of("suspended"));
            when(flinkDeployment.getLastSavepointPath(NS, JOB))
                    .thenReturn(Optional.of("s3://solstice/savepoints/savepoint-xyz"));

            SolsticeResource resource = resource(cooperativeSpec());
            reconciler.reconcile(resource, null);

            verify(flinkDeployment, never()).resume(anyString(), anyString(), any());
            assertEquals("HOLD", resource.getStatus().getLastAction());
            assertTrue(resource.getStatus().getDecisionReason().contains("UNKNOWN"));
        }

        @Test
        @DisplayName("a high stale reading on an unknown grid does not suspend")
        void doesNotSuspendOnAnUnknownGridEvenAboveThreshold() {
            gridReturns("UNKNOWN", 900);
            when(flinkDeployment.getJobState(NS, JOB)).thenReturn(Optional.of("running"));

            SolsticeResource resource = resource(cooperativeSpec());
            reconciler.reconcile(resource, null);

            verify(flinkDeployment, never()).suspend(anyString(), anyString());
            assertEquals("HOLD", resource.getStatus().getLastAction());
        }

        @Test
        void reportsAFailedSuspendPatch() {
            gridReturns("DIRTY", 850);
            when(flinkDeployment.getJobState(NS, JOB)).thenReturn(Optional.of("running"));
            when(flinkDeployment.suspend(NS, JOB)).thenReturn(false);

            SolsticeResource resource = resource(cooperativeSpec());
            reconciler.reconcile(resource, null);

            assertEquals("SUSPEND_FAILED", resource.getStatus().getLastAction());
            assertNotNull(resource.getStatus().getLastError());
        }
    }

    @Nested
    @DisplayName("carbon is only counted while a job is actually suspended")
    class Metrics {

        private GenericKubernetesResource flinkDeploymentIn(String lifecycleState) {
            GenericKubernetesResource deployment = new GenericKubernetesResource();
            ObjectMeta meta = new ObjectMeta();
            meta.setName(JOB);
            meta.setNamespace(NS);
            deployment.setMetadata(meta);
            deployment.setAdditionalProperty("spec", Map.of("job", Map.of("state", "running")));
            deployment.setAdditionalProperty("status", Map.of("lifecycleState", lifecycleState));
            return deployment;
        }

        private SolsticeResource resourceNamed(String controller) {
            SolsticeResource resource = resource(cooperativeSpec());
            resource.getMetadata().setName(controller);
            return resource;
        }

        private double sample(String metric, String controller, String labelName, String labelValue) {
            for (MetricSnapshot snapshot : PrometheusRegistry.defaultRegistry.scrape()) {
                if (!metric.startsWith(snapshot.getMetadata().getName())) {
                    continue;
                }
                for (DataPointSnapshot point : snapshot.getDataPoints()) {
                    Labels labels = point.getLabels();
                    if (controller.equals(labels.get("controller")) && labelValue.equals(labels.get(labelName))) {
                        if (point instanceof GaugeSnapshot.GaugeDataPointSnapshot gauge) {
                            return gauge.getValue();
                        }
                        if (point instanceof CounterSnapshot.CounterDataPointSnapshot counter) {
                            return counter.getValue();
                        }
                    }
                }
            }
            return 0;
        }

        @Test
        @DisplayName("a dirty grid alone does not count as a suspension")
        void aHeldJobOnADirtyGridIsNotCountedAsSuspended() {
            gridReturns("DIRTY", 850);
            when(flinkDeployment.get(NS, JOB)).thenReturn(Optional.of(flinkDeploymentIn("STABLE")));
            SchedulingPolicy alwaysHold = new SchedulingPolicy() {
                @Override
                public String name() {
                    return "always-hold";
                }

                @Override
                public SuspensionPlan decide(SchedulingContext context) {
                    return SuspensionPlan.hold("window too short");
                }
            };
            reconciler = new SolsticeReconciler(telemetry, new ForecastService(), flinkDeployment,
                    new PlanExecutor(flink, flinkDeployment), new LegacySavepointSuspender(flink),
                    alwaysHold);

            reconciler.reconcile(resourceNamed("metrics-held"), null);

            assertEquals(0.0, sample("solstice_job_suspended", "metrics-held", "job", JOB));
        }

        @Test
        @DisplayName("a job Flink reports as suspended is counted")
        void aSuspendedJobIsCounted() {
            gridReturns("DIRTY", 850);
            when(flinkDeployment.get(NS, JOB)).thenReturn(Optional.of(flinkDeploymentIn("SUSPENDED")));
            when(flinkDeployment.getJobState(NS, JOB)).thenReturn(Optional.of("suspended"));

            reconciler.reconcile(resourceNamed("metrics-suspended"), null);

            assertEquals(1.0, sample("solstice_job_suspended", "metrics-suspended", "job", JOB));
        }

        @Test
        @DisplayName("a savepoint is counted once, not once per reconcile")
        void aSavepointPhaseIsCountedOnce() {
            gridReturns("DIRTY", 850);
            when(flinkDeployment.getJobState(NS, JOB)).thenReturn(Optional.of("running"));
            when(flinkDeployment.suspend(NS, JOB)).thenReturn(true);
            SolsticeResource resource = resourceNamed("metrics-once");

            reconciler.reconcile(resource, null);
            when(flinkDeployment.getJobState(NS, JOB)).thenReturn(Optional.of("suspended"));
            when(flinkDeployment.getError(NS, JOB)).thenReturn(Optional.empty());
            reconciler.reconcile(resource, null);
            reconciler.reconcile(resource, null);

            assertEquals("REQUESTED", resource.getStatus().getSavepointPhase());
            assertEquals(1.0, sample("solstice_savepoint_total", "metrics-once", "result", "REQUESTED"));
        }
    }
}
