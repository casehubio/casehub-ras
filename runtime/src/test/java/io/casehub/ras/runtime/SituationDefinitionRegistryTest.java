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
        return new SituationDefinition(sitId, eventTypes, Duration.ofMinutes(5), mode,
                new CaseTriggerConfig("ns", "case", "1.0", Map.of()));
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
}
