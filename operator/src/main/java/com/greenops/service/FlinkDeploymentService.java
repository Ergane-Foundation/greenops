package com.greenops.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import io.fabric8.kubernetes.client.dsl.base.PatchContext;
import io.fabric8.kubernetes.client.dsl.base.PatchType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class FlinkDeploymentService {

    private static final Logger log = LoggerFactory.getLogger(FlinkDeploymentService.class);

    public static final String STATE_RUNNING = "running";
    public static final String STATE_SUSPENDED = "suspended";

    private static final CustomResourceDefinitionContext FLINK_DEPLOYMENT_CONTEXT =
            new CustomResourceDefinitionContext.Builder()
                    .withGroup("flink.apache.org")
                    .withVersion("v1beta1")
                    .withPlural("flinkdeployments")
                    .withKind("FlinkDeployment")
                    .withScope("Namespaced")
                    .build();

    private final KubernetesClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public FlinkDeploymentService(KubernetesClient client) {
        this.client = client;
    }

    public Optional<GenericKubernetesResource> get(String namespace, String name) {
        try {
            return Optional.ofNullable(
                    client.genericKubernetesResources(FLINK_DEPLOYMENT_CONTEXT)
                            .inNamespace(namespace)
                            .withName(name)
                            .get());
        } catch (Exception e) {
            log.warn("Could not read FlinkDeployment {}/{}: {}", namespace, name, e.getMessage());
            return Optional.empty();
        }
    }

    public List<GenericKubernetesResource> list(String namespace, Map<String, String> labelSelector) {
        try {
            var resources = client.genericKubernetesResources(FLINK_DEPLOYMENT_CONTEXT).inNamespace(namespace);
            if (labelSelector == null || labelSelector.isEmpty()) {
                return resources.list().getItems();
            }
            return resources.withLabels(labelSelector).list().getItems();
        } catch (Exception e) {
            log.warn("Could not list FlinkDeployments in {}: {}", namespace, e.getMessage());
            return List.of();
        }
    }

    public Optional<String> getJobState(String namespace, String name) {
        return get(namespace, name).flatMap(resource -> {
            Object state = nested(resource.getAdditionalProperties(), "spec", "job", "state");
            return state == null ? Optional.empty() : Optional.of(String.valueOf(state));
        });
    }

    public Optional<String> getLastSavepointPath(String namespace, String name) {
        return get(namespace, name).flatMap(resource -> {
            Map<String, Object> props = resource.getAdditionalProperties();
            Object location = nested(props, "status", "jobStatus", "upgradeSavepointPath");
            if (location == null) {
                location = nested(props,
                        "status", "jobStatus", "savepointInfo", "lastSavepoint", "location");
            }
            if (location == null) {
                return Optional.empty();
            }
            String path = String.valueOf(location);
            return path.isBlank() ? Optional.empty() : Optional.of(path);
        });
    }

    public Optional<String> getObservedJobState(String namespace, String name) {
        return get(namespace, name).flatMap(resource -> {
            Object state = nested(resource.getAdditionalProperties(), "status", "jobStatus", "state");
            return state == null ? Optional.empty() : Optional.of(String.valueOf(state));
        });
    }

    public boolean suspend(String namespace, String name) {
        return patchJobState(namespace, name, STATE_SUSPENDED, null);
    }

    public boolean resume(String namespace, String name, String savepointPath) {
        return patchJobState(namespace, name, STATE_RUNNING, savepointPath);
    }

    private boolean patchJobState(String namespace, String name, String desiredState, String savepointPath) {
        Optional<GenericKubernetesResource> existing = get(namespace, name);
        if (existing.isEmpty()) {
            log.warn("FlinkDeployment {}/{} not found, skipping {} request", namespace, name, desiredState);
            return false;
        }

        Map<String, Object> job = new LinkedHashMap<>();
        job.put("state", desiredState);
        if (savepointPath != null && !savepointPath.isBlank()) {
            job.put("initialSavepointPath", savepointPath);
        }

        Map<String, Object> patch = Map.of("spec", Map.of("job", job));

        try {
            client.genericKubernetesResources(FLINK_DEPLOYMENT_CONTEXT)
                    .inNamespace(namespace)
                    .withName(name)
                    .patch(PatchContext.of(PatchType.JSON_MERGE),
                            mapper.writeValueAsString(patch));
            if (savepointPath != null && !savepointPath.isBlank()) {
                log.info("Patched FlinkDeployment {}/{} to state={} from savepoint {}",
                        namespace, name, desiredState, savepointPath);
            } else {
                log.info("Patched FlinkDeployment {}/{} to state={}", namespace, name, desiredState);
            }
            return true;
        } catch (Exception e) {
            log.error("Failed to patch FlinkDeployment {}/{} to state={}: {}",
                    namespace, name, desiredState, e.getMessage());
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private static Object nested(Map<String, Object> root, String... path) {
        Object current = root;
        for (String key : path) {
            if (!(current instanceof Map)) return null;
            current = ((Map<String, Object>) current).get(key);
            if (current == null) return null;
        }
        return current;
    }
}
