package com.solstice.cost;

public final class JobFeatures {

    private final String jobName;
    private final long stateSizeBytes;
    private final int parallelism;
    private final long checkpointIntervalSeconds;

    private JobFeatures(Builder builder) {
        this.jobName = builder.jobName;
        this.stateSizeBytes = builder.stateSizeBytes;
        this.parallelism = builder.parallelism;
        this.checkpointIntervalSeconds = builder.checkpointIntervalSeconds;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static JobFeatures unknown(String jobName) {
        return builder().jobName(jobName).build();
    }

    public String getJobName() {
        return jobName;
    }

    public long getStateSizeBytes() {
        return stateSizeBytes;
    }

    public int getParallelism() {
        return parallelism;
    }

    public long getCheckpointIntervalSeconds() {
        return checkpointIntervalSeconds;
    }

    public double getStateSizeMegabytes() {
        return stateSizeBytes / (1024.0 * 1024.0);
    }

    public boolean hasStateSize() {
        return stateSizeBytes > 0;
    }

    public static final class Builder {
        private String jobName;
        private long stateSizeBytes;
        private int parallelism = 1;
        private long checkpointIntervalSeconds;

        public Builder jobName(String jobName) {
            this.jobName = jobName;
            return this;
        }

        public Builder stateSizeBytes(long stateSizeBytes) {
            this.stateSizeBytes = stateSizeBytes;
            return this;
        }

        public Builder parallelism(int parallelism) {
            this.parallelism = parallelism;
            return this;
        }

        public Builder checkpointIntervalSeconds(long checkpointIntervalSeconds) {
            this.checkpointIntervalSeconds = checkpointIntervalSeconds;
            return this;
        }

        public JobFeatures build() {
            return new JobFeatures(this);
        }
    }
}
