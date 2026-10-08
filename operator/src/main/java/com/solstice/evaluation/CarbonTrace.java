package com.solstice.evaluation;

import com.solstice.forecast.CarbonForecast;
import com.solstice.forecast.ForecastPoint;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public final class CarbonTrace {

    private final List<ForecastPoint> points;
    private final Duration resolution;

    public CarbonTrace(List<ForecastPoint> points, Duration resolution) {
        this.points = List.copyOf(points);
        this.resolution = resolution;
    }

    public static CarbonTrace diurnal(Instant start, int days, Duration resolution, long seed) {
        Random random = new Random(seed);
        List<ForecastPoint> points = new ArrayList<>();
        long steps = Duration.ofDays(days).toMinutes() / resolution.toMinutes();

        for (long i = 0; i <= steps; i++) {
            Instant at = start.plus(resolution.multipliedBy(i));
            double hours = (at.toEpochMilli() / 3_600_000.0) % 24;
            double angle = (hours - 19) / 24.0 * 2 * Math.PI;
            double base = 300 + 250 * Math.cos(angle);
            double noise = random.nextGaussian() * 30;
            points.add(new ForecastPoint(at, (int) Math.max(0, Math.round(base + noise))));
        }
        return new CarbonTrace(points, resolution);
    }

    public static CarbonTrace spiky(Instant start, int days, Duration resolution, long seed) {
        Random random = new Random(seed);
        List<ForecastPoint> points = new ArrayList<>();
        long steps = Duration.ofDays(days).toMinutes() / resolution.toMinutes();

        int current = 200;
        for (long i = 0; i <= steps; i++) {
            Instant at = start.plus(resolution.multipliedBy(i));
            if (random.nextDouble() < 0.08) {
                current = random.nextBoolean() ? 700 + random.nextInt(200) : 100 + random.nextInt(120);
            }
            points.add(new ForecastPoint(at, current));
        }
        return new CarbonTrace(points, resolution);
    }

    public static CarbonTrace flickering(Instant start, int days, Duration resolution, long seed) {
        Random random = new Random(seed);
        List<ForecastPoint> points = new ArrayList<>();
        long steps = Duration.ofDays(days).toMinutes() / resolution.toMinutes();

        long i = 0;
        while (i <= steps) {
            boolean dirty = random.nextDouble() < 0.35;
            long runLength = dirty
                    ? 1 + random.nextInt(3)
                    : 6 + random.nextInt(18);
            int intensity = dirty ? 650 + random.nextInt(250) : 120 + random.nextInt(200);

            for (long r = 0; r < runLength && i <= steps; r++, i++) {
                points.add(new ForecastPoint(start.plus(resolution.multipliedBy(i)), intensity));
            }
        }
        return new CarbonTrace(points, resolution);
    }

    public List<ForecastPoint> getPoints() {
        return points;
    }

    public Duration getResolution() {
        return resolution;
    }

    public Instant startsAt() {
        return points.isEmpty() ? null : points.get(0).getAt();
    }

    public int intensityAt(int index) {
        return points.get(Math.max(0, Math.min(points.size() - 1, index))).getCarbonIntensity();
    }

    public int size() {
        return points.size();
    }

    public CarbonForecast forecastFrom(int index, int horizonSteps) {
        int from = Math.max(0, index);
        int to = Math.min(points.size(), from + horizonSteps + 1);
        if (from >= to) {
            return CarbonForecast.empty();
        }
        return new CarbonForecast(points.subList(from, to), "replay", points.get(from).getAt());
    }
}
