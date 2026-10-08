package com.solstice.inventory;

import com.solstice.model.SolsticeSpec;
import com.solstice.service.FlinkDeploymentService;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JobInventoryTest {

    private static final String NS = "solstice";

    private FlinkDeploymentService flinkDeployment;
    private JobInventory inventory;

    @BeforeEach
    void setUp() {
        flinkDeployment = mock(FlinkDeploymentService.class);
        inventory = new JobInventory(flinkDeployment);
    }

    private GenericKubernetesResource resource(String name,
                                               String state,
                                               Map<String, String> annotations,
                                               Map<String, Object> status) {
        GenericKubernetesResource resource = new GenericKubernetesResource();
        ObjectMeta meta = new ObjectMeta();
        meta.setName(name);
        meta.setNamespace(NS);
        meta.setAnnotations(annotations);
        resource.setMetadata(meta);
        resource.setAdditionalProperty("spec",
                Map.of("job", Map.of("state", state, "parallelism", 2)));
        if (status != null) {
            resource.setAdditionalProperty("status", status);
        }
        return resource;
    }

    @Test
    @DisplayName("a resource maps to a job with its state and savepoint")
    void mapsAResourceToAJob() {
        GenericKubernetesResource resource = resource("orders", "suspended", Map.of(),
                Map.of("jobStatus", Map.of("upgradeSavepointPath", "s3://solstice/savepoints/sp-1")));

        ManagedJob job = inventory.toManagedJob(resource, NS);

        assertEquals("orders", job.getName());
        assertEquals(NS, job.getNamespace());
        assertTrue(job.isSuspended());
        assertEquals("s3://solstice/savepoints/sp-1", job.getLastSavepointPath());
        assertEquals(2, job.getFeatures().getParallelism());
    }

    @Test
    void readsPriorityAndSuspensionLimitFromAnnotations() {
        GenericKubernetesResource resource = resource("orders", "running",
                Map.of(JobInventory.PRIORITY_ANNOTATION, "10",
                        JobInventory.MAX_SUSPENSION_ANNOTATION, "1800",
                        JobInventory.STATE_SIZE_ANNOTATION, "52428800"),
                null);

        ManagedJob job = inventory.toManagedJob(resource, NS);

        assertEquals(10, job.getPriority());
        assertEquals(Duration.ofMinutes(30), job.getMaxSuspension());
        assertEquals(50.0, job.getFeatures().getStateSizeMegabytes(), 0.001);
    }

    @Test
    @DisplayName("a job without annotations gets sensible defaults")
    void defaultsWhenAnnotationsAreAbsent() {
        ManagedJob job = inventory.toManagedJob(resource("orders", "running", Map.of(), null), NS);

        assertEquals(ManagedJob.DEFAULT_PRIORITY, job.getPriority());
        assertNull(job.getMaxSuspension());
        assertFalse(job.hasSuspensionLimit());
        assertTrue(job.toleratesSuspensionOf(Duration.ofHours(12)));
    }

    @Test
    @DisplayName("a malformed annotation is ignored rather than failing discovery")
    void malformedAnnotationsFallBack() {
        GenericKubernetesResource resource = resource("orders", "running",
                Map.of(JobInventory.PRIORITY_ANNOTATION, "urgent",
                        JobInventory.MAX_SUSPENSION_ANNOTATION, "soon"),
                null);

        ManagedJob job = inventory.toManagedJob(resource, NS);

        assertEquals(ManagedJob.DEFAULT_PRIORITY, job.getPriority());
        assertNull(job.getMaxSuspension());
    }

    @Test
    void aSuspensionLimitIsEnforced() {
        ManagedJob job = ManagedJob.builder()
                .name("orders")
                .maxSuspension(Duration.ofMinutes(30))
                .build();

        assertTrue(job.toleratesSuspensionOf(Duration.ofMinutes(20)));
        assertTrue(job.toleratesSuspensionOf(Duration.ofMinutes(30)));
        assertFalse(job.toleratesSuspensionOf(Duration.ofHours(2)));
    }

    @Test
    @DisplayName("without a selector the single named job is used, as before")
    void fallsBackToTheSingleNamedJob() {
        SolsticeSpec spec = new SolsticeSpec();
        spec.setFlinkNamespace(NS);
        spec.setFlinkJobName("solstice-flink");
        when(flinkDeployment.get(NS, "solstice-flink"))
                .thenReturn(Optional.of(resource("solstice-flink", "running", Map.of(), null)));

        List<ManagedJob> jobs = inventory.discover(spec);

        assertEquals(1, jobs.size());
        assertEquals("solstice-flink", jobs.get(0).getName());
    }

    @Test
    @DisplayName("a named job that does not exist yet is still reported")
    void reportsAMissingNamedJob() {
        SolsticeSpec spec = new SolsticeSpec();
        spec.setFlinkNamespace(NS);
        spec.setFlinkJobName("solstice-flink");
        when(flinkDeployment.get(anyString(), anyString())).thenReturn(Optional.empty());

        List<ManagedJob> jobs = inventory.discover(spec);

        assertEquals(1, jobs.size());
        assertFalse(jobs.get(0).isSuspended());
    }

    @Test
    @DisplayName("a selector discovers many jobs, ordered by priority")
    void discoversManyJobsInPriorityOrder() {
        SolsticeSpec spec = new SolsticeSpec();
        spec.setFlinkNamespace(NS);
        spec.setJobSelector(Map.of("carbon-aware", "true"));

        when(flinkDeployment.list(any(), any())).thenReturn(List.of(
                resource("reports", "running", Map.of(JobInventory.PRIORITY_ANNOTATION, "80"), null),
                resource("orders", "running", Map.of(JobInventory.PRIORITY_ANNOTATION, "10"), null),
                resource("audit", "running", Map.of(JobInventory.PRIORITY_ANNOTATION, "40"), null)));

        List<ManagedJob> jobs = inventory.discover(spec);

        assertEquals(3, jobs.size());
        assertEquals(List.of("orders", "audit", "reports"),
                jobs.stream().map(ManagedJob::getName).toList());
    }

    @Test
    void anEmptySelectorResultIsNotAnError() {
        SolsticeSpec spec = new SolsticeSpec();
        spec.setFlinkNamespace(NS);
        spec.setJobSelector(Map.of("carbon-aware", "true"));
        when(flinkDeployment.list(any(), any())).thenReturn(List.of());

        assertTrue(inventory.discover(spec).isEmpty());
    }
}
