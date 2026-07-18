package io.casehub.ras.api;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class SituationRegistrationTest {

    private static final SituationDefinition DEF = new SituationDefinition(
            "sit-1", Set.of("test.event"), Duration.ofMinutes(5), null,
            new ChainMode.Or(Set.of("g1")),
            new TriggerAction.CreateCase(new CaseTriggerConfig("ns", "name", "1.0", Map.of())), null);

    @Test
    void convenienceConstructorUsesDefaultExtractor() {
        var reg = new SituationRegistration(DEF);
        assertThat(reg.correlationKeyExtractor())
                .isSameAs(DefaultCorrelationKeyExtractor.INSTANCE);
    }

    @Test
    void nullExtractorDefaultsToDefault() {
        var reg = new SituationRegistration(DEF, null);
        assertThat(reg.correlationKeyExtractor())
                .isSameAs(DefaultCorrelationKeyExtractor.INSTANCE);
    }

    @Test
    void customExtractorIsUsed() {
        CorrelationKeyExtractor custom = event -> "custom-key";
        var reg = new SituationRegistration(DEF, custom);
        assertThat(reg.correlationKeyExtractor()).isSameAs(custom);
    }

    @Test
    void nullDefinitionIsRejected() {
        assertThatNullPointerException()
                .isThrownBy(() -> new SituationRegistration(null))
                .withMessage("definition");
    }

    @Test
    void fourArgConstructorSetsFilterAndDynamicData() {
        EventFilter filter = event -> true;
        var reg = new SituationRegistration(DEF, DefaultCorrelationKeyExtractor.INSTANCE,
                                            filter, Map.of());
        assertThat(reg.eventFilter()).isSameAs(filter);
        assertThat(reg.compiledDynamicData()).isEmpty();
    }

    @Test
    void twoArgConstructorDefaultsFilterAndDynamicDataToNull() {
        var reg = new SituationRegistration(DEF, DefaultCorrelationKeyExtractor.INSTANCE);
        assertThat(reg.eventFilter()).isNull();
        assertThat(reg.compiledDynamicData()).isNull();
    }

    @Test
    void singleArgConstructorDefaultsAll() {
        var reg = new SituationRegistration(DEF);
        assertThat(reg.correlationKeyExtractor()).isSameAs(DefaultCorrelationKeyExtractor.INSTANCE);
        assertThat(reg.eventFilter()).isNull();
        assertThat(reg.compiledDynamicData()).isNull();
    }
}
