package io.casehub.ras.testing;

import io.casehub.ras.api.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class MockCaseTriggerTest {

    private static final Instant T1 = Instant.parse("2026-06-25T10:00:00Z");

    @Test
    void fireRecordsCaseAndReturnsId() {
        var trigger = new MockCaseTrigger();
        var config = new CaseTriggerConfig("ns", "case-name", "1.0", Map.of());
        var ctx = SituationContext.initial("sit-1", "key-1", "tenant-a", T1);

        var caseId = trigger.fire(config, ctx);

        assertThat(caseId).isNotNull();
        assertThat(trigger.firedCases()).hasSize(1);
        var fired = trigger.firedCases().getFirst();
        assertThat(fired.caseId()).isEqualTo(caseId);
        assertThat(fired.triggerConfig()).isEqualTo(config);
        assertThat(fired.context()).isEqualTo(ctx);
    }

    @Test
    void resetClearsFiredCases() {
        var trigger = new MockCaseTrigger();
        var config = new CaseTriggerConfig("ns", "name", "1.0", Map.of());
        var ctx = SituationContext.initial("sit-1", "key-1", "tenant-a", T1);
        trigger.fire(config, ctx);

        trigger.reset();

        assertThat(trigger.firedCases()).isEmpty();
    }

    @Test
    void multipleFiresAccumulate() {
        var trigger = new MockCaseTrigger();
        var config = new CaseTriggerConfig("ns", "name", "1.0", Map.of());
        var ctx = SituationContext.initial("sit-1", "key-1", "tenant-a", T1);
        trigger.fire(config, ctx);
        trigger.fire(config, ctx);

        assertThat(trigger.firedCases()).hasSize(2);
    }
}
