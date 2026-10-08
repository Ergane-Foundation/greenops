package com.solstice.scheduling;

import com.solstice.cost.CostModel;
import com.solstice.cost.JobFeatures;
import com.solstice.cost.SuspensionCost;
import com.solstice.cost.SuspensionDecision;
import com.solstice.forecast.CarbonWindow;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

public class ForecastAwarePolicy implements SchedulingPolicy {

    public static final String NAME = "forecast";

    private final CostModel costModel;
    private final SchedulingPolicy fallback;

    public ForecastAwarePolicy(CostModel costModel, SchedulingPolicy fallback) {
        this.costModel = costModel;
        this.fallback = fallback == null ? new ThresholdPolicy() : fallback;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public SuspensionPlan decide(SchedulingContext context) {
        if (!context.hasForecast()) {
            return fallback.decide(context);
        }

        JobFeatures features = JobFeatures.unknown(context.getJobName());
        Instant now = context.getEvaluatedAt();
        int threshold = context.getCarbonThreshold();

        if (context.isGridDirty()) {
            return decideWhileDirty(context, features, now, threshold);
        }
        return decideWhileClean(context, features, now, threshold);
    }

    private SuspensionPlan decideWhileDirty(SchedulingContext context,
                                            JobFeatures features,
                                            Instant now,
                                            int threshold) {
        Optional<CarbonWindow> current = context.getForecast().currentWindowAbove(threshold, now);

        if (current.isEmpty()) {
            return fallback.decide(context);
        }

        CarbonWindow window = current.get();
        Duration remaining = remainingOf(window, now);

        if (context.isCurrentlySuspended()) {
            return SuspensionPlan.suspend(String.format(
                    "still inside a dirty window with %s left", humanise(remaining)));
        }

        SuspensionDecision decision = costModel.evaluate(features, remaining);
        if (decision.isWorthIt()) {
            return SuspensionPlan.suspend(decision.getReason());
        }
        return SuspensionPlan.hold(decision.getReason());
    }

    private SuspensionPlan decideWhileClean(SchedulingContext context,
                                            JobFeatures features,
                                            Instant now,
                                            int threshold) {
        Optional<CarbonWindow> next = context.getForecast().nextWindowAbove(threshold, now);

        if (next.isPresent()) {
            CarbonWindow window = next.get();
            Duration until = window.startsIn(now);
            Duration length = window.getEnd() == null ? null : window.getDuration();
            SuspensionCost cost = costModel.costOf(features);
            Duration savepoint = cost.getSavepoint().conservative();

            if (!until.isNegative() && until.compareTo(savepoint) <= 0) {
                SuspensionDecision decision = costModel.evaluate(features, length);
                if (decision.isWorthIt()) {
                    return SuspensionPlan.suspend(String.format(
                            "dirty window starts in %s, savepoint needs %s, starting it now",
                            humanise(until), humanise(savepoint)));
                }
            }
        }

        if (context.isCurrentlySuspended()) {
            return SuspensionPlan.resume("grid is clean and the job is suspended");
        }
        return fallback.decide(context);
    }

    private Duration remainingOf(CarbonWindow window, Instant now) {
        if (window.getEnd() == null) {
            return null;
        }
        Duration remaining = Duration.between(now, window.getEnd());
        return remaining.isNegative() ? Duration.ZERO : remaining;
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
