package io.casehub.ras.runtime;

import io.casehub.platform.api.expression.CompiledExpression;
import io.casehub.platform.api.expression.ExpressionEngine;
import io.casehub.platform.api.expression.ExpressionEngineRegistry;
import io.casehub.platform.api.expression.ExpressionEvaluator;
import io.casehub.platform.api.expression.JQExpressionEvaluator;
import io.casehub.platform.api.expression.LambdaExpression;
import io.casehub.platform.api.expression.MvelExpressionEvaluator;
import io.casehub.ras.api.CaseTriggerConfig;
import io.casehub.ras.api.ChainMode;
import io.casehub.ras.api.DefaultCorrelationKeyExtractor;
import io.casehub.ras.api.SituationDefinition;
import io.casehub.ras.api.SituationRegistration;
import io.casehub.ras.api.TriggerAction;
import io.casehub.ras.api.TriggerMode;
import io.casehub.ras.testing.FixedDetectionResult;
import io.casehub.ras.testing.MockGanglion;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNoException;

class SituationDefinitionRegistryTest {

    private MockGanglion ganglion(String id, String... eventTypes) {
        return new MockGanglion(id, Set.of(eventTypes),
                FixedDetectionResult.detected(id, 0.8));
    }

    private SituationDefinition definition(String sitId, Set<String> eventTypes, ChainMode mode) {
        return new SituationDefinition(sitId, eventTypes, Duration.ofMinutes(5), null, mode,
                new TriggerAction.CreateCase(new CaseTriggerConfig("ns", "case", "1.0", Map.of())), null);
    }

    @Test
    void findByEventTypeReturnsMatchingRegistrations() {
        var g1 = ganglion("g1", "temp.reading");
        var def = definition("sit-1", Set.of("temp.reading"), new ChainMode.Or(Set.of("g1")));
        var reg = new SituationRegistration(def);

        var registry = new SituationDefinitionRegistry(
                List.of(() -> List.of(reg)), List.of(g1));

        assertThat(registry.findByEventType("temp.reading")).containsExactly(reg);
        assertThat(registry.findByEventType("unknown.type")).isEmpty();
    }

    @Test
    void ganglionLookupWorks() {
        var g1 = ganglion("g1", "temp.reading");
        var def = definition("sit-1", Set.of("temp.reading"), new ChainMode.Or(Set.of("g1")));

        var registry = new SituationDefinitionRegistry(
                List.of(() -> List.of(new SituationRegistration(def))), List.of(g1));

        assertThat(registry.ganglion("g1")).isSameAs(g1);
    }

    @Test
    void duplicateSituationIdThrows() {
        var g1 = ganglion("g1", "temp.reading");
        var def1 = definition("sit-1", Set.of("temp.reading"), new ChainMode.Or(Set.of("g1")));
        var def2 = definition("sit-1", Set.of("temp.reading"), new ChainMode.Or(Set.of("g1")));

        assertThatIllegalStateException().isThrownBy(() ->
                new SituationDefinitionRegistry(
                        List.of(() -> List.of(new SituationRegistration(def1)),
                                () -> List.of(new SituationRegistration(def2))),
                        List.of(g1)))
                .withMessageContaining("sit-1");
    }

    @Test
    void missingGanglionThrows() {
        var def = definition("sit-1", Set.of("temp.reading"),
                new ChainMode.And(Set.of("g1", "g-missing")));
        var g1 = ganglion("g1", "temp.reading");

        assertThatIllegalStateException().isThrownBy(() ->
                new SituationDefinitionRegistry(
                        List.of(() -> List.of(new SituationRegistration(def))),
                        List.of(g1)))
                .withMessageContaining("g-missing");
    }

    @Test
    void ganglionEventTypeMismatchThrows() {
        var g1 = ganglion("g1", "vibration.reading");
        var def = definition("sit-1", Set.of("temp.reading"), new ChainMode.Or(Set.of("g1")));

        assertThatIllegalStateException().isThrownBy(() ->
                new SituationDefinitionRegistry(
                        List.of(() -> List.of(new SituationRegistration(def))),
                        List.of(g1)))
                .withMessageContaining("g1")
                .withMessageContaining("temp.reading");
    }

    @Test
    void multipleEventTypesRouteCorrectly() {
        var g1 = ganglion("g1", "temp.reading", "vibration.reading");
        var def = definition("sit-1", Set.of("temp.reading", "vibration.reading"),
                new ChainMode.Or(Set.of("g1")));
        var reg = new SituationRegistration(def);

        var registry = new SituationDefinitionRegistry(
                List.of(() -> List.of(reg)), List.of(g1));

        assertThat(registry.findByEventType("temp.reading")).containsExactly(reg);
        assertThat(registry.findByEventType("vibration.reading")).containsExactly(reg);
    }

    @Test
    void register_adds_situation_found_by_event_type() {
        var g1 = ganglion("g1", "io.test.event");
        var registry = new SituationDefinitionRegistry(List.of(), List.of(g1));

        var def = definition("sit-A", Set.of("io.test.event"), new ChainMode.Or(Set.of("g1")));
        var reg = new SituationRegistration(def);

        registry.register(reg);

        assertThat(registry.findByEventType("io.test.event")).containsExactly(reg);
    }

    @Test
    void register_rejects_duplicate_situationId() {
        var g1 = ganglion("g1", "io.test.event");
        var def = definition("sit-A", Set.of("io.test.event"), new ChainMode.Or(Set.of("g1")));
        var registry = new SituationDefinitionRegistry(
                List.of(() -> List.of(new SituationRegistration(def))), List.of(g1));

        var def2 = definition("sit-A", Set.of("io.test.event"), new ChainMode.Or(Set.of("g1")));
        var reg2 = new SituationRegistration(def2);

        assertThatIllegalStateException().isThrownBy(() -> registry.register(reg2))
                .withMessageContaining("sit-A");
    }

    @Test
    void register_validates_ganglion_references() {
        var g1 = ganglion("g1", "io.test.event");
        var registry = new SituationDefinitionRegistry(List.of(), List.of(g1));

        var def = definition("sit-A", Set.of("io.test.event"), new ChainMode.Or(Set.of("g-unknown")));
        var reg = new SituationRegistration(def);

        assertThatIllegalStateException().isThrownBy(() -> registry.register(reg))
                .withMessageContaining("g-unknown");
    }

    @Test
    void deregister_removes_situation() {
        var g1 = ganglion("g1", "io.test.event");
        var registry = new SituationDefinitionRegistry(List.of(), List.of(g1));

        var def = definition("sit-A", Set.of("io.test.event"), new ChainMode.Or(Set.of("g1")));
        var reg = new SituationRegistration(def);
        registry.register(reg);

        assertThat(registry.findByEventType("io.test.event")).containsExactly(reg);

        registry.deregister("sit-A");

        assertThat(registry.findByEventType("io.test.event")).isEmpty();
    }

    @Test
    void deregister_is_idempotent() {
        var g1 = ganglion("g1", "io.test.event");
        var registry = new SituationDefinitionRegistry(List.of(), List.of(g1));

        assertThatNoException().isThrownBy(() -> registry.deregister("nonexistent"));
    }

    @Test
    void deregister_updates_maxCorrelationWindow() {
        var g1 = ganglion("g1", "io.test.event");
        var registry = new SituationDefinitionRegistry(List.of(), List.of(g1));

        var def1 = new SituationDefinition("sit-A", Set.of("io.test.event"), Duration.ofMinutes(10), null,
                new ChainMode.Or(Set.of("g1")),
                new TriggerAction.CreateCase(new CaseTriggerConfig("ns", "case", "1.0", Map.of())), null);
        var def2 = new SituationDefinition("sit-B", Set.of("io.test.event"), Duration.ofMinutes(5), null,
                new ChainMode.Or(Set.of("g1")),
                new TriggerAction.CreateCase(new CaseTriggerConfig("ns", "case", "1.0", Map.of())), null);

        registry.register(new SituationRegistration(def1));
        registry.register(new SituationRegistration(def2));

        assertThat(registry.maxCorrelationWindow()).isEqualTo(Duration.ofMinutes(10));

        registry.deregister("sit-A");

        assertThat(registry.maxCorrelationWindow()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void findByEventType_is_thread_safe_during_registration() throws InterruptedException {
        var g1 = ganglion("g1", "io.test.event");
        var registry = new SituationDefinitionRegistry(List.of(), List.of(g1));

        Thread reader = new Thread(() -> {
            for (int i = 0; i < 1000; i++) {
                registry.findByEventType("io.test.event");
            }
        });

        Thread writer = new Thread(() -> {
            for (int i = 0; i < 100; i++) {
                var def = definition("sit-" + i, Set.of("io.test.event"), new ChainMode.Or(Set.of("g1")));
                registry.register(new SituationRegistration(def));
            }
        });

        reader.start();
        writer.start();

        reader.join();
        writer.join();

        assertThat(registry.findByEventType("io.test.event")).hasSize(100);
    }


    @Test
    void register_compiles_correlationKeyExpression() {
        var g1           = ganglion("g1", "io.test.event");
        var mockRegistry = new StubExpressionEngineRegistry();
        var registry = new SituationDefinitionRegistry(
                List.of(), List.of(g1), mockRegistry);

        var def = new SituationDefinition("sit-A", Set.of("io.test.event"),
                                          Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")),
                                          new TriggerAction.CreateCase(new CaseTriggerConfig("ns", "case", "1.0", Map.of())),
                                          null, new JQExpressionEvaluator(".subject"), null, Map.of());
        registry.register(new SituationRegistration(def));

        var regs = registry.findByEventType("io.test.event");
        assertThat(regs).hasSize(1);
        assertThat(regs.get(0).correlationKeyExtractor())
                .isNotSameAs(DefaultCorrelationKeyExtractor.INSTANCE);
        assertThat(mockRegistry.compileCount).isEqualTo(1);
    }

    @Test
    void register_compiles_eventFilter() {
        var g1           = ganglion("g1", "io.test.event");
        var mockRegistry = new StubExpressionEngineRegistry();
        var registry = new SituationDefinitionRegistry(
                List.of(), List.of(g1), mockRegistry);

        var def = new SituationDefinition("sit-A", Set.of("io.test.event"),
                                          Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")),
                                          new TriggerAction.CreateCase(new CaseTriggerConfig("ns", "case", "1.0", Map.of())),
                                          null, null,
                                          new MvelExpressionEvaluator("data.severity >= 3"), Map.of());
        registry.register(new SituationRegistration(def));

        var regs = registry.findByEventType("io.test.event");
        assertThat(regs).hasSize(1);
        assertThat(regs.get(0).eventFilter()).isNotNull();
        assertThat(mockRegistry.compileCount).isEqualTo(1);
    }

    @Test
    void register_failsFast_whenExpressionEngineNotFound() {
        var g1            = ganglion("g1", "io.test.event");
        var emptyRegistry = new StubExpressionEngineRegistry(false);
        var registry = new SituationDefinitionRegistry(
                List.of(), List.of(g1), emptyRegistry);

        var def = new SituationDefinition("sit-A", Set.of("io.test.event"),
                                          Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")),
                                          new TriggerAction.CreateCase(new CaseTriggerConfig("ns", "case", "1.0", Map.of())),
                                          null, new JQExpressionEvaluator(".subject"), null, Map.of());

        assertThatIllegalStateException()
                .isThrownBy(() -> registry.register(new SituationRegistration(def)))
                .withMessageContaining("sit-A")
                .withMessageContaining("jq");
    }

    @SuppressWarnings("unchecked")
    @Test
    void register_lambdaExpression_passedThroughWithoutCompilation() {
        var g1           = ganglion("g1", "io.test.event");
        var mockRegistry = new StubExpressionEngineRegistry();
        var registry = new SituationDefinitionRegistry(
                List.of(), List.of(g1), mockRegistry);

        LambdaExpression<Map, String> lambda = new LambdaExpression<>(
                ctx -> (String) ((Map<?, ?>) ctx).get("subject"));
        var def = new SituationDefinition("sit-A", Set.of("io.test.event"),
                                          Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")),
                                          new TriggerAction.CreateCase(new CaseTriggerConfig("ns", "case", "1.0", Map.of())),
                                          null, lambda, null, Map.of());
        registry.register(new SituationRegistration(def));

        var regs = registry.findByEventType("io.test.event");
        assertThat(regs).hasSize(1);
        assertThat(regs.get(0).correlationKeyExtractor())
                .isNotSameAs(DefaultCorrelationKeyExtractor.INSTANCE);
        assertThat(mockRegistry.compileCount).isZero();
    }

    @Test
    void getCompiledDynamicData_returns_compiled_expressions() {
        var g1           = ganglion("g1", "io.test.event");
        var mockRegistry = new StubExpressionEngineRegistry();
        var registry = new SituationDefinitionRegistry(
                List.of(), List.of(g1), mockRegistry);

        var dynamicData = Map.<String, ExpressionEvaluator>of(
                "orderId", new JQExpressionEvaluator(".correlationKey"));
        var def = new SituationDefinition("sit-A", Set.of("io.test.event"),
                                          Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")),
                                          new TriggerAction.CreateCase(new CaseTriggerConfig("ns", "case", "1.0", Map.of())),
                                          null, null, null, dynamicData);
        registry.register(new SituationRegistration(def));

        var compiled = registry.getCompiledDynamicData("sit-A");
        assertThat(compiled).isNotNull().containsKey("orderId");
    }

    @Test
    void getCompiledDynamicData_returns_null_when_no_expressions() {
        var g1       = ganglion("g1", "io.test.event");
        var registry = new SituationDefinitionRegistry(List.of(), List.of(g1));

        var def = definition("sit-A", Set.of("io.test.event"), new ChainMode.Or(Set.of("g1")));
        registry.register(new SituationRegistration(def));

        assertThat(registry.getCompiledDynamicData("sit-A")).isNull();
    }

    @Test
    void deregister_clears_compiled_dynamic_data() {
        var g1           = ganglion("g1", "io.test.event");
        var mockRegistry = new StubExpressionEngineRegistry();
        var registry = new SituationDefinitionRegistry(
                List.of(), List.of(g1), mockRegistry);

        var dynamicData = Map.<String, ExpressionEvaluator>of(
                "orderId", new JQExpressionEvaluator(".correlationKey"));
        var def = new SituationDefinition("sit-A", Set.of("io.test.event"),
                                          Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")),
                                          new TriggerAction.CreateCase(new CaseTriggerConfig("ns", "case", "1.0", Map.of())),
                                          null, null, null, dynamicData);
        registry.register(new SituationRegistration(def));

        assertThat(registry.getCompiledDynamicData("sit-A")).isNotNull();

        registry.deregister("sit-A");

        assertThat(registry.getCompiledDynamicData("sit-A")).isNull();
    }

    @Test
    void noExpressions_registration_unchanged() {
        var g1       = ganglion("g1", "io.test.event");
        var registry = new SituationDefinitionRegistry(List.of(), List.of(g1));

        var def = definition("sit-A", Set.of("io.test.event"), new ChainMode.Or(Set.of("g1")));
        var reg = new SituationRegistration(def);
        registry.register(reg);

        var regs = registry.findByEventType("io.test.event");
        assertThat(regs).hasSize(1);
        assertThat(regs.get(0).correlationKeyExtractor())
                .isSameAs(DefaultCorrelationKeyExtractor.INSTANCE);
        assertThat(regs.get(0).eventFilter()).isNull();
        assertThat(regs.get(0).compiledDynamicData()).isNull();
    }


    @Test
    void jqAdapter_extractsCorrelationKeyFromCloudEvent() {
        var g1 = ganglion("g1", "io.test.event");
        var realRegistry = new io.casehub.platform.expression.DefaultExpressionEngineRegistry();
        realRegistry.register(new io.casehub.platform.expression.JQExpressionEngine());
        var registry = new SituationDefinitionRegistry(List.of(), List.of(g1), realRegistry);

        var def = new SituationDefinition("sit-A", Set.of("io.test.event"),
                java.time.Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")),
                new TriggerAction.CreateCase(new CaseTriggerConfig("ns", "case", "1.0", Map.of())),
                null, new JQExpressionEvaluator(".data.orderId"), null, Map.of());
        registry.register(new SituationRegistration(def));

        var regs = registry.findByEventType("io.test.event");
        var extractor = regs.get(0).correlationKeyExtractor();

        var event = io.cloudevents.core.builder.CloudEventBuilder.v1()
                .withId("evt-1").withSource(java.net.URI.create("/test")).withType("io.test.event")
                .withSubject("ignored-subject")
                .withExtension("tenancyid", "tenant-A")
                .withData("application/json", "{\"orderId\":\"ORD-42\"}".getBytes())
                .build();

        assertThat(extractor.extract(event)).isEqualTo("ORD-42");
    }

    @Test
    void jqAdapter_nullResultFallsBackToSingleton() {
        var g1 = ganglion("g1", "io.test.event");
        var realRegistry = new io.casehub.platform.expression.DefaultExpressionEngineRegistry();
        realRegistry.register(new io.casehub.platform.expression.JQExpressionEngine());
        var registry = new SituationDefinitionRegistry(List.of(), List.of(g1), realRegistry);

        var def = new SituationDefinition("sit-A", Set.of("io.test.event"),
                java.time.Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")),
                new TriggerAction.CreateCase(new CaseTriggerConfig("ns", "case", "1.0", Map.of())),
                null, new JQExpressionEvaluator(".data.missingField"), null, Map.of());
        registry.register(new SituationRegistration(def));

        var regs = registry.findByEventType("io.test.event");
        var extractor = regs.get(0).correlationKeyExtractor();

        var event = io.cloudevents.core.builder.CloudEventBuilder.v1()
                .withId("evt-1").withSource(java.net.URI.create("/test")).withType("io.test.event")
                .withData("application/json", "{\"orderId\":\"ORD-42\"}".getBytes())
                .build();

        assertThat(extractor.extract(event)).isEqualTo("_singleton");
    }


// --- Descriptor ganglion tests ---

    @Test
    void descriptorGanglionRegisteredAndFindable() {
        var descriptor = new io.casehub.ras.api.GanglionDescriptor.NaiveBayes(
                "yaml-g", Set.of("test.event"),
                List.of("NORMAL", "ANOMALY"), new double[]{0.9, 0.1},
                Map.of("f1", new io.casehub.ras.api.GanglionDescriptor.NaiveBayes.Feature(
                        new io.casehub.platform.api.expression.JQExpressionEvaluator(".data.f"),
                        List.of("X", "Y"),
                        new double[][]{{0.8, 0.2}, {0.3, 0.7}})),
                new io.casehub.ras.api.GanglionDescriptor.NaiveBayes.SignalMapping("ANOMALY", 0.75, 0.30, null),
                Map.of(),
                Map.of());

        io.casehub.ras.api.SituationDefinitionProvider provider = new io.casehub.ras.api.SituationDefinitionProvider() {
            public List<io.casehub.ras.api.SituationRegistration> registrations()    {return List.of();}

            public List<io.casehub.ras.api.GanglionDescriptor> ganglionDescriptors() {return List.of(descriptor);}
        };

        var registry = new SituationDefinitionRegistry(
                List.of(provider), List.of(),
                new StubExpressionEngineRegistry(), new InMemoryGanglionStateStore(), null, null);

        assertThat(registry.ganglion("yaml-g")).isNotNull();
        assertThat(registry.ganglion("yaml-g")).isInstanceOf(NaiveBayesGanglion.class);
    }

    @Test
    void duplicateGanglionIdBetweenDescriptorAndCdiThrows() {
        var descriptor = new io.casehub.ras.api.GanglionDescriptor.NaiveBayes(
                "dup-g", Set.of("test.event"),
                List.of("A", "B"), new double[]{0.5, 0.5},
                Map.of("f1", new io.casehub.ras.api.GanglionDescriptor.NaiveBayes.Feature(
                        new io.casehub.platform.api.expression.JQExpressionEvaluator(".data.f"),
                        List.of("X"), new double[][]{{0.6}, {0.4}})),
                new io.casehub.ras.api.GanglionDescriptor.NaiveBayes.SignalMapping("B", 0.75, 0.30, null),
                Map.of(),
                Map.of());

        io.casehub.ras.api.SituationDefinitionProvider provider = new io.casehub.ras.api.SituationDefinitionProvider() {
            public List<io.casehub.ras.api.SituationRegistration> registrations()    {return List.of();}

            public List<io.casehub.ras.api.GanglionDescriptor> ganglionDescriptors() {return List.of(descriptor);}
        };

        var cdiGanglion = ganglion("dup-g", "test.event");

        assertThatIllegalStateException().isThrownBy(() ->
                                                             new SituationDefinitionRegistry(
                                                                     List.of(provider), List.of(cdiGanglion),
                                                                     new StubExpressionEngineRegistry(), new InMemoryGanglionStateStore(), null, null))
                                         .withMessageContaining("Duplicate ganglionId 'dup-g'");
    }

    @Test
    void yamlSituationReferencingDescriptorGanglionValidates() {
        var descriptor = new io.casehub.ras.api.GanglionDescriptor.NaiveBayes(
                "desc-g", Set.of("test.event"),
                List.of("A", "B"), new double[]{0.5, 0.5},
                Map.of("f1", new io.casehub.ras.api.GanglionDescriptor.NaiveBayes.Feature(
                        new io.casehub.platform.api.expression.JQExpressionEvaluator(".data.f"),
                        List.of("X"), new double[][]{{0.6}, {0.4}})),
                new io.casehub.ras.api.GanglionDescriptor.NaiveBayes.SignalMapping("B", 0.75, 0.30, null),
                Map.of(),
                Map.of());

        var def = definition("sit-1", Set.of("test.event"), new ChainMode.Or(Set.of("desc-g")));

        io.casehub.ras.api.SituationDefinitionProvider provider = new io.casehub.ras.api.SituationDefinitionProvider() {
            public List<io.casehub.ras.api.SituationRegistration> registrations() {
                return List.of(new io.casehub.ras.api.SituationRegistration(def));
            }

            public List<io.casehub.ras.api.GanglionDescriptor> ganglionDescriptors() {return List.of(descriptor);}
        };

        assertThatNoException().isThrownBy(() ->
                                                   new SituationDefinitionRegistry(
                                                           List.of(provider), List.of(),
                                                           new StubExpressionEngineRegistry(), new InMemoryGanglionStateStore(), null, null));
    }

    @Test
    void invalidDescriptorWrapsErrorWithGanglionContext() {
        var descriptor = new io.casehub.ras.api.GanglionDescriptor.NaiveBayes(
                "bad-g", Set.of("test.event"),
                List.of("A", "B"), new double[]{0.7, 0.7},
                Map.of(), new io.casehub.ras.api.GanglionDescriptor.NaiveBayes.SignalMapping("B", 0.75, 0.30, null),
                Map.of(),
                Map.of());

        io.casehub.ras.api.SituationDefinitionProvider provider = new io.casehub.ras.api.SituationDefinitionProvider() {
            public List<io.casehub.ras.api.SituationRegistration> registrations()    {return List.of();}

            public List<io.casehub.ras.api.GanglionDescriptor> ganglionDescriptors() {return List.of(descriptor);}
        };

        assertThatIllegalStateException().isThrownBy(() ->
                                                             new SituationDefinitionRegistry(
                                                                     List.of(provider), List.of(),
                                                                     new StubExpressionEngineRegistry(), new InMemoryGanglionStateStore(), null, null))
                                         .withMessageContaining("bad-g")
                                         .withMessageContaining("priors must sum to 1.0");
    }

    @Test
    void evidenceTemplatesWrappedInEvidenceExtractingGanglion() {
        var descriptor = new io.casehub.ras.api.GanglionDescriptor.NaiveBayes(
                "evid-g", Set.of("test.event"),
                List.of("A", "B"), new double[]{0.5, 0.5},
                Map.of("f1", new io.casehub.ras.api.GanglionDescriptor.NaiveBayes.Feature(
                        new io.casehub.platform.api.expression.JQExpressionEvaluator(".data.f"),
                        List.of("X"), new double[][]{{0.6}, {0.4}})),
                new io.casehub.ras.api.GanglionDescriptor.NaiveBayes.SignalMapping("B", 0.75, 0.30, null),
                Map.of("raw", new io.casehub.platform.api.expression.JQExpressionEvaluator(".data.f")),
                Map.of());

        io.casehub.ras.api.SituationDefinitionProvider provider = new io.casehub.ras.api.SituationDefinitionProvider() {
            public List<io.casehub.ras.api.SituationRegistration> registrations()    {return List.of();}

            public List<io.casehub.ras.api.GanglionDescriptor> ganglionDescriptors() {return List.of(descriptor);}
        };

        var registry = new SituationDefinitionRegistry(
                List.of(provider), List.of(),
                new StubExpressionEngineRegistry(), new InMemoryGanglionStateStore(), null, null);

        assertThat(registry.ganglion("evid-g")).isInstanceOf(EvidenceExtractingGanglion.class);
    }

    @Test
    void evidenceTemplatesEmptyNoWrapping() {
        var descriptor = new io.casehub.ras.api.GanglionDescriptor.NaiveBayes(
                "no-evid", Set.of("test.event"),
                List.of("A", "B"), new double[]{0.5, 0.5},
                Map.of("f1", new io.casehub.ras.api.GanglionDescriptor.NaiveBayes.Feature(
                        new io.casehub.platform.api.expression.JQExpressionEvaluator(".data.f"),
                        List.of("X"), new double[][]{{0.6}, {0.4}})),
                new io.casehub.ras.api.GanglionDescriptor.NaiveBayes.SignalMapping("B", 0.75, 0.30, null),
                Map.of(),
                Map.of());

        io.casehub.ras.api.SituationDefinitionProvider provider = new io.casehub.ras.api.SituationDefinitionProvider() {
            public List<io.casehub.ras.api.SituationRegistration> registrations()    {return List.of();}

            public List<io.casehub.ras.api.GanglionDescriptor> ganglionDescriptors() {return List.of(descriptor);}
        };

        var registry = new SituationDefinitionRegistry(
                List.of(provider), List.of(),
                new StubExpressionEngineRegistry(), new InMemoryGanglionStateStore(), null, null);

        assertThat(registry.ganglion("no-evid")).isInstanceOf(NaiveBayesGanglion.class);
    }

    @Test
    void expressionRulesDescriptorRegisteredAndFindable() {
        var descriptor = new io.casehub.ras.api.GanglionDescriptor.ExpressionRules(
                "rules-g", Set.of("test.event"),
                List.of(new io.casehub.ras.api.GanglionDescriptor.ExpressionRules.Rule(
                        new io.casehub.platform.api.expression.JQExpressionEvaluator(".data.x == \"Y\""),
                        io.casehub.ras.api.DetectionSignal.DETECTED, 0.9, null, Map.of())),
                Map.of());

        io.casehub.ras.api.SituationDefinitionProvider provider = new io.casehub.ras.api.SituationDefinitionProvider() {
            public List<io.casehub.ras.api.SituationRegistration> registrations()    {return List.of();}

            public List<io.casehub.ras.api.GanglionDescriptor> ganglionDescriptors() {return List.of(descriptor);}
        };

        var registry = new SituationDefinitionRegistry(
                List.of(provider), List.of(),
                new StubExpressionEngineRegistry(), new InMemoryGanglionStateStore(), null, null);

        assertThat(registry.ganglion("rules-g")).isNotNull();
        assertThat(registry.ganglion("rules-g")).isInstanceOf(ExpressionRulesGanglion.class);
    }

    @Test
    void expressionRulesWithEvidenceTemplatesWrapped() {
        var descriptor = new io.casehub.ras.api.GanglionDescriptor.ExpressionRules(
                "rules-evid", Set.of("test.event"),
                List.of(new io.casehub.ras.api.GanglionDescriptor.ExpressionRules.Rule(
                        null, io.casehub.ras.api.DetectionSignal.NOISE, 0.0, null, Map.of())),
                Map.of("raw", new io.casehub.platform.api.expression.JQExpressionEvaluator(".data.x")));

        io.casehub.ras.api.SituationDefinitionProvider provider = new io.casehub.ras.api.SituationDefinitionProvider() {
            public List<io.casehub.ras.api.SituationRegistration> registrations()    {return List.of();}

            public List<io.casehub.ras.api.GanglionDescriptor> ganglionDescriptors() {return List.of(descriptor);}
        };

        var registry = new SituationDefinitionRegistry(
                List.of(provider), List.of(),
                new StubExpressionEngineRegistry(), new InMemoryGanglionStateStore(), null, null);

        assertThat(registry.ganglion("rules-evid")).isInstanceOf(EvidenceExtractingGanglion.class);
    }

    @Test
    void expressionRulesAndNaiveBayesCoexist() {
        var nbDescriptor = new io.casehub.ras.api.GanglionDescriptor.NaiveBayes(
                "nb-g", Set.of("test.event"),
                List.of("A", "B"), new double[]{0.5, 0.5},
                Map.of("f1", new io.casehub.ras.api.GanglionDescriptor.NaiveBayes.Feature(
                        new io.casehub.platform.api.expression.JQExpressionEvaluator(".data.f"),
                        List.of("X"), new double[][]{{0.6}, {0.4}})),
                new io.casehub.ras.api.GanglionDescriptor.NaiveBayes.SignalMapping("B", 0.75, 0.30, null),
                Map.of(),
                Map.of());

        var erDescriptor = new io.casehub.ras.api.GanglionDescriptor.ExpressionRules(
                "er-g", Set.of("test.event"),
                List.of(new io.casehub.ras.api.GanglionDescriptor.ExpressionRules.Rule(
                        null, io.casehub.ras.api.DetectionSignal.NOISE, 0.0, null, Map.of())),
                Map.of());

        io.casehub.ras.api.SituationDefinitionProvider provider = new io.casehub.ras.api.SituationDefinitionProvider() {
            public List<io.casehub.ras.api.SituationRegistration> registrations()    {return List.of();}

            public List<io.casehub.ras.api.GanglionDescriptor> ganglionDescriptors() {return List.of(nbDescriptor, erDescriptor);}
        };

        var registry = new SituationDefinitionRegistry(
                List.of(provider), List.of(),
                new StubExpressionEngineRegistry(), new InMemoryGanglionStateStore(), null, null);

        assertThat(registry.ganglion("nb-g")).isInstanceOf(NaiveBayesGanglion.class);
        assertThat(registry.ganglion("er-g")).isInstanceOf(ExpressionRulesGanglion.class);
    }

    @Test
    void duplicateGanglionIdAcrossExpressionRulesAndCdiThrows() {
        var descriptor = new io.casehub.ras.api.GanglionDescriptor.ExpressionRules(
                "dup-g", Set.of("test.event"),
                List.of(new io.casehub.ras.api.GanglionDescriptor.ExpressionRules.Rule(
                        null, io.casehub.ras.api.DetectionSignal.NOISE, 0.0, null, Map.of())),
                Map.of());

        io.casehub.ras.api.SituationDefinitionProvider provider = new io.casehub.ras.api.SituationDefinitionProvider() {
            public List<io.casehub.ras.api.SituationRegistration> registrations()    {return List.of();}

            public List<io.casehub.ras.api.GanglionDescriptor> ganglionDescriptors() {return List.of(descriptor);}
        };

        var cdiGanglion = ganglion("dup-g", "test.event");

        assertThatIllegalStateException().isThrownBy(() ->
                                                             new SituationDefinitionRegistry(
                                                                     List.of(provider), List.of(cdiGanglion),
                                                                     new StubExpressionEngineRegistry(), new InMemoryGanglionStateStore(), null, null))
                                         .withMessageContaining("Duplicate ganglionId 'dup-g'");
    }


    private static class StubExpressionEngineRegistry implements ExpressionEngineRegistry {
        int compileCount = 0;
        private final boolean resolveSucceeds;

        StubExpressionEngineRegistry()                        {this(true);}

        StubExpressionEngineRegistry(boolean resolveSucceeds) {this.resolveSucceeds = resolveSucceeds;}

        @Override
        public void register(ExpressionEngine engine)         {}

        @Override
        public Optional<ExpressionEngine> resolve(String type) {
            return resolveSucceeds ? Optional.of(new StubEngine()) : Optional.empty();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <C, R> CompiledExpression<C, R> compile(
                String type, String expression, Class<C> contextType, Class<R> resultType) {
            compileCount++;
            return (CompiledExpression<C, R>) new StubCompiledExpression(expression);
        }

        @Override
        public <C, R> CompiledExpression<C, R> compile(
                String type, String expression, Class<C> contextType, Class<R> resultType,
                Map<String, Object> variables) {
            return compile(type, expression, contextType, resultType);
        }

        @Override
        public void validate(String type, String expression) {}

        @SuppressWarnings("rawtypes")
        private record StubCompiledExpression(String expression) implements CompiledExpression<Map, Object> {
            @Override
            public String type()            {return "stub";}

            @Override
            public Object eval(Map context) {return context.get("subject");}
        }

        private static class StubEngine implements ExpressionEngine {
            @Override
            public String type()                                                                                    {return "stub";}

            @Override
            public <C, R> CompiledExpression<C, R> compile(String e, Class<C> c, Class<R> r)                        {return null;}

            @Override
            public <C, R> CompiledExpression<C, R> compile(String e, Class<C> c, Class<R> r, Map<String, Object> v) {return null;}

            @Override
            public void validate(String e)                                                                          {}
        }
    }

    @Test
    void expressionRulesWithPerRuleEvidenceCompiled() {
        var descriptor = new io.casehub.ras.api.GanglionDescriptor.ExpressionRules(
                "per-rule-evid", Set.of("test.event"),
                List.of(new io.casehub.ras.api.GanglionDescriptor.ExpressionRules.Rule(
                        new io.casehub.platform.api.expression.JQExpressionEvaluator("true"),
                        io.casehub.ras.api.DetectionSignal.DETECTED, 0.9, null,
                        Map.of("extracted", new io.casehub.platform.api.expression.JQExpressionEvaluator(".type")))),
                Map.of());

        io.casehub.ras.api.SituationDefinitionProvider provider = new io.casehub.ras.api.SituationDefinitionProvider() {
            public List<io.casehub.ras.api.SituationRegistration> registrations()    {return List.of();}

            public List<io.casehub.ras.api.GanglionDescriptor> ganglionDescriptors() {return List.of(descriptor);}
        };

        var registry = new SituationDefinitionRegistry(
                List.of(provider), List.of(),
                new StubExpressionEngineRegistry(), new InMemoryGanglionStateStore(), null, null);

        assertThat(registry.ganglion("per-rule-evid")).isNotNull();
    }

    @Test
    void naiveBayesWithPerOutcomeEvidenceCompiled() {
        var descriptor = new io.casehub.ras.api.GanglionDescriptor.NaiveBayes(
                "per-outcome-evid", Set.of("test.event"),
                List.of("A", "B"), new double[]{0.5, 0.5},
                Map.of("f1", new io.casehub.ras.api.GanglionDescriptor.NaiveBayes.Feature(
                        new io.casehub.platform.api.expression.JQExpressionEvaluator(".data.f"),
                        List.of("X"), new double[][]{{0.6}, {0.4}})),
                new io.casehub.ras.api.GanglionDescriptor.NaiveBayes.SignalMapping("B", 0.75, 0.30, null),
                Map.of(),
                Map.of("A", Map.of("detail", new io.casehub.platform.api.expression.JQExpressionEvaluator(".data.detail"))));

        io.casehub.ras.api.SituationDefinitionProvider provider = new io.casehub.ras.api.SituationDefinitionProvider() {
            public List<io.casehub.ras.api.SituationRegistration> registrations()    {return List.of();}

            public List<io.casehub.ras.api.GanglionDescriptor> ganglionDescriptors() {return List.of(descriptor);}
        };

        var registry = new SituationDefinitionRegistry(
                List.of(provider), List.of(),
                new StubExpressionEngineRegistry(), new InMemoryGanglionStateStore(), null, null);

        assertThat(registry.ganglion("per-outcome-evid")).isNotNull();
    }

    @Test
    void expressionRulesWithConfidenceExpressionCompiled() {
        var descriptor = new io.casehub.ras.api.GanglionDescriptor.ExpressionRules(
                "dyn-g", Set.of("test.event"),
                List.of(new io.casehub.ras.api.GanglionDescriptor.ExpressionRules.Rule(
                        null, io.casehub.ras.api.DetectionSignal.DETECTED, 0.5,
                        new io.casehub.platform.api.expression.JQExpressionEvaluator(".data.score"),
                        Map.of())),
                Map.of());

        io.casehub.ras.api.SituationDefinitionProvider provider = new io.casehub.ras.api.SituationDefinitionProvider() {
            public List<io.casehub.ras.api.SituationRegistration> registrations()    {return List.of();}

            public List<io.casehub.ras.api.GanglionDescriptor> ganglionDescriptors() {return List.of(descriptor);}
        };
        var registry = new SituationDefinitionRegistry(
                List.of(provider), List.of(), new StubExpressionEngineRegistry());
        assertThat(registry.ganglion("dyn-g")).isNotNull();
    }

    @Test
    void endToEndDynamicConfidenceExpression() {
        var realRegistry = new io.casehub.platform.expression.DefaultExpressionEngineRegistry();
        realRegistry.register(new io.casehub.platform.expression.JQExpressionEngine());
        var provider = new YamlSituationDefinitionProvider(
                Thread.currentThread().getContextClassLoader()
                      .getResourceAsStream("META-INF/ras-situations-dynamic-confidence.yaml"));
        var registry = new SituationDefinitionRegistry(
                List.of(provider), List.of(), realRegistry);

        io.casehub.ras.api.Ganglion ganglion = registry.ganglion("dyn-confidence");
        var event = io.cloudevents.core.builder.CloudEventBuilder.v1()
                                                                 .withId("e1").withSource(java.net.URI.create("/t")).withType("test.dynamic")
                                                                 .withData("application/json", "{\"severity\":\"HIGH\",\"score\":85}".getBytes())
                                                                 .build();
        var ctx = io.casehub.ras.api.SituationContext.initial(
                "dyn-confidence-sit", "key", "t1", java.time.Instant.now());
        io.casehub.ras.api.DetectionResult result = ganglion.detect(event, ctx);
        assertThat(result.confidence()).isEqualTo(0.85);
        assertThat(result.signal()).isEqualTo(io.casehub.ras.api.DetectionSignal.DETECTED);
    }

    @Test
    void allSituationIdsReturnsAllRegisteredIds() {
        var g1   = ganglion("g1", "event.a", "event.b");
        var def1 = definition("sit-1", Set.of("event.a"), new ChainMode.Or(Set.of("g1")));
        var def2 = definition("sit-2", Set.of("event.b"), new ChainMode.Or(Set.of("g1")));
        var registry = new SituationDefinitionRegistry(
                List.of(() -> List.of(new SituationRegistration(def1), new SituationRegistration(def2))),
                List.of(g1));
        assertThat(registry.allSituationIds()).containsExactlyInAnyOrder("sit-1", "sit-2");
    }

    @Test
    void feedbackConfigReturnsNullWhenAbsent() {
        var g1  = ganglion("g1", "event.a");
        var def = definition("sit-1", Set.of("event.a"), new ChainMode.Or(Set.of("g1")));
        var registry = new SituationDefinitionRegistry(
                List.of(() -> List.of(new SituationRegistration(def))),
                List.of(g1));
        assertThat(registry.feedbackConfig("sit-1")).isNull();
    }

    @Test
    void feedbackConfigReturnsFeedbackWhenPresent() {
        var g1 = ganglion("g1", "event.a");
        var config = new io.casehub.ras.api.FeedbackConfig(
                Set.of("dismissed"), Set.of("escalated"),
                java.time.Duration.ofHours(6), 0.1, java.time.Duration.ofDays(90), false);
        var def = new SituationDefinition("sit-1", Set.of("event.a"),
                                          Duration.ofMinutes(5), null,
                                          new ChainMode.Or(Set.of("g1")),
                                          new TriggerAction.CreateCase(new CaseTriggerConfig("ns", "c", "1", Map.of())),
                                          null, null, null, Map.of(), config, null);
        var registry = new SituationDefinitionRegistry(
                List.of(() -> List.of(new SituationRegistration(def))),
                List.of(g1));
        assertThat(registry.feedbackConfig("sit-1")).isNotNull();
        assertThat(registry.feedbackConfig("sit-1").learningRate()).isEqualTo(0.1);
    }

    @Test
    void feedbackConfigReturnsNullForUnknownSituation() {
        var registry = new SituationDefinitionRegistry(List.of(), List.of());
        assertThat(registry.feedbackConfig("nonexistent")).isNull();
    }

    @Test
    void ganglionDescriptorReturnsDescriptorForYamlGanglion() {
        var descriptor = new io.casehub.ras.api.GanglionDescriptor.NaiveBayes(
                "nb-desc", Set.of("test.event"),
                List.of("A", "B"), new double[]{0.5, 0.5},
                Map.of("f1", new io.casehub.ras.api.GanglionDescriptor.NaiveBayes.Feature(
                        new io.casehub.platform.api.expression.JQExpressionEvaluator(".data.f"),
                        List.of("X", "Y"), new double[][]{{0.8, 0.2}, {0.3, 0.7}})),
                new io.casehub.ras.api.GanglionDescriptor.NaiveBayes.SignalMapping("B", 0.75, 0.30, null),
                Map.of(), Map.of());

        io.casehub.ras.api.SituationDefinitionProvider provider = new io.casehub.ras.api.SituationDefinitionProvider() {
            public List<SituationRegistration> registrations()                       {return List.of();}

            public List<io.casehub.ras.api.GanglionDescriptor> ganglionDescriptors() {return List.of(descriptor);}
        };

        var registry = new SituationDefinitionRegistry(
                List.of(provider), List.of(),
                new StubExpressionEngineRegistry(), new InMemoryGanglionStateStore(), null, null);

        assertThat(registry.ganglionDescriptor("nb-desc")).isSameAs(descriptor);
    }

    @Test
    void ganglionDescriptorReturnsNullForCdiGanglion() {
        var g1       = ganglion("cdi-g", "event.a");
        var registry = new SituationDefinitionRegistry(List.of(), List.of(g1));
        assertThat(registry.ganglionDescriptor("cdi-g")).isNull();
    }

    @Test
    void cycleDetection_selfEdge_repeating_rejected() {
        var g = ganglion("g", "ras.situation.triggered");
        var def = new SituationDefinition("A", Set.of("ras.situation.triggered"),
                                          null, null, new ChainMode.Count("g", 1),
                                          new TriggerAction.NotifyOnly(),
                                          new TriggerMode.Repeating(Duration.ofMinutes(1)));

        assertThatIllegalStateException().isThrownBy(() ->
                                                             new SituationDefinitionRegistry(
                                                                     List.of(() -> List.of(new SituationRegistration(def))),
                                                                     List.of(g)))
                                         .withMessageContaining("Cycle");
    }

    @Test
    void cycleDetection_selfEdge_fireOnce_allowed() {
        var g = ganglion("g", "ras.situation.triggered");
        var def = new SituationDefinition("A", Set.of("ras.situation.triggered"),
                                          null, null, new ChainMode.Count("g", 1),
                                          new TriggerAction.NotifyOnly(), null);

        assertThatNoException().isThrownBy(() ->
                                                   new SituationDefinitionRegistry(
                                                           List.of(() -> List.of(new SituationRegistration(def))),
                                                           List.of(g)));
    }

    @Test
    void cycleDetection_transitiveCycle_rejected() {
        var g = ganglion("g", "ras.situation.triggered");
        var defA = new SituationDefinition("A", Set.of("ras.situation.triggered"),
                                           null, null, new ChainMode.Count("g", 1),
                                           new TriggerAction.NotifyOnly(),
                                           new TriggerMode.Repeating(Duration.ofMinutes(1)));
        var defB = new SituationDefinition("B", Set.of("ras.situation.triggered"),
                                           null, null, new ChainMode.Count("g", 1),
                                           new TriggerAction.NotifyOnly(),
                                           new TriggerMode.Repeating(Duration.ofMinutes(1)));

        assertThatIllegalStateException().isThrownBy(() ->
                                                             new SituationDefinitionRegistry(
                                                                     List.of(() -> List.of(
                                                                             new SituationRegistration(defA),
                                                                             new SituationRegistration(defB))),
                                                                     List.of(g)))
                                         .withMessageContaining("Cycle");
    }

    @Test
    void cycleDetection_transitiveCycle_fireOnce_also_rejected() {
        var g = ganglion("g", "ras.situation.triggered");
        var defA = new SituationDefinition("A", Set.of("ras.situation.triggered"),
                                           null, null, new ChainMode.Count("g", 1),
                                           new TriggerAction.NotifyOnly(), null);
        var defB = new SituationDefinition("B", Set.of("ras.situation.triggered"),
                                           null, null, new ChainMode.Count("g", 1),
                                           new TriggerAction.NotifyOnly(), null);

        assertThatIllegalStateException().isThrownBy(() ->
                                                             new SituationDefinitionRegistry(
                                                                     List.of(() -> List.of(
                                                                             new SituationRegistration(defA),
                                                                             new SituationRegistration(defB))),
                                                                     List.of(g)))
                                         .withMessageContaining("Cycle");
    }

    @Test
    void cycleDetection_dag_allowed() {
        var g = ganglion("g", "ras.situation.triggered", "raw.event");
        var defA = new SituationDefinition("A", Set.of("ras.situation.triggered"),
                                           null, null, new ChainMode.Count("g", 1),
                                           new TriggerAction.NotifyOnly(), null);
        var defB = new SituationDefinition("B", Set.of("raw.event"),
                                           null, null, new ChainMode.Count("g", 1),
                                           new TriggerAction.NotifyOnly(), null);

        assertThatNoException().isThrownBy(() ->
                                                   new SituationDefinitionRegistry(
                                                           List.of(() -> List.of(
                                                                   new SituationRegistration(defA),
                                                                   new SituationRegistration(defB))),
                                                           List.of(g)));
    }

    @Test
    void cycleDetection_dynamicRegister_rejects_cycle() {
        var g = ganglion("g", "ras.situation.triggered");
        var defA = new SituationDefinition("A", Set.of("ras.situation.triggered"),
                                           null, null, new ChainMode.Count("g", 1),
                                           new TriggerAction.NotifyOnly(), null);

        var registry = new SituationDefinitionRegistry(
                List.of(() -> List.of(new SituationRegistration(defA))),
                List.of(g));

        var defB = new SituationDefinition("B", Set.of("ras.situation.triggered"),
                                           null, null, new ChainMode.Count("g", 1),
                                           new TriggerAction.NotifyOnly(), null);

        assertThatIllegalStateException().isThrownBy(() ->
                                                             registry.register(new SituationRegistration(defB)))
                                         .withMessageContaining("Cycle");
    }


}
