package com.solstice.cost;

import java.time.Duration;
import java.time.Instant;

public final class CostObservation {

    private String jobName;
    private String observedAt;
    private long savepointSeconds;
    private long restartSeconds;
    private long stateSizeBytes;
    private int parallelism;

    public CostObservation() {
    }

    private CostObservation(String jobName,
                            Instant observedAt,
                            Duration savepoint,
                            Duration restart,
                            long stateSizeBytes,
                            int parallelism) {
        this.jobName = jobName;
        this.observedAt = observedAt == null ? null : observedAt.toString();
        this.savepointSeconds = savepoint == null ? -1 : savepoint.toSeconds();
        this.restartSeconds = restart == null ? -1 : restart.toSeconds();
        this.stateSizeBytes = stateSizeBytes;
        this.parallelism = parallelism;
    }

    public static CostObservation savepoint(String jobName, Instant at, Duration taken, JobFeatures features) {
        return new CostObservation(jobName, at, taken, null,
                features == null ? 0 : features.getStateSizeBytes(),
                features == null ? 1 : features.getParallelism());
    }

    public static CostObservation restart(String jobName, Instant at, Duration taken, JobFeatures features) {
        return new CostObservation(jobName, at, null, taken,
                features == null ? 0 : features.getStateSizeBytes(),
                features == null ? 1 : features.getParallelism());
    }

    public String getJobName() {
        return jobName;
    }

    public void setJobName(String jobName) {
        this.jobName = jobName;
    }

    public String getObservedAt() {
        return observedAt;
    }

    public void setObservedAt(String observedAt) {
        this.observedAt = observedAt;
    }

    public long getSavepointSeconds() {
        return savepointSeconds;
    }

    public void setSavepointSeconds(long savepointSeconds) {
        this.savepointSeconds = savepointSeconds;
    }

    public long getRestartSeconds() {
        return restartSeconds;
    }

    public void setRestartSeconds(long restartSeconds) {
        this.restartSeconds = restartSeconds;
    }

    public long getStateSizeBytes() {
        return stateSizeBytes;
    }

    public void setStateSizeBytes(long stateSizeBytes) {
        this.stateSizeBytes = stateSizeBytes;
    }

    public int getParallelism() {
        return parallelism;
    }

    public void setParallelism(int parallelism) {
        this.parallelism = parallelism;
    }

    public boolean hasSavepoint() {
        return savepointSeconds >= 0;
    }

    public boolean hasRestart() {
        return restartSeconds >= 0;
    }

    public JobFeatures toFeatures() {
        return JobFeatures.builder()
                .jobName(jobName)
                .stateSizeBytes(stateSizeBytes)
                .parallelism(parallelism)
                .build();
    }
}
