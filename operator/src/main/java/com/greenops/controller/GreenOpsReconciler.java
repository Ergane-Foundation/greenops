package com.greenops.controller;

import com.greenops.model.GreenOpsResource;
import com.greenops.model.GreenOpsSpec;
import com.greenops.model.GreenOpsStatus;
import com.greenops.service.FlinkService;
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
        if (spec == null || spec.getTelemetryEndpoint() == null) {
            log.warn("GreenOpsController {} has no spec.telemetryEndpoint — skipping", resource.getMetadata().getName());
            return UpdateControl.<GreenOpsResource>noUpdate()
                    .rescheduleAfter(Duration.ofSeconds(RECONCILE_INTERVAL_SECONDS));
        }

        TelemetryService.GridStatus gridStatus = telemetryService.getCurrentStatus(spec.getTelemetryEndpoint());

        log.info("[GreenOps] Grid status: {} | Carbon: {} gCO2/kWh | Threshold: {}",
                gridStatus.getStatus(), gridStatus.getCarbonIntensity(), spec.getCarbonThreshold());

        String action;
        if (gridStatus.isDirty(spec.getCarbonThreshold())) {
            log.info("[GreenOps] DIRTY GRID — scaling {} taskmanager to 0", spec.getFlinkJobName());
            flinkService.scaleTaskManager(spec.getFlinkNamespace(), spec.getFlinkJobName(), 0);
            action = "SCALE_DOWN";
        } else {
            log.info("[GreenOps] CLEAN GRID — scaling {} taskmanager to 1", spec.getFlinkJobName());
            flinkService.scaleTaskManager(spec.getFlinkNamespace(), spec.getFlinkJobName(), 1);
            action = "SCALE_UP";
        }

        GreenOpsStatus status = resource.getStatus() == null ? new GreenOpsStatus() : resource.getStatus();
        status.setGridStatus(gridStatus.getStatus());
        status.setCarbonIntensity(gridStatus.getCarbonIntensity());
        status.setLastAction(action);
        status.setLastReconciledAt(Instant.now().toString());
        resource.setStatus(status);

        return UpdateControl.patchStatus(resource)
                .rescheduleAfter(Duration.ofSeconds(RECONCILE_INTERVAL_SECONDS));
    }
}
