package io.casehub.ras.runtime;

import io.casehub.ras.api.*;
import io.casehub.api.engine.CaseHub;
import io.casehub.api.model.CaseDefinition;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import static org.assertj.core.api.Assertions.*;

class DefaultCaseTriggerTest {

    private static final Instant T1 = Instant.parse("2026-06-25T10:00:00Z");

    private CaseHub stubCaseHub(String namespace, String name, String version) {
        return new CaseHub() {
            private final CaseDefinition def = new CaseDefinition(namespace, name, version);
            @Override
            public CaseDefinition getDefinition() { return def; }
            @Override
            public CompletionStage<UUID> startCase(Object inputData) {
                return CompletableFuture.completedFuture(UUID.randomUUID());
            }
        };
    }

    @Test
    void firesMatchingCaseHub() {
        var hub = stubCaseHub("ns", "case-name", "1.0");
        var trigger = new DefaultCaseTrigger(List.of(hub));
        var config = new CaseTriggerConfig("ns", "case-name", "1.0", Map.of());
        var ctx = SituationContext.initial("sit-1", "key-1", "tenant-a", T1);

        var caseId = trigger.fire(config, ctx).await().indefinitely();

        assertThat(caseId).isNotNull();
    }

    @Test
    void throwsWhenNoCaseHubMatches() {
        var hub = stubCaseHub("ns", "other-case", "1.0");
        var trigger = new DefaultCaseTrigger(List.of(hub));
        var config = new CaseTriggerConfig("ns", "case-name", "1.0", Map.of());
        var ctx = SituationContext.initial("sit-1", "key-1", "tenant-a", T1);

        assertThatIllegalStateException()
                .isThrownBy(() -> trigger.fire(config, ctx).await().indefinitely())
                .withMessageContaining("No CaseHub");
    }

    @Test
    void throwsWhenMultipleCaseHubsMatch() {
        var hub1 = stubCaseHub("ns", "case-name", "1.0");
        var hub2 = stubCaseHub("ns", "case-name", "1.0");
        var trigger = new DefaultCaseTrigger(List.of(hub1, hub2));
        var config = new CaseTriggerConfig("ns", "case-name", "1.0", Map.of());
        var ctx = SituationContext.initial("sit-1", "key-1", "tenant-a", T1);

        assertThatIllegalStateException()
                .isThrownBy(() -> trigger.fire(config, ctx).await().indefinitely())
                .withMessageContaining("Multiple CaseHub");
    }
}
