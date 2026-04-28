package com.greenops.model;

public class GreenOpsStatus {
    private String gridStatus;
    private Integer carbonIntensity;
    private String lastAction;
    private String lastReconciledAt;

    public String getGridStatus() { return gridStatus; }
    public void setGridStatus(String gridStatus) { this.gridStatus = gridStatus; }

    public Integer getCarbonIntensity() { return carbonIntensity; }
    public void setCarbonIntensity(Integer carbonIntensity) { this.carbonIntensity = carbonIntensity; }

    public String getLastAction() { return lastAction; }
    public void setLastAction(String lastAction) { this.lastAction = lastAction; }

    public String getLastReconciledAt() { return lastReconciledAt; }
    public void setLastReconciledAt(String lastReconciledAt) { this.lastReconciledAt = lastReconciledAt; }
}
