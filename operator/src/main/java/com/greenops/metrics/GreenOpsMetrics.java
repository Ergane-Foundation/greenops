package com.greenops.metrics;

import io.prometheus.metrics.core.metrics.Counter;
import io.prometheus.metrics.core.metrics.Gauge;
import io.prometheus.metrics.core.metrics.Histogram;
import io.prometheus.metrics.exporter.httpserver.HTTPServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class GreenOpsMetrics {

    private static final Logger log = LoggerFactory.getLogger(GreenOpsMetrics.class);

    private static final Gauge CARBON_INTENSITY = Gauge.builder()
            .name("greenops_carbon_intensity_gco2_kwh")
            .help("Carbon intensity last read from the telemetry service")
            .labelNames("controller", "zone")
            .register();

    private static final Gauge CARBON_THRESHOLD = Gauge.builder()
            .name("greenops_carbon_threshold_gco2_kwh")
            .help("Configured carbon intensity threshold")
            .labelNames("controller")
            .register();

    private static final Gauge GRID_DIRTY = Gauge.builder()
            .name("greenops_grid_dirty")
            .help("1 when the grid is considered dirty, 0 otherwise")
            .labelNames("controller")
            .register();

    private static final Gauge JOB_SUSPENDED = Gauge.builder()
            .name("greenops_job_suspended")
            .help("1 while the managed Flink job is suspended, 0 while it runs")
            .labelNames("controller", "job")
            .register();

    private static final Counter RECONCILE_TOTAL = Counter.builder()
            .name("greenops_reconcile_total")
            .help("Reconciliations by the action taken")
            .labelNames("controller", "action")
            .register();

    private static final Counter SUSPENSION_SECONDS = Counter.builder()
            .name("greenops_suspension_seconds_total")
            .help("Total seconds the managed job has spent suspended")
            .labelNames("controller", "job")
            .register();

    private static final Counter CARBON_AVOIDED = Counter.builder()
            .name("greenops_carbon_avoided_grams_total")
            .help("Estimated grams of CO2 avoided by suspending the job")
            .labelNames("controller", "job")
            .register();

    private static final Counter SAVEPOINT_TOTAL = Counter.builder()
            .name("greenops_savepoint_total")
            .help("Savepoint outcomes")
            .labelNames("controller", "result")
            .register();

    private static final Histogram SAVEPOINT_DURATION = Histogram.builder()
            .name("greenops_savepoint_duration_seconds")
            .help("Time taken for a savepoint to complete")
            .labelNames("controller")
            .register();

    private static final Gauge FORECAST_INTENSITY = Gauge.builder()
            .name("greenops_forecast_intensity_gco2_kwh")
            .help("Forecast carbon intensity at a number of hours ahead")
            .labelNames("controller", "hours_ahead")
            .register();

    private static final Gauge FORECAST_WINDOW_STARTS_IN = Gauge.builder()
            .name("greenops_forecast_dirty_window_starts_in_seconds")
            .help("Seconds until the next forecast window above the threshold begins")
            .labelNames("controller")
            .register();

    private static final Gauge FORECAST_WINDOW_DURATION = Gauge.builder()
            .name("greenops_forecast_dirty_window_duration_seconds")
            .help("Length of the next forecast window above the threshold")
            .labelNames("controller")
            .register();

    private static final Gauge FORECAST_WINDOW_PEAK = Gauge.builder()
            .name("greenops_forecast_dirty_window_peak_gco2_kwh")
            .help("Peak carbon intensity within the next forecast window")
            .labelNames("controller")
            .register();

    private static final Map<String, Instant> SUSPENDED_SINCE = new ConcurrentHashMap<>();
    private static final Map<String, Double> INTENSITY_AT_SUSPEND = new ConcurrentHashMap<>();

    private static HTTPServer server;

    private GreenOpsMetrics() {}

    public static synchronized void start(int port) {
        if (server != null) {
            return;
        }
        try {
            server = HTTPServer.builder().port(port).buildAndStart();
            log.info("Metrics available on :{}/metrics", port);
        } catch (IOException e) {
            log.error("Could not start metrics server on port {}: {}", port, e.getMessage());
        }
    }

    public static synchronized void stop() {
        if (server != null) {
            server.close();
            server = null;
        }
    }

    public static void recordGrid(String controller, String zone, int intensity, int threshold, boolean dirty) {
        CARBON_INTENSITY.labelValues(controller, zone == null ? "unknown" : zone).set(intensity);
        CARBON_THRESHOLD.labelValues(controller).set(threshold);
        GRID_DIRTY.labelValues(controller).set(dirty ? 1 : 0);
    }

    public static void recordForecastPoint(String controller, long hoursAhead, int intensity) {
        FORECAST_INTENSITY.labelValues(controller, String.valueOf(hoursAhead)).set(intensity);
    }

    public static void recordForecastWindow(String controller,
                                            double startsInSeconds,
                                            double durationSeconds,
                                            int peakIntensity) {
        FORECAST_WINDOW_STARTS_IN.labelValues(controller).set(startsInSeconds);
        FORECAST_WINDOW_DURATION.labelValues(controller).set(durationSeconds);
        FORECAST_WINDOW_PEAK.labelValues(controller).set(peakIntensity);
    }

    public static void clearForecastWindow(String controller) {
        FORECAST_WINDOW_STARTS_IN.labelValues(controller).set(-1);
        FORECAST_WINDOW_DURATION.labelValues(controller).set(0);
        FORECAST_WINDOW_PEAK.labelValues(controller).set(0);
    }

    public static void recordAction(String controller, String action) {
        RECONCILE_TOTAL.labelValues(controller, action == null ? "NONE" : action).inc();
    }

    public static void recordSavepoint(String controller, String result) {
        SAVEPOINT_TOTAL.labelValues(controller, result).inc();
    }

    public static void recordSavepointDuration(String controller, Duration duration) {
        SAVEPOINT_DURATION.labelValues(controller).observe(duration.toMillis() / 1000.0);
    }

    public static void markRunning(String controller, String job, double nodePowerWatts) {
        String key = controller + "/" + job;
        JOB_SUSPENDED.labelValues(controller, job).set(0);

        Instant since = SUSPENDED_SINCE.remove(key);
        if (since == null) {
            return;
        }
        double seconds = Duration.between(since, Instant.now()).toMillis() / 1000.0;
        SUSPENSION_SECONDS.labelValues(controller, job).inc(seconds);

        double intensity = INTENSITY_AT_SUSPEND.getOrDefault(key, 0.0);
        INTENSITY_AT_SUSPEND.remove(key);

        double kwh = (nodePowerWatts / 1000.0) * (seconds / 3600.0);
        CARBON_AVOIDED.labelValues(controller, job).inc(kwh * intensity);
    }

    public static void accrueSuspension(String controller, String job, double carbonIntensity, double nodePowerWatts) {
        String key = controller + "/" + job;
        Instant since = SUSPENDED_SINCE.get(key);
        Instant now = Instant.now();
        if (since == null) {
            SUSPENDED_SINCE.put(key, now);
            INTENSITY_AT_SUSPEND.put(key, carbonIntensity);
            JOB_SUSPENDED.labelValues(controller, job).set(1);
            return;
        }

        double seconds = Duration.between(since, now).toMillis() / 1000.0;
        if (seconds <= 0) {
            return;
        }
        SUSPENDED_SINCE.put(key, now);
        INTENSITY_AT_SUSPEND.put(key, carbonIntensity);
        SUSPENSION_SECONDS.labelValues(controller, job).inc(seconds);

        double kwh = (nodePowerWatts / 1000.0) * (seconds / 3600.0);
        CARBON_AVOIDED.labelValues(controller, job).inc(kwh * carbonIntensity);
    }
}
