package io.casehub.ras.runtime;

import io.casehub.api.engine.CaseHub;
import io.casehub.api.model.CaseDefinition;
import io.casehub.platform.api.expression.CompiledExpression;
import io.casehub.ras.api.*;
import io.casehub.ras.testing.FixedDetectionResult;
import io.casehub.ras.testing.MockGanglion;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

        var caseId = trigger.fire(config, ctx);

        assertThat(caseId).isNotNull();
    }

    @Test
    void throwsWhenNoCaseHubMatches() {
        var hub = stubCaseHub("ns", "other-case", "1.0");
        var trigger = new DefaultCaseTrigger(List.of(hub), List.of());
        var config = new CaseTriggerConfig("ns", "case-name", "1.0", Map.of());
        var ctx = SituationContext.initial("sit-1", "key-1", "tenant-a", T1);

        assertThatIllegalStateException()
                .isThrownBy(() -> trigger.fire(config, ctx))
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
                .isThrownBy(() -> trigger.fire(config, ctx))
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

        trigger.fire(config, ctx);

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

        trigger.fire(config, ctx);

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

        trigger.fire(config, ctx);

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

        trigger.fire(config, ctx);

        var input = capturedInput.get();
        assertThat(input.get("key")).isEqualTo("value");
        assertThat(input.get("situationId")).isEqualTo("sit-1");
        assertThat(input.get("correlationKey")).isEqualTo("key-1");
        assertThat(input.get("tenancyId")).isEqualTo("tenant-a");
        assertThat(input).containsKey("detections");
    }


    @SuppressWarnings("unchecked")
    private CaseHub capturingCaseHub(String ns, String name, String ver,
                                     java.util.concurrent.atomic.AtomicReference<Map<String, Object>> capture) {
        return new CaseHub() {
            private final CaseDefinition def = new CaseDefinition(ns, name, ver);

            @Override
            public CaseDefinition getDefinition() {return def;}

            @Override
            public CompletionStage<UUID> startCase(Object inputData) {
                capture.set((Map<String, Object>) inputData);
                return CompletableFuture.completedFuture(UUID.randomUUID());
            }
        };
    }

    @Test
    @SuppressWarnings("unchecked")
    void dynamicCaseDataMergedIntoCaseInput() {
        var capture = new java.util.concurrent.atomic.AtomicReference<Map<String, Object>>();
        var hub     = capturingCaseHub("ns", "case-name", "1.0", capture);

        var ganglion = new MockGanglion("g1", Set.of("e"), FixedDetectionResult.detected("g1", 0.9));
        CompiledExpression<Map, Object> corrKeyExpr = new CompiledExpression<>() {
            @Override
            public String type()            {return "test";}

            @Override
            public Object eval(Map context) {return context.get("correlationKey");}
        };
        var compiledDynamic = Map.<String, CompiledExpression<Map, Object>>of("extractedKey", corrKeyExpr);

        var def = new SituationDefinition("sit-1", Set.of("e"),
                                          Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")),
                                          new TriggerAction.CreateCase(new CaseTriggerConfig("ns", "case-name", "1.0", Map.of())),
                                          null);
        var reg = new SituationRegistration(def, DefaultCorrelationKeyExtractor.INSTANCE,
                                            null, compiledDynamic);
        var registry = new SituationDefinitionRegistry(
                List.of(() -> List.of(reg)), List.of(ganglion));

        var trigger = new DefaultCaseTrigger(List.of(hub), List.of(), registry);
        var ctx     = SituationContext.initial("sit-1", "order-123", "tenant-a", T1);

        trigger.fire(new CaseTriggerConfig("ns", "case-name", "1.0", Map.of()), ctx)
               ;

        var input = capture.get();
        assertThat(input).isNotNull();
        assertThat(input.get("extractedKey")).isEqualTo("order-123");
        assertThat(input.get("situationId")).isEqualTo("sit-1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void dynamicExpressionErrorSkipsKeyButCreatesCase() {
        var capture = new java.util.concurrent.atomic.AtomicReference<Map<String, Object>>();
        var hub     = capturingCaseHub("ns", "case-name", "1.0", capture);

        var ganglion = new MockGanglion("g1", Set.of("e"), FixedDetectionResult.detected("g1", 0.9));
        CompiledExpression<Map, Object> throwingExpr = new CompiledExpression<>() {
            @Override
            public String type()            {return "test";}

            @Override
            public Object eval(Map context) {throw new RuntimeException("broken");}
        };
        var compiledDynamic = Map.<String, CompiledExpression<Map, Object>>of("brokenKey", throwingExpr);

        var def = new SituationDefinition("sit-1", Set.of("e"),
                                          Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")),
                                          new TriggerAction.CreateCase(new CaseTriggerConfig("ns", "case-name", "1.0",
                                                                                             Map.of("static", "value"))),
                                          null);
        var reg = new SituationRegistration(def, DefaultCorrelationKeyExtractor.INSTANCE,
                                            null, compiledDynamic);
        var registry = new SituationDefinitionRegistry(
                List.of(() -> List.of(reg)), List.of(ganglion));

        var trigger = new DefaultCaseTrigger(List.of(hub), List.of(), registry);
        var ctx     = SituationContext.initial("sit-1", "corr-1", "tenant-a", T1);

        trigger.fire(new CaseTriggerConfig("ns", "case-name", "1.0",
                                           Map.of("static", "value")), ctx);

        var input = capture.get();
        assertThat(input).containsKey("static");
        assertThat(input).doesNotContainKey("brokenKey");
        assertThat(input.get("situationId")).isEqualTo("sit-1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void mergeOrderStaticThenDynamicThenMetadata() {
        var capture = new java.util.concurrent.atomic.AtomicReference<Map<String, Object>>();
        var hub     = capturingCaseHub("ns", "case-name", "1.0", capture);

        var ganglion = new MockGanglion("g1", Set.of("e"), FixedDetectionResult.detected("g1", 0.9));
        CompiledExpression<Map, Object> dynamicExpr = new CompiledExpression<>() {
            @Override
            public String type()            {return "test";}

            @Override
            public Object eval(Map context) {return "dynamic-value";}
        };
        CompiledExpression<Map, Object> overrideSitId = new CompiledExpression<>() {
            @Override
            public String type()            {return "test";}

            @Override
            public Object eval(Map context) {return "attacker-sit";}
        };
        var compiledDynamic = Map.<String, CompiledExpression<Map, Object>>of(
                "foo", dynamicExpr,
                "situationId", overrideSitId);

        var def = new SituationDefinition("sit-1", Set.of("e"),
                                          Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")),
                                          new TriggerAction.CreateCase(new CaseTriggerConfig("ns", "case-name", "1.0",
                                                                                             Map.of("foo", "static-value"))),
                                          null);
        var reg = new SituationRegistration(def, DefaultCorrelationKeyExtractor.INSTANCE,
                                            null, compiledDynamic);
        var registry = new SituationDefinitionRegistry(
                List.of(() -> List.of(reg)), List.of(ganglion));

        var trigger = new DefaultCaseTrigger(List.of(hub), List.of(), registry);
        var ctx     = SituationContext.initial("sit-1", "corr-1", "tenant-a", T1);

        trigger.fire(new CaseTriggerConfig("ns", "case-name", "1.0",
                                           Map.of("foo", "static-value")), ctx);

        var input = capture.get();
        assertThat(input.get("foo")).isEqualTo("dynamic-value");
        assertThat(input.get("situationId")).isEqualTo("sit-1");
    }
}
