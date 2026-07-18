package com.greenops.scheduling;

public interface SchedulingPolicy {

    String name();

    SuspensionPlan decide(SchedulingContext context);
}
