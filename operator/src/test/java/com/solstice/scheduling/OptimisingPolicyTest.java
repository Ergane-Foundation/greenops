package com.solstice.scheduling;

import com.solstice.cost.CostModel;
import com.solstice.cost.JobFeatures;
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

class OptimisingPolicyTest {

    private static final Instant T0 = Instant.parse("2026-08-07T00:00:00Z");

    private final CostModel costModel = new CostModel(
            new StaticCostPredictor(Duration.ofSeconds(60), Duration.ofSeconds(120)), 2.0);

    private CarbonForecast dirtyForecast() {
        List<ForecastPoint> points = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            points.add(new ForecastPoint(T0.plus(Duration.ofMinutes(30L * i)), 850));
        }
        points.add(new ForecastPoint(T0.plus(Duration.ofHours(6)), 100));
        return new CarbonForecast(points, "test", T0);
    }

    private SchedulingContext dirtyContext() {
        return SchedulingContext.builder()
                .carbonThreshold(400)
                .carbonIntensity(850)
                .gridStatus("DIRTY")
                .evaluatedAt(T0)
                .forecast(dirtyForecast())
                .build();
    }

    private ManagedJob job(String name, int parallelism) {
        return ManagedJob.builder()
                .name(name)
                .namespace("solstice")
                .features(JobFeatures.builder().jobName(name).parallelism(parallelism).build())
                .build();
    }

    private OptimisingPolicy policyAllowing(int concurrent) {
        return new OptimisingPolicy(costModel,
                new ForecastAwarePolicy(costModel, new ThresholdPolicy()),
                250, concurrent);
    }

    @Test
    @DisplayName("with room for everyone nothing is deferred")
    void suspendsEverythingWhenCapacityAllows() {
        List<JobPlan> plans = policyAllowing(10)
                .plan(List.of(job("a", 1), job("b", 1), job("c", 1)), dirtyContext());

        assertEquals(3, plans.size());
        assertTrue(plans.stream().allMatch(p -> p.getPlan().isSuspend()));
    }

    @Test
    @DisplayName("when only some may go, the ones saving most per unit of disruption win")
    void ranksByCarbonSavedPerUnitOfCost() {
        List<JobPlan> plans = policyAllowing(2)
                .plan(List.of(job("small", 1), job("large", 8), job("medium", 4)), dirtyContext());

        List<String> suspended = plans.stream()
                .filter(p -> p.getPlan().isSuspend())
                .map(JobPlan::getJobName)
                .toList();

        assertEquals(2, suspended.size());
        assertTrue(suspended.contains("large"));
        assertTrue(suspended.contains("medium"));
    }

    @Test
    @DisplayName("a deferred job is held with a reason rather than silently skipped")
    void deferredJobsExplainThemselves() {
        List<JobPlan> plans = policyAllowing(1)
                .plan(List.of(job("small", 1), job("large", 8)), dirtyContext());

        JobPlan deferred = plans.stream()
                .filter(p -> !p.getPlan().isSuspend())
                .findFirst()
                .orElseThrow();

        assertEquals(SuspensionAction.HOLD, deferred.getPlan().getAction());
        assertTrue(deferred.getPlan().getReason().contains("only 1 job"));
    }

    @Test
    @DisplayName("no limit means no ranking is done at all")
    void zeroLimitMeansUnlimited() {
        List<JobPlan> plans = policyAllowing(0)
                .plan(List.of(job("a", 1), job("b", 1), job("c", 1)), dirtyContext());

        assertTrue(plans.stream().allMatch(p -> p.getPlan().isSuspend()));
    }

    @Test
    @DisplayName("a bigger job avoids more carbon over the same window")
    void benefitScalesWithTheJob() {
        OptimisingPolicy policy = policyAllowing(1);
        Duration outage = Duration.ofHours(6);

        SuspensionBenefit small = policy.benefitOf(job("small", 1), dirtyContext(), outage);
        SuspensionBenefit large = policy.benefitOf(job("large", 8), dirtyContext(), outage);

        assertTrue(large.getGramsAvoided() > small.getGramsAvoided());
        assertTrue(large.score() > small.score());
    }

    @Test
    void withoutAWindowThereIsNoBenefitToWeigh() {
        SuspensionBenefit benefit = policyAllowing(1).benefitOf(job("a", 1), dirtyContext(), null);

        assertTrue(!benefit.isEligible());
        assertEquals(0, benefit.score());
    }

    @Test
    @DisplayName("ranking is stable when two jobs score identically")
    void tiesBreakOnName() {
        List<JobPlan> plans = policyAllowing(1)
                .plan(List.of(job("zebra", 4), job("alpha", 4)), dirtyContext());

        String suspended = plans.stream()
                .filter(p -> p.getPlan().isSuspend())
                .map(JobPlan::getJobName)
                .findFirst()
                .orElseThrow();

        assertEquals("alpha", suspended);
    }
}
