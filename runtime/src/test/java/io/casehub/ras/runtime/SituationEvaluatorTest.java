package io.casehub.ras.runtime;

import io.casehub.ras.api.*;
import io.casehub.ras.memory.InMemorySituationStore;
import io.casehub.ras.testing.FixedDetectionResult;
import io.casehub.ras.testing.MockCaseTrigger;
import io.casehub.ras.testing.MockGanglion;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import org.junit.jupiter.api.BeforeEach;
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

class SituationEvaluatorTest {

    private InMemorySituationStore store;
    private MockCaseTrigger caseTrigger;
    private DefaultRasTriggerPolicy policy;
    private SituationEvaluator evaluator;

    private static final Instant T1 = Instant.parse("2026-06-25T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-06-25T10:01:00Z");
    private static final CaseTriggerConfig TRIGGER_CONFIG =
            new CaseTriggerConfig("ns", "case", "1.0", Map.of());

    @BeforeEach
    void setUp() {
        store = new InMemorySituationStore();
        caseTrigger = new MockCaseTrigger();
        policy = new DefaultRasTriggerPolicy();
    }

    private void buildEvaluator(List<Ganglion> ganglia, SituationDefinition def) {
        var reg = new SituationRegistration(def);
        var registry = new SituationDefinitionRegistry(
                List.of(() -> List.of(reg)), ganglia);
        evaluator = new SituationEvaluator(store, policy, caseTrigger, registry);
    }

    private CloudEvent event(String type, Instant time) {
        return CloudEventBuilder.v1()
                .withId("evt-1")
                .withSource(URI.create("/test"))
                .withType(type)
                .withTime(OffsetDateTime.ofInstant(time, ZoneOffset.UTC))
                .build();
    }

    @Test
    void singleGanglionOrModeTriggersCase() {
        var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
                FixedDetectionResult.detected("g1", 0.9));
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                Duration.ofMinutes(5), new ChainMode.Or(Set.of("g1")), TRIGGER_CONFIG);
        buildEvaluator(List.of(ganglion), def);

        evaluator.evaluate(event("temp.reading", T1), def, "key-1", "tenant-a");

        assertThat(caseTrigger.firedCases()).hasSize(1);
        assertThat(store.find("sit-1", "key-1", "tenant-a").await().indefinitely()).isEmpty();
    }

    @Test
    void andModeAccumulatesUntilAllFired() {
        var g1 = new MockGanglion("g1", Set.of("temp.reading"),
                FixedDetectionResult.detected("g1", 0.9));
        var g2 = new MockGanglion("g2", Set.of("vibration.reading"),
                FixedDetectionResult.detected("g2", 0.8));
        var def = new SituationDefinition("sit-1",
                Set.of("temp.reading", "vibration.reading"),
                Duration.ofMinutes(5), new ChainMode.And(Set.of("g1", "g2")), TRIGGER_CONFIG);
        buildEvaluator(List.of(g1, g2), def);

        evaluator.evaluate(event("temp.reading", T1), def, "key-1", "tenant-a");
        assertThat(caseTrigger.firedCases()).isEmpty();
        assertThat(store.find("sit-1", "key-1", "tenant-a").await().indefinitely()).isPresent();

        evaluator.evaluate(event("vibration.reading", T2), def, "key-1", "tenant-a");
        assertThat(caseTrigger.firedCases()).hasSize(1);
        assertThat(store.find("sit-1", "key-1", "tenant-a").await().indefinitely()).isEmpty();
    }

    @Test
    void noiseDetectionDoesNotTrigger() {
        var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
                FixedDetectionResult.noise("g1"));
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                Duration.ofMinutes(5), new ChainMode.Or(Set.of("g1")), TRIGGER_CONFIG);
        buildEvaluator(List.of(ganglion), def);

        evaluator.evaluate(event("temp.reading", T1), def, "key-1", "tenant-a");

        assertThat(caseTrigger.firedCases()).isEmpty();
        assertThat(store.find("sit-1", "key-1", "tenant-a").await().indefinitely()).isPresent();
    }

    @Test
    void windowExpiryResetsContext() {
        var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
                FixedDetectionResult.detected("g1", 0.9));
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                Duration.ofMinutes(1), new ChainMode.Count("g1", 2), TRIGGER_CONFIG);
        buildEvaluator(List.of(ganglion), def);

        evaluator.evaluate(event("temp.reading", T1), def, "key-1", "tenant-a");
        assertThat(store.find("sit-1", "key-1", "tenant-a").await().indefinitely())
                .isPresent().get().satisfies(ctx ->
                        assertThat(ctx.detections()).hasSize(1));

        // T1 + 2 minutes > 1 minute window → expired → fresh context
        Instant expired = T1.plus(Duration.ofMinutes(2));
        evaluator.evaluate(event("temp.reading", expired), def, "key-1", "tenant-a");
        assertThat(store.find("sit-1", "key-1", "tenant-a").await().indefinitely())
                .isPresent().get().satisfies(ctx ->
                        assertThat(ctx.detections()).hasSize(1));
    }

    @Test
    void ganglionNotHandlingEventTypeIsNotDispatched() {
        var g1 = new MockGanglion("g1", Set.of("temp.reading"),
                FixedDetectionResult.detected("g1", 0.9));
        var g2 = new MockGanglion("g2", Set.of("vibration.reading"),
                FixedDetectionResult.detected("g2", 0.8));
        var def = new SituationDefinition("sit-1",
                Set.of("temp.reading", "vibration.reading"),
                Duration.ofMinutes(5), new ChainMode.And(Set.of("g1", "g2")), TRIGGER_CONFIG);
        buildEvaluator(List.of(g1, g2), def);

        evaluator.evaluate(event("temp.reading", T1), def, "key-1", "tenant-a");

        assertThat(g1.callCount()).isEqualTo(1);
        assertThat(g2.callCount()).isEqualTo(0);
    }

    @Test
    void nullEventTimeFallsBackToProcessingTime() {
        var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
                FixedDetectionResult.detected("g1", 0.9));
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                Duration.ofMinutes(5), new ChainMode.Or(Set.of("g1")), TRIGGER_CONFIG);
        buildEvaluator(List.of(ganglion), def);

        CloudEvent noTime = CloudEventBuilder.v1()
                .withId("evt-1").withSource(URI.create("/test")).withType("temp.reading")
                .build();

        evaluator.evaluate(noTime, def, "key-1", "tenant-a");
        assertThat(caseTrigger.firedCases()).hasSize(1);
    }

    @Test
    void caseTriggerFailureRetainsContextForRetry() {
        var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
                FixedDetectionResult.detected("g1", 0.9));
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                Duration.ofMinutes(5), new ChainMode.Or(Set.of("g1")), TRIGGER_CONFIG);

        var failOnceTrigger = new CaseTrigger() {
            private int callCount = 0;
            @Override
            public io.smallrye.mutiny.Uni<java.util.UUID> fire(CaseTriggerConfig config, SituationContext context) {
                callCount++;
                if (callCount == 1) {
                    return io.smallrye.mutiny.Uni.createFrom().failure(
                            new RuntimeException("Transient failure"));
                }
                return io.smallrye.mutiny.Uni.createFrom().item(java.util.UUID.randomUUID());
            }
        };

        var registry = new SituationDefinitionRegistry(
                List.of(() -> List.of(new SituationRegistration(def))),
                List.of(ganglion));
        evaluator = new SituationEvaluator(store, policy, failOnceTrigger, registry);

        evaluator.evaluate(event("temp.reading", T1), def, "key-1", "tenant-a");

        var saved = store.find("sit-1", "key-1", "tenant-a").await().indefinitely();
        assertThat(saved).isPresent();
        assertThat(saved.get().detections()).hasSize(1);
        assertThat(saved.get().detections().get(0).result().signal()).isEqualTo(DetectionSignal.DETECTED);

        evaluator.evaluate(event("temp.reading", T2), def, "key-1", "tenant-a");
        assertThat(store.find("sit-1", "key-1", "tenant-a").await().indefinitely()).isEmpty();
    }
}
