package com.greenops.scheduling;

import java.time.Instant;

public final class SchedulingContext {

    private final String jobName;
    private final int carbonIntensity;
    private final int carbonThreshold;
    private final String gridStatus;
    private final boolean currentlySuspended;
    private final String lastSavepointPath;
    private final Instant evaluatedAt;

    private SchedulingContext(Builder builder) {
        this.jobName = builder.jobName;
        this.carbonIntensity = builder.carbonIntensity;
        this.carbonThreshold = builder.carbonThreshold;
        this.gridStatus = builder.gridStatus;
        this.currentlySuspended = builder.currentlySuspended;
        this.lastSavepointPath = builder.lastSavepointPath;
        this.evaluatedAt = builder.evaluatedAt == null ? Instant.now() : builder.evaluatedAt;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getJobName() {
        return jobName;
    }

    public int getCarbonIntensity() {
        return carbonIntensity;
    }

    public int getCarbonThreshold() {
        return carbonThreshold;
    }

    public String getGridStatus() {
        return gridStatus;
    }

    public boolean isCurrentlySuspended() {
        return currentlySuspended;
    }

    public String getLastSavepointPath() {
        return lastSavepointPath;
    }

    public Instant getEvaluatedAt() {
        return evaluatedAt;
    }

    public boolean isGridDirty() {
        if ("DIRTY".equalsIgnoreCase(gridStatus)) {
            return true;
        }
        if ("GREEN".equalsIgnoreCase(gridStatus)) {
            return false;
        }
        return carbonIntensity > carbonThreshold;
    }

    public static final class Builder {
        private String jobName;
        private int carbonIntensity;
        private int carbonThreshold = 400;
        private String gridStatus = "UNKNOWN";
        private boolean currentlySuspended;
        private String lastSavepointPath;
        private Instant evaluatedAt;

        public Builder jobName(String jobName) {
            this.jobName = jobName;
            return this;
        }

        public Builder carbonIntensity(int carbonIntensity) {
            this.carbonIntensity = carbonIntensity;
            return this;
        }

        public Builder carbonThreshold(int carbonThreshold) {
            this.carbonThreshold = carbonThreshold;
            return this;
        }

        public Builder gridStatus(String gridStatus) {
            this.gridStatus = gridStatus;
            return this;
        }

        public Builder currentlySuspended(boolean currentlySuspended) {
            this.currentlySuspended = currentlySuspended;
            return this;
        }

        public Builder lastSavepointPath(String lastSavepointPath) {
            this.lastSavepointPath = lastSavepointPath;
            return this;
        }

        public Builder evaluatedAt(Instant evaluatedAt) {
            this.evaluatedAt = evaluatedAt;
            return this;
        }

        public SchedulingContext build() {
            return new SchedulingContext(this);
        }
    }
}
