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
                Set.of("iot.temperature"), Duration.ofMinutes(10), CHAIN, TRIGGER);

        assertThat(def.situationId()).isEqualTo("equipment-failure");
        assertThat(def.eventTypes()).containsExactly("iot.temperature");
        assertThat(def.correlationWindow()).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void nullCorrelationWindowMeansPersistent() {
        var def = new SituationDefinition("persistent-sit",
                Set.of("iot.temperature"), null, CHAIN, TRIGGER);
        assertThat(def.correlationWindow()).isNull();
    }

    @Test
    void emptyEventTypesRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SituationDefinition("sit-1",
                        Set.of(), null, CHAIN, TRIGGER))
                .withMessageContaining("must not be empty");
    }

    @Test
    void nullEventTypesRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SituationDefinition("sit-1",
                        null, null, CHAIN, TRIGGER))
                .withMessageContaining("must not be empty");
    }

    @Test
    void zeroCorrelationWindowRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SituationDefinition("sit-1",
                        Set.of("type"), Duration.ZERO, CHAIN, TRIGGER))
                .withMessageContaining("positive");
    }

    @Test
    void negativeCorrelationWindowRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SituationDefinition("sit-1",
                        Set.of("type"), Duration.ofMinutes(-5), CHAIN, TRIGGER))
                .withMessageContaining("positive");
    }

    @Test
    void nullChainModeRejected() {
        assertThatNullPointerException()
                .isThrownBy(() -> new SituationDefinition("sit-1",
                        Set.of("type"), null, null, TRIGGER))
                .withMessage("chainMode");
    }

    @Test
    void nullTriggerConfigRejected() {
        assertThatNullPointerException()
                .isThrownBy(() -> new SituationDefinition("sit-1",
                        Set.of("type"), null, CHAIN, null))
                .withMessage("triggerConfig");
    }
}
