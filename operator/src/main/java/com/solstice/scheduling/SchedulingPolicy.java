package com.solstice.scheduling;

public interface SchedulingPolicy {

    String name();

    SuspensionPlan decide(SchedulingContext context);
}
