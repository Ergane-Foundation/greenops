package com.solstice.evaluation;

import com.solstice.cost.CostModel;
import com.solstice.cost.CostPredictor;
import com.solstice.cost.JobFeatures;
import com.solstice.cost.StaticCostPredictor;
import com.solstice.inventory.ManagedJob;
import com.solstice.scheduling.ForecastAwarePolicy;
import com.solstice.scheduling.SchedulingPolicy;
import com.solstice.scheduling.ThresholdPolicy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class EvaluationRunner {

    private EvaluationRunner() {
    }

    public static void main(String[] args) {
        int days = intArg(args, "--days", 14);
        int thresholdGco2 = intArg(args, "--threshold", 400);
        long seed = intArg(args, "--seed", 42);
        int stepMinutes = intArg(args, "--step-minutes", 5);
        int savepointMinutes = intArg(args, "--savepoint-minutes", 5);
        int restartMinutes = intArg(args, "--restart-minutes", 10);

        Duration step = Duration.ofMinutes(stepMinutes);
        Instant start = Instant.parse("2026-01-01T00:00:00Z");

        CostPredictor predictor = new StaticCostPredictor(
                Duration.ofMinutes(savepointMinutes), Duration.ofMinutes(restartMinutes));
        CostModel costModel = new CostModel(predictor, 2.0);

        ManagedJob job = ManagedJob.builder()
                .name("orders")
                .namespace("solstice")
                .features(JobFeatures.builder().jobName("orders").parallelism(2).build())
                .build();

        List<SchedulingPolicy> policies = List.of(
                new ThresholdPolicy(),
                new ForecastAwarePolicy(costModel, new ThresholdPolicy()));

        List<TraceCase> traces = List.of(
                new TraceCase("diurnal", CarbonTrace.diurnal(start, days, step, seed)),
                new TraceCase("spiky", CarbonTrace.spiky(start, days, step, seed)),
                new TraceCase("flickering", CarbonTrace.flickering(start, days, step, seed)));

        System.out.printf("Solstice policy comparison over %d days, threshold %d gCO2/kWh%n",
                days, thresholdGco2);
        System.out.printf("savepoint %dm, restart %dm, trace resolution %dm, seed %d%n%n",
                savepointMinutes, restartMinutes, stepMinutes, seed);

        for (TraceCase traceCase : traces) {
            System.out.println("trace: " + traceCase.name);
            System.out.println(EvaluationResult.header());

            PolicyEvaluator evaluator = new PolicyEvaluator(traceCase.trace, thresholdGco2, 250, 288);
            List<EvaluationResult> results = new ArrayList<>();
            for (SchedulingPolicy policy : policies) {
                results.add(evaluator.evaluate(policy, costModel, job));
            }
            results.forEach(r -> System.out.println(r.toRow()));
            System.out.println();
        }
    }

    private static int intArg(String[] args, String flag, int fallback) {
        for (int i = 0; i < args.length - 1; i++) {
            if (flag.equals(args[i])) {
                try {
                    return Integer.parseInt(args[i + 1]);
                } catch (NumberFormatException e) {
                    return fallback;
                }
            }
        }
        return fallback;
    }

    private static final class TraceCase {
        private final String name;
        private final CarbonTrace trace;

        private TraceCase(String name, CarbonTrace trace) {
            this.name = name;
            this.trace = trace;
        }
    }
}
