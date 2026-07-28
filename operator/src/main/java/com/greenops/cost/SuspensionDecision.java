package com.greenops.cost;

import java.time.Duration;

public final class SuspensionDecision {

    public enum Verdict {
        WORTH_IT,
        WINDOW_TOO_SHORT,
        NO_WINDOW_KNOWN
    }

    private final Verdict verdict;
    private final Duration windowDuration;
    private final Duration breakEven;
    private final SuspensionCost cost;
    private final String reason;

    private SuspensionDecision(Verdict verdict,
                               Duration windowDuration,
                               Duration breakEven,
                               SuspensionCost cost,
                               String reason) {
        this.verdict = verdict;
        this.windowDuration = windowDuration;
        this.breakEven = breakEven;
        this.cost = cost;
        this.reason = reason;
    }

    public static SuspensionDecision worthIt(Duration window, Duration breakEven, SuspensionCost cost) {
        return new SuspensionDecision(Verdict.WORTH_IT, window, breakEven, cost, String.format(
                "window %s covers the %s round trip",
                humanise(window), humanise(cost.conservativeTotal())));
    }

    public static SuspensionDecision windowTooShort(Duration window, Duration breakEven, SuspensionCost cost) {
        return new SuspensionDecision(Verdict.WINDOW_TOO_SHORT, window, breakEven, cost, String.format(
                "window %s below the %s needed to cover a %s round trip",
                humanise(window), humanise(breakEven), humanise(cost.conservativeTotal())));
    }

    public static SuspensionDecision noWindowKnown(SuspensionCost cost) {
        return new SuspensionDecision(Verdict.NO_WINDOW_KNOWN, null, null, cost,
                "no forecast window available, deciding on the current reading alone");
    }

    public Verdict getVerdict() {
        return verdict;
    }

    public Duration getWindowDuration() {
        return windowDuration;
    }

    public Duration getBreakEven() {
        return breakEven;
    }

    public SuspensionCost getCost() {
        return cost;
    }

    public String getReason() {
        return reason;
    }

    public boolean isWorthIt() {
        return verdict == Verdict.WORTH_IT;
    }

    private static String humanise(Duration duration) {
        if (duration == null) {
            return "unknown";
        }
        long seconds = duration.toSeconds();
        if (seconds < 120) {
            return seconds + "s";
        }
        long minutes = seconds / 60;
        if (minutes < 120) {
            return minutes + "m";
        }
        return (minutes / 60) + "h" + (minutes % 60 == 0 ? "" : String.valueOf(minutes % 60) + "m");
    }
}
