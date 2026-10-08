package com.solstice.forecast;

import java.time.Duration;
import java.time.Instant;

public final class CarbonWindow {

    private final Instant start;
    private final Instant end;
    private final int peakIntensity;
    private final double meanIntensity;

    public CarbonWindow(Instant start, Instant end, int peakIntensity, double meanIntensity) {
        this.start = start;
        this.end = end;
        this.peakIntensity = peakIntensity;
        this.meanIntensity = meanIntensity;
    }

    public Instant getStart() {
        return start;
    }

    public Instant getEnd() {
        return end;
    }

    public int getPeakIntensity() {
        return peakIntensity;
    }

    public double getMeanIntensity() {
        return meanIntensity;
    }

    public Duration getDuration() {
        return Duration.between(start, end);
    }

    public boolean isOpenEnded() {
        return end == null;
    }

    public Duration startsIn(Instant from) {
        return Duration.between(from, start);
    }

    @Override
    public String toString() {
        return "window " + start + " to " + end + " peak " + peakIntensity;
    }
}
