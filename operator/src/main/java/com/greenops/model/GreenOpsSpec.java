package com.greenops.model;

public class GreenOpsSpec {

    private int carbonThreshold = 400;
    private String flinkJobName;
    private java.util.Map<String, String> jobSelector;
    private String flinkNamespace;
    private String telemetryEndpoint;
    private String forecastEndpoint;
    private String flinkRestEndpoint;
    private String savepointDirectory = "s3://greenops/savepoints";
    private int savepointTimeoutSeconds = 300;

    private boolean cooperativeSuspension = true;
    private double nodePowerWatts = 250.0;
    private String schedulingPolicy = "threshold";
    private String costPredictor = "static";
    private int assumedSavepointSeconds = 60;
    private int assumedRestartSeconds = 120;
    private double breakEvenMultiplier = 2.0;

    public int getCarbonThreshold() { return carbonThreshold; }
    public void setCarbonThreshold(int carbonThreshold) { this.carbonThreshold = carbonThreshold; }

    public String getFlinkJobName() { return flinkJobName; }
    public void setFlinkJobName(String flinkJobName) { this.flinkJobName = flinkJobName; }

    public java.util.Map<String, String> getJobSelector() { return jobSelector; }
    public void setJobSelector(java.util.Map<String, String> jobSelector) { this.jobSelector = jobSelector; }

    public String getFlinkNamespace() { return flinkNamespace; }
    public void setFlinkNamespace(String flinkNamespace) { this.flinkNamespace = flinkNamespace; }

    public String getTelemetryEndpoint() { return telemetryEndpoint; }
    public void setTelemetryEndpoint(String telemetryEndpoint) { this.telemetryEndpoint = telemetryEndpoint; }

    public String getForecastEndpoint() { return forecastEndpoint; }
    public void setForecastEndpoint(String forecastEndpoint) { this.forecastEndpoint = forecastEndpoint; }

    public String getFlinkRestEndpoint() { return flinkRestEndpoint; }
    public void setFlinkRestEndpoint(String flinkRestEndpoint) { this.flinkRestEndpoint = flinkRestEndpoint; }

    public String getSavepointDirectory() { return savepointDirectory; }
    public void setSavepointDirectory(String savepointDirectory) { this.savepointDirectory = savepointDirectory; }

    public int getSavepointTimeoutSeconds() { return savepointTimeoutSeconds; }
    public void setSavepointTimeoutSeconds(int savepointTimeoutSeconds) { this.savepointTimeoutSeconds = savepointTimeoutSeconds; }

    public boolean isCooperativeSuspension() { return cooperativeSuspension; }
    public void setCooperativeSuspension(boolean cooperativeSuspension) { this.cooperativeSuspension = cooperativeSuspension; }

    public double getNodePowerWatts() { return nodePowerWatts; }
    public void setNodePowerWatts(double nodePowerWatts) { this.nodePowerWatts = nodePowerWatts; }

    public String getSchedulingPolicy() { return schedulingPolicy; }
    public void setSchedulingPolicy(String schedulingPolicy) { this.schedulingPolicy = schedulingPolicy; }

    public String getCostPredictor() { return costPredictor; }
    public void setCostPredictor(String costPredictor) { this.costPredictor = costPredictor; }

    public int getAssumedSavepointSeconds() { return assumedSavepointSeconds; }
    public void setAssumedSavepointSeconds(int assumedSavepointSeconds) { this.assumedSavepointSeconds = assumedSavepointSeconds; }

    public int getAssumedRestartSeconds() { return assumedRestartSeconds; }
    public void setAssumedRestartSeconds(int assumedRestartSeconds) { this.assumedRestartSeconds = assumedRestartSeconds; }

    public double getBreakEvenMultiplier() { return breakEvenMultiplier; }
    public void setBreakEvenMultiplier(double breakEvenMultiplier) { this.breakEvenMultiplier = breakEvenMultiplier; }
}
