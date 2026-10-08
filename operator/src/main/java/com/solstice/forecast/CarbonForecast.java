package com.solstice.forecast;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public final class CarbonForecast {

    private static final CarbonForecast EMPTY =
            new CarbonForecast(Collections.emptyList(), "none", null);

    private final List<ForecastPoint> points;
    private final String source;
    private final Instant lastUpdated;

    public CarbonForecast(List<ForecastPoint> points, String source, Instant lastUpdated) {
        List<ForecastPoint> sorted = new ArrayList<>(points);
        sorted.sort(Comparator.comparing(ForecastPoint::getAt));
        this.points = Collections.unmodifiableList(sorted);
        this.source = source;
        this.lastUpdated = lastUpdated;
    }

    public static CarbonForecast empty() {
        return EMPTY;
    }

    public boolean isEmpty() {
        return points.isEmpty();
    }

    public List<ForecastPoint> getPoints() {
        return points;
    }

    public String getSource() {
        return source;
    }

    public Instant getLastUpdated() {
        return lastUpdated;
    }

    public Duration getHorizon() {
        if (points.size() < 2) {
            return Duration.ZERO;
        }
        return Duration.between(points.get(0).getAt(), points.get(points.size() - 1).getAt());
    }

    public Optional<Integer> intensityAt(Instant moment) {
        ForecastPoint best = null;
        for (ForecastPoint point : points) {
            if (!point.getAt().isAfter(moment)) {
                best = point;
            } else {
                break;
            }
        }
        if (best == null && !points.isEmpty() && points.get(0).getAt().isAfter(moment)) {
            best = points.get(0);
        }
        return Optional.ofNullable(best).map(ForecastPoint::getCarbonIntensity);
    }

    public Optional<CarbonWindow> nextWindowAbove(int threshold, Instant from) {
        Instant start = null;
        int peak = 0;
        long sum = 0;
        int count = 0;

        for (ForecastPoint point : points) {
            if (point.getAt().isBefore(from)) {
                continue;
            }
            boolean above = point.getCarbonIntensity() > threshold;

            if (above && start == null) {
                start = point.getAt();
                peak = point.getCarbonIntensity();
                sum = point.getCarbonIntensity();
                count = 1;
            } else if (above) {
                peak = Math.max(peak, point.getCarbonIntensity());
                sum += point.getCarbonIntensity();
                count++;
            } else if (start != null) {
                return Optional.of(new CarbonWindow(start, point.getAt(), peak, (double) sum / count));
            }
        }

        if (start != null) {
            Instant end = points.isEmpty() ? null : points.get(points.size() - 1).getAt();
            return Optional.of(new CarbonWindow(start, end, peak, count == 0 ? 0 : (double) sum / count));
        }
        return Optional.empty();
    }

    public Optional<CarbonWindow> currentWindowAbove(int threshold, Instant now) {
        Optional<Integer> currentIntensity = intensityAt(now);
        if (currentIntensity.isEmpty() || currentIntensity.get() <= threshold) {
            return Optional.empty();
        }
        return nextWindowAbove(threshold, windowStartAtOrBefore(threshold, now));
    }

    private Instant windowStartAtOrBefore(int threshold, Instant moment) {
        Instant start = moment;
        for (ForecastPoint point : points) {
            if (point.getAt().isAfter(moment)) {
                break;
            }
            if (point.getCarbonIntensity() > threshold) {
                if (start.isAfter(point.getAt())) {
                    start = point.getAt();
                }
            } else {
                start = moment;
            }
        }
        return start;
    }

    public Optional<Instant> nextDropBelow(int threshold, Instant from) {
        for (ForecastPoint point : points) {
            if (point.getAt().isBefore(from)) {
                continue;
            }
            if (point.getCarbonIntensity() <= threshold) {
                return Optional.of(point.getAt());
            }
        }
        return Optional.empty();
    }

    public double meanIntensityBetween(Instant start, Instant end) {
        long sum = 0;
        int count = 0;
        for (ForecastPoint point : points) {
            if (point.getAt().isBefore(start)) {
                continue;
            }
            if (end != null && point.getAt().isAfter(end)) {
                break;
            }
            sum += point.getCarbonIntensity();
            count++;
        }
        return count == 0 ? 0 : (double) sum / count;
    }

}
