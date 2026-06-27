package io.casehub.ras.runtime;

import io.casehub.ras.api.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

class SituationRegistrationTest {

    private static final SituationDefinition DEF = new SituationDefinition(
            "sit-1", Set.of("test.event"), Duration.ofMinutes(5), null,
            new ChainMode.Or(Set.of("g1")),
            new CaseTriggerConfig("ns", "name", "1.0", Map.of()));

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
}
