package com.greenops.model;

import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Kind;
import io.fabric8.kubernetes.model.annotation.Plural;
import io.fabric8.kubernetes.model.annotation.Singular;
import io.fabric8.kubernetes.model.annotation.Version;

@Group("greenops.io")
@Version("v1")
@Kind("GreenOpsController")
@Plural("greenopscontrollers")
@Singular("greenopscontroller")
public class GreenOpsResource extends CustomResource<GreenOpsSpec, GreenOpsStatus> implements Namespaced {
}
