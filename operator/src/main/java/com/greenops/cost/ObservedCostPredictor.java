package com.greenops.cost;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ObservedCostPredictor implements CostPredictor {

    public static final String NAME = "observed";
    private static final int MINIMUM_SAMPLES = 3;

    private final CostHistory history;
    private final CostPredictor fallback;

    public ObservedCostPredictor(CostHistory history, CostPredictor fallback) {
        this.history = history == null ? new CostHistory() : history;
        this.fallback = fallback;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public CostEstimate estimateSavepointDuration(JobFeatures features) {
        return estimate(history.savepointSeconds(features.getJobName()),
                () -> fallback.estimateSavepointDuration(features));
    }

    @Override
    public CostEstimate estimateRestartDuration(JobFeatures features) {
        return estimate(history.restartSeconds(features.getJobName()),
                () -> fallback.estimateRestartDuration(features));
    }

    private CostEstimate estimate(List<Long> samples, java.util.function.Supplier<CostEstimate> fallbackEstimate) {
        if (samples.size() < MINIMUM_SAMPLES) {
            return fallbackEstimate.get();
        }

        List<Long> sorted = new ArrayList<>(samples);
        Collections.sort(sorted);

        long median = percentile(sorted, 0.5);
        long low = percentile(sorted, 0.1);
        long high = percentile(sorted, 0.9);

        return CostEstimate.of(
                Duration.ofSeconds(median),
                Duration.ofSeconds(low),
                Duration.ofSeconds(high),
                sorted.size(),
                NAME);
    }

    private long percentile(List<Long> sorted, double fraction) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int index = (int) Math.round(fraction * (sorted.size() - 1));
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }
}
