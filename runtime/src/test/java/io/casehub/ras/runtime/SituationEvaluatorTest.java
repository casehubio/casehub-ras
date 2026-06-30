package io.casehub.ras.runtime;

import io.casehub.ras.api.*;
import io.casehub.ras.persistence.memory.InMemorySituationStore;
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
import java.util.Optional;
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
        evaluator = new SituationEvaluator(store, policy, caseTrigger, registry, 3);
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
                Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")), TRIGGER_CONFIG, null);
        buildEvaluator(List.of(ganglion), def);

        evaluator.evaluate(event("temp.reading", T1), def, "key-1", "tenant-a");

        assertThat(caseTrigger.firedCases()).hasSize(1);
        assertThat(store.find("sit-1", "key-1", "tenant-a").await().indefinitely()).isPresent();
    }

    @Test
    void andModeAccumulatesUntilAllFired() {
        var g1 = new MockGanglion("g1", Set.of("temp.reading"),
                FixedDetectionResult.detected("g1", 0.9));
        var g2 = new MockGanglion("g2", Set.of("vibration.reading"),
                FixedDetectionResult.detected("g2", 0.8));
        var def = new SituationDefinition("sit-1",
                Set.of("temp.reading", "vibration.reading"),
                Duration.ofMinutes(5), null, new ChainMode.And(Set.of("g1", "g2")), TRIGGER_CONFIG, null);
        buildEvaluator(List.of(g1, g2), def);

        evaluator.evaluate(event("temp.reading", T1), def, "key-1", "tenant-a");
        assertThat(caseTrigger.firedCases()).isEmpty();
        assertThat(store.find("sit-1", "key-1", "tenant-a").await().indefinitely()).isPresent();

        evaluator.evaluate(event("vibration.reading", T2), def, "key-1", "tenant-a");
        assertThat(caseTrigger.firedCases()).hasSize(1);
        assertThat(store.find("sit-1", "key-1", "tenant-a").await().indefinitely()).isPresent();
    }

    @Test
    void noiseDetectionDoesNotTrigger() {
        var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
                FixedDetectionResult.noise("g1"));
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")), TRIGGER_CONFIG, null);
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
                Duration.ofMinutes(1), null, new ChainMode.Count("g1", 2), TRIGGER_CONFIG, null);
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
                Duration.ofMinutes(5), null, new ChainMode.And(Set.of("g1", "g2")), TRIGGER_CONFIG, null);
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
                Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")), TRIGGER_CONFIG, null);
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
                Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")), TRIGGER_CONFIG, null);

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
        evaluator = new SituationEvaluator(store, policy, failOnceTrigger, registry, 3);

        evaluator.evaluate(event("temp.reading", T1), def, "key-1", "tenant-a");

        var saved = store.find("sit-1", "key-1", "tenant-a").await().indefinitely();
        assertThat(saved).isPresent();
        assertThat(saved.get().detections()).hasSize(1);
        assertThat(saved.get().detections().get(0).result().signal()).isEqualTo(DetectionSignal.DETECTED);

        evaluator.evaluate(event("temp.reading", T2), def, "key-1", "tenant-a");
        var afterRetry = store.find("sit-1", "key-1", "tenant-a").await().indefinitely();
        assertThat(afterRetry).isPresent();
        boolean claimTaken = !store.tryClaimTrigger("sit-1", "key-1", "tenant-a", T2)
                .await().indefinitely();
        assertThat(claimTaken).isTrue();
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
                            context.firstSignal(), context.lastSignal(), List.of(latest),
                            context.storeVersion(), null, 0));
                }
                return Uni.createFrom().item(context);
            }
        };
        // null correlationWindow → persistent situation
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                null, null, new ChainMode.Count("g1", 5), TRIGGER_CONFIG, null);
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
                Duration.ofMinutes(5), null, new ChainMode.Count("g1", 5), TRIGGER_CONFIG, null);
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
                Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")), TRIGGER_CONFIG, null);
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
                new ChainMode.Count("g1", 3), TRIGGER_CONFIG, null);
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
                new ChainMode.Or(Set.of("g1")), TRIGGER_CONFIG, null);
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
                new ChainMode.Or(Set.of("g1")), TRIGGER_CONFIG, null);
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
                new ChainMode.Or(Set.of("g1")), TRIGGER_CONFIG, null);
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

    // --- Conflict retry tests ---

    @Test
    void conflictOnSaveRetriesAndSucceeds() {
        var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
                FixedDetectionResult.detected("g1", 0.9));
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")), TRIGGER_CONFIG, null);

        var conflictStore = new ConflictSimulatingStore(store, 1);
        var reg = new SituationRegistration(def);
        var registry = new SituationDefinitionRegistry(
                List.of(() -> List.of(reg)), List.of(ganglion));
        evaluator = new SituationEvaluator(conflictStore, policy, caseTrigger, registry, 3);

        evaluator.evaluate(event("temp.reading", T1), def, "key-1", "tenant-a");

        assertThat(caseTrigger.firedCases()).hasSize(1);
    }

    @Test
    void allRetriesExhaustedLosesEvent() {
        var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
                FixedDetectionResult.detected("g1", 0.4));
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                Duration.ofMinutes(5), null, new ChainMode.Count("g1", 3), TRIGGER_CONFIG, null);

        var alwaysConflict = new ConflictSimulatingStore(store, Integer.MAX_VALUE);
        var reg = new SituationRegistration(def);
        var registry = new SituationDefinitionRegistry(
                List.of(() -> List.of(reg)), List.of(ganglion));
        evaluator = new SituationEvaluator(alwaysConflict, policy, caseTrigger, registry, 3);

        evaluator.evaluate(event("temp.reading", T1), def, "key-1", "tenant-a");

        // Event lost — nothing saved, no case triggered
        assertThat(caseTrigger.firedCases()).isEmpty();
        assertThat(store.find("sit-1", "key-1", "tenant-a").await().indefinitely()).isEmpty();
    }

    @Test
    void detectionNotRecomputedOnRetry() {
        var detectCount = new AtomicInteger();
        var ganglion = new Ganglion() {
            @Override public String ganglionId() { return "g1"; }
            @Override public Set<String> handledEventTypes() { return Set.of("temp.reading"); }
            @Override public Uni<DetectionResult> detect(CloudEvent event, SituationContext context) {
                detectCount.incrementAndGet();
                return Uni.createFrom().item(FixedDetectionResult.detected("g1", 0.4));
            }
        };
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                Duration.ofMinutes(5), null, new ChainMode.Count("g1", 3), TRIGGER_CONFIG, null);

        var conflictStore = new ConflictSimulatingStore(store, 2);
        var reg = new SituationRegistration(def);
        var registry = new SituationDefinitionRegistry(
                List.of(() -> List.of(reg)), List.of(ganglion));
        evaluator = new SituationEvaluator(conflictStore, policy, caseTrigger, registry, 3);

        evaluator.evaluate(event("temp.reading", T1), def, "key-1", "tenant-a");

        assertThat(detectCount.get()).isEqualTo(1);
    }

    @Test
    void compactionRerunOnRetry() {
        var compactCalls = new AtomicInteger();
        var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
                FixedDetectionResult.detected("g1", 0.4)) {
            @Override
            public Uni<SituationContext> compact(SituationContext context) {
                compactCalls.incrementAndGet();
                return Uni.createFrom().item(context);
            }
        };
        // null correlationWindow → persistent → compact invoked
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                null, null, new ChainMode.Count("g1", 5), TRIGGER_CONFIG, null);

        var conflictStore = new ConflictSimulatingStore(store, 1);
        var reg = new SituationRegistration(def);
        var registry = new SituationDefinitionRegistry(
                List.of(() -> List.of(reg)), List.of(ganglion));
        evaluator = new SituationEvaluator(conflictStore, policy, caseTrigger, registry, 3);

        evaluator.evaluate(event("temp.reading", T1), def, "key-1", "tenant-a");

        // compact called on first attempt (conflict) + second attempt (success) = 2
        assertThat(compactCalls.get()).isEqualTo(2);
    }

    @Test
    void winnerRemovedSituationRetryCreatesFreshContext() {
        var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
                FixedDetectionResult.detected("g1", 0.4));
        // Count mode — needs 3 detections, so a single event → CONTINUE_ACCUMULATING
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                Duration.ofMinutes(5), null, new ChainMode.Count("g1", 3), TRIGGER_CONFIG, null);

        // Store that simulates: first save conflicts, AND winner removed the situation
        var conflictAndRemoveStore = new SituationStore() {
            private final SituationStore delegate = store;
            private boolean conflicted = false;

            @Override
            public Uni<Optional<SituationContext>> find(String situationId, String correlationKey, String tenancyId) {
                return delegate.find(situationId, correlationKey, tenancyId);
            }

            @Override
            public Uni<SituationContext> save(SituationContext context) {
                if (!conflicted) {
                    conflicted = true;
                    // Simulate winner saving and then removing (CREATE_CASE path)
                    delegate.save(context).await().indefinitely();
                    delegate.remove(context.situationId(), context.correlationKey(), context.tenancyId()).await().indefinitely();
                    throw new SituationConflictException("Simulated conflict", null);
                }
                return delegate.save(context);
            }

            @Override
            public Uni<Void> remove(String situationId, String correlationKey, String tenancyId) {
                return delegate.remove(situationId, correlationKey, tenancyId);
            }

            @Override
            public Uni<Void> removeExpired(Instant cutoff) {
                return delegate.removeExpired(cutoff);
            }
        };

        var reg = new SituationRegistration(def);
        var registry = new SituationDefinitionRegistry(
                List.of(() -> List.of(reg)), List.of(ganglion));
        evaluator = new SituationEvaluator(conflictAndRemoveStore, policy, caseTrigger, registry, 3);

        evaluator.evaluate(event("temp.reading", T1), def, "key-1", "tenant-a");

        // Retry created fresh context, applied detection, CONTINUE_ACCUMULATING → saved
        var saved = store.find("sit-1", "key-1", "tenant-a").await().indefinitely();
        assertThat(saved).isPresent();
        assertThat(saved.get().detections()).hasSize(1);
        assertThat(saved.get().storeVersion()).isPresent();
    }

    // --- Claim coordination tests ---

    @Test
    void firstEventCreateCaseSavesAndClaims() {
        var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
                FixedDetectionResult.detected("g1", 0.9));
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")), TRIGGER_CONFIG, null);
        buildEvaluator(List.of(ganglion), def);

        evaluator.evaluate(event("temp.reading", T1), def, "key-1", "tenant-a");

        assertThat(caseTrigger.firedCases()).hasSize(1);
        var saved = store.find("sit-1", "key-1", "tenant-a").await().indefinitely();
        assertThat(saved).isPresent();
        boolean secondClaim = store.tryClaimTrigger("sit-1", "key-1", "tenant-a", T1)
                .await().indefinitely();
        assertThat(secondClaim).isFalse();
    }

    @Test
    void claimPreventsDuplicateTrigger() {
        var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
                FixedDetectionResult.detected("g1", 0.9));
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")), TRIGGER_CONFIG, null);

        var claimOnceStore = new ClaimTrackingStore(store);
        var reg = new SituationRegistration(def);
        var registry = new SituationDefinitionRegistry(
                List.of(() -> List.of(reg)), List.of(ganglion));
        evaluator = new SituationEvaluator(claimOnceStore, policy, caseTrigger, registry, 3);

        evaluator.evaluate(event("temp.reading", T1), def, "key-1", "tenant-a");
        assertThat(caseTrigger.firedCases()).hasSize(1);

        evaluator.evaluate(event("temp.reading", T2), def, "key-1", "tenant-a");
        assertThat(caseTrigger.firedCases()).hasSize(1);
    }

    @Test
    void existingEntityClaimFailsNoSave() {
        var g1 = new MockGanglion("g1", Set.of("temp.reading"),
                FixedDetectionResult.detected("g1", 0.4));
        var g2 = new MockGanglion("g2", Set.of("vibration.reading"),
                FixedDetectionResult.detected("g2", 0.8));
        var def = new SituationDefinition("sit-1",
                Set.of("temp.reading", "vibration.reading"),
                Duration.ofMinutes(5), null, new ChainMode.And(Set.of("g1", "g2")), TRIGGER_CONFIG, null);
        buildEvaluator(List.of(g1, g2), def);

        evaluator.evaluate(event("temp.reading", T1), def, "key-1", "tenant-a");
        var afterFirst = store.find("sit-1", "key-1", "tenant-a").await().indefinitely().orElseThrow();
        Instant firstLastSignal = afterFirst.lastSignal();

        store.tryClaimTrigger("sit-1", "key-1", "tenant-a", T1).await().indefinitely();

        evaluator.evaluate(event("vibration.reading", T2), def, "key-1", "tenant-a");
        assertThat(caseTrigger.firedCases()).isEmpty();

        var afterSecond = store.find("sit-1", "key-1", "tenant-a").await().indefinitely().orElseThrow();
        assertThat(afterSecond.lastSignal()).isEqualTo(firstLastSignal);
    }

    @Test
    void triggerFailureAfterClaimResets() {
        var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
                FixedDetectionResult.detected("g1", 0.9));
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")), TRIGGER_CONFIG, null);

        var failingTrigger = new CaseTrigger() {
            @Override
            public Uni<java.util.UUID> fire(CaseTriggerConfig config, SituationContext context) {
                return Uni.createFrom().failure(new RuntimeException("Trigger failed"));
            }
        };

        var reg = new SituationRegistration(def);
        var registry = new SituationDefinitionRegistry(
                List.of(() -> List.of(reg)), List.of(ganglion));
        evaluator = new SituationEvaluator(store, policy, failingTrigger, registry, 3);

        evaluator.evaluate(event("temp.reading", T1), def, "key-1", "tenant-a");

        var saved = store.find("sit-1", "key-1", "tenant-a").await().indefinitely();
        assertThat(saved).isPresent();
        boolean reclaimable = store.tryClaimTrigger("sit-1", "key-1", "tenant-a", T1)
                .await().indefinitely();
        assertThat(reclaimable).isTrue();
    }

    @Test
    void triggerFailureRecoveryOnNextEvent() {
        var callCount = new AtomicInteger();
        var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
                FixedDetectionResult.detected("g1", 0.9));
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")), TRIGGER_CONFIG, null);

        var failOnceTrigger = new CaseTrigger() {
            @Override
            public Uni<java.util.UUID> fire(CaseTriggerConfig config, SituationContext context) {
                if (callCount.incrementAndGet() == 1) {
                    return Uni.createFrom().failure(new RuntimeException("Transient"));
                }
                return Uni.createFrom().item(java.util.UUID.randomUUID());
            }
        };

        var reg = new SituationRegistration(def);
        var registry = new SituationDefinitionRegistry(
                List.of(() -> List.of(reg)), List.of(ganglion));
        evaluator = new SituationEvaluator(store, policy, failOnceTrigger, registry, 3);

        evaluator.evaluate(event("temp.reading", T1), def, "key-1", "tenant-a");
        assertThat(callCount.get()).isEqualTo(1);

        evaluator.evaluate(event("temp.reading", T2), def, "key-1", "tenant-a");
        assertThat(callCount.get()).isEqualTo(2);
    }

    @Test
    void postTriggerEventsDoNotRefreshLastSignal() {
        var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
                FixedDetectionResult.detected("g1", 0.9));
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")), TRIGGER_CONFIG, null);
        buildEvaluator(List.of(ganglion), def);

        evaluator.evaluate(event("temp.reading", T1), def, "key-1", "tenant-a");
        assertThat(caseTrigger.firedCases()).hasSize(1);

        var afterTrigger = store.find("sit-1", "key-1", "tenant-a").await().indefinitely().orElseThrow();
        Instant originalLastSignal = afterTrigger.lastSignal();

        evaluator.evaluate(event("temp.reading", T2), def, "key-1", "tenant-a");
        assertThat(caseTrigger.firedCases()).hasSize(1);

        var afterSecond = store.find("sit-1", "key-1", "tenant-a").await().indefinitely().orElseThrow();
        assertThat(afterSecond.lastSignal()).isEqualTo(originalLastSignal);
    }

    @Test
    void claimSucceedsSaveFailsResetsAndRetries() {
        var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
                FixedDetectionResult.detected("g1", 0.4));
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                Duration.ofMinutes(5), null, new ChainMode.Count("g1", 2), TRIGGER_CONFIG, null);

        var ctx = SituationContext.initial("sit-1", "key-1", "tenant-a", T1);
        store.save(ctx).await().indefinitely();

        var conflictOnSecondSave = new ClaimTrackingStore(store) {
            private int saveCount = 0;
            @Override
            public Uni<SituationContext> save(SituationContext context) {
                saveCount++;
                if (saveCount == 2) {
                    throw new SituationConflictException("Simulated version conflict", null);
                }
                return super.save(context);
            }
        };

        var reg = new SituationRegistration(def);
        var registry = new SituationDefinitionRegistry(
                List.of(() -> List.of(reg)), List.of(ganglion));
        evaluator = new SituationEvaluator(conflictOnSecondSave, policy, caseTrigger, registry, 3);

        evaluator.evaluate(event("temp.reading", T1), def, "key-1", "tenant-a");

        boolean claimAvailable = store.tryClaimTrigger("sit-1", "key-1", "tenant-a", T1)
                .await().indefinitely();
        assertThat(claimAvailable).isTrue();
    }

    private static class ConflictSimulatingStore implements SituationStore {
        private final SituationStore delegate;
        private int conflictsRemaining;

        ConflictSimulatingStore(SituationStore delegate, int conflictCount) {
            this.delegate = delegate;
            this.conflictsRemaining = conflictCount;
        }

        @Override
        public Uni<Optional<SituationContext>> find(String situationId, String correlationKey,
                                                     String tenancyId) {
            return delegate.find(situationId, correlationKey, tenancyId);
        }

        @Override
        public Uni<SituationContext> save(SituationContext context) {
            if (conflictsRemaining > 0) {
                conflictsRemaining--;
                throw new SituationConflictException("Simulated conflict", null);
            }
            return delegate.save(context);
        }

        @Override
        public Uni<Void> remove(String situationId, String correlationKey, String tenancyId) {
            return delegate.remove(situationId, correlationKey, tenancyId);
        }

        @Override
        public Uni<Void> removeExpired(Instant cutoff) {
            return delegate.removeExpired(cutoff);
        }

        @Override
        public Uni<Boolean> tryClaimTrigger(String situationId, String correlationKey,
                                             String tenancyId, Instant triggerTime) {
            return delegate.tryClaimTrigger(situationId, correlationKey, tenancyId, triggerTime);
        }

        @Override
        public Uni<Void> resetTriggerClaim(String situationId, String correlationKey,
                                            String tenancyId) {
            return delegate.resetTriggerClaim(situationId, correlationKey, tenancyId);
        }
    }

    private static class ClaimTrackingStore implements SituationStore {
        private final SituationStore delegate;

        ClaimTrackingStore(SituationStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public Uni<Optional<SituationContext>> find(String situationId, String correlationKey,
                                                     String tenancyId) {
            return delegate.find(situationId, correlationKey, tenancyId);
        }

        @Override
        public Uni<SituationContext> save(SituationContext context) {
            return delegate.save(context);
        }

        @Override
        public Uni<Void> remove(String situationId, String correlationKey, String tenancyId) {
            return delegate.remove(situationId, correlationKey, tenancyId);
        }

        @Override
        public Uni<Void> removeExpired(Instant cutoff) {
            return delegate.removeExpired(cutoff);
        }

        @Override
        public Uni<Boolean> tryClaimTrigger(String situationId, String correlationKey,
                                             String tenancyId, Instant triggerTime) {
            return delegate.tryClaimTrigger(situationId, correlationKey, tenancyId, triggerTime);
        }

        @Override
        public Uni<Void> resetTriggerClaim(String situationId, String correlationKey,
                                            String tenancyId) {
            return delegate.resetTriggerClaim(situationId, correlationKey, tenancyId);
        }
    }
}
