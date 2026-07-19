package com.greenops.controller;

import com.greenops.metrics.GreenOpsMetrics;
import com.greenops.model.GreenOpsResource;
import com.greenops.model.GreenOpsSpec;
import com.greenops.model.GreenOpsStatus;
import com.greenops.scheduling.PlanExecutor;
import com.greenops.scheduling.SchedulingContext;
import com.greenops.scheduling.SchedulingPolicy;
import com.greenops.scheduling.SuspensionPlan;
import com.greenops.scheduling.ThresholdPolicy;
import com.greenops.service.FlinkDeploymentService;
import com.greenops.service.FlinkService;
import com.greenops.service.LegacySavepointSuspender;
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
    private final FlinkDeploymentService flinkDeploymentService;
    private final PlanExecutor planExecutor;
    private final LegacySavepointSuspender legacySuspender;
    private final SchedulingPolicy policy;

    public GreenOpsReconciler(KubernetesClient client) {
        this(new TelemetryService(),
                new FlinkService(client),
                new FlinkDeploymentService(client));
    }

    GreenOpsReconciler(TelemetryService telemetryService,
                       FlinkService flinkService,
                       FlinkDeploymentService flinkDeploymentService) {
        this(telemetryService, flinkDeploymentService,
                new PlanExecutor(flinkService, flinkDeploymentService),
                new LegacySavepointSuspender(flinkService),
                new ThresholdPolicy());
    }

    GreenOpsReconciler(TelemetryService telemetryService,
                       FlinkDeploymentService flinkDeploymentService,
                       PlanExecutor planExecutor,
                       LegacySavepointSuspender legacySuspender,
                       SchedulingPolicy policy) {
        this.telemetryService = telemetryService;
        this.flinkDeploymentService = flinkDeploymentService;
        this.planExecutor = planExecutor;
        this.legacySuspender = legacySuspender;
        this.policy = policy;
    }

    @Override
    public UpdateControl<GreenOpsResource> reconcile(GreenOpsResource resource, Context<GreenOpsResource> context) {
        GreenOpsSpec spec = resource.getSpec();
        GreenOpsStatus status = resource.getStatus() == null ? new GreenOpsStatus() : resource.getStatus();
        status.setLastReconciledAt(Instant.now().toString());

        if (spec == null || spec.getTelemetryEndpoint() == null) {
            log.warn("GreenOpsController {} has no spec.telemetryEndpoint, skipping",
                    resource.getMetadata().getName());
            return UpdateControl.<GreenOpsResource>noUpdate()
                    .rescheduleAfter(Duration.ofSeconds(RECONCILE_INTERVAL_SECONDS));
        }

        String controller = resource.getMetadata().getName();
        TelemetryService.GridStatus gridStatus = telemetryService.getCurrentStatus(spec.getTelemetryEndpoint());
        status.setGridStatus(gridStatus.getStatus());
        status.setCarbonIntensity(gridStatus.getCarbonIntensity());

        SchedulingContext schedulingContext = buildContext(spec, status, gridStatus);
        boolean dirty = schedulingContext.isGridDirty();

        GreenOpsMetrics.recordGrid(controller, gridStatus.getZone(),
                gridStatus.getCarbonIntensity(), spec.getCarbonThreshold(), dirty);

        log.info("[GreenOps] Grid status: {} | Carbon: {} gCO2/kWh | Threshold: {}",
                gridStatus.getStatus(), gridStatus.getCarbonIntensity(), spec.getCarbonThreshold());

        SuspensionPlan plan = policy.decide(schedulingContext);
        log.info("[GreenOps] Policy {} decided {}", policy.name(), plan);
        status.setDecisionReason(plan.getReason());

        if (spec.isCooperativeSuspension()) {
            planExecutor.apply(plan, spec, status);
        } else {
            legacySuspender.apply(plan, spec, status);
        }

        if (dirty) {
            GreenOpsMetrics.accrueSuspension(controller, spec.getFlinkJobName(),
                    gridStatus.getCarbonIntensity(), spec.getNodePowerWatts());
        } else {
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

    private SchedulingContext buildContext(GreenOpsSpec spec,
                                           GreenOpsStatus status,
                                           TelemetryService.GridStatus gridStatus) {
        boolean suspended = false;
        if (spec.isCooperativeSuspension()) {
            Optional<String> jobState = flinkDeploymentService
                    .getJobState(spec.getFlinkNamespace(), spec.getFlinkJobName());
            suspended = jobState.isPresent()
                    && FlinkDeploymentService.STATE_SUSPENDED.equalsIgnoreCase(jobState.get());
        }

        return SchedulingContext.builder()
                .jobName(spec.getFlinkJobName())
                .carbonIntensity(gridStatus.getCarbonIntensity())
                .carbonThreshold(spec.getCarbonThreshold())
                .gridStatus(gridStatus.getStatus())
                .currentlySuspended(suspended)
                .lastSavepointPath(status.getLastSavepointPath())
                .evaluatedAt(Instant.now())
                .build();
    }
}
