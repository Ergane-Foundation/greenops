package com.greenops.cost;

import java.time.Duration;

public final class CostEstimate {

    private final Duration value;
    private final Duration low;
    private final Duration high;
    private final int sampleCount;
    private final String source;

    private CostEstimate(Duration value, Duration low, Duration high, int sampleCount, String source) {
        this.value = value;
        this.low = low;
        this.high = high;
        this.sampleCount = sampleCount;
        this.source = source;
    }

    public static CostEstimate certain(Duration value, String source) {
        return new CostEstimate(value, value, value, 0, source);
    }

    public static CostEstimate of(Duration value, Duration low, Duration high, int sampleCount, String source) {
        return new CostEstimate(value, low, high, sampleCount, source);
    }

    public Duration getValue() {
        return value;
    }

    public Duration getLow() {
        return low;
    }

    public Duration getHigh() {
        return high;
    }

    public int getSampleCount() {
        return sampleCount;
    }

    public String getSource() {
        return source;
    }

    public boolean isConfident() {
        return sampleCount >= 5;
    }

    public Duration conservative() {
        return high == null ? value : high;
    }

    @Override
    public String toString() {
        return value.toSeconds() + "s from " + source
                + (sampleCount > 0 ? " over " + sampleCount + " samples" : "");
    }
}
