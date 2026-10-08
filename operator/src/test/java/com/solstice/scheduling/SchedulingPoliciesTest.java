package com.solstice.scheduling;

import com.solstice.model.SolsticeSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchedulingPoliciesTest {

    private SolsticeSpec specWith(String policy) {
        SolsticeSpec spec = new SolsticeSpec();
        spec.setSchedulingPolicy(policy);
        return spec;
    }

    @Test
    void defaultsToTheThresholdPolicy() {
        assertEquals("threshold", SchedulingPolicies.fromSpec(new SolsticeSpec()).name());
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
    @DisplayName("the optimising policy plans across the fleet rather than per job")
    void optimisingIsRecognised() {
        assertTrue(SchedulingPolicies.isOptimising(specWith("optimising")));
        assertFalse(SchedulingPolicies.isOptimising(specWith("forecast")));
        assertFalse(SchedulingPolicies.isOptimising(new SolsticeSpec()));
    }

    @Test
    @DisplayName("the optimiser falls back to the forecast policy for each individual job")
    void optimisingUsesForecastPerJob() {
        assertEquals("forecast", SchedulingPolicies.fromSpec(specWith("optimising")).name());
    }

    @Test
    void theBreakEvenMultiplierReachesTheCostModel() {
        SolsticeSpec spec = specWith("forecast");
        spec.setBreakEvenMultiplier(3.0);
        spec.setAssumedSavepointSeconds(60);
        spec.setAssumedRestartSeconds(120);

        assertEquals(540,
                SchedulingPolicies.costModelFor(spec)
                        .costOf(com.solstice.cost.JobFeatures.unknown("x"))
                        .breakEvenWindow(3.0).toSeconds());
    }
}
