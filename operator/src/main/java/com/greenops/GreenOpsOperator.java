package com.greenops;

import com.greenops.controller.GreenOpsReconciler;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.javaoperatorsdk.operator.Operator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GreenOpsOperator {

    private static final Logger log = LoggerFactory.getLogger(GreenOpsOperator.class);

    public static void main(String[] args) {
        log.info("Starting GreenOps operator...");

        KubernetesClient client = new KubernetesClientBuilder().build();
        Operator operator = new Operator(o -> o.withKubernetesClient(client));
        operator.register(new GreenOpsReconciler(client));
        operator.start();

        log.info("GreenOps operator started. Watching GreenOpsController resources.");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down operator...");
            operator.stop();
            client.close();
        }));
    }
}
