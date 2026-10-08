package com.solstice.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class TelemetryService {

    private static final Logger log = LoggerFactory.getLogger(TelemetryService.class);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    public GridStatus getCurrentStatus(String telemetryEndpoint) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(telemetryEndpoint))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("Telemetry returned HTTP {} from {}", resp.statusCode(), telemetryEndpoint);
                return GridStatus.unknown();
            }
            JsonNode node = mapper.readTree(resp.body());
            String status = node.path("grid_status").asText("UNKNOWN");
            int intensity = node.path("carbon_intensity").asInt(0);
            String zone = node.path("zone").asText("unknown");
            return new GridStatus(status, intensity, zone);
        } catch (Exception e) {
            log.error("Failed to fetch telemetry from {}: {}", telemetryEndpoint, e.getMessage());
            return GridStatus.unknown();
        }
    }

    public static class GridStatus {
        private final String status;
        private final int carbonIntensity;
        private final String zone;

        public GridStatus(String status, int carbonIntensity) {
            this(status, carbonIntensity, "unknown");
        }

        public GridStatus(String status, int carbonIntensity, String zone) {
            this.status = status;
            this.carbonIntensity = carbonIntensity;
            this.zone = zone;
        }

        public static GridStatus unknown() { return new GridStatus("UNKNOWN", 0, "unknown"); }

        public String getStatus() { return status; }
        public int getCarbonIntensity() { return carbonIntensity; }
        public String getZone() { return zone; }

        public boolean isDirty(int threshold) {
            if ("DIRTY".equalsIgnoreCase(status)) return true;
            if ("GREEN".equalsIgnoreCase(status)) return false;
            return carbonIntensity > threshold;
        }
    }
}
