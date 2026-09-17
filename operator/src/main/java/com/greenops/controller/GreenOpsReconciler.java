package com.greenops.controller;

import com.greenops.forecast.CarbonForecast;
import com.greenops.forecast.CarbonWindow;
import com.greenops.cost.CostHistory;
import com.greenops.cost.CostObservation;
import com.greenops.cost.CostRecorder;
import com.greenops.forecast.ForecastService;
import com.greenops.inventory.JobInventory;
import com.greenops.inventory.ManagedJob;
import com.greenops.model.ManagedJobStatus;
import com.greenops.metrics.GreenOpsMetrics;
import com.greenops.model.GreenOpsResource;
import com.greenops.model.GreenOpsSpec;
import com.greenops.model.GreenOpsStatus;
import com.greenops.scheduling.FleetPlanner;
import com.greenops.scheduling.JobPlan;
import com.greenops.scheduling.OptimisingPolicy;
import com.greenops.scheduling.PlanExecutor;
import com.greenops.scheduling.SchedulingContext;
import com.greenops.scheduling.SchedulingPolicies;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@ControllerConfiguration
public class GreenOpsReconciler implements Reconciler<GreenOpsResource> {

    private static final Logger log = LoggerFactory.getLogger(GreenOpsReconciler.class);
    private static final long RECONCILE_INTERVAL_SECONDS = 60;
    private static final long[] FORECAST_SAMPLE_HOURS = {1, 3, 6, 12, 24};

    private final TelemetryService telemetryService;
    private final ForecastService forecastService;
    private final FlinkDeploymentService flinkDeploymentService;
    private final JobInventory jobInventory;
    private final CostRecorder costRecorder = new CostRecorder();
    private final PlanExecutor planExecutor;
    private final LegacySavepointSuspender legacySuspender;
    private final SchedulingPolicy policyOverride;

    public GreenOpsReconciler(KubernetesClient client) {
        this(new TelemetryService(),
                new FlinkService(client),
                new FlinkDeploymentService(client));
    }

    GreenOpsReconciler(TelemetryService telemetryService,
                       FlinkService flinkService,
                       FlinkDeploymentService flinkDeploymentService) {
        this(telemetryService, new ForecastService(), flinkDeploymentService,
                new PlanExecutor(flinkService, flinkDeploymentService),
                new LegacySavepointSuspender(flinkService),
                null);
    }

    GreenOpsReconciler(TelemetryService telemetryService,
                       ForecastService forecastService,
                       FlinkDeploymentService flinkDeploymentService,
                       PlanExecutor planExecutor,
                       LegacySavepointSuspender legacySuspender,
                       SchedulingPolicy policy) {
        this.telemetryService = telemetryService;
        this.forecastService = forecastService;
        this.flinkDeploymentService = flinkDeploymentService;
        this.jobInventory = new JobInventory(flinkDeploymentService);
        this.planExecutor = planExecutor;
        this.legacySuspender = legacySuspender;
        this.policyOverride = policy;
    }

    @Override
    public UpdateControl<GreenOpsResource> reconcile(GreenOpsResource resource, Context<GreenOpsResource> context) {
        GreenOpsSpec spec = resource.getSpec();
        GreenOpsStatus status = resource.getStatus() == null ? new GreenOpsStatus() : resource.getStatus();
        String phaseBefore = status.getSavepointPhase();
        status.setLastReconciledAt(Instant.now().toString());

        if (spec == null || spec.getTelemetryEndpoint() == null) {
            log.warn("GreenOpsController {} has no spec.telemetryEndpoint, skipping",
                    resource.getMetadata().getName());
            return UpdateControl.<GreenOpsResource>noUpdate()
                    .rescheduleAfter(Duration.ofSeconds(RECONCILE_INTERVAL_SECONDS));
        }

        String controller = controllerName(resource);
        TelemetryService.GridStatus gridStatus = telemetryService.getCurrentStatus(spec.getTelemetryEndpoint());
        status.setGridStatus(gridStatus.getStatus());
        status.setCarbonIntensity(gridStatus.getCarbonIntensity());

        CarbonForecast forecast = forecastService.fetch(spec.getForecastEndpoint());
        recordForecast(controllerName(resource), spec, status, forecast);

        List<ManagedJob> managedJobs = jobInventory.discover(spec);
        recordManagedJobs(status, managedJobs);
        observeCosts(managedJobs, status);

        SchedulingContext schedulingContext = buildContext(spec, status, gridStatus, forecast);
        boolean dirty = schedulingContext.isGridDirty();

        GreenOpsMetrics.recordGrid(controller, gridStatus.getZone(),
                gridStatus.getCarbonIntensity(), spec.getCarbonThreshold(), dirty);

        log.info("[GreenOps] Grid status: {} | Carbon: {} gCO2/kWh | Threshold: {}",
                gridStatus.getStatus(), gridStatus.getCarbonIntensity(), spec.getCarbonThreshold());

        CostHistory costHistory = CostHistory.of(status.getCostHistory());
        SchedulingPolicy policy = policyOverride != null
                ? policyOverride
                : SchedulingPolicies.fromSpec(spec, costHistory);
        status.setActivePolicy(SchedulingPolicies.isOptimising(spec)
                ? OptimisingPolicy.NAME
                : policy.name());

        boolean gridKnown = isKnown(gridStatus);
        SuspensionPlan unknownGrid = SuspensionPlan.hold(String.format(
                "grid status is %s, leaving every job as it is until telemetry recovers",
                gridStatus.getStatus()));
        if (!gridKnown) {
            log.warn("[GreenOps] {}", unknownGrid.getReason());
        }

        if (!spec.isCooperativeSuspension()) {
            SuspensionPlan plan = gridKnown ? policy.decide(schedulingContext) : unknownGrid;
            log.info("[GreenOps] Policy {} decided {}", policy.name(), plan);
            status.setDecisionReason(plan.getReason());
            legacySuspender.apply(plan, spec, status);
        } else {
            List<JobPlan> jobPlans;
            if (!gridKnown) {
                jobPlans = new ArrayList<>();
                for (ManagedJob job : managedJobs) {
                    jobPlans.add(new JobPlan(job, unknownGrid));
                }
            } else if (SchedulingPolicies.isOptimising(spec)) {
                jobPlans = SchedulingPolicies.optimiserFor(spec, costHistory).plan(managedJobs, schedulingContext);
            } else {
                jobPlans = new FleetPlanner(policy).plan(managedJobs, schedulingContext);
            }
            applyFleet(jobPlans, status);
        }

        for (ManagedJob job : managedJobs) {
            if (spec.isCooperativeSuspension() && job.isObservedSuspended()) {
                GreenOpsMetrics.accrueSuspension(controller, job.getName(),
                        gridStatus.getCarbonIntensity(), spec.getNodePowerWatts());
            } else {
                GreenOpsMetrics.markRunning(controller, job.getName(), spec.getNodePowerWatts());
            }
        }

        GreenOpsMetrics.recordAction(controller, status.getLastAction());
        String phase = status.getSavepointPhase();
        if (phase != null && !phase.equals(phaseBefore)) {
            GreenOpsMetrics.recordSavepoint(controller, phase);
        }

        resource.setStatus(status);
        return UpdateControl.patchStatus(resource)
                .rescheduleAfter(Duration.ofSeconds(RECONCILE_INTERVAL_SECONDS));
    }

    private void observeCosts(List<ManagedJob> jobs, GreenOpsStatus status) {
        CostHistory history = CostHistory.of(status.getCostHistory());
        Instant now = Instant.now();
        boolean changed = false;

        for (ManagedJob job : jobs) {
            if (job.isSuspended() && costRecorder.isAwaitingSuspend(job.getName())) {
                Optional<CostObservation> observed =
                        costRecorder.suspendObserved(job.getName(), now, job.getFeatures());
                if (observed.isPresent()) {
                    history.add(observed.get());
                    changed = true;
                    log.info("[GreenOps] {} savepoint took {}s",
                            job.getName(), observed.get().getSavepointSeconds());
                }
            }
            if (!job.isSuspended() && costRecorder.isAwaitingResume(job.getName())) {
                Optional<CostObservation> observed =
                        costRecorder.resumeObserved(job.getName(), now, job.getFeatures());
                if (observed.isPresent()) {
                    history.add(observed.get());
                    changed = true;
                    log.info("[GreenOps] {} restart took {}s",
                            job.getName(), observed.get().getRestartSeconds());
                }
            }
        }

        if (changed || status.getCostHistory() == null) {
            status.setCostHistory(history.all());
        }
    }

    private void applyFleet(List<JobPlan> jobPlans, GreenOpsStatus status) {
        if (jobPlans.isEmpty()) {
            status.setLastAction("NO_JOBS");
            status.setDecisionReason("no FlinkDeployments matched");
            return;
        }

        List<ManagedJobStatus> reported = new ArrayList<>();
        for (JobPlan jobPlan : jobPlans) {
            log.info("[GreenOps] {} decided {}", jobPlan.getJobName(), jobPlan.getPlan());
            boolean wasSuspended = jobPlan.getJob().isSuspended();
            String action = planExecutor.apply(jobPlan.getPlan(), jobPlan.getJob(), status);

            if ("SUSPEND_REQUESTED".equals(action) && !wasSuspended) {
                costRecorder.suspendRequested(jobPlan.getJobName(), Instant.now());
            } else if ("RESUME_FROM_SAVEPOINT".equals(action) && wasSuspended) {
                costRecorder.resumeRequested(jobPlan.getJobName(), Instant.now());
            }

            ManagedJobStatus entry = new ManagedJobStatus();
            entry.setName(jobPlan.getJobName());
            entry.setPriority(jobPlan.getJob().getPriority());
            entry.setState(jobPlan.getPlan().isSuspend() ? "suspended" : "running");
            entry.setLastAction(action);
            entry.setLastSavepointPath(jobPlan.getJob().getLastSavepointPath());
            reported.add(entry);
        }
        status.setJobs(reported);

        JobPlan first = jobPlans.get(0);
        status.setDecisionReason(jobPlans.size() == 1
                ? first.getPlan().getReason()
                : String.format("%d jobs planned, %s: %s",
                        jobPlans.size(), first.getJobName(), first.getPlan().getReason()));
    }

    private void recordManagedJobs(GreenOpsStatus status, List<ManagedJob> jobs) {
        status.setManagedJobCount(jobs.size());
        List<ManagedJobStatus> reported = new ArrayList<>();
        for (ManagedJob job : jobs) {
            ManagedJobStatus entry = new ManagedJobStatus();
            entry.setName(job.getName());
            entry.setState(job.isSuspended() ? "suspended" : "running");
            entry.setPriority(job.getPriority());
            entry.setLastSavepointPath(job.getLastSavepointPath());
            reported.add(entry);
        }
        status.setJobs(reported);
    }

    private static boolean isKnown(TelemetryService.GridStatus gridStatus) {
        return "DIRTY".equalsIgnoreCase(gridStatus.getStatus())
                || "GREEN".equalsIgnoreCase(gridStatus.getStatus());
    }

    private String controllerName(GreenOpsResource resource) {
        return resource.getMetadata().getName();
    }

    private void recordForecast(String controller, GreenOpsSpec spec, GreenOpsStatus status, CarbonForecast forecast) {
        if (forecast.isEmpty()) {
            GreenOpsMetrics.clearForecastWindow(controller);
            status.setForecastSource(null);
            status.setForecastHorizonHours(null);
            status.setNextDirtyWindowStart(null);
            status.setNextDirtyWindowEnd(null);
            status.setNextDirtyWindowPeak(null);
            return;
        }

        status.setForecastSource(forecast.getSource());
        status.setForecastHorizonHours((int) forecast.getHorizon().toHours());

        Instant now = Instant.now();
        for (long hoursAhead : FORECAST_SAMPLE_HOURS) {
            forecast.intensityAt(now.plus(Duration.ofHours(hoursAhead)))
                    .ifPresent(intensity -> GreenOpsMetrics.recordForecastPoint(controller, hoursAhead, intensity));
        }
        Optional<CarbonWindow> window = forecast.currentWindowAbove(spec.getCarbonThreshold(), now);
        if (window.isEmpty()) {
            window = forecast.nextWindowAbove(spec.getCarbonThreshold(), now);
        }

        if (window.isPresent()) {
            CarbonWindow w = window.get();
            status.setNextDirtyWindowStart(w.getStart() == null ? null : w.getStart().toString());
            status.setNextDirtyWindowEnd(w.getEnd() == null ? null : w.getEnd().toString());
            status.setNextDirtyWindowPeak(w.getPeakIntensity());
            double startsIn = Math.max(0, w.startsIn(now).toSeconds());
            double duration = w.getEnd() == null ? 0 : w.getDuration().toSeconds();
            GreenOpsMetrics.recordForecastWindow(controller, startsIn, duration, w.getPeakIntensity());
            log.info("[GreenOps] Forecast {} over {}h, next dirty window {} to {} peaking at {}",
                    forecast.getSource(), forecast.getHorizon().toHours(),
                    w.getStart(), w.getEnd(), w.getPeakIntensity());
        } else {
            status.setNextDirtyWindowStart(null);
            status.setNextDirtyWindowEnd(null);
            status.setNextDirtyWindowPeak(null);
            GreenOpsMetrics.clearForecastWindow(controller);
            log.info("[GreenOps] Forecast {} over {}h, nothing above threshold ahead",
                    forecast.getSource(), forecast.getHorizon().toHours());
        }
    }

    private SchedulingContext buildContext(GreenOpsSpec spec,
                                           GreenOpsStatus status,
                                           TelemetryService.GridStatus gridStatus,
                                           CarbonForecast forecast) {
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
                .forecast(forecast)
                .build();
    }
}
