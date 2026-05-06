package com.greenops.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

public class FlinkService {

    private static final Logger log = LoggerFactory.getLogger(FlinkService.class);
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(5);

    private final KubernetesClient client;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    public FlinkService(KubernetesClient client) {
        this.client = client;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.mapper = new ObjectMapper();
    }

    // --- Flink REST API ---------------------------------------------------

    public Optional<String> getRunningJobId(String flinkRestEndpoint) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(flinkRestEndpoint + "/jobs"))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            log.warn("Flink /jobs returned HTTP {}: {}", resp.statusCode(), resp.body());
            return Optional.empty();
        }
        JsonNode jobs = mapper.readTree(resp.body()).path("jobs");
        for (JsonNode job : jobs) {
            if ("RUNNING".equalsIgnoreCase(job.path("status").asText())) {
                return Optional.of(job.path("id").asText());
            }
        }
        return Optional.empty();
    }

    public String triggerSavepoint(String flinkRestEndpoint, String jobId, String savepointDir) throws Exception {
        String url = flinkRestEndpoint + "/jobs/" + jobId + "/savepoints";
        String body = "{\"cancel-job\":false,\"target-directory\":\"" + savepointDir + "\"}";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException("Failed to trigger savepoint: HTTP " + resp.statusCode() + " " + resp.body());
        }
        String triggerId = mapper.readTree(resp.body()).path("request-id").asText();
        if (triggerId == null || triggerId.isEmpty()) {
            throw new IllegalStateException("Flink did not return a request-id: " + resp.body());
        }
        return triggerId;
    }

    public SavepointResult pollSavepointCompletion(String flinkRestEndpoint, String jobId, String triggerId, int timeoutSeconds) throws Exception {
        String url = flinkRestEndpoint + "/jobs/" + jobId + "/savepoints/" + triggerId;
        Instant deadline = Instant.now().plusSeconds(timeoutSeconds);

        while (Instant.now().isBefore(deadline)) {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("Savepoint status returned HTTP {} — retrying", resp.statusCode());
                Thread.sleep(POLL_INTERVAL.toMillis());
                continue;
            }
            JsonNode body = mapper.readTree(resp.body());
            String statusId = body.path("status").path("id").asText();

            if ("COMPLETED".equalsIgnoreCase(statusId)) {
                JsonNode operation = body.path("operation");
                if (operation.has("failure-cause")) {
                    String cause = operation.path("failure-cause").path("class").asText("unknown");
                    String stackTrace = operation.path("failure-cause").path("stack-trace").asText("");
                    return SavepointResult.failure(cause + (stackTrace.isEmpty() ? "" : " — " + stackTrace.split("\n")[0]));
                }
                String location = operation.path("location").asText();
                return SavepointResult.success(location);
            }
            // IN_PROGRESS — continue polling
            Thread.sleep(POLL_INTERVAL.toMillis());
        }
        return SavepointResult.timeout();
    }

    // --- Kubernetes scale --------------------------------------------------

    public void scaleJobManager(String namespace, String flinkJobName, int replicas) {
        // Native-mode Flink: the operator creates only a JobManager Deployment
        // (named {flinkJobName}); TaskManagers are pods spawned directly by the JM.
        // Scaling the JM Deployment to 0 tears down the whole cluster. On scale-up
        // the JM restarts; to resume from a savepoint the FlinkDeployment CR needs
        // spec.job.initialSavepointPath set (handled in the reconciler before
        // scaling back up).
        Deployment current = client.apps().deployments()
                .inNamespace(namespace)
                .withName(flinkJobName)
                .get();

        if (current == null) {
            log.warn("Deployment {}/{} not found — skipping scale", namespace, flinkJobName);
            return;
        }

        int currentReplicas = current.getSpec().getReplicas() == null ? -1 : current.getSpec().getReplicas();
        if (currentReplicas == replicas) {
            log.info("Deployment {}/{} already at {} replicas", namespace, flinkJobName, replicas);
            return;
        }

        log.info("Scaling {}/{} from {} -> {}", namespace, flinkJobName, currentReplicas, replicas);
        client.apps().deployments()
                .inNamespace(namespace)
                .withName(flinkJobName)
                .scale(replicas);
    }
}
