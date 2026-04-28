package com.greenops.service;

import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FlinkService {

    private static final Logger log = LoggerFactory.getLogger(FlinkService.class);

    private final KubernetesClient client;

    public FlinkService(KubernetesClient client) {
        this.client = client;
    }

    public void scaleTaskManager(String namespace, String flinkJobName, int replicas) {
        // Native-mode Flink: the operator creates only a JobManager Deployment
        // (named {flinkJobName}); TaskManagers are pods managed by the JM itself.
        // Scaling the JM Deployment to 0 tears down the whole cluster — stateless
        // demo path for Week 3. Week 4 will switch to patching FlinkDeployment
        // spec.job.state so savepoints happen before shutdown.
        String deploymentName = flinkJobName;
        Deployment current = client.apps().deployments()
                .inNamespace(namespace)
                .withName(deploymentName)
                .get();

        if (current == null) {
            log.warn("Deployment {}/{} not found — skipping scale", namespace, deploymentName);
            return;
        }

        int currentReplicas = current.getSpec().getReplicas() == null ? -1 : current.getSpec().getReplicas();
        if (currentReplicas == replicas) {
            log.info("Deployment {}/{} already at {} replicas", namespace, deploymentName, replicas);
            return;
        }

        log.info("Scaling {}/{} from {} -> {}", namespace, deploymentName, currentReplicas, replicas);
        client.apps().deployments()
                .inNamespace(namespace)
                .withName(deploymentName)
                .scale(replicas);
    }
}
