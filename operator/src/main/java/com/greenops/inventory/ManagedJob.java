package com.greenops.inventory;

import com.greenops.cost.JobFeatures;

import java.time.Duration;

public final class ManagedJob {

    public static final int DEFAULT_PRIORITY = 50;

    private final String name;
    private final String namespace;
    private final boolean suspended;
    private final String lastSavepointPath;
    private final int priority;
    private final Duration maxSuspension;
    private final JobFeatures features;

    private ManagedJob(Builder builder) {
        this.name = builder.name;
        this.namespace = builder.namespace;
        this.suspended = builder.suspended;
        this.lastSavepointPath = builder.lastSavepointPath;
        this.priority = builder.priority;
        this.maxSuspension = builder.maxSuspension;
        this.features = builder.features == null
                ? JobFeatures.unknown(builder.name)
                : builder.features;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getName() {
        return name;
    }

    public String getNamespace() {
        return namespace;
    }

    public boolean isSuspended() {
        return suspended;
    }

    public String getLastSavepointPath() {
        return lastSavepointPath;
    }

    public int getPriority() {
        return priority;
    }

    public Duration getMaxSuspension() {
        return maxSuspension;
    }

    public JobFeatures getFeatures() {
        return features;
    }

    public boolean hasSuspensionLimit() {
        return maxSuspension != null && !maxSuspension.isZero() && !maxSuspension.isNegative();
    }

    public boolean toleratesSuspensionOf(Duration duration) {
        if (!hasSuspensionLimit() || duration == null) {
            return true;
        }
        return duration.compareTo(maxSuspension) <= 0;
    }

    @Override
    public String toString() {
        return namespace + "/" + name + (suspended ? " suspended" : " running");
    }

    public static final class Builder {
        private String name;
        private String namespace;
        private boolean suspended;
        private String lastSavepointPath;
        private int priority = DEFAULT_PRIORITY;
        private Duration maxSuspension;
        private JobFeatures features;

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder namespace(String namespace) {
            this.namespace = namespace;
            return this;
        }

        public Builder suspended(boolean suspended) {
            this.suspended = suspended;
            return this;
        }

        public Builder lastSavepointPath(String lastSavepointPath) {
            this.lastSavepointPath = lastSavepointPath;
            return this;
        }

        public Builder priority(int priority) {
            this.priority = priority;
            return this;
        }

        public Builder maxSuspension(Duration maxSuspension) {
            this.maxSuspension = maxSuspension;
            return this;
        }

        public Builder features(JobFeatures features) {
            this.features = features;
            return this;
        }

        public ManagedJob build() {
            return new ManagedJob(this);
        }
    }
}
