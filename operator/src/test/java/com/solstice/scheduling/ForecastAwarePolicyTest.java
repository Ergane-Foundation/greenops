package com.solstice.scheduling;

import com.solstice.cost.CostModel;
import com.solstice.cost.StaticCostPredictor;
import com.solstice.forecast.CarbonForecast;
import com.solstice.forecast.ForecastPoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForecastAwarePolicyTest {

    private static final Instant T0 = Instant.parse("2026-07-29T00:00:00Z");
    private static final String JOB = "solstice-flink";

    private final CostModel costModel = new CostModel(
            new StaticCostPredictor(Duration.ofSeconds(60), Duration.ofSeconds(120)), 2.0);
    private final ForecastAwarePolicy policy = new ForecastAwarePolicy(costModel, new ThresholdPolicy());

    private CarbonForecast forecastOf(int... intensities) {
        List<ForecastPoint> points = new ArrayList<>();
        for (int i = 0; i < intensities.length; i++) {
            points.add(new ForecastPoint(T0.plus(Duration.ofMinutes(30L * i)), intensities[i]));
        }
        return new CarbonForecast(points, "test", T0);
    }

    private CarbonForecast fineForecastOf(int minutesPerStep, int... intensities) {
        List<ForecastPoint> points = new ArrayList<>();
        for (int i = 0; i < intensities.length; i++) {
            points.add(new ForecastPoint(T0.plus(Duration.ofMinutes((long) minutesPerStep * i)), intensities[i]));
        }
        return new CarbonForecast(points, "test", T0);
    }

    private SchedulingContext.Builder at(Instant now) {
        return SchedulingContext.builder()
                .jobName(JOB)
                .carbonThreshold(400)
                .evaluatedAt(now);
    }

    @Test
    @DisplayName("a long dirty window is worth suspending for")
    void suspendsForALongWindow() {
        SchedulingContext context = at(T0)
                .gridStatus("DIRTY").carbonIntensity(850)
                .forecast(forecastOf(850, 850, 850, 850, 850, 850, 100))
                .build();

        SuspensionPlan plan = policy.decide(context);

        assertEquals(SuspensionAction.SUSPEND, plan.getAction());
    }

    @Test
    @DisplayName("a dirty window shorter than the round trip is not worth taking")
    void holdsThroughAShortWindow() {
        SchedulingContext context = at(T0.plus(Duration.ofMinutes(25)))
                .gridStatus("DIRTY").carbonIntensity(850)
                .forecast(forecastOf(850, 100, 100, 100))
                .build();

        SuspensionPlan plan = policy.decide(context);

        assertEquals(SuspensionAction.HOLD, plan.getAction());
        assertTrue(plan.getReason().contains("below"));
    }

    @Test
    @DisplayName("this is where the forecast policy and a plain threshold disagree")
    void divergesFromTheThresholdPolicyOnShortWindows() {
        SchedulingContext context = at(T0.plus(Duration.ofMinutes(25)))
                .gridStatus("DIRTY").carbonIntensity(850)
                .forecast(forecastOf(850, 100, 100, 100))
                .build();

        assertEquals(SuspensionAction.SUSPEND, new ThresholdPolicy().decide(context).getAction());
        assertEquals(SuspensionAction.HOLD, policy.decide(context).getAction());
    }

    @Test
    @DisplayName("a job already suspended stays down until the window ends")
    void staysSuspendedInsideAWindow() {
        SchedulingContext context = at(T0.plus(Duration.ofMinutes(30)))
                .gridStatus("DIRTY").carbonIntensity(850)
                .currentlySuspended(true)
                .forecast(forecastOf(850, 850, 850, 850, 100))
                .build();

        SuspensionPlan plan = policy.decide(context);

        assertEquals(SuspensionAction.SUSPEND, plan.getAction());
        assertTrue(plan.getReason().contains("still inside"));
    }

    @Test
    @DisplayName("the savepoint starts before the window so it lands as the peak arrives")
    void suspendsPreemptivelyBeforeAnImminentWindow() {
        SchedulingContext context = at(T0.plus(Duration.ofMinutes(29)))
                .gridStatus("GREEN").carbonIntensity(120)
                .forecast(forecastOf(120, 850, 850, 850, 850, 850, 100))
                .build();

        SuspensionPlan plan = policy.decide(context);

        assertEquals(SuspensionAction.SUSPEND, plan.getAction());
        assertTrue(plan.getReason().contains("starts in"));
    }

    @Test
    @DisplayName("a window far away is not acted on yet")
    void doesNotPreemptADistantWindow() {
        SchedulingContext context = at(T0)
                .gridStatus("GREEN").carbonIntensity(120)
                .forecast(forecastOf(120, 120, 120, 120, 850, 850, 850))
                .build();

        SuspensionPlan plan = policy.decide(context);

        assertEquals(SuspensionAction.RESUME, plan.getAction());
    }

    @Test
    @DisplayName("a short window is not worth pre empting either")
    void doesNotPreemptForAShortWindow() {
        SchedulingContext context = at(T0.plus(Duration.ofSeconds(90)))
                .gridStatus("GREEN").carbonIntensity(120)
                .forecast(fineForecastOf(2, 120, 850, 100, 100))
                .build();

        SuspensionPlan plan = policy.decide(context);

        assertEquals(SuspensionAction.RESUME, plan.getAction());
    }

    @Test
    void resumesWhenTheGridIsCleanAndTheJobIsDown() {
        SchedulingContext context = at(T0)
                .gridStatus("GREEN").carbonIntensity(120)
                .currentlySuspended(true)
                .forecast(forecastOf(120, 120, 120))
                .build();

        SuspensionPlan plan = policy.decide(context);

        assertEquals(SuspensionAction.RESUME, plan.getAction());
    }

    @Test
    @DisplayName("without a forecast it behaves exactly like the threshold policy")
    void fallsBackWithoutAForecast() {
        SchedulingContext dirty = at(T0).gridStatus("DIRTY").carbonIntensity(850).build();
        SchedulingContext clean = at(T0).gridStatus("GREEN").carbonIntensity(120).build();

        assertEquals(SuspensionAction.SUSPEND, policy.decide(dirty).getAction());
        assertEquals(SuspensionAction.RESUME, policy.decide(clean).getAction());
    }

    @Test
    void isNamed() {
        assertEquals("forecast", policy.name());
    }
}
