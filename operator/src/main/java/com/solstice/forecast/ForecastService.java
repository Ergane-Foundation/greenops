package com.solstice.forecast;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

public class ForecastService {

    private static final Logger log = LoggerFactory.getLogger(ForecastService.class);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    public CarbonForecast fetch(String forecastEndpoint) {
        if (forecastEndpoint == null || forecastEndpoint.isBlank()) {
            return CarbonForecast.empty();
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(forecastEndpoint))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("Forecast endpoint returned HTTP {} from {}", response.statusCode(), forecastEndpoint);
                return CarbonForecast.empty();
            }
            return parse(response.body());
        } catch (Exception e) {
            log.warn("Could not fetch forecast from {}: {}", forecastEndpoint, e.getMessage());
            return CarbonForecast.empty();
        }
    }

    CarbonForecast parse(String body) throws Exception {
        JsonNode root = mapper.readTree(body);
        List<ForecastPoint> points = new ArrayList<>();

        for (JsonNode node : root.path("points")) {
            String moment = node.path("datetime").asText(null);
            if (moment == null) {
                continue;
            }
            Instant at = parseInstant(moment);
            if (at == null) {
                continue;
            }
            points.add(new ForecastPoint(at, node.path("carbon_intensity").asInt(0)));
        }

        String source = root.path("source").asText("unknown");
        Instant lastUpdated = parseInstant(root.path("last_updated").asText(null));
        return new CarbonForecast(points, source, lastUpdated);
    }

    private Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (Exception ignored) {
            try {
                return Instant.parse(value);
            } catch (Exception e) {
                log.debug("Unparseable forecast timestamp: {}", value);
                return null;
            }
        }
    }
}
