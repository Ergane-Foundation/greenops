package com.solstice;

import com.solstice.controller.SolsticeReconciler;
import com.solstice.metrics.SolsticeMetrics;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.javaoperatorsdk.operator.Operator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SolsticeOperator {

    private static final Logger log = LoggerFactory.getLogger(SolsticeOperator.class);

    public static void main(String[] args) {
        log.info("Starting Solstice operator...");

        int metricsPort = Integer.parseInt(System.getenv().getOrDefault("METRICS_PORT", "9400"));
        SolsticeMetrics.start(metricsPort);

        KubernetesClient client = new KubernetesClientBuilder().build();
        Operator operator = new Operator(o -> o.withKubernetesClient(client));
        operator.register(new SolsticeReconciler(client));
        operator.start();

        log.info("Solstice operator started. Watching SolsticeController resources.");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down operator...");
            operator.stop();
            SolsticeMetrics.stop();
            client.close();
        }));
    }
}
