package com.solstice.evaluation;

import java.time.Duration;

public final class EvaluationResult {

    private final String policyName;
    private final String predictorName;
    private final double gramsAvoided;
    private final Duration suspendedFor;
    private final int suspensions;
    private final int wastedSuspensions;
    private final int slaBreaches;

    public EvaluationResult(String policyName,
                            String predictorName,
                            double gramsAvoided,
                            Duration suspendedFor,
                            int suspensions,
                            int wastedSuspensions,
                            int slaBreaches) {
        this.policyName = policyName;
        this.predictorName = predictorName;
        this.gramsAvoided = gramsAvoided;
        this.suspendedFor = suspendedFor;
        this.suspensions = suspensions;
        this.wastedSuspensions = wastedSuspensions;
        this.slaBreaches = slaBreaches;
    }

    public String getPolicyName() {
        return policyName;
    }

    public String getPredictorName() {
        return predictorName;
    }

    public double getGramsAvoided() {
        return gramsAvoided;
    }

    public Duration getSuspendedFor() {
        return suspendedFor;
    }

    public int getSuspensions() {
        return suspensions;
    }

    public int getWastedSuspensions() {
        return wastedSuspensions;
    }

    public int getSlaBreaches() {
        return slaBreaches;
    }

    public double wastedFraction() {
        return suspensions == 0 ? 0 : (double) wastedSuspensions / suspensions;
    }

    public double gramsPerSuspension() {
        return suspensions == 0 ? 0 : gramsAvoided / suspensions;
    }

    public String toRow() {
        return String.format("%-12s %-10s %10.1f %10d %8d %8d %8d",
                policyName, predictorName, gramsAvoided,
                suspendedFor.toMinutes(), suspensions, wastedSuspensions, slaBreaches);
    }

    public static String header() {
        return String.format("%-12s %-10s %10s %10s %8s %8s %8s",
                "policy", "predictor", "gCO2", "downMin", "susp", "wasted", "sla");
    }
}
