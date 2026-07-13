package io.casehub.ras.runtime;

import io.casehub.api.engine.CaseHub;
import io.casehub.api.model.CaseDefinition;
import io.casehub.ras.api.CaseInputContributor;
import io.casehub.ras.api.CaseTriggerConfig;
import io.casehub.ras.api.SituationContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

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
        var trigger = new DefaultCaseTrigger(List.of(hub), List.of());
        var config = new CaseTriggerConfig("ns", "case-name", "1.0", Map.of());
        var ctx = SituationContext.initial("sit-1", "key-1", "tenant-a", T1);

        var caseId = trigger.fire(config, ctx).await().indefinitely();

        assertThat(caseId).isNotNull();
    }

    @Test
    void throwsWhenNoCaseHubMatches() {
        var hub = stubCaseHub("ns", "other-case", "1.0");
        var trigger = new DefaultCaseTrigger(List.of(hub), List.of());
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
        var trigger = new DefaultCaseTrigger(List.of(hub1, hub2), List.of());
        var config = new CaseTriggerConfig("ns", "case-name", "1.0", Map.of());
        var ctx = SituationContext.initial("sit-1", "key-1", "tenant-a", T1);

        assertThatIllegalStateException()
                .isThrownBy(() -> trigger.fire(config, ctx).await().indefinitely())
                .withMessageContaining("Multiple CaseHub");
    }

    @Test
    @SuppressWarnings("unchecked")
    void contributorDataMergedIntoCaseInput() {
        var capturedInput = new java.util.concurrent.atomic.AtomicReference<Map<String, Object>>();
        CaseHub hub = new CaseHub() {
            private final CaseDefinition def = new CaseDefinition("ns", "case-name", "1.0");

            @Override
            public CaseDefinition getDefinition() {return def;}

            @Override
            public CompletionStage<UUID> startCase(Object inputData) {
                capturedInput.set((Map<String, Object>) inputData);
                return CompletableFuture.completedFuture(UUID.randomUUID());
            }
        };

        CaseInputContributor contributor = (config, context) ->
                                                   Map.of("deviceId", "sensor-1", "deviceClass", "thermostat");

        var trigger = new DefaultCaseTrigger(List.of(hub), List.of(contributor));
        var config  = new CaseTriggerConfig("ns", "case-name", "1.0", Map.of());
        var ctx     = SituationContext.initial("sit-1", "device/sensor-1", "tenant-a", T1);

        trigger.fire(config, ctx).await().indefinitely();

        var input = capturedInput.get();
        assertThat(input).isNotNull();
        assertThat(input.get("deviceId")).isEqualTo("sensor-1");
        assertThat(input.get("deviceClass")).isEqualTo("thermostat");
        assertThat(input.get("situationId")).isEqualTo("sit-1");
        assertThat(input.get("correlationKey")).isEqualTo("device/sensor-1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void multipleContributorsMerge() {
        var capturedInput = new java.util.concurrent.atomic.AtomicReference<Map<String, Object>>();
        CaseHub hub = new CaseHub() {
            private final CaseDefinition def = new CaseDefinition("ns", "case-name", "1.0");

            @Override
            public CaseDefinition getDefinition() {return def;}

            @Override
            public CompletionStage<UUID> startCase(Object inputData) {
                capturedInput.set((Map<String, Object>) inputData);
                return CompletableFuture.completedFuture(UUID.randomUUID());
            }
        };

        CaseInputContributor c1 = (config, context) -> Map.of("field1", "value1");
        CaseInputContributor c2 = (config, context) -> Map.of("field2", "value2");

        var trigger = new DefaultCaseTrigger(List.of(hub), List.of(c1, c2));
        var config  = new CaseTriggerConfig("ns", "case-name", "1.0", Map.of());
        var ctx     = SituationContext.initial("sit-1", "key-1", "tenant-a", T1);

        trigger.fire(config, ctx).await().indefinitely();

        var input = capturedInput.get();
        assertThat(input.get("field1")).isEqualTo("value1");
        assertThat(input.get("field2")).isEqualTo("value2");
    }

    @Test
    @SuppressWarnings("unchecked")
    void contributorCanOverrideBaseCaseData() {
        var capturedInput = new java.util.concurrent.atomic.AtomicReference<Map<String, Object>>();
        CaseHub hub = new CaseHub() {
            private final CaseDefinition def = new CaseDefinition("ns", "case-name", "1.0");

            @Override
            public CaseDefinition getDefinition() {return def;}

            @Override
            public CompletionStage<UUID> startCase(Object inputData) {
                capturedInput.set((Map<String, Object>) inputData);
                return CompletableFuture.completedFuture(UUID.randomUUID());
            }
        };

        CaseInputContributor contributor = (config, context) ->
                                                   Map.of("overridden", "dynamic-value");

        var trigger = new DefaultCaseTrigger(List.of(hub), List.of(contributor));
        var config = new CaseTriggerConfig("ns", "case-name", "1.0",
                                           Map.of("overridden", "static-value"));
        var ctx = SituationContext.initial("sit-1", "key-1", "tenant-a", T1);

        trigger.fire(config, ctx).await().indefinitely();

        assertThat(capturedInput.get().get("overridden")).isEqualTo("dynamic-value");
    }

    @Test
    @SuppressWarnings("unchecked")
    void noContributorsPreservesExistingBehavior() {
        var capturedInput = new java.util.concurrent.atomic.AtomicReference<Map<String, Object>>();
        CaseHub hub = new CaseHub() {
            private final CaseDefinition def = new CaseDefinition("ns", "case-name", "1.0");

            @Override
            public CaseDefinition getDefinition() {return def;}

            @Override
            public CompletionStage<UUID> startCase(Object inputData) {
                capturedInput.set((Map<String, Object>) inputData);
                return CompletableFuture.completedFuture(UUID.randomUUID());
            }
        };

        var trigger = new DefaultCaseTrigger(List.of(hub), List.of());
        var config = new CaseTriggerConfig("ns", "case-name", "1.0",
                                           Map.of("key", "value"));
        var ctx = SituationContext.initial("sit-1", "key-1", "tenant-a", T1);

        trigger.fire(config, ctx).await().indefinitely();

        var input = capturedInput.get();
        assertThat(input.get("key")).isEqualTo("value");
        assertThat(input.get("situationId")).isEqualTo("sit-1");
        assertThat(input.get("correlationKey")).isEqualTo("key-1");
        assertThat(input.get("tenancyId")).isEqualTo("tenant-a");
        assertThat(input).containsKey("detections");
    }


}
