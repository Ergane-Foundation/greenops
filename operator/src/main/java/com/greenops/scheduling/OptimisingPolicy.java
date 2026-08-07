package com.greenops.scheduling;

import com.greenops.cost.CostModel;
import com.greenops.cost.SuspensionCost;
import com.greenops.forecast.CarbonWindow;
import com.greenops.inventory.ManagedJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public class OptimisingPolicy {

    public static final String NAME = "optimising";

    private static final Logger log = LoggerFactory.getLogger(OptimisingPolicy.class);

    private final CostModel costModel;
    private final SchedulingPolicy perJobPolicy;
    private final double nodePowerWatts;
    private final int maxConcurrentSuspensions;

    public OptimisingPolicy(CostModel costModel,
                            SchedulingPolicy perJobPolicy,
                            double nodePowerWatts,
                            int maxConcurrentSuspensions) {
        this.costModel = costModel;
        this.perJobPolicy = perJobPolicy;
        this.nodePowerWatts = nodePowerWatts <= 0 ? 250 : nodePowerWatts;
        this.maxConcurrentSuspensions = maxConcurrentSuspensions;
    }

    public String name() {
        return NAME;
    }

    public List<JobPlan> plan(List<ManagedJob> jobs, SchedulingContext base) {
        FleetPlanner planner = new FleetPlanner(perJobPolicy);
        List<JobPlan> candidates = planner.plan(jobs, base);

        if (maxConcurrentSuspensions <= 0) {
            return candidates;
        }

        List<JobPlan> wantSuspension = candidates.stream()
                .filter(p -> p.getPlan().isSuspend())
                .toList();

        if (wantSuspension.size() <= maxConcurrentSuspensions) {
            return candidates;
        }

        Duration outage = expectedOutage(base);
        List<SuspensionBenefit> ranked = new ArrayList<>();
        for (JobPlan candidate : wantSuspension) {
            ranked.add(benefitOf(candidate.getJob(), base, outage));
        }
        ranked.sort(Comparator.comparingDouble(SuspensionBenefit::score).reversed()
                .thenComparing(SuspensionBenefit::getJobName));

        List<String> allowed = ranked.stream()
                .limit(maxConcurrentSuspensions)
                .map(SuspensionBenefit::getJobName)
                .toList();

        log.info("[GreenOps] {} jobs want suspending but at most {} may go at once, choosing {}",
                wantSuspension.size(), maxConcurrentSuspensions, allowed);

        List<JobPlan> result = new ArrayList<>();
        for (JobPlan candidate : candidates) {
            if (candidate.getPlan().isSuspend() && !allowed.contains(candidate.getJobName())) {
                result.add(new JobPlan(candidate.getJob(), SuspensionPlan.hold(String.format(
                        "deferred, only %d job(s) may be suspended at once",
                        maxConcurrentSuspensions))));
            } else {
                result.add(candidate);
            }
        }
        return result;
    }

    SuspensionBenefit benefitOf(ManagedJob job, SchedulingContext context, Duration outage) {
        if (outage == null || outage.isZero() || outage.isNegative()) {
            return SuspensionBenefit.ineligible(job.getName(), "no window to measure");
        }

        SuspensionCost cost = costModel.costOf(job.getFeatures());
        Duration roundTrip = cost.conservativeTotal();

        double meanIntensity = context.hasForecast()
                ? context.getForecast().meanIntensityBetween(
                        context.getEvaluatedAt(), context.getEvaluatedAt().plus(outage))
                : context.getCarbonIntensity();

        double kwh = (nodePowerWatts / 1000.0) * (outage.toSeconds() / 3600.0);
        double grams = kwh * meanIntensity * Math.max(1, job.getFeatures().getParallelism());

        return SuspensionBenefit.eligible(job.getName(), grams, roundTrip, outage);
    }

    private Duration expectedOutage(SchedulingContext context) {
        if (!context.hasForecast()) {
            return null;
        }
        Instant now = context.getEvaluatedAt();
        Optional<CarbonWindow> window = context.getForecast()
                .currentWindowAbove(context.getCarbonThreshold(), now);
        if (window.isEmpty()) {
            window = context.getForecast().nextWindowAbove(context.getCarbonThreshold(), now);
        }
        return window
                .filter(w -> w.getEnd() != null)
                .map(w -> {
                    Duration remaining = Duration.between(now, w.getEnd());
                    return remaining.isNegative() ? Duration.ZERO : remaining;
                })
                .orElse(null);
    }
}
