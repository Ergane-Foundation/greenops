package com.greenops.model;

public class GreenOpsSpec {
    private int carbonThreshold = 400;
    private String flinkJobName;
    private String flinkNamespace;
    private String telemetryEndpoint;

    public int getCarbonThreshold() { return carbonThreshold; }
    public void setCarbonThreshold(int carbonThreshold) { this.carbonThreshold = carbonThreshold; }

    public String getFlinkJobName() { return flinkJobName; }
    public void setFlinkJobName(String flinkJobName) { this.flinkJobName = flinkJobName; }

    public String getFlinkNamespace() { return flinkNamespace; }
    public void setFlinkNamespace(String flinkNamespace) { this.flinkNamespace = flinkNamespace; }

    public String getTelemetryEndpoint() { return telemetryEndpoint; }
    public void setTelemetryEndpoint(String telemetryEndpoint) { this.telemetryEndpoint = telemetryEndpoint; }
}
