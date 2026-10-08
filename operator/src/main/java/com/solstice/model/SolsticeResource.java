package com.solstice.model;

import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Kind;
import io.fabric8.kubernetes.model.annotation.Plural;
import io.fabric8.kubernetes.model.annotation.Singular;
import io.fabric8.kubernetes.model.annotation.Version;

@Group("solstice.io")
@Version("v1")
@Kind("SolsticeController")
@Plural("solsticecontrollers")
@Singular("solsticecontroller")
public class SolsticeResource extends CustomResource<SolsticeSpec, SolsticeStatus> implements Namespaced {
}
