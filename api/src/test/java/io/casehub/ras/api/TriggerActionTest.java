package io.casehub.ras.api;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class TriggerActionTest {

    @Test
    void createCase_wraps_config() {
        var config = new CaseTriggerConfig("ns", "case", "1.0", Map.of());
        var action = new TriggerAction.CreateCase(config);
        assertThat(action.config()).isSameAs(config);
    }

    @Test
    void createCase_rejects_null_config() {
        assertThatNullPointerException()
                .isThrownBy(() -> new TriggerAction.CreateCase(null))
                .withMessage("config");
    }

    @Test
    void notifyOnly_is_a_triggerAction() {
        TriggerAction action = new TriggerAction.NotifyOnly();
        assertThat(action).isInstanceOf(TriggerAction.class);
    }

    @Test
    void sealed_permits_exhaustive_switch() {
        TriggerAction action = new TriggerAction.NotifyOnly();
        String result = switch (action) {
            case TriggerAction.CreateCase c -> "create:" + c.config().caseName();
            case TriggerAction.NotifyOnly n -> "notify";
        };
        assertThat(result).isEqualTo("notify");
    }
}
