package com.solstice.forecast;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForecastServiceTest {

    private final ForecastService service = new ForecastService();

    @Test
    void parsesTheTelemetryPayload() throws Exception {
        String body = """
                {
                  "zone": "IN-NO",
                  "source": "simulated",
                  "last_updated": "2026-07-23T10:00:00+00:00",
                  "points": [
                    {"datetime": "2026-07-23T10:00:00+00:00", "carbon_intensity": 120},
                    {"datetime": "2026-07-23T10:30:00+00:00", "carbon_intensity": 480}
                  ]
                }
                """;

        CarbonForecast forecast = service.parse(body);

        assertEquals(2, forecast.getPoints().size());
        assertEquals("simulated", forecast.getSource());
        assertEquals(Instant.parse("2026-07-23T10:00:00Z"), forecast.getPoints().get(0).getAt());
        assertEquals(480, forecast.getPoints().get(1).getCarbonIntensity());
    }

    @Test
    @DisplayName("points with an unreadable timestamp are dropped rather than failing the parse")
    void skipsUnparseablePoints() throws Exception {
        String body = """
                {
                  "source": "test",
                  "points": [
                    {"datetime": "not-a-date", "carbon_intensity": 100},
                    {"carbon_intensity": 200},
                    {"datetime": "2026-07-23T11:00:00Z", "carbon_intensity": 300}
                  ]
                }
                """;

        CarbonForecast forecast = service.parse(body);

        assertEquals(1, forecast.getPoints().size());
        assertEquals(300, forecast.getPoints().get(0).getCarbonIntensity());
    }

    @Test
    void anEmptyPayloadYieldsAnEmptyForecast() throws Exception {
        assertTrue(service.parse("{\"points\": []}").isEmpty());
    }

    @Test
    @DisplayName("an unset endpoint is not an error, it just means no forecast")
    void aBlankEndpointYieldsEmpty() {
        assertTrue(service.fetch(null).isEmpty());
        assertTrue(service.fetch("").isEmpty());
    }
}
