package com.greenops.model;

public class GreenOpsStatus {
    private String gridStatus;
    private Integer carbonIntensity;
    private String lastAction;
    private String lastReconciledAt;
    private String savepointPhase;
    private String lastSavepointPath;
    private String lastSavepointAt;
    private String lastError;

    public String getGridStatus() { return gridStatus; }
    public void setGridStatus(String gridStatus) { this.gridStatus = gridStatus; }

    public Integer getCarbonIntensity() { return carbonIntensity; }
    public void setCarbonIntensity(Integer carbonIntensity) { this.carbonIntensity = carbonIntensity; }

    public String getLastAction() { return lastAction; }
    public void setLastAction(String lastAction) { this.lastAction = lastAction; }

    public String getLastReconciledAt() { return lastReconciledAt; }
    public void setLastReconciledAt(String lastReconciledAt) { this.lastReconciledAt = lastReconciledAt; }

    public String getSavepointPhase() { return savepointPhase; }
    public void setSavepointPhase(String savepointPhase) { this.savepointPhase = savepointPhase; }

    public String getLastSavepointPath() { return lastSavepointPath; }
    public void setLastSavepointPath(String lastSavepointPath) { this.lastSavepointPath = lastSavepointPath; }

    public String getLastSavepointAt() { return lastSavepointAt; }
    public void setLastSavepointAt(String lastSavepointAt) { this.lastSavepointAt = lastSavepointAt; }

    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
}
