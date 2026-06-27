package io.casehub.ras.runtime;

import io.casehub.ras.api.*;
import io.casehub.ras.memory.InMemorySituationStore;
import io.casehub.ras.testing.FixedDetectionResult;
import io.casehub.ras.testing.MockCaseTrigger;
import io.casehub.ras.testing.MockGanglion;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import io.smallrye.mutiny.Uni;
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
import java.util.concurrent.atomic.AtomicInteger;
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
                Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")), TRIGGER_CONFIG);
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
                Duration.ofMinutes(5), null, new ChainMode.And(Set.of("g1", "g2")), TRIGGER_CONFIG);
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
                Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")), TRIGGER_CONFIG);
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
                Duration.ofMinutes(1), null, new ChainMode.Count("g1", 2), TRIGGER_CONFIG);
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
                Duration.ofMinutes(5), null, new ChainMode.And(Set.of("g1", "g2")), TRIGGER_CONFIG);
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
                Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")), TRIGGER_CONFIG);
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
                Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")), TRIGGER_CONFIG);

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

    @Test
    void compactInvokedForPersistentSituations() {
        var compactCalls = new AtomicInteger();
        var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
                FixedDetectionResult.detected("g1", 0.4)) {
            @Override
            public Uni<SituationContext> compact(SituationContext context) {
                compactCalls.incrementAndGet();
                if (context.detections().size() > 1) {
                    var latest = context.detections().get(context.detections().size() - 1);
                    return Uni.createFrom().item(new SituationContext(
                            context.situationId(), context.correlationKey(), context.tenancyId(),
                            context.firstSignal(), context.lastSignal(), List.of(latest)));
                }
                return Uni.createFrom().item(context);
            }
        };
        // null correlationWindow → persistent situation
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                null, null, new ChainMode.Count("g1", 5), TRIGGER_CONFIG);
        buildEvaluator(List.of(ganglion), def);

        evaluator.evaluate(event("temp.reading", T1), def, "key-1", "tenant-a");
        evaluator.evaluate(event("temp.reading", T2), def, "key-1", "tenant-a");

        assertThat(compactCalls.get()).isEqualTo(2);
        var saved = store.find("sit-1", "key-1", "tenant-a").await().indefinitely();
        assertThat(saved).isPresent();
        // compact() kept only the latest detection each time
        assertThat(saved.get().detections()).hasSize(1);
    }

    @Test
    void compactNotInvokedForWindowedSituations() {
        var compactCalls = new AtomicInteger();
        var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
                FixedDetectionResult.detected("g1", 0.4)) {
            @Override
            public Uni<SituationContext> compact(SituationContext context) {
                compactCalls.incrementAndGet();
                return Uni.createFrom().item(context);
            }
        };
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                Duration.ofMinutes(5), null, new ChainMode.Count("g1", 5), TRIGGER_CONFIG);
        buildEvaluator(List.of(ganglion), def);

        evaluator.evaluate(event("temp.reading", T1), def, "key-1", "tenant-a");
        evaluator.evaluate(event("temp.reading", T2), def, "key-1", "tenant-a");

        assertThat(compactCalls.get()).isZero();
        var saved = store.find("sit-1", "key-1", "tenant-a").await().indefinitely();
        assertThat(saved).isPresent();
        assertThat(saved.get().detections()).hasSize(2);
    }

    @Test
    void nullBufferDelayProcessesImmediately() {
        var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
                FixedDetectionResult.detected("g1", 0.9));
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")), TRIGGER_CONFIG);
        buildEvaluator(List.of(ganglion), def);

        evaluator.evaluate(event("temp.reading", T1), def, "key-1", "tenant-a");

        assertThat(caseTrigger.firedCases()).hasSize(1);
    }

    @Test
    void bufferReordersOutOfOrderEvents() {
        var detections = new java.util.ArrayList<Instant>();
        var ganglion = new Ganglion() {
            @Override public String ganglionId() { return "g1"; }
            @Override public Set<String> handledEventTypes() { return Set.of("temp.reading"); }
            @Override public io.smallrye.mutiny.Uni<DetectionResult> detect(
                    io.cloudevents.CloudEvent event, SituationContext context) {
                detections.add(event.getTime().toInstant());
                return io.smallrye.mutiny.Uni.createFrom().item(
                        FixedDetectionResult.detected("g1", 0.4));
            }
        };
        // 5-second buffer, Count(g1, 3) so it accumulates
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                Duration.ofMinutes(5), Duration.ofSeconds(5),
                new ChainMode.Count("g1", 3), TRIGGER_CONFIG);
        buildEvaluator(List.of(ganglion), def);

        var t10 = Instant.parse("2026-06-25T10:00:10Z");
        var t5 = Instant.parse("2026-06-25T10:00:05Z");
        var t3 = Instant.parse("2026-06-25T10:00:03Z");

        // T=10 arrives first — buffered (watermark = 10-5 = 5, nothing to drain)
        evaluator.evaluate(event("temp.reading", t10), def, "key-1", "tenant-a");
        assertThat(detections).isEmpty();

        // T=5 arrives — buffered. watermark = 10-5 = 5. T=5 <= 5 → released.
        evaluator.evaluate(event("temp.reading", t5), def, "key-1", "tenant-a");
        assertThat(detections).containsExactly(t5);

        // T=3 arrives — late (below watermark). Released immediately.
        evaluator.evaluate(event("temp.reading", t3), def, "key-1", "tenant-a");
        assertThat(detections).containsExactly(t5, t3);
    }

    @Test
    void nullTimeEventBypassesBuffer() {
        var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
                FixedDetectionResult.detected("g1", 0.9));
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                Duration.ofMinutes(5), Duration.ofSeconds(5),
                new ChainMode.Or(Set.of("g1")), TRIGGER_CONFIG);
        buildEvaluator(List.of(ganglion), def);

        CloudEvent noTime = CloudEventBuilder.v1()
                .withId("evt-null").withSource(URI.create("/test"))
                .withType("temp.reading").build();

        evaluator.evaluate(noTime, def, "key-1", "tenant-a");

        assertThat(caseTrigger.firedCases()).hasSize(1);
    }

    @Test
    void midBatchTerminationStopsProcessing() {
        var callCount = new AtomicInteger();
        var ganglion = new Ganglion() {
            @Override public String ganglionId() { return "g1"; }
            @Override public Set<String> handledEventTypes() { return Set.of("temp.reading"); }
            @Override public io.smallrye.mutiny.Uni<DetectionResult> detect(
                    io.cloudevents.CloudEvent event, SituationContext context) {
                callCount.incrementAndGet();
                return io.smallrye.mutiny.Uni.createFrom().item(
                        FixedDetectionResult.detected("g1", 0.9));
            }
        };
        // Or mode with 1 ganglion → first detection triggers CREATE_CASE
        // Buffer with large delay so all events stay buffered until a late event releases them
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                Duration.ofMinutes(5), Duration.ofSeconds(2),
                new ChainMode.Or(Set.of("g1")), TRIGGER_CONFIG);
        buildEvaluator(List.of(ganglion), def);

        var t1 = Instant.parse("2026-06-25T10:00:01Z");
        var t2 = Instant.parse("2026-06-25T10:00:02Z");
        var t10 = Instant.parse("2026-06-25T10:00:10Z");

        // Buffer t1 and t2
        evaluator.evaluate(event("temp.reading", t1), def, "key-1", "tenant-a");
        evaluator.evaluate(event("temp.reading", t2), def, "key-1", "tenant-a");
        assertThat(callCount.get()).isZero();

        // t10 arrives — watermark = 10-2 = 8. All three events released.
        // t1 triggers CREATE_CASE → loop stops → t2 and t10 not processed.
        evaluator.evaluate(event("temp.reading", t10), def, "key-1", "tenant-a");
        assertThat(callCount.get()).isEqualTo(1);
        assertThat(caseTrigger.firedCases()).hasSize(1);
    }

    @Test
    void idleFlushProcessesBufferedEvents() {
        var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
                FixedDetectionResult.detected("g1", 0.9));
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                Duration.ofMinutes(5), Duration.ofSeconds(5),
                new ChainMode.Or(Set.of("g1")), TRIGGER_CONFIG);
        buildEvaluator(List.of(ganglion), def);

        // Event arrives, stays buffered (within delay window)
        var t1 = Instant.parse("2026-06-25T10:00:01Z");
        evaluator.evaluate(event("temp.reading", t1), def, "key-1", "tenant-a");
        assertThat(caseTrigger.firedCases()).isEmpty();

        // Simulate idle flush after bufferDelay
        Instant flushTime = Instant.now().plusSeconds(10);
        evaluator.flushIdleBuffers(flushTime);

        assertThat(caseTrigger.firedCases()).hasSize(1);
    }
}
