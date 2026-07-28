package com.greenops.cost;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public final class CostHistory {

    public static final int DEFAULT_CAPACITY = 50;

    private final int capacity;
    private final List<CostObservation> observations;

    public CostHistory() {
        this(DEFAULT_CAPACITY, Collections.emptyList());
    }

    public CostHistory(int capacity, List<CostObservation> seed) {
        this.capacity = capacity <= 0 ? DEFAULT_CAPACITY : capacity;
        this.observations = new ArrayList<>(seed == null ? Collections.emptyList() : seed);
        trim();
    }

    public static CostHistory of(List<CostObservation> seed) {
        return new CostHistory(DEFAULT_CAPACITY, seed);
    }

    public CostHistory add(CostObservation observation) {
        if (observation != null) {
            observations.add(observation);
            trim();
        }
        return this;
    }

    private void trim() {
        while (observations.size() > capacity) {
            observations.remove(0);
        }
    }

    public List<CostObservation> all() {
        return Collections.unmodifiableList(observations);
    }

    public List<CostObservation> forJob(String jobName) {
        if (jobName == null) {
            return all();
        }
        return observations.stream()
                .filter(o -> jobName.equals(o.getJobName()))
                .collect(Collectors.toList());
    }

    public List<Long> savepointSeconds(String jobName) {
        return forJob(jobName).stream()
                .filter(CostObservation::hasSavepoint)
                .map(CostObservation::getSavepointSeconds)
                .collect(Collectors.toList());
    }

    public List<Long> restartSeconds(String jobName) {
        return forJob(jobName).stream()
                .filter(CostObservation::hasRestart)
                .map(CostObservation::getRestartSeconds)
                .collect(Collectors.toList());
    }

    public int size() {
        return observations.size();
    }

    public boolean isEmpty() {
        return observations.isEmpty();
    }
}
