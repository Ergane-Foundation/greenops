package com.solstice.service;

public final class SavepointResult {

    public enum Phase { COMPLETED, FAILED, TIMEOUT }

    private final Phase phase;
    private final String path;
    private final String message;

    private SavepointResult(Phase phase, String path, String message) {
        this.phase = phase;
        this.path = path;
        this.message = message;
    }

    public static SavepointResult success(String path) {
        return new SavepointResult(Phase.COMPLETED, path, null);
    }

    public static SavepointResult failure(String cause) {
        return new SavepointResult(Phase.FAILED, null, cause);
    }

    public static SavepointResult timeout() {
        return new SavepointResult(Phase.TIMEOUT, null, "Savepoint did not complete within timeout");
    }

    public Phase getPhase() { return phase; }
    public String getPath() { return path; }
    public String getMessage() { return message; }

    public boolean isSuccess() { return phase == Phase.COMPLETED; }
}
