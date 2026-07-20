package io.casehub.ras.api;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class PolicyDecisionTest {

    @Test
    void convenienceConstructor_emptyMetadata() {
        var pd = new PolicyDecision(TriggerDecision.TRIGGER);
        assertThat(pd.decision()).isEqualTo(TriggerDecision.TRIGGER);
        assertThat(pd.metadata()).isEmpty();
    }

    @Test
    void fullConstructor_preservesMetadata() {
        var meta = Map.<String, Object>of("key", "value");
        var pd = new PolicyDecision(TriggerDecision.SUPPRESS, meta);
        assertThat(pd.decision()).isEqualTo(TriggerDecision.SUPPRESS);
        assertThat(pd.metadata()).containsEntry("key", "value");
    }

    @Test
    void metadata_isImmutableCopy() {
        var mutable = new java.util.HashMap<String, Object>();
        mutable.put("key", "value");
        var pd = new PolicyDecision(TriggerDecision.TRIGGER, mutable);
        mutable.put("extra", "should-not-appear");
        assertThat(pd.metadata()).doesNotContainKey("extra");
    }

    @Test
    void nullDecision_throws() {
        assertThatNullPointerException()
                .isThrownBy(() -> new PolicyDecision(null, Map.of()))
                .withMessage("decision");
    }

    @Test
    void nullMetadata_throws() {
        assertThatNullPointerException()
                .isThrownBy(() -> new PolicyDecision(TriggerDecision.TRIGGER, null))
                .withMessage("metadata");
    }
}
