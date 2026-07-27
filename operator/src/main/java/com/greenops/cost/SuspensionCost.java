package com.greenops.cost;

import java.time.Duration;

public final class SuspensionCost {

    private final CostEstimate savepoint;
    private final CostEstimate restart;

    public SuspensionCost(CostEstimate savepoint, CostEstimate restart) {
        this.savepoint = savepoint;
        this.restart = restart;
    }

    public CostEstimate getSavepoint() {
        return savepoint;
    }

    public CostEstimate getRestart() {
        return restart;
    }

    public Duration total() {
        return savepoint.getValue().plus(restart.getValue());
    }

    public Duration conservativeTotal() {
        return savepoint.conservative().plus(restart.conservative());
    }

    public boolean isConfident() {
        return savepoint.isConfident() && restart.isConfident();
    }

    public Duration breakEvenWindow(double overheadMultiplier) {
        long seconds = (long) Math.ceil(conservativeTotal().toSeconds() * overheadMultiplier);
        return Duration.ofSeconds(seconds);
    }

    @Override
    public String toString() {
        return "savepoint " + savepoint.getValue().toSeconds()
                + "s plus restart " + restart.getValue().toSeconds() + "s";
    }
}
