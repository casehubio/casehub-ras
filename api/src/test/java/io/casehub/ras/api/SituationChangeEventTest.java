package io.casehub.ras.api;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class SituationChangeEventTest {

    @Test
    void constructor_includes_context() {
        var context = new SituationContext("sit", "key", "tenant",
                Instant.now(), Instant.now(), List.of(), OptionalLong.empty(), null, 0);
        var event = new SituationChangeEvent("tenant", "sit", "key",
                SituationChangeEvent.ChangeType.TRIGGERED, context);
        assertThat(event.context()).isSameAs(context);
    }

    @Test
    void constructor_rejects_null_context() {
        assertThatNullPointerException()
                .isThrownBy(() -> new SituationChangeEvent("t", "s", "k",
                        SituationChangeEvent.ChangeType.TRIGGERED, null))
                .withMessage("context");
    }

    @Test
    void constructor_rejects_null_tenancyId() {
        var context = new SituationContext("sit", "key", "tenant",
                Instant.now(), Instant.now(), List.of(), OptionalLong.empty(), null, 0);
        assertThatNullPointerException()
                .isThrownBy(() -> new SituationChangeEvent(null, "s", "k",
                        SituationChangeEvent.ChangeType.TRIGGERED, context))
                .withMessage("tenancyId");
    }

    @Test
    void constructor_rejects_null_situationId() {
        var context = new SituationContext("sit", "key", "tenant",
                Instant.now(), Instant.now(), List.of(), OptionalLong.empty(), null, 0);
        assertThatNullPointerException()
                .isThrownBy(() -> new SituationChangeEvent("t", null, "k",
                        SituationChangeEvent.ChangeType.TRIGGERED, context))
                .withMessage("situationId");
    }

    @Test
    void constructor_rejects_null_correlationKey() {
        var context = new SituationContext("sit", "key", "tenant",
                Instant.now(), Instant.now(), List.of(), OptionalLong.empty(), null, 0);
        assertThatNullPointerException()
                .isThrownBy(() -> new SituationChangeEvent("t", "s", null,
                        SituationChangeEvent.ChangeType.TRIGGERED, context))
                .withMessage("correlationKey");
    }

    @Test
    void constructor_rejects_null_changeType() {
        var context = new SituationContext("sit", "key", "tenant",
                Instant.now(), Instant.now(), List.of(), OptionalLong.empty(), null, 0);
        assertThatNullPointerException()
                .isThrownBy(() -> new SituationChangeEvent("t", "s", "k",
                        null, context))
                .withMessage("changeType");
    }
}
