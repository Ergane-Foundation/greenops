package com.greenops.scheduling;

import com.greenops.inventory.ManagedJob;
import com.greenops.model.GreenOpsSpec;
import com.greenops.model.GreenOpsStatus;
import com.greenops.service.FlinkDeploymentService;
import com.greenops.service.FlinkService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

public class PlanExecutor {

    private static final Logger log = LoggerFactory.getLogger(PlanExecutor.class);

    private final FlinkService flinkService;
    private final FlinkDeploymentService flinkDeploymentService;

    public PlanExecutor(FlinkService flinkService, FlinkDeploymentService flinkDeploymentService) {
        this.flinkService = flinkService;
        this.flinkDeploymentService = flinkDeploymentService;
    }

    public void apply(SuspensionPlan plan, GreenOpsSpec spec, GreenOpsStatus status) {
        apply(plan, spec.getFlinkNamespace(), spec.getFlinkJobName(), status);
    }

    public String apply(SuspensionPlan plan, ManagedJob job, GreenOpsStatus status) {
        return apply(plan, job.getNamespace(), job.getName(), status);
    }

    private String apply(SuspensionPlan plan, String namespace, String name, GreenOpsStatus status) {
        switch (plan.getAction()) {
            case SUSPEND -> suspend(namespace, name, status);
            case RESUME -> resume(namespace, name, status);
            case HOLD -> hold(plan, status);
        }
        return status.getLastAction();
    }

    private void hold(SuspensionPlan plan, GreenOpsStatus status) {
        log.info("[GreenOps] Holding: {}", plan.getReason());
        status.setLastAction("HOLD");
    }

    private void suspend(String namespace, String name, GreenOpsStatus status) {
        Optional<String> currentState = flinkDeploymentService.getJobState(namespace, name);
        if (currentState.isPresent()
                && FlinkDeploymentService.STATE_SUSPENDED.equalsIgnoreCase(currentState.get())) {
            log.info("[GreenOps] FlinkDeployment {}/{} already suspended", namespace, name);
            status.setLastAction("ALREADY_SUSPENDED");
            return;
        }

        log.info("[GreenOps] Requesting cooperative suspend of {}/{}", namespace, name);
        if (!flinkDeploymentService.suspend(namespace, name)) {
            status.setLastAction("SUSPEND_FAILED");
            status.setLastError("Could not patch FlinkDeployment " + namespace + "/" + name);
            return;
        }

        status.setSavepointPhase("REQUESTED");
        status.setLastAction("SUSPEND_REQUESTED");
        status.setLastError(null);
    }

    private void resume(String namespace, String name, GreenOpsStatus status) {
        Optional<String> currentState = flinkDeploymentService.getJobState(namespace, name);
        if (currentState.isPresent()
                && FlinkDeploymentService.STATE_RUNNING.equalsIgnoreCase(currentState.get())) {
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

        Optional<String> savepoint = flinkDeploymentService.getLastSavepointPath(namespace, name);
        if (savepoint.isEmpty()) {
            log.error("[GreenOps] Not resuming {}/{}: it has no savepoint of its own and would start with empty state",
                    namespace, name);
            status.setLastAction("RESUME_BLOCKED_NO_SAVEPOINT");
            status.setSavepointPhase("MISSING");
            status.setLastError("No savepoint recorded for " + namespace + "/" + name
                    + ", resume it by hand if starting from empty state is acceptable");
            return;
        }

        String savepointPath = savepoint.get();
        log.info("[GreenOps] Resuming {}/{} from savepoint {}", namespace, name, savepointPath);

        if (!flinkDeploymentService.resume(namespace, name, savepointPath)) {
            status.setLastAction("RESUME_FAILED");
            status.setLastError("Could not patch FlinkDeployment " + namespace + "/" + name);
            return;
        }

        status.setLastSavepointPath(savepointPath);
        status.setSavepointPhase("RESTORED");
        status.setLastAction("RESUME_FROM_SAVEPOINT");
        status.setLastError(null);
    }
}
