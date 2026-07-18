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
}
