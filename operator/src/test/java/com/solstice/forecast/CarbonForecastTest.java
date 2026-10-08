package com.solstice.forecast;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CarbonForecastTest {

    private static final Instant T0 = Instant.parse("2026-07-23T00:00:00Z");

    private CarbonForecast of(int... intensities) {
        List<ForecastPoint> points = new ArrayList<>();
        for (int i = 0; i < intensities.length; i++) {
            points.add(new ForecastPoint(T0.plus(Duration.ofMinutes(30L * i)), intensities[i]));
        }
        return new CarbonForecast(points, "test", T0);
    }

    @Test
    void anEmptyForecastAnswersNothing() {
        CarbonForecast forecast = CarbonForecast.empty();
        assertTrue(forecast.isEmpty());
        assertTrue(forecast.nextWindowAbove(400, T0).isEmpty());
        assertTrue(forecast.intensityAt(T0).isEmpty());
        assertEquals(Duration.ZERO, forecast.getHorizon());
    }

    @Test
    void pointsAreSortedRegardlessOfInputOrder() {
        List<ForecastPoint> unsorted = List.of(
                new ForecastPoint(T0.plus(Duration.ofHours(2)), 300),
                new ForecastPoint(T0, 100),
                new ForecastPoint(T0.plus(Duration.ofHours(1)), 200));
        CarbonForecast forecast = new CarbonForecast(unsorted, "test", T0);
        assertEquals(100, forecast.getPoints().get(0).getCarbonIntensity());
        assertEquals(300, forecast.getPoints().get(2).getCarbonIntensity());
    }

    @Test
    @DisplayName("intensity at a moment uses the last point at or before it")
    void intensityUsesPrecedingPoint() {
        CarbonForecast forecast = of(100, 200, 300);
        assertEquals(100, forecast.intensityAt(T0).orElseThrow());
        assertEquals(100, forecast.intensityAt(T0.plus(Duration.ofMinutes(29))).orElseThrow());
        assertEquals(200, forecast.intensityAt(T0.plus(Duration.ofMinutes(30))).orElseThrow());
        assertEquals(300, forecast.intensityAt(T0.plus(Duration.ofHours(5))).orElseThrow());
    }

    @Test
    void findsAWindowAboveThreshold() {
        CarbonForecast forecast = of(100, 200, 500, 600, 550, 200, 100);
        CarbonWindow window = forecast.nextWindowAbove(400, T0).orElseThrow();

        assertEquals(T0.plus(Duration.ofHours(1)), window.getStart());
        assertEquals(T0.plus(Duration.ofMinutes(150)), window.getEnd());
        assertEquals(Duration.ofMinutes(90), window.getDuration());
        assertEquals(600, window.getPeakIntensity());
    }

    @Test
    @DisplayName("a window still open at the end of the horizon is reported, not discarded")
    void reportsAWindowThatRunsPastTheHorizon() {
        CarbonForecast forecast = of(100, 200, 500, 600);
        CarbonWindow window = forecast.nextWindowAbove(400, T0).orElseThrow();
        assertEquals(T0.plus(Duration.ofHours(1)), window.getStart());
        assertEquals(600, window.getPeakIntensity());
    }

    @Test
    void findsNoWindowWhenNothingCrossesTheThreshold() {
        assertTrue(of(100, 200, 300, 250).nextWindowAbove(400, T0).isEmpty());
    }

    @Test
    @DisplayName("only windows starting at or after the given moment are returned")
    void ignoresWindowsAlreadyPast() {
        CarbonForecast forecast = of(500, 600, 100, 100, 700, 800);
        CarbonWindow window = forecast.nextWindowAbove(400, T0.plus(Duration.ofHours(1))).orElseThrow();
        assertEquals(T0.plus(Duration.ofHours(2)), window.getStart());
        assertEquals(800, window.getPeakIntensity());
    }

    @Test
    void findsWhenTheGridNextDropsBelowThreshold() {
        CarbonForecast forecast = of(500, 600, 550, 200, 100);
        Instant drop = forecast.nextDropBelow(400, T0).orElseThrow();
        assertEquals(T0.plus(Duration.ofMinutes(90)), drop);
    }

    @Test
    void reportsTheCurrentWindowWhenAlreadyDirty() {
        CarbonForecast forecast = of(500, 600, 550, 200);
        Optional<CarbonWindow> window = forecast.currentWindowAbove(400, T0.plus(Duration.ofMinutes(30)));
        assertTrue(window.isPresent());
        assertEquals(Duration.ofMinutes(90), window.orElseThrow().getDuration());
    }

    @Test
    void reportsNoCurrentWindowWhenClean() {
        CarbonForecast forecast = of(100, 200, 500);
        assertFalse(forecast.currentWindowAbove(400, T0).isPresent());
    }

    @Test
    void averagesIntensityOverARange() {
        CarbonForecast forecast = of(100, 200, 300, 400);
        double mean = forecast.meanIntensityBetween(T0, T0.plus(Duration.ofMinutes(60)));
        assertEquals(200.0, mean, 0.001);
    }

    @Test
    void reportsItsHorizon() {
        assertEquals(Duration.ofHours(2), of(1, 2, 3, 4, 5).getHorizon());
    }
}
