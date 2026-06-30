package io.casehub.ras.api;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

class SituationDefinitionTest {

    private static final CaseTriggerConfig TRIGGER = new CaseTriggerConfig(
            "io.casehub", "maintenance", "1.0", Map.of());
    private static final ChainMode CHAIN = new ChainMode.Or(Set.of("g1"));

    @Test
    void validDefinitionIsCreated() {
        var def = new SituationDefinition("equipment-failure",
                Set.of("iot.temperature"), Duration.ofMinutes(10), null, CHAIN, TRIGGER, null);

        assertThat(def.situationId()).isEqualTo("equipment-failure");
        assertThat(def.eventTypes()).containsExactly("iot.temperature");
        assertThat(def.correlationWindow()).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void nullCorrelationWindowMeansPersistent() {
        var def = new SituationDefinition("persistent-sit",
                Set.of("iot.temperature"), null, null, CHAIN, TRIGGER, null);
        assertThat(def.correlationWindow()).isNull();
    }

    @Test
    void emptyEventTypesRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SituationDefinition("sit-1",
                        Set.of(), null, null, CHAIN, TRIGGER, null))
                .withMessageContaining("must not be empty");
    }

    @Test
    void nullEventTypesRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SituationDefinition("sit-1",
                        null, null, null, CHAIN, TRIGGER, null))
                .withMessageContaining("must not be empty");
    }

    @Test
    void zeroCorrelationWindowRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SituationDefinition("sit-1",
                        Set.of("type"), Duration.ZERO, null, CHAIN, TRIGGER, null))
                .withMessageContaining("positive");
    }

    @Test
    void negativeCorrelationWindowRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SituationDefinition("sit-1",
                        Set.of("type"), Duration.ofMinutes(-5), null, CHAIN, TRIGGER, null))
                .withMessageContaining("positive");
    }

    @Test
    void nullChainModeRejected() {
        assertThatNullPointerException()
                .isThrownBy(() -> new SituationDefinition("sit-1",
                        Set.of("type"), null, null, null, TRIGGER, null))
                .withMessage("chainMode");
    }

    @Test
    void nullTriggerConfigRejected() {
        assertThatNullPointerException()
                .isThrownBy(() -> new SituationDefinition("sit-1",
                        Set.of("type"), null, null, CHAIN, null, null))
                .withMessage("triggerConfig");
    }

    @Test
    void nullEventBufferDelayIsAllowed() {
        var def = new SituationDefinition("sit-1",
                Set.of("type"), null, null, CHAIN, TRIGGER, null);
        assertThat(def.eventBufferDelay()).isNull();
    }

    @Test
    void validEventBufferDelay() {
        var def = new SituationDefinition("sit-1",
                Set.of("type"), null, Duration.ofSeconds(5), CHAIN, TRIGGER, null);
        assertThat(def.eventBufferDelay()).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void zeroEventBufferDelayRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SituationDefinition("sit-1",
                        Set.of("type"), null, Duration.ZERO, CHAIN, TRIGGER, null))
                .withMessageContaining("positive");
    }

    @Test
    void negativeEventBufferDelayRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SituationDefinition("sit-1",
                        Set.of("type"), null, Duration.ofSeconds(-1), CHAIN, TRIGGER, null))
                .withMessageContaining("positive");
    }

    @Test
    void nullTriggerModeDefaultsToFireOnce() {
        var def = new SituationDefinition("sit-1", Set.of("e"),
                Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")), TRIGGER, null);
        assertThat(def.triggerMode()).isInstanceOf(TriggerMode.FireOnce.class);
    }

    @Test
    void explicitTriggerModeIsPreserved() {
        var mode = new TriggerMode.Repeating(Duration.ofMinutes(5));
        var def = new SituationDefinition("sit-1", Set.of("e"),
                Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")), TRIGGER, mode);
        assertThat(def.triggerMode()).isEqualTo(mode);
    }
}
