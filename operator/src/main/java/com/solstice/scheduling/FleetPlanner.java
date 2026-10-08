package com.solstice.scheduling;

import com.solstice.forecast.CarbonWindow;
import com.solstice.inventory.ManagedJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class FleetPlanner {

    private static final Logger log = LoggerFactory.getLogger(FleetPlanner.class);

    private final SchedulingPolicy policy;

    public FleetPlanner(SchedulingPolicy policy) {
        this.policy = policy;
    }

    public SchedulingPolicy getPolicy() {
        return policy;
    }

    public List<JobPlan> plan(List<ManagedJob> jobs, SchedulingContext base) {
        List<JobPlan> plans = new ArrayList<>();
        for (ManagedJob job : jobs) {
            SchedulingContext context = contextFor(job, base);
            SuspensionPlan decided = policy.decide(context);
            plans.add(new JobPlan(job, applyTolerance(job, decided, context)));
        }
        return plans;
    }

    private SchedulingContext contextFor(ManagedJob job, SchedulingContext base) {
        return SchedulingContext.builder()
                .jobName(job.getName())
                .carbonIntensity(base.getCarbonIntensity())
                .carbonThreshold(base.getCarbonThreshold())
                .gridStatus(base.getGridStatus())
                .currentlySuspended(job.isSuspended())
                .lastSavepointPath(job.getLastSavepointPath())
                .evaluatedAt(base.getEvaluatedAt())
                .forecast(base.getForecast())
                .build();
    }

    private SuspensionPlan applyTolerance(ManagedJob job, SuspensionPlan plan, SchedulingContext context) {
        if (!plan.isSuspend() || !job.hasSuspensionLimit()) {
            return plan;
        }

        Duration expected = expectedSuspension(context);
        if (expected == null) {
            return plan;
        }

        if (!job.toleratesSuspensionOf(expected)) {
            String reason = String.format(
                    "%s would be down for %s but tolerates only %s",
                    job.getName(), humanise(expected), humanise(job.getMaxSuspension()));
            log.info("[Solstice] {}", reason);
            return SuspensionPlan.hold(reason);
        }
        return plan;
    }

    private Duration expectedSuspension(SchedulingContext context) {
        if (!context.hasForecast()) {
            return null;
        }
        Instant now = context.getEvaluatedAt();
        int threshold = context.getCarbonThreshold();

        Optional<CarbonWindow> window = context.getForecast().currentWindowAbove(threshold, now);
        if (window.isEmpty()) {
            window = context.getForecast().nextWindowAbove(threshold, now);
        }
        return window
                .filter(w -> w.getEnd() != null)
                .map(w -> {
                    Duration remaining = Duration.between(now, w.getEnd());
                    return remaining.isNegative() ? Duration.ZERO : remaining;
                })
                .orElse(null);
    }

    private static String humanise(Duration duration) {
        if (duration == null) {
            return "an unknown time";
        }
        long seconds = duration.toSeconds();
        if (seconds < 120) {
            return seconds + "s";
        }
        long minutes = seconds / 60;
        if (minutes < 120) {
            return minutes + "m";
        }
        return (minutes / 60) + "h";
    }
}
