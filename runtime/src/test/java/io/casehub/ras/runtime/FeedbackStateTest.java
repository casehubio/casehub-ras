package io.casehub.ras.runtime;

import org.junit.jupiter.api.Test;

import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.*;

class FeedbackStateTest {

    private final FeedbackState state = new FeedbackState();

    @Test void effectiveThresholdEmptyByDefault() {
        assertTrue(state.effectiveThreshold("sit-1", "t1").isEmpty());
    }

    @Test void applyAndRetrieveThreshold() {
        state.applyThresholdOverride("sit-1", "t1", 0.8);
        OptionalDouble result = state.effectiveThreshold("sit-1", "t1");
        assertTrue(result.isPresent());
        assertEquals(0.8, result.getAsDouble(), 0.001);
    }

    @Test void thresholdTenantIsolation() {
        state.applyThresholdOverride("sit-1", "tenantA", 0.8);
        state.applyThresholdOverride("sit-1", "tenantB", 0.3);
        assertEquals(0.8, state.effectiveThreshold("sit-1", "tenantA").getAsDouble(), 0.001);
        assertEquals(0.3, state.effectiveThreshold("sit-1", "tenantB").getAsDouble(), 0.001);
    }

    @Test void rejectsNaNThreshold() {
        state.applyThresholdOverride("sit-1", "t1", Double.NaN);
        assertTrue(state.effectiveThreshold("sit-1", "t1").isEmpty());
    }

    @Test void rejectsZeroThreshold() {
        state.applyThresholdOverride("sit-1", "t1", 0.0);
        assertTrue(state.effectiveThreshold("sit-1", "t1").isEmpty());
    }

    @Test void rejectsNegativeThreshold() {
        state.applyThresholdOverride("sit-1", "t1", -0.1);
        assertTrue(state.effectiveThreshold("sit-1", "t1").isEmpty());
    }

    @Test void rejectsOverOneThreshold() {
        state.applyThresholdOverride("sit-1", "t1", 1.1);
        assertTrue(state.effectiveThreshold("sit-1", "t1").isEmpty());
    }

    @Test void acceptsExactlyOneThreshold() {
        state.applyThresholdOverride("sit-1", "t1", 1.0);
        assertTrue(state.effectiveThreshold("sit-1", "t1").isPresent());
    }

    @Test void adjustedLogPriorsEmptyByDefault() {
        assertTrue(state.adjustedLogPriors("g1", "t1").isEmpty());
    }

    @Test void applyAndRetrieveLogPriors() {
        state.applyPriorOverride("g1", "t1", new double[]{0.3, 0.7});
        var result = state.adjustedLogPriors("g1", "t1");
        assertTrue(result.isPresent());
        assertEquals(Math.log(0.3), result.get()[0], 0.001);
        assertEquals(Math.log(0.7), result.get()[1], 0.001);
    }

    @Test void priorsTenantIsolation() {
        state.applyPriorOverride("g1", "tenantA", new double[]{0.2, 0.8});
        state.applyPriorOverride("g1", "tenantB", new double[]{0.5, 0.5});
        assertEquals(Math.log(0.2), state.adjustedLogPriors("g1", "tenantA").get()[0], 0.001);
        assertEquals(Math.log(0.5), state.adjustedLogPriors("g1", "tenantB").get()[0], 0.001);
    }

    @Test void rejectsZeroPrior() {
        state.applyPriorOverride("g1", "t1", new double[]{0.0, 1.0});
        assertTrue(state.adjustedLogPriors("g1", "t1").isEmpty());
    }

    @Test void rejectsNegativePrior() {
        state.applyPriorOverride("g1", "t1", new double[]{-0.1, 1.1});
        assertTrue(state.adjustedLogPriors("g1", "t1").isEmpty());
    }

    @Test void rejectsNaNPrior() {
        state.applyPriorOverride("g1", "t1", new double[]{Double.NaN, 0.5});
        assertTrue(state.adjustedLogPriors("g1", "t1").isEmpty());
    }

    @Test void currentRawPriorsFallsBackToBase() {
        double[] base = {0.1, 0.9};
        assertArrayEquals(base, state.currentRawPriors("g1", "t1", base));
    }

    @Test void currentRawPriorsReturnsAdjusted() {
        state.applyPriorOverride("g1", "t1", new double[]{0.3, 0.7});
        double[] result = state.currentRawPriors("g1", "t1", new double[]{0.1, 0.9});
        assertEquals(0.3, result[0], 0.01);
        assertEquals(0.7, result[1], 0.01);
    }
}
