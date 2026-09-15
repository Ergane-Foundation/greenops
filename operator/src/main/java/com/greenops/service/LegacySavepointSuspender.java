package com.greenops.service;

import com.greenops.model.GreenOpsSpec;
import com.greenops.model.GreenOpsStatus;
import com.greenops.scheduling.SuspensionPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Optional;

public class LegacySavepointSuspender {

    private static final Logger log = LoggerFactory.getLogger(LegacySavepointSuspender.class);

    private final FlinkService flinkService;

    public LegacySavepointSuspender(FlinkService flinkService) {
        this.flinkService = flinkService;
    }

    public void apply(SuspensionPlan plan, GreenOpsSpec spec, GreenOpsStatus status) {
        switch (plan.getAction()) {
            case SUSPEND -> suspend(spec, status);
            case RESUME -> resume(spec, status);
            case HOLD -> {
                log.info("[GreenOps] Holding: {}", plan.getReason());
                status.setLastAction("HOLD");
            }
        }
    }

    private void resume(GreenOpsSpec spec, GreenOpsStatus status) {
        log.info("[GreenOps] CLEAN GRID, scaling JobManager to 1");
        flinkService.scaleJobManager(spec.getFlinkNamespace(), spec.getFlinkJobName(), 1);
        status.setLastAction("SCALE_UP");
    }

    private void suspend(GreenOpsSpec spec, GreenOpsStatus status) {
        log.info("[GreenOps] DIRTY GRID, beginning direct suspension lifecycle");

        if (spec.getFlinkRestEndpoint() == null || spec.getFlinkRestEndpoint().isBlank()) {
            refuseWithoutSavepoint(status, "flinkRestEndpoint is not configured, so no savepoint can be taken");
            return;
        }

        try {
            Optional<String> jobId = flinkService.getRunningJobId(spec.getFlinkRestEndpoint());
            if (jobId.isEmpty()) {
                refuseWithoutSavepoint(status, "no RUNNING Flink job to take a savepoint from");
                return;
            }

            log.info("[GreenOps] Triggering savepoint for job {} to {}",
                    jobId.get(), spec.getSavepointDirectory());
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

    private void refuseWithoutSavepoint(GreenOpsStatus status, String reason) {
        log.error("[GreenOps] Not scaling down: {}", reason);
        status.setLastAction("SAVEPOINT_UNAVAILABLE");
        status.setSavepointPhase("SKIPPED");
        status.setLastError(reason);
    }
}
