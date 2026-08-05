package com.greenops.scheduling;

import com.greenops.inventory.ManagedJob;

public final class JobPlan {

    private final ManagedJob job;
    private final SuspensionPlan plan;

    public JobPlan(ManagedJob job, SuspensionPlan plan) {
        this.job = job;
        this.plan = plan;
    }

    public ManagedJob getJob() {
        return job;
    }

    public SuspensionPlan getPlan() {
        return plan;
    }

    public String getJobName() {
        return job.getName();
    }

    @Override
    public String toString() {
        return job.getName() + " " + plan;
    }
}
