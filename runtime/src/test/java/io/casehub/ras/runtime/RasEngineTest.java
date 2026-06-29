package io.casehub.ras.runtime;

import io.casehub.ras.api.*;
import io.casehub.ras.persistence.memory.InMemorySituationStore;
import io.casehub.ras.testing.FixedDetectionResult;
import io.casehub.ras.testing.MockCaseTrigger;
import io.casehub.ras.testing.MockGanglion;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

class RasEngineTest {

    private static final Instant T1 = Instant.parse("2026-06-25T10:00:00Z");
    private static final CaseTriggerConfig TRIGGER =
            new CaseTriggerConfig("ns", "case", "1.0", Map.of());

    private CloudEvent event(String type, String tenancyId) {
        var builder = CloudEventBuilder.v1()
                .withId("evt-1").withSource(URI.create("/test")).withType(type)
                .withTime(OffsetDateTime.ofInstant(T1, ZoneOffset.UTC));
        if (tenancyId != null) {
            builder = builder.withExtension("tenancyid", tenancyId);
        }
        return builder.build();
    }

    @Test
    void routesEventToMatchingDefinition() {
        var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
                FixedDetectionResult.detected("g1", 0.9));
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")), TRIGGER);
        var reg = new SituationRegistration(def);
        var registry = new SituationDefinitionRegistry(
                List.of(() -> List.of(reg)), List.of(ganglion));
        var store = new InMemorySituationStore();
        var caseTrigger = new MockCaseTrigger();
        var evaluator = new SituationEvaluator(store, new DefaultRasTriggerPolicy(),
                caseTrigger, registry, 3);
        var engine = new RasEngine(registry, evaluator);

        engine.onCloudEvent(event("temp.reading", "tenant-a"));

        assertThat(caseTrigger.firedCases()).hasSize(1);
    }

    @Test
    void skipsEventWithoutTenancyId() {
        var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
                FixedDetectionResult.detected("g1", 0.9));
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")), TRIGGER);
        var registry = new SituationDefinitionRegistry(
                List.of(() -> List.of(new SituationRegistration(def))), List.of(ganglion));
        var store = new InMemorySituationStore();
        var caseTrigger = new MockCaseTrigger();
        var evaluator = new SituationEvaluator(store, new DefaultRasTriggerPolicy(),
                caseTrigger, registry, 3);
        var engine = new RasEngine(registry, evaluator);

        engine.onCloudEvent(event("temp.reading", null));

        assertThat(ganglion.callCount()).isEqualTo(0);
        assertThat(caseTrigger.firedCases()).isEmpty();
    }

    @Test
    void unmatchedEventTypeIsIgnored() {
        var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
                FixedDetectionResult.detected("g1", 0.9));
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")), TRIGGER);
        var registry = new SituationDefinitionRegistry(
                List.of(() -> List.of(new SituationRegistration(def))), List.of(ganglion));
        var store = new InMemorySituationStore();
        var caseTrigger = new MockCaseTrigger();
        var evaluator = new SituationEvaluator(store, new DefaultRasTriggerPolicy(),
                caseTrigger, registry, 3);
        var engine = new RasEngine(registry, evaluator);

        engine.onCloudEvent(event("unknown.type", "tenant-a"));

        assertThat(ganglion.callCount()).isEqualTo(0);
    }
}
