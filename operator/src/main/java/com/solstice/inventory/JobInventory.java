package com.solstice.inventory;

import com.solstice.cost.JobFeatures;
import com.solstice.model.SolsticeSpec;
import com.solstice.service.FlinkDeploymentService;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public class JobInventory {

    private static final Logger log = LoggerFactory.getLogger(JobInventory.class);

    public static final String PRIORITY_ANNOTATION = "solstice.io/priority";
    public static final String MAX_SUSPENSION_ANNOTATION = "solstice.io/max-suspension-seconds";
    public static final String STATE_SIZE_ANNOTATION = "solstice.io/state-size-bytes";

    private final FlinkDeploymentService flinkDeploymentService;

    public JobInventory(FlinkDeploymentService flinkDeploymentService) {
        this.flinkDeploymentService = flinkDeploymentService;
    }

    public List<ManagedJob> discover(SolsticeSpec spec) {
        String namespace = spec.getFlinkNamespace();

        if (spec.getJobSelector() == null || spec.getJobSelector().isEmpty()) {
            return singleJob(spec, namespace);
        }

        List<GenericKubernetesResource> resources =
                flinkDeploymentService.list(namespace, spec.getJobSelector());

        List<ManagedJob> jobs = new ArrayList<>();
        for (GenericKubernetesResource resource : resources) {
            jobs.add(toManagedJob(resource, namespace));
        }
        jobs.sort(Comparator.comparingInt(ManagedJob::getPriority)
                .thenComparing(ManagedJob::getName));

        log.info("[Solstice] Managing {} job(s) matching {}", jobs.size(), spec.getJobSelector());
        return jobs;
    }

    private List<ManagedJob> singleJob(SolsticeSpec spec, String namespace) {
        String name = spec.getFlinkJobName();
        if (name == null || name.isBlank()) {
            return List.of();
        }
        return flinkDeploymentService.get(namespace, name)
                .map(resource -> List.of(toManagedJob(resource, namespace)))
                .orElseGet(() -> List.of(ManagedJob.builder()
                        .name(name)
                        .namespace(namespace)
                        .features(JobFeatures.unknown(name))
                        .build()));
    }

    ManagedJob toManagedJob(GenericKubernetesResource resource, String fallbackNamespace) {
        String name = resource.getMetadata() == null ? null : resource.getMetadata().getName();
        String namespace = resource.getMetadata() == null || resource.getMetadata().getNamespace() == null
                ? fallbackNamespace
                : resource.getMetadata().getNamespace();

        Map<String, String> annotations = resource.getMetadata() == null
                ? Map.of()
                : resource.getMetadata().getAnnotations() == null
                        ? Map.of()
                        : resource.getMetadata().getAnnotations();

        Map<String, Object> props = resource.getAdditionalProperties();
        boolean suspended = FlinkDeploymentService.STATE_SUSPENDED
                .equalsIgnoreCase(String.valueOf(nested(props, "spec", "job", "state")));

        boolean observedSuspended = FlinkDeploymentService.LIFECYCLE_SUSPENDED
                .equalsIgnoreCase(String.valueOf(nested(props, "status", "lifecycleState")));

        Object savepoint = nested(props, "status", "jobStatus", "upgradeSavepointPath");
        if (savepoint == null) {
            savepoint = nested(props, "status", "jobStatus", "savepointInfo", "lastSavepoint", "location");
        }

        Object parallelism = nested(props, "spec", "job", "parallelism");

        return ManagedJob.builder()
                .name(name)
                .namespace(namespace)
                .suspended(suspended)
                .observedSuspended(observedSuspended)
                .lastSavepointPath(savepoint == null ? null : String.valueOf(savepoint))
                .priority(intAnnotation(annotations, PRIORITY_ANNOTATION, ManagedJob.DEFAULT_PRIORITY))
                .maxSuspension(durationAnnotation(annotations, MAX_SUSPENSION_ANNOTATION))
                .features(JobFeatures.builder()
                        .jobName(name)
                        .stateSizeBytes(longAnnotation(annotations, STATE_SIZE_ANNOTATION, 0))
                        .parallelism(parallelism instanceof Number n ? n.intValue() : 1)
                        .build())
                .build();
    }

    private int intAnnotation(Map<String, String> annotations, String key, int fallback) {
        try {
            String value = annotations.get(key);
            return value == null ? fallback : Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            log.warn("Annotation {} is not a number, using {}", key, fallback);
            return fallback;
        }
    }

    private long longAnnotation(Map<String, String> annotations, String key, long fallback) {
        try {
            String value = annotations.get(key);
            return value == null ? fallback : Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            log.warn("Annotation {} is not a number, using {}", key, fallback);
            return fallback;
        }
    }

    private Duration durationAnnotation(Map<String, String> annotations, String key) {
        String value = annotations.get(key);
        if (value == null) {
            return null;
        }
        try {
            long seconds = Long.parseLong(value.trim());
            return seconds <= 0 ? null : Duration.ofSeconds(seconds);
        } catch (NumberFormatException e) {
            log.warn("Annotation {} is not a number of seconds, ignoring", key);
            return null;
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
