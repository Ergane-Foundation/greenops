package com.greenops.cost;

import java.util.List;
import java.util.Optional;

public final class RidgeRegression {

    private final double[] coefficients;
    private final double residualStandardDeviation;
    private final int sampleCount;

    private RidgeRegression(double[] coefficients, double residualStandardDeviation, int sampleCount) {
        this.coefficients = coefficients;
        this.residualStandardDeviation = residualStandardDeviation;
        this.sampleCount = sampleCount;
    }

    public static Optional<RidgeRegression> fit(List<double[]> features, List<Double> targets, double lambda) {
        if (features == null || targets == null || features.size() != targets.size() || features.isEmpty()) {
            return Optional.empty();
        }

        int rows = features.size();
        int cols = features.get(0).length + 1;

        double[][] design = new double[rows][cols];
        for (int i = 0; i < rows; i++) {
            design[i][0] = 1.0;
            System.arraycopy(features.get(i), 0, design[i], 1, cols - 1);
        }

        double[][] normal = new double[cols][cols];
        double[] rhs = new double[cols];

        for (int a = 0; a < cols; a++) {
            for (int b = 0; b < cols; b++) {
                double sum = 0;
                for (int i = 0; i < rows; i++) {
                    sum += design[i][a] * design[i][b];
                }
                normal[a][b] = sum;
            }
            normal[a][a] += (a == 0 ? 0 : lambda);

            double sum = 0;
            for (int i = 0; i < rows; i++) {
                sum += design[i][a] * targets.get(i);
            }
            rhs[a] = sum;
        }

        Optional<double[]> solved = solve(normal, rhs);
        if (solved.isEmpty()) {
            return Optional.empty();
        }
        double[] coefficients = solved.get();

        double squaredError = 0;
        for (int i = 0; i < rows; i++) {
            double predicted = 0;
            for (int c = 0; c < cols; c++) {
                predicted += coefficients[c] * design[i][c];
            }
            double residual = targets.get(i) - predicted;
            squaredError += residual * residual;
        }
        int degreesOfFreedom = Math.max(1, rows - cols);
        double residualSd = Math.sqrt(squaredError / degreesOfFreedom);

        return Optional.of(new RidgeRegression(coefficients, residualSd, rows));
    }

    private static Optional<double[]> solve(double[][] matrix, double[] rhs) {
        int n = rhs.length;
        double[][] augmented = new double[n][n + 1];
        for (int i = 0; i < n; i++) {
            System.arraycopy(matrix[i], 0, augmented[i], 0, n);
            augmented[i][n] = rhs[i];
        }

        for (int pivot = 0; pivot < n; pivot++) {
            int best = pivot;
            for (int row = pivot + 1; row < n; row++) {
                if (Math.abs(augmented[row][pivot]) > Math.abs(augmented[best][pivot])) {
                    best = row;
                }
            }
            if (Math.abs(augmented[best][pivot]) < 1e-10) {
                return Optional.empty();
            }
            double[] swap = augmented[pivot];
            augmented[pivot] = augmented[best];
            augmented[best] = swap;

            for (int row = pivot + 1; row < n; row++) {
                double factor = augmented[row][pivot] / augmented[pivot][pivot];
                for (int col = pivot; col <= n; col++) {
                    augmented[row][col] -= factor * augmented[pivot][col];
                }
            }
        }

        double[] solution = new double[n];
        for (int row = n - 1; row >= 0; row--) {
            double sum = augmented[row][n];
            for (int col = row + 1; col < n; col++) {
                sum -= augmented[row][col] * solution[col];
            }
            solution[row] = sum / augmented[row][row];
        }
        return Optional.of(solution);
    }

    public double predict(double[] features) {
        double result = coefficients[0];
        for (int i = 0; i < features.length && i + 1 < coefficients.length; i++) {
            result += coefficients[i + 1] * features[i];
        }
        return result;
    }

    public double[] getCoefficients() {
        return coefficients.clone();
    }

    public double getResidualStandardDeviation() {
        return residualStandardDeviation;
    }

    public int getSampleCount() {
        return sampleCount;
    }
}
