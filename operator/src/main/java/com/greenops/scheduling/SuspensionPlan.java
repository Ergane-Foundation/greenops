package com.greenops.scheduling;

import java.util.Objects;

public final class SuspensionPlan {

    private final SuspensionAction action;
    private final String reason;

    private SuspensionPlan(SuspensionAction action, String reason) {
        this.action = Objects.requireNonNull(action);
        this.reason = reason;
    }

    public static SuspensionPlan suspend(String reason) {
        return new SuspensionPlan(SuspensionAction.SUSPEND, reason);
    }

    public static SuspensionPlan resume(String reason) {
        return new SuspensionPlan(SuspensionAction.RESUME, reason);
    }

    public static SuspensionPlan hold(String reason) {
        return new SuspensionPlan(SuspensionAction.HOLD, reason);
    }

    public SuspensionAction getAction() {
        return action;
    }

    public String getReason() {
        return reason;
    }

    public boolean isSuspend() {
        return action == SuspensionAction.SUSPEND;
    }

    public boolean isResume() {
        return action == SuspensionAction.RESUME;
    }

    @Override
    public String toString() {
        return action + (reason == null ? "" : " (" + reason + ")");
    }
}
