package com.solstice.evaluation;

import com.solstice.cost.CostHistory;
import com.solstice.cost.CostObservation;
import com.solstice.cost.JobFeatures;
import com.solstice.cost.ObservedCostPredictor;
import com.solstice.cost.RegressionCostPredictor;
import com.solstice.cost.StaticCostPredictor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PredictorEvaluatorTest {

    private static final String JOB = "orders";
    private static final Instant T0 = Instant.parse("2026-08-14T09:00:00Z");

    private final StaticCostPredictor fallback =
            new StaticCostPredictor(Duration.ofSeconds(60), Duration.ofSeconds(120));
    private final PredictorEvaluator evaluator = new PredictorEvaluator();

    private JobFeatures features(long megabytes) {
        return JobFeatures.builder()
                .jobName(JOB)
                .stateSizeBytes(megabytes * 1024 * 1024)
                .parallelism(2)
                .build();
    }

    private List<CostObservation> varyingStateSize(int count, long seed) {
        Random random = new Random(seed);
        List<CostObservation> observations = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            long megabytes = 10 + random.nextInt(400);
            long seconds = Math.max(1, Math.round(20 + 0.4 * megabytes + random.nextGaussian() * 5));
            observations.add(CostObservation.savepoint(JOB, T0,
                    Duration.ofSeconds(seconds), features(megabytes)));
        }
        return observations;
    }

    private List<CostObservation> stableStateSize(int count, long seed) {
        Random random = new Random(seed);
        List<CostObservation> observations = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            long seconds = Math.max(1, Math.round(45 + random.nextGaussian() * 6));
            observations.add(CostObservation.savepoint(JOB, T0,
                    Duration.ofSeconds(seconds), features(100)));
        }
        return observations;
    }

    private void report(String scenario, PredictorEvaluator.Accuracy... rows) {
        System.out.println("scenario: " + scenario);
        System.out.println(PredictorEvaluator.Accuracy.header());
        for (PredictorEvaluator.Accuracy row : rows) {
            System.out.println(row.toRow());
        }
        System.out.println();
    }

    @Test
    @DisplayName("when duration follows state size the regression beats a median")
    void regressionWinsWhereStateSizeVaries() {
        List<CostObservation> training = varyingStateSize(40, 1);
        List<CostObservation> heldOut = varyingStateSize(20, 2);
        CostHistory history = PredictorEvaluator.historyOf(training);

        PredictorEvaluator.Accuracy observed = evaluator.evaluateSavepoint(
                new ObservedCostPredictor(history, fallback), heldOut, "observed");
        PredictorEvaluator.Accuracy regression = evaluator.evaluateSavepoint(
                new RegressionCostPredictor(history, new ObservedCostPredictor(history, fallback)),
                heldOut, "regression");

        report("state size varies", observed, regression);

        assertTrue(regression.getMeanAbsoluteError() < observed.getMeanAbsoluteError(),
                "regression should beat the median when there is a relationship to learn");
    }

    @Test
    @DisplayName("with a stable state size the median is hard to beat, and that is a real finding")
    void medianHoldsUpWhereThereIsNothingToLearn() {
        List<CostObservation> training = stableStateSize(40, 3);
        List<CostObservation> heldOut = stableStateSize(20, 4);
        CostHistory history = PredictorEvaluator.historyOf(training);

        PredictorEvaluator.Accuracy observed = evaluator.evaluateSavepoint(
                new ObservedCostPredictor(history, fallback), heldOut, "observed");
        PredictorEvaluator.Accuracy regression = evaluator.evaluateSavepoint(
                new RegressionCostPredictor(history, new ObservedCostPredictor(history, fallback)),
                heldOut, "regression");

        report("state size stable", observed, regression);

        assertTrue(Math.abs(regression.getMeanAbsoluteError() - observed.getMeanAbsoluteError()) < 8,
                "with nothing to learn the two should land close together");
    }

    @Test
    @DisplayName("both learned predictors beat a fixed guess")
    void anythingLearnedBeatsAConstant() {
        List<CostObservation> training = varyingStateSize(40, 5);
        List<CostObservation> heldOut = varyingStateSize(20, 6);
        CostHistory history = PredictorEvaluator.historyOf(training);

        PredictorEvaluator.Accuracy staticGuess =
                evaluator.evaluateSavepoint(fallback, heldOut, "static");
        PredictorEvaluator.Accuracy observed = evaluator.evaluateSavepoint(
                new ObservedCostPredictor(history, fallback), heldOut, "observed");
        PredictorEvaluator.Accuracy regression = evaluator.evaluateSavepoint(
                new RegressionCostPredictor(history, new ObservedCostPredictor(history, fallback)),
                heldOut, "regression");

        report("against a fixed guess", staticGuess, observed, regression);

        assertTrue(observed.getMeanAbsoluteError() < staticGuess.getMeanAbsoluteError());
        assertTrue(regression.getMeanAbsoluteError() < staticGuess.getMeanAbsoluteError());
    }
}
