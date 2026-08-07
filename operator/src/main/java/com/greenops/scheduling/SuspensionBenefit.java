package com.greenops.scheduling;

import java.time.Duration;

public final class SuspensionBenefit {

    private final String jobName;
    private final double gramsAvoided;
    private final Duration cost;
    private final Duration outage;
    private final boolean eligible;
    private final String reason;

    private SuspensionBenefit(String jobName,
                              double gramsAvoided,
                              Duration cost,
                              Duration outage,
                              boolean eligible,
                              String reason) {
        this.jobName = jobName;
        this.gramsAvoided = gramsAvoided;
        this.cost = cost;
        this.outage = outage;
        this.eligible = eligible;
        this.reason = reason;
    }

    public static SuspensionBenefit eligible(String jobName,
                                             double gramsAvoided,
                                             Duration cost,
                                             Duration outage) {
        return new SuspensionBenefit(jobName, gramsAvoided, cost, outage, true, null);
    }

    public static SuspensionBenefit ineligible(String jobName, String reason) {
        return new SuspensionBenefit(jobName, 0, Duration.ZERO, Duration.ZERO, false, reason);
    }

    public String getJobName() {
        return jobName;
    }

    public double getGramsAvoided() {
        return gramsAvoided;
    }

    public Duration getCost() {
        return cost;
    }

    public Duration getOutage() {
        return outage;
    }

    public boolean isEligible() {
        return eligible;
    }

    public String getReason() {
        return reason;
    }

    public double score() {
        if (!eligible) {
            return 0;
        }
        long costSeconds = Math.max(1, cost.toSeconds());
        return gramsAvoided / costSeconds;
    }

    @Override
    public String toString() {
        return jobName + " saves " + Math.round(gramsAvoided) + "g for "
                + cost.toSeconds() + "s of disruption";
    }
}
