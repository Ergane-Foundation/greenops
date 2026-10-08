package com.solstice.scheduling;

public class ThresholdPolicy implements SchedulingPolicy {

    public static final String NAME = "threshold";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public SuspensionPlan decide(SchedulingContext context) {
        if (context.isGridDirty()) {
            return SuspensionPlan.suspend(String.format(
                    "carbon %d above threshold %d",
                    context.getCarbonIntensity(), context.getCarbonThreshold()));
        }
        return SuspensionPlan.resume(String.format(
                "carbon %d at or below threshold %d",
                context.getCarbonIntensity(), context.getCarbonThreshold()));
    }
}
