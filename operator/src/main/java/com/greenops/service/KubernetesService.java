package com.greenops.service;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;

public final class KubernetesService {
    private KubernetesService() {}

    public static KubernetesClient newClient() {
        return new KubernetesClientBuilder().build();
    }
}
