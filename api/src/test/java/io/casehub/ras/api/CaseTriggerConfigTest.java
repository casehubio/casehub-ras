package io.casehub.ras.api;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class CaseTriggerConfigTest {

    @Test
    void validConfigIsCreated() {
        var config = new CaseTriggerConfig("io.casehub", "maintenance", "1.0",
                Map.of("priority", "HIGH"));

        assertThat(config.caseNamespace()).isEqualTo("io.casehub");
        assertThat(config.caseName()).isEqualTo("maintenance");
        assertThat(config.caseVersion()).isEqualTo("1.0");
        assertThat(config.baseCaseData()).containsEntry("priority", "HIGH");
    }

    @Test
    void nullNamespaceIsRejected() {
        assertThatNullPointerException()
                .isThrownBy(() -> new CaseTriggerConfig(null, "name", "1.0", Map.of()))
                .withMessage("caseNamespace");
    }

    @Test
    void nullNameIsRejected() {
        assertThatNullPointerException()
                .isThrownBy(() -> new CaseTriggerConfig("ns", null, "1.0", Map.of()))
                .withMessage("caseName");
    }

    @Test
    void nullVersionIsRejected() {
        assertThatNullPointerException()
                .isThrownBy(() -> new CaseTriggerConfig("ns", "name", null, Map.of()))
                .withMessage("caseVersion");
    }

    @Test
    void nullBaseCaseDataNormalisedToEmptyMap() {
        var config = new CaseTriggerConfig("ns", "name", "1.0", null);
        assertThat(config.baseCaseData()).isNotNull().isEmpty();
    }
}
