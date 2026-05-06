package com.greenops.controller;

import com.greenops.model.GreenOpsResource;
import com.greenops.model.GreenOpsSpec;
import com.greenops.model.GreenOpsStatus;
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

    public GreenOpsReconciler(KubernetesClient client) {
        this.telemetryService = new TelemetryService();
        this.flinkService = new FlinkService(client);
    }

    @Override
    public UpdateControl<GreenOpsResource> reconcile(GreenOpsResource resource, Context<GreenOpsResource> context) {
        GreenOpsSpec spec = resource.getSpec();
        GreenOpsStatus status = resource.getStatus() == null ? new GreenOpsStatus() : resource.getStatus();
        status.setLastReconciledAt(Instant.now().toString());

        if (spec == null || spec.getTelemetryEndpoint() == null) {
            log.warn("GreenOpsController {} has no spec.telemetryEndpoint — skipping", resource.getMetadata().getName());
            return UpdateControl.<GreenOpsResource>noUpdate()
                    .rescheduleAfter(Duration.ofSeconds(RECONCILE_INTERVAL_SECONDS));
        }

        TelemetryService.GridStatus gridStatus = telemetryService.getCurrentStatus(spec.getTelemetryEndpoint());
        status.setGridStatus(gridStatus.getStatus());
        status.setCarbonIntensity(gridStatus.getCarbonIntensity());

        log.info("[GreenOps] Grid status: {} | Carbon: {} gCO2/kWh | Threshold: {}",
                gridStatus.getStatus(), gridStatus.getCarbonIntensity(), spec.getCarbonThreshold());

        if (gridStatus.isDirty(spec.getCarbonThreshold())) {
            handleDirtyGrid(spec, status);
        } else {
            handleCleanGrid(spec, status);
        }

        resource.setStatus(status);
        return UpdateControl.patchStatus(resource)
                .rescheduleAfter(Duration.ofSeconds(RECONCILE_INTERVAL_SECONDS));
    }

    private void handleDirtyGrid(GreenOpsSpec spec, GreenOpsStatus status) {
        log.info("[GreenOps] DIRTY GRID — beginning stateful suspension lifecycle");

        if (spec.getFlinkRestEndpoint() == null || spec.getFlinkRestEndpoint().isBlank()) {
            log.warn("[GreenOps] flinkRestEndpoint not configured — falling back to direct scale-down (no savepoint)");
            flinkService.scaleJobManager(spec.getFlinkNamespace(), spec.getFlinkJobName(), 0);
            status.setLastAction("SCALE_DOWN_NO_SAVEPOINT");
            status.setSavepointPhase("SKIPPED");
            return;
        }

        try {
            Optional<String> jobId = flinkService.getRunningJobId(spec.getFlinkRestEndpoint());
            if (jobId.isEmpty()) {
                log.warn("[GreenOps] No RUNNING Flink job — scaling down without savepoint");
                flinkService.scaleJobManager(spec.getFlinkNamespace(), spec.getFlinkJobName(), 0);
                status.setLastAction("SCALE_DOWN_NO_JOB");
                status.setSavepointPhase("SKIPPED");
                return;
            }

            log.info("[GreenOps] Triggering savepoint for job {} -> {}", jobId.get(), spec.getSavepointDirectory());
            status.setSavepointPhase("IN_PROGRESS");
            String triggerId = flinkService.triggerSavepoint(
                    spec.getFlinkRestEndpoint(), jobId.get(), spec.getSavepointDirectory());

            log.info("[GreenOps] Polling savepoint completion (triggerId={}, timeout={}s)",
                    triggerId, spec.getSavepointTimeoutSeconds());
            SavepointResult result = flinkService.pollSavepointCompletion(
                    spec.getFlinkRestEndpoint(), jobId.get(), triggerId, spec.getSavepointTimeoutSeconds());

            if (!result.isSuccess()) {
                log.error("[GreenOps] Savepoint {} — aborting scale-down. {}",
                        result.getPhase(), result.getMessage());
                status.setSavepointPhase(result.getPhase().name());
                status.setLastAction("SAVEPOINT_FAILED");
                status.setLastError(result.getMessage());
                return;
            }

            log.info("[GreenOps] Savepoint COMPLETED at: {} — scaling JobManager to 0", result.getPath());
            status.setSavepointPhase("COMPLETED");
            status.setLastSavepointPath(result.getPath());
            status.setLastSavepointAt(Instant.now().toString());
            status.setLastError(null);

            flinkService.scaleJobManager(spec.getFlinkNamespace(), spec.getFlinkJobName(), 0);
            status.setLastAction("SCALE_DOWN_AFTER_SAVEPOINT");

        } catch (Exception e) {
            log.error("[GreenOps] Exception in dirty grid handler — aborting scale-down", e);
            status.setLastAction("SAVEPOINT_ERROR");
            status.setSavepointPhase("FAILED");
            status.setLastError(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private void handleCleanGrid(GreenOpsSpec spec, GreenOpsStatus status) {
        log.info("[GreenOps] CLEAN GRID — scaling JobManager to 1");
        flinkService.scaleJobManager(spec.getFlinkNamespace(), spec.getFlinkJobName(), 1);
        status.setLastAction("SCALE_UP");
    }
}
