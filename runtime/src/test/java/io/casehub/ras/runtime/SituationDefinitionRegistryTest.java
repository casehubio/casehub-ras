package io.casehub.ras.runtime;

import io.casehub.ras.api.*;
import io.casehub.ras.testing.MockGanglion;
import io.casehub.ras.testing.FixedDetectionResult;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

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
}
