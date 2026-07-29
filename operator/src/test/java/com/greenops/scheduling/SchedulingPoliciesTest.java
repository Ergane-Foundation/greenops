package com.greenops.scheduling;

import com.greenops.model.GreenOpsSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class SchedulingPoliciesTest {

    private GreenOpsSpec specWith(String policy) {
        GreenOpsSpec spec = new GreenOpsSpec();
        spec.setSchedulingPolicy(policy);
        return spec;
    }

    @Test
    void defaultsToTheThresholdPolicy() {
        assertEquals("threshold", SchedulingPolicies.fromSpec(new GreenOpsSpec()).name());
    }

    @Test
    void selectsTheForecastPolicyByName() {
        SchedulingPolicy policy = SchedulingPolicies.fromSpec(specWith("forecast"));

        assertEquals("forecast", policy.name());
        assertInstanceOf(ForecastAwarePolicy.class, policy);
    }

    @Test
    void nameMatchingIgnoresCaseAndSpacing() {
        assertEquals("forecast", SchedulingPolicies.fromSpec(specWith("  Forecast ")).name());
    }

    @Test
    @DisplayName("an unrecognised policy falls back rather than failing the reconcile")
    void unknownPolicyFallsBack() {
        assertEquals("threshold", SchedulingPolicies.fromSpec(specWith("magic")).name());
    }

    @Test
    @DisplayName("the optimising policy is named but not built yet, so it falls back for now")
    void optimisingIsNotAvailableYet() {
        assertEquals("threshold", SchedulingPolicies.fromSpec(specWith("optimising")).name());
    }

    @Test
    void theBreakEvenMultiplierReachesTheCostModel() {
        GreenOpsSpec spec = specWith("forecast");
        spec.setBreakEvenMultiplier(3.0);
        spec.setAssumedSavepointSeconds(60);
        spec.setAssumedRestartSeconds(120);

        assertEquals(540,
                SchedulingPolicies.costModelFor(spec)
                        .costOf(com.greenops.cost.JobFeatures.unknown("x"))
                        .breakEvenWindow(3.0).toSeconds());
    }
}
