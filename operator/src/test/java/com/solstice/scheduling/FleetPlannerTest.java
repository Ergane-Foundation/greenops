package com.solstice.scheduling;

import com.solstice.cost.CostModel;
import com.solstice.cost.StaticCostPredictor;
import com.solstice.forecast.CarbonForecast;
import com.solstice.forecast.ForecastPoint;
import com.solstice.inventory.ManagedJob;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FleetPlannerTest {

    private static final Instant T0 = Instant.parse("2026-08-05T00:00:00Z");

    private final CostModel costModel = new CostModel(
            new StaticCostPredictor(Duration.ofSeconds(60), Duration.ofSeconds(120)), 2.0);

    private CarbonForecast forecastOf(int... intensities) {
        List<ForecastPoint> points = new ArrayList<>();
        for (int i = 0; i < intensities.length; i++) {
            points.add(new ForecastPoint(T0.plus(Duration.ofMinutes(30L * i)), intensities[i]));
        }
        return new CarbonForecast(points, "test", T0);
    }

    private SchedulingContext dirtyContext(CarbonForecast forecast) {
        return SchedulingContext.builder()
                .carbonThreshold(400)
                .carbonIntensity(850)
                .gridStatus("DIRTY")
                .evaluatedAt(T0)
                .forecast(forecast)
                .build();
    }

    private ManagedJob job(String name, int priority, Duration maxSuspension) {
        return ManagedJob.builder()
                .name(name)
                .namespace("solstice")
                .priority(priority)
                .maxSuspension(maxSuspension)
                .build();
    }

    @Test
    @DisplayName("every job gets its own decision")
    void plansEachJobSeparately() {
        FleetPlanner planner = new FleetPlanner(new ThresholdPolicy());
        List<ManagedJob> jobs = List.of(
                job("orders", 10, null),
                job("reports", 80, null));

        List<JobPlan> plans = planner.plan(jobs, dirtyContext(CarbonForecast.empty()));

        assertEquals(2, plans.size());
        assertEquals("orders", plans.get(0).getJobName());
        assertTrue(plans.get(0).getPlan().isSuspend());
        assertTrue(plans.get(1).getPlan().isSuspend());
    }

    @Test
    @DisplayName("a job that cannot tolerate the outage is held back")
    void slaToleranceVetoesASuspension() {
        FleetPlanner planner = new FleetPlanner(new ForecastAwarePolicy(costModel, new ThresholdPolicy()));
        CarbonForecast eightHoursDirty = forecastOf(850, 850, 850, 850, 850, 850, 850, 850, 850, 850, 850, 850, 850, 850, 850, 850, 100);

        List<ManagedJob> jobs = List.of(
                job("batch", 10, Duration.ofHours(12)),
                job("realtime", 20, Duration.ofMinutes(15)));

        List<JobPlan> plans = planner.plan(jobs, dirtyContext(eightHoursDirty));

        assertTrue(plans.get(0).getPlan().isSuspend());
        assertEquals(SuspensionAction.HOLD, plans.get(1).getPlan().getAction());
        assertTrue(plans.get(1).getPlan().getReason().contains("tolerates only"));
    }

    @Test
    @DisplayName("a job with no stated tolerance accepts any outage")
    void noToleranceMeansNoVeto() {
        FleetPlanner planner = new FleetPlanner(new ForecastAwarePolicy(costModel, new ThresholdPolicy()));
        CarbonForecast longDirty = forecastOf(850, 850, 850, 850, 850, 850, 850, 850, 100);

        List<JobPlan> plans = planner.plan(List.of(job("batch", 10, null)), dirtyContext(longDirty));

        assertTrue(plans.get(0).getPlan().isSuspend());
    }

    @Test
    @DisplayName("tolerance only vetoes suspensions, never a resume")
    void toleranceDoesNotBlockResume() {
        FleetPlanner planner = new FleetPlanner(new ThresholdPolicy());
        SchedulingContext clean = SchedulingContext.builder()
                .carbonThreshold(400)
                .carbonIntensity(120)
                .gridStatus("GREEN")
                .evaluatedAt(T0)
                .build();

        List<JobPlan> plans = planner.plan(
                List.of(job("realtime", 20, Duration.ofMinutes(5))), clean);

        assertEquals(SuspensionAction.RESUME, plans.get(0).getPlan().getAction());
    }

    @Test
    @DisplayName("each job is judged on its own suspended state, not the fleet's")
    void perJobStateIsUsed() {
        FleetPlanner planner = new FleetPlanner(new ForecastAwarePolicy(costModel, new ThresholdPolicy()));
        CarbonForecast dirty = forecastOf(850, 850, 850, 850, 850, 100);

        ManagedJob running = ManagedJob.builder().name("a").namespace("solstice").suspended(false).build();
        ManagedJob alreadyDown = ManagedJob.builder().name("b").namespace("solstice").suspended(true).build();

        List<JobPlan> plans = planner.plan(List.of(running, alreadyDown), dirtyContext(dirty));

        assertTrue(plans.get(0).getPlan().isSuspend());
        assertTrue(plans.get(1).getPlan().getReason().contains("still inside"));
    }

    @Test
    void anEmptyFleetPlansNothing() {
        FleetPlanner planner = new FleetPlanner(new ThresholdPolicy());
        assertTrue(planner.plan(List.of(), dirtyContext(CarbonForecast.empty())).isEmpty());
    }
}
