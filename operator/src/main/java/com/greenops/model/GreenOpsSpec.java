package com.greenops.model;

public class GreenOpsSpec {
    private int carbonThreshold = 400;
    private String flinkJobName;
    private String flinkNamespace;
    private String telemetryEndpoint;
    private String forecastEndpoint;
    private String flinkRestEndpoint;
    private String savepointDirectory = "s3://greenops/savepoints";
    private int savepointTimeoutSeconds = 300;

    private boolean cooperativeSuspension = true;
    private double nodePowerWatts = 250.0;

    public int getCarbonThreshold() { return carbonThreshold; }
    public void setCarbonThreshold(int carbonThreshold) { this.carbonThreshold = carbonThreshold; }

    public String getFlinkJobName() { return flinkJobName; }
    public void setFlinkJobName(String flinkJobName) { this.flinkJobName = flinkJobName; }

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
}
