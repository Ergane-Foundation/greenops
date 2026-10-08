package com.solstice.scheduling;

import com.solstice.inventory.ManagedJob;
import com.solstice.model.SolsticeSpec;
import com.solstice.model.SolsticeStatus;
import com.solstice.service.FlinkDeploymentService;
import com.solstice.service.FlinkService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Optional;

public class PlanExecutor {

    private static final Logger log = LoggerFactory.getLogger(PlanExecutor.class);

    private final FlinkService flinkService;
    private final FlinkDeploymentService flinkDeploymentService;

    public PlanExecutor(FlinkService flinkService, FlinkDeploymentService flinkDeploymentService) {
        this.flinkService = flinkService;
        this.flinkDeploymentService = flinkDeploymentService;
    }

    public void apply(SuspensionPlan plan, SolsticeSpec spec, SolsticeStatus status) {
        apply(plan, spec.getFlinkNamespace(), spec.getFlinkJobName(), status);
    }

    public String apply(SuspensionPlan plan, ManagedJob job, SolsticeStatus status) {
        return apply(plan, job.getNamespace(), job.getName(), status);
    }

    private String apply(SuspensionPlan plan, String namespace, String name, SolsticeStatus status) {
        switch (plan.getAction()) {
            case SUSPEND -> suspend(namespace, name, status);
            case RESUME -> resume(namespace, name, status);
            case HOLD -> hold(plan, status);
        }
        return status.getLastAction();
    }

    private void hold(SuspensionPlan plan, SolsticeStatus status) {
        log.info("[Solstice] Holding: {}", plan.getReason());
        status.setLastAction("HOLD");
    }

    private void suspend(String namespace, String name, SolsticeStatus status) {
        Optional<String> currentState = flinkDeploymentService.getJobState(namespace, name);
        if (currentState.isPresent()
                && FlinkDeploymentService.STATE_SUSPENDED.equalsIgnoreCase(currentState.get())) {
            log.info("[Solstice] FlinkDeployment {}/{} already suspended", namespace, name);
            status.setLastAction("ALREADY_SUSPENDED");
            if ("REQUESTED".equals(status.getSavepointPhase()) || "FAILED".equals(status.getSavepointPhase())) {
                confirmSavepoint(namespace, name, status);
            }
            return;
        }

        String upgradeMode = flinkDeploymentService.getUpgradeMode(namespace, name).orElse("stateless");
        if (!FlinkDeploymentService.UPGRADE_MODE_SAVEPOINT.equalsIgnoreCase(upgradeMode)) {
            log.error("[Solstice] Not suspending {}/{}: upgradeMode is {}, so Flink would not take a savepoint",
                    namespace, name, upgradeMode);
            status.setLastAction("SUSPEND_BLOCKED_UPGRADE_MODE");
            status.setSavepointPhase("SKIPPED");
            status.setLastError("FlinkDeployment " + namespace + "/" + name + " has upgradeMode " + upgradeMode
                    + ", set it to savepoint for Solstice to suspend it");
            return;
        }

        log.info("[Solstice] Requesting cooperative suspend of {}/{}", namespace, name);
        if (!flinkDeploymentService.suspend(namespace, name)) {
            status.setLastAction("SUSPEND_FAILED");
            status.setLastError("Could not patch FlinkDeployment " + namespace + "/" + name);
            return;
        }

        status.setSavepointPhase("REQUESTED");
        status.setLastAction("SUSPEND_REQUESTED");
        status.setLastError(null);
    }

    private void confirmSavepoint(String namespace, String name, SolsticeStatus status) {
        Optional<String> lifecycle = flinkDeploymentService.getLifecycleState(namespace, name);
        if (lifecycle.isPresent() && FlinkDeploymentService.LIFECYCLE_SUSPENDED.equalsIgnoreCase(lifecycle.get())) {
            Optional<String> savepoint = flinkDeploymentService.getLastSavepointPath(namespace, name);
            log.info("[Solstice] FlinkDeployment {}/{} suspended with savepoint {}",
                    namespace, name, savepoint.orElse("unknown"));
            status.setSavepointPhase("COMPLETED");
            savepoint.ifPresent(status::setLastSavepointPath);
            status.setLastSavepointAt(Instant.now().toString());
            status.setLastError(null);
            return;
        }

        Optional<String> error = flinkDeploymentService.getError(namespace, name);
        if (error.isPresent()) {
            log.error("[Solstice] FlinkDeployment {}/{} has not suspended, Flink reports: {}",
                    namespace, name, error.get());
            status.setSavepointPhase("FAILED");
            status.setLastError("Suspend of " + namespace + "/" + name + " has not completed: " + error.get());
        }
    }

    private void resume(String namespace, String name, SolsticeStatus status) {
        Optional<String> currentState = flinkDeploymentService.getJobState(namespace, name);
        if (currentState.isPresent()
                && FlinkDeploymentService.STATE_RUNNING.equalsIgnoreCase(currentState.get())) {
            if (flinkService.isScaledToZero(namespace, name)) {
                log.warn("[Solstice] FlinkDeployment {}/{} wants to run but its Deployment sits at 0, scaling back up",
                        namespace, name);
                flinkService.scaleJobManager(namespace, name, 1);
                status.setLastAction("SCALE_UP_STALE_REPLICAS");
                return;
            }
            log.info("[Solstice] FlinkDeployment {}/{} already running", namespace, name);
            status.setLastAction("ALREADY_RUNNING");
            return;
        }

        Optional<String> savepoint = flinkDeploymentService.getLastSavepointPath(namespace, name);
        if (savepoint.isEmpty()) {
            log.error("[Solstice] Not resuming {}/{}: it has no savepoint of its own and would start with empty state",
                    namespace, name);
            status.setLastAction("RESUME_BLOCKED_NO_SAVEPOINT");
            status.setSavepointPhase("MISSING");
            status.setLastError("No savepoint recorded for " + namespace + "/" + name
                    + ", resume it by hand if starting from empty state is acceptable");
            return;
        }

        String savepointPath = savepoint.get();
        log.info("[Solstice] Resuming {}/{} from savepoint {}", namespace, name, savepointPath);

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
