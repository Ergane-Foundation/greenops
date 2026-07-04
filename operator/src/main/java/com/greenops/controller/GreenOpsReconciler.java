package com.greenops.controller;

import com.greenops.model.GreenOpsResource;
import com.greenops.model.GreenOpsSpec;
import com.greenops.metrics.GreenOpsMetrics;
import com.greenops.model.GreenOpsStatus;
import com.greenops.service.FlinkDeploymentService;
import com.greenops.service.FlinkService;
import com.greenops.service.SavepointResult;
import com.greenops.service.TelemetryService;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@ControllerConfiguration
public class GreenOpsReconciler implements Reconciler<GreenOpsResource> {

    private static final Logger log = LoggerFactory.getLogger(GreenOpsReconciler.class);
    private static final long RECONCILE_INTERVAL_SECONDS = 60;

    private final TelemetryService telemetryService;
    private final FlinkService flinkService;
    private final FlinkDeploymentService flinkDeploymentService;

    public GreenOpsReconciler(KubernetesClient client) {
        this(new TelemetryService(), new FlinkService(client), new FlinkDeploymentService(client));
    }

    GreenOpsReconciler(TelemetryService telemetryService,
                       FlinkService flinkService,
                       FlinkDeploymentService flinkDeploymentService) {
        this.telemetryService = telemetryService;
        this.flinkService = flinkService;
        this.flinkDeploymentService = flinkDeploymentService;
    }

    @Override
    public UpdateControl<GreenOpsResource> reconcile(GreenOpsResource resource, Context<GreenOpsResource> context) {
        GreenOpsSpec spec = resource.getSpec();
        GreenOpsStatus status = resource.getStatus() == null ? new GreenOpsStatus() : resource.getStatus();
        status.setLastReconciledAt(Instant.now().toString());

        if (spec == null || spec.getTelemetryEndpoint() == null) {
            log.warn("GreenOpsController {} has no spec.telemetryEndpoint, skipping", resource.getMetadata().getName());
            return UpdateControl.<GreenOpsResource>noUpdate()
                    .rescheduleAfter(Duration.ofSeconds(RECONCILE_INTERVAL_SECONDS));
        }

        String controller = resource.getMetadata().getName();
        TelemetryService.GridStatus gridStatus = telemetryService.getCurrentStatus(spec.getTelemetryEndpoint());
        status.setGridStatus(gridStatus.getStatus());
        status.setCarbonIntensity(gridStatus.getCarbonIntensity());

        boolean dirty = gridStatus.isDirty(spec.getCarbonThreshold());
        GreenOpsMetrics.recordGrid(controller, gridStatus.getZone(),
                gridStatus.getCarbonIntensity(), spec.getCarbonThreshold(), dirty);

        log.info("[GreenOps] Grid status: {} | Carbon: {} gCO2/kWh | Threshold: {}",
                gridStatus.getStatus(), gridStatus.getCarbonIntensity(), spec.getCarbonThreshold());

        if (dirty) {
            handleDirtyGrid(spec, status);
            GreenOpsMetrics.accrueSuspension(controller, spec.getFlinkJobName(),
                    gridStatus.getCarbonIntensity(), spec.getNodePowerWatts());
        } else {
            handleCleanGrid(spec, status);
            GreenOpsMetrics.markRunning(controller, spec.getFlinkJobName(), spec.getNodePowerWatts());
        }

        GreenOpsMetrics.recordAction(controller, status.getLastAction());
        if (status.getSavepointPhase() != null) {
            GreenOpsMetrics.recordSavepoint(controller, status.getSavepointPhase());
        }

        resource.setStatus(status);
        return UpdateControl.patchStatus(resource)
                .rescheduleAfter(Duration.ofSeconds(RECONCILE_INTERVAL_SECONDS));
    }

    private void handleDirtyGrid(GreenOpsSpec spec, GreenOpsStatus status) {
        if (spec.isCooperativeSuspension()) {
            suspendCooperatively(spec, status);
        } else {
            suspendByScaling(spec, status);
        }
    }

    private void suspendCooperatively(GreenOpsSpec spec, GreenOpsStatus status) {
        String namespace = spec.getFlinkNamespace();
        String name = spec.getFlinkJobName();

        Optional<String> currentState = flinkDeploymentService.getJobState(namespace, name);
        if (currentState.isPresent() && FlinkDeploymentService.STATE_SUSPENDED.equalsIgnoreCase(currentState.get())) {
            log.info("[GreenOps] FlinkDeployment {}/{} already suspended", namespace, name);
            status.setLastAction("ALREADY_SUSPENDED");
            return;
        }

        log.info("[GreenOps] DIRTY GRID, requesting cooperative suspend of {}/{}", namespace, name);

        boolean patched = flinkDeploymentService.suspend(namespace, name);
        if (!patched) {
            status.setLastAction("SUSPEND_FAILED");
            status.setLastError("Could not patch FlinkDeployment " + namespace + "/" + name);
            return;
        }

        status.setSavepointPhase("REQUESTED");
        status.setLastAction("SUSPEND_REQUESTED");
        status.setLastError(null);
    }

    private void suspendByScaling(GreenOpsSpec spec, GreenOpsStatus status) {
        log.info("[GreenOps] DIRTY GRID, beginning direct suspension lifecycle");

        if (spec.getFlinkRestEndpoint() == null || spec.getFlinkRestEndpoint().isBlank()) {
            log.warn("[GreenOps] flinkRestEndpoint not configured, falling back to direct scale down without savepoint");
            flinkService.scaleJobManager(spec.getFlinkNamespace(), spec.getFlinkJobName(), 0);
            status.setLastAction("SCALE_DOWN_NO_SAVEPOINT");
            status.setSavepointPhase("SKIPPED");
            return;
        }

        try {
            Optional<String> jobId = flinkService.getRunningJobId(spec.getFlinkRestEndpoint());
            if (jobId.isEmpty()) {
                log.warn("[GreenOps] No RUNNING Flink job, scaling down without savepoint");
                flinkService.scaleJobManager(spec.getFlinkNamespace(), spec.getFlinkJobName(), 0);
                status.setLastAction("SCALE_DOWN_NO_JOB");
                status.setSavepointPhase("SKIPPED");
                return;
            }

            log.info("[GreenOps] Triggering savepoint for job {} to {}", jobId.get(), spec.getSavepointDirectory());
            status.setSavepointPhase("IN_PROGRESS");
            String triggerId = flinkService.triggerSavepoint(
                    spec.getFlinkRestEndpoint(), jobId.get(), spec.getSavepointDirectory());

            log.info("[GreenOps] Polling savepoint completion (triggerId={}, timeout={}s)",
                    triggerId, spec.getSavepointTimeoutSeconds());
            SavepointResult result = flinkService.pollSavepointCompletion(
                    spec.getFlinkRestEndpoint(), jobId.get(), triggerId, spec.getSavepointTimeoutSeconds());

            if (!result.isSuccess()) {
                log.error("[GreenOps] Savepoint {}, aborting scale down. {}",
                        result.getPhase(), result.getMessage());
                status.setSavepointPhase(result.getPhase().name());
                status.setLastAction("SAVEPOINT_FAILED");
                status.setLastError(result.getMessage());
                return;
            }

            log.info("[GreenOps] Savepoint COMPLETED at {}, scaling JobManager to 0", result.getPath());
            status.setSavepointPhase("COMPLETED");
            status.setLastSavepointPath(result.getPath());
            status.setLastSavepointAt(Instant.now().toString());
            status.setLastError(null);

            flinkService.scaleJobManager(spec.getFlinkNamespace(), spec.getFlinkJobName(), 0);
            status.setLastAction("SCALE_DOWN_AFTER_SAVEPOINT");

        } catch (Exception e) {
            log.error("[GreenOps] Exception in dirty grid handler, aborting scale down", e);
            status.setLastAction("SAVEPOINT_ERROR");
            status.setSavepointPhase("FAILED");
            status.setLastError(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private void handleCleanGrid(GreenOpsSpec spec, GreenOpsStatus status) {
        if (spec.isCooperativeSuspension()) {
            resumeCooperatively(spec, status);
        } else {
            log.info("[GreenOps] CLEAN GRID, scaling JobManager to 1");
            flinkService.scaleJobManager(spec.getFlinkNamespace(), spec.getFlinkJobName(), 1);
            status.setLastAction("SCALE_UP");
        }
    }

    private void resumeCooperatively(GreenOpsSpec spec, GreenOpsStatus status) {
        String namespace = spec.getFlinkNamespace();
        String name = spec.getFlinkJobName();

        Optional<String> currentState = flinkDeploymentService.getJobState(namespace, name);
        if (currentState.isPresent() && FlinkDeploymentService.STATE_RUNNING.equalsIgnoreCase(currentState.get())) {
            if (flinkService.isScaledToZero(namespace, name)) {
                log.warn("[GreenOps] FlinkDeployment {}/{} wants to run but its Deployment sits at 0, scaling back up",
                        namespace, name);
                flinkService.scaleJobManager(namespace, name, 1);
                status.setLastAction("SCALE_UP_STALE_REPLICAS");
                return;
            }
            log.info("[GreenOps] FlinkDeployment {}/{} already running", namespace, name);
            status.setLastAction("ALREADY_RUNNING");
            return;
        }

        String savepointPath = flinkDeploymentService
                .getLastSavepointPath(namespace, name)
                .orElse(status.getLastSavepointPath());

        if (savepointPath == null || savepointPath.isBlank()) {
            log.warn("[GreenOps] CLEAN GRID, resuming {}/{} with no known savepoint, job will start from the jar",
                    namespace, name);
        } else {
            log.info("[GreenOps] CLEAN GRID, resuming {}/{} from savepoint {}", namespace, name, savepointPath);
        }

        boolean patched = flinkDeploymentService.resume(namespace, name, savepointPath);
        if (!patched) {
            status.setLastAction("RESUME_FAILED");
            status.setLastError("Could not patch FlinkDeployment " + namespace + "/" + name);
            return;
        }

        if (savepointPath != null && !savepointPath.isBlank()) {
            status.setLastSavepointPath(savepointPath);
            status.setSavepointPhase("RESTORED");
            status.setLastAction("RESUME_FROM_SAVEPOINT");
        } else {
            status.setSavepointPhase("NONE");
            status.setLastAction("RESUME_WITHOUT_SAVEPOINT");
        }
        status.setLastError(null);
    }
}
