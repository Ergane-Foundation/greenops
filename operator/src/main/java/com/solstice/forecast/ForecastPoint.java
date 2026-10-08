package com.solstice.forecast;

import java.time.Instant;

public final class ForecastPoint {

    private final Instant at;
    private final int carbonIntensity;

    public ForecastPoint(Instant at, int carbonIntensity) {
        this.at = at;
        this.carbonIntensity = carbonIntensity;
    }

    public Instant getAt() {
        return at;
    }

    public int getCarbonIntensity() {
        return carbonIntensity;
    }

    @Override
    public String toString() {
        return at + "=" + carbonIntensity;
    }
}
