package com.solstice.evaluation;

import com.solstice.cost.CostModel;
import com.solstice.cost.SuspensionCost;
import com.solstice.inventory.ManagedJob;
import com.solstice.scheduling.SchedulingContext;
import com.solstice.scheduling.SchedulingPolicy;
import com.solstice.scheduling.SuspensionPlan;

import java.time.Duration;
import java.time.Instant;

public class PolicyEvaluator {

    private final CarbonTrace trace;
    private final int threshold;
    private final double nodePowerWatts;
    private final int horizonSteps;

    public PolicyEvaluator(CarbonTrace trace, int threshold, double nodePowerWatts, int horizonSteps) {
        this.trace = trace;
        this.threshold = threshold;
        this.nodePowerWatts = nodePowerWatts;
        this.horizonSteps = horizonSteps;
    }

    public EvaluationResult evaluate(SchedulingPolicy policy, CostModel costModel, ManagedJob job) {
        boolean suspended = false;
        int suspensions = 0;
        int wasted = 0;
        int slaBreaches = 0;
        double gramsAvoided = 0;
        long suspendedSteps = 0;
        long stepsInCurrentSuspension = 0;

        Duration step = trace.getResolution();
        double hoursPerStep = step.toMinutes() / 60.0;
        SuspensionCost cost = costModel.costOf(job.getFeatures());
        long breakEvenSteps = Math.max(1, cost.conservativeTotal().toMinutes() / step.toMinutes());

        for (int i = 0; i < trace.size(); i++) {
            int intensity = trace.intensityAt(i);
            Instant now = trace.getPoints().get(i).getAt();

            SchedulingContext context = SchedulingContext.builder()
                    .jobName(job.getName())
                    .carbonIntensity(intensity)
                    .carbonThreshold(threshold)
                    .gridStatus(intensity > threshold ? "DIRTY" : "GREEN")
                    .currentlySuspended(suspended)
                    .evaluatedAt(now)
                    .forecast(trace.forecastFrom(i, horizonSteps))
                    .build();

            SuspensionPlan plan = policy.decide(context);

            if (plan.isSuspend()) {
                if (!suspended) {
                    suspended = true;
                    suspensions++;
                    stepsInCurrentSuspension = 0;
                }
                suspendedSteps++;
                stepsInCurrentSuspension++;
                gramsAvoided += (nodePowerWatts / 1000.0) * hoursPerStep * intensity;

                if (job.hasSuspensionLimit()
                        && step.multipliedBy(stepsInCurrentSuspension).compareTo(job.getMaxSuspension()) > 0) {
                    slaBreaches++;
                }
            } else if (plan.isResume()) {
                if (suspended && stepsInCurrentSuspension < breakEvenSteps) {
                    wasted++;
                }
                suspended = false;
                stepsInCurrentSuspension = 0;
            }
        }

        if (suspended && stepsInCurrentSuspension < breakEvenSteps) {
            wasted++;
        }

        return new EvaluationResult(
                policy.name(),
                costModel.getPredictor().name(),
                gramsAvoided,
                step.multipliedBy(suspendedSteps),
                suspensions,
                wasted,
                slaBreaches);
    }
}
