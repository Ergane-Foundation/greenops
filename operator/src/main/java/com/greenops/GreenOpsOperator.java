package com.greenops;

import com.greenops.controller.GreenOpsReconciler;
import com.greenops.metrics.GreenOpsMetrics;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.javaoperatorsdk.operator.Operator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GreenOpsOperator {

    private static final Logger log = LoggerFactory.getLogger(GreenOpsOperator.class);

    public static void main(String[] args) {
        log.info("Starting GreenOps operator...");

        int metricsPort = Integer.parseInt(System.getenv().getOrDefault("METRICS_PORT", "9400"));
        GreenOpsMetrics.start(metricsPort);

        KubernetesClient client = new KubernetesClientBuilder().build();
        Operator operator = new Operator(o -> o.withKubernetesClient(client));
        operator.register(new GreenOpsReconciler(client));
        operator.start();

        log.info("GreenOps operator started. Watching GreenOpsController resources.");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down operator...");
            operator.stop();
            GreenOpsMetrics.stop();
            client.close();
        }));
    }
}
