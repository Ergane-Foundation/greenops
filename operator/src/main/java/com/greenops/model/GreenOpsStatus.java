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
    private String decisionReason;
    private String activePolicy;
    private Integer managedJobCount;
    private java.util.List<ManagedJobStatus> jobs;
    private String forecastSource;
    private Integer forecastHorizonHours;
    private String nextDirtyWindowStart;
    private String nextDirtyWindowEnd;
    private Integer nextDirtyWindowPeak;

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

    public String getDecisionReason() { return decisionReason; }
    public void setDecisionReason(String decisionReason) { this.decisionReason = decisionReason; }

    public String getActivePolicy() { return activePolicy; }
    public void setActivePolicy(String activePolicy) { this.activePolicy = activePolicy; }

    public Integer getManagedJobCount() { return managedJobCount; }
    public void setManagedJobCount(Integer managedJobCount) { this.managedJobCount = managedJobCount; }

    public java.util.List<ManagedJobStatus> getJobs() { return jobs; }
    public void setJobs(java.util.List<ManagedJobStatus> jobs) { this.jobs = jobs; }

    public String getForecastSource() { return forecastSource; }
    public void setForecastSource(String forecastSource) { this.forecastSource = forecastSource; }

    public Integer getForecastHorizonHours() { return forecastHorizonHours; }
    public void setForecastHorizonHours(Integer forecastHorizonHours) { this.forecastHorizonHours = forecastHorizonHours; }

    public String getNextDirtyWindowStart() { return nextDirtyWindowStart; }
    public void setNextDirtyWindowStart(String nextDirtyWindowStart) { this.nextDirtyWindowStart = nextDirtyWindowStart; }

    public String getNextDirtyWindowEnd() { return nextDirtyWindowEnd; }
    public void setNextDirtyWindowEnd(String nextDirtyWindowEnd) { this.nextDirtyWindowEnd = nextDirtyWindowEnd; }

    public Integer getNextDirtyWindowPeak() { return nextDirtyWindowPeak; }
    public void setNextDirtyWindowPeak(Integer nextDirtyWindowPeak) { this.nextDirtyWindowPeak = nextDirtyWindowPeak; }
}
