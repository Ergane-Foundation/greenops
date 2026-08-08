package com.greenops.evaluation;

import com.greenops.cost.CostModel;
import com.greenops.cost.JobFeatures;
import com.greenops.cost.StaticCostPredictor;
import com.greenops.inventory.ManagedJob;
import com.greenops.scheduling.ForecastAwarePolicy;
import com.greenops.scheduling.ThresholdPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolicyEvaluatorTest {

    private static final Instant START = Instant.parse("2026-08-08T00:00:00Z");
    private static final Duration STEP = Duration.ofMinutes(5);

    private final CostModel costModel = new CostModel(
            new StaticCostPredictor(Duration.ofMinutes(5), Duration.ofMinutes(10)), 2.0);

    private ManagedJob job() {
        return ManagedJob.builder()
                .name("orders")
                .namespace("greenops")
                .features(JobFeatures.builder().jobName("orders").parallelism(2).build())
                .build();
    }

    private PolicyEvaluator evaluatorOver(CarbonTrace trace) {
        return new PolicyEvaluator(trace, 400, 250, 96);
    }

    @Test
    @DisplayName("a diurnal trace produces suspensions from both policies")
    void bothPoliciesActOnADiurnalTrace() {
        CarbonTrace trace = CarbonTrace.diurnal(START, 7, STEP, 42);
        PolicyEvaluator evaluator = evaluatorOver(trace);

        EvaluationResult threshold = evaluator.evaluate(new ThresholdPolicy(), costModel, job());
        EvaluationResult forecast = evaluator.evaluate(
                new ForecastAwarePolicy(costModel, new ThresholdPolicy()), costModel, job());

        assertTrue(threshold.getSuspensions() > 0);
        assertTrue(forecast.getSuspensions() > 0);
        assertTrue(threshold.getGramsAvoided() > 0);
    }

    @Test
    @DisplayName("the forecast policy declines the short dirty windows a threshold would take")
    void forecastPolicyDeclinesShortWindows() {
        CarbonTrace trace = CarbonTrace.flickering(START, 7, STEP, 7);
        PolicyEvaluator evaluator = evaluatorOver(trace);

        EvaluationResult threshold = evaluator.evaluate(new ThresholdPolicy(), costModel, job());
        EvaluationResult forecast = evaluator.evaluate(
                new ForecastAwarePolicy(costModel, new ThresholdPolicy()), costModel, job());

        System.out.println(EvaluationResult.header());
        System.out.println(threshold.toRow());
        System.out.println(forecast.toRow());

        assertTrue(forecast.getSuspensions() < threshold.getSuspensions(),
                "forecast policy should decline windows too short to pay for themselves");
        assertTrue(forecast.getWastedSuspensions() <= threshold.getWastedSuspensions(),
                "forecast policy should waste no more suspensions than a threshold");
    }

    @Test
    @DisplayName("a job with a tight tolerance records breaches under a threshold policy")
    void slaBreachesAreCounted() {
        CarbonTrace trace = CarbonTrace.diurnal(START, 3, STEP, 11);
        ManagedJob impatient = ManagedJob.builder()
                .name("realtime")
                .namespace("greenops")
                .maxSuspension(Duration.ofMinutes(30))
                .features(JobFeatures.unknown("realtime"))
                .build();

        EvaluationResult result = evaluatorOver(trace)
                .evaluate(new ThresholdPolicy(), costModel, impatient);

        assertTrue(result.getSlaBreaches() > 0,
                "a half hour tolerance cannot survive an overnight dirty stretch");
    }

    @Test
    @DisplayName("a trace that never crosses the threshold produces no suspensions at all")
    void aCleanTraceNeverSuspends() {
        CarbonTrace clean = new CarbonTrace(
                CarbonTrace.diurnal(START, 1, STEP, 1).getPoints().stream()
                        .map(p -> new com.greenops.forecast.ForecastPoint(p.getAt(), 100))
                        .toList(),
                STEP);

        EvaluationResult result = evaluatorOver(clean).evaluate(new ThresholdPolicy(), costModel, job());

        assertEquals(0, result.getSuspensions());
        assertEquals(0, result.getGramsAvoided(), 0.001);
    }

    @Test
    @DisplayName("replaying the same trace twice gives the same answer")
    void evaluationIsDeterministic() {
        CarbonTrace trace = CarbonTrace.diurnal(START, 5, STEP, 99);
        PolicyEvaluator evaluator = evaluatorOver(trace);

        EvaluationResult first = evaluator.evaluate(new ThresholdPolicy(), costModel, job());
        EvaluationResult second = evaluator.evaluate(new ThresholdPolicy(), costModel, job());

        assertEquals(first.getGramsAvoided(), second.getGramsAvoided(), 0.001);
        assertEquals(first.getSuspensions(), second.getSuspensions());
    }

    @Test
    @DisplayName("the same seed reproduces the same trace")
    void tracesAreReproducible() {
        CarbonTrace a = CarbonTrace.diurnal(START, 2, STEP, 5);
        CarbonTrace b = CarbonTrace.diurnal(START, 2, STEP, 5);

        assertEquals(a.size(), b.size());
        for (int i = 0; i < a.size(); i++) {
            assertEquals(a.intensityAt(i), b.intensityAt(i));
        }
    }
}
