package com.solstice.evaluation;

import com.solstice.cost.CostHistory;
import com.solstice.cost.CostObservation;
import com.solstice.cost.CostPredictor;
import com.solstice.cost.JobFeatures;

import java.time.Duration;
import java.util.List;

public class PredictorEvaluator {

    public static final class Accuracy {
        private final String predictorName;
        private final double meanAbsoluteError;
        private final double medianAbsoluteError;
        private final int samples;

        Accuracy(String predictorName, double meanAbsoluteError, double medianAbsoluteError, int samples) {
            this.predictorName = predictorName;
            this.meanAbsoluteError = meanAbsoluteError;
            this.medianAbsoluteError = medianAbsoluteError;
            this.samples = samples;
        }

        public String getPredictorName() {
            return predictorName;
        }

        public double getMeanAbsoluteError() {
            return meanAbsoluteError;
        }

        public double getMedianAbsoluteError() {
            return medianAbsoluteError;
        }

        public int getSamples() {
            return samples;
        }

        public String toRow() {
            return String.format("%-12s %12.1f %14.1f %8d",
                    predictorName, meanAbsoluteError, medianAbsoluteError, samples);
        }

        public static String header() {
            return String.format("%-12s %12s %14s %8s", "predictor", "meanAbsErr", "medianAbsErr", "samples");
        }
    }

    public Accuracy evaluateSavepoint(CostPredictor predictor,
                                      List<CostObservation> heldOut,
                                      String name) {
        double[] errors = new double[heldOut.size()];
        double total = 0;

        for (int i = 0; i < heldOut.size(); i++) {
            CostObservation observation = heldOut.get(i);
            JobFeatures features = observation.toFeatures();
            Duration predicted = predictor.estimateSavepointDuration(features).getValue();
            double error = Math.abs(predicted.toSeconds() - observation.getSavepointSeconds());
            errors[i] = error;
            total += error;
        }

        java.util.Arrays.sort(errors);
        double median = errors.length == 0 ? 0 : errors[errors.length / 2];
        double mean = errors.length == 0 ? 0 : total / errors.length;

        return new Accuracy(name, mean, median, heldOut.size());
    }

    public static CostHistory historyOf(List<CostObservation> observations) {
        return new CostHistory(Math.max(1, observations.size()), observations);
    }
}
