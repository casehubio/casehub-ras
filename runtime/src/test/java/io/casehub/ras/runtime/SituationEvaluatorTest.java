package io.casehub.ras.runtime;

import io.casehub.ras.api.CaseTrigger;
import io.casehub.ras.api.CaseTriggerConfig;
import io.casehub.ras.api.ChainMode;
import io.casehub.ras.api.DetectionResult;
import io.casehub.ras.api.DetectionSignal;
import io.casehub.ras.api.Ganglion;
import io.casehub.ras.api.PolicyDecision;
import io.casehub.ras.api.RasTriggerPolicy;
import io.casehub.ras.api.SituationChangeEvent;
import io.casehub.ras.api.SituationConflictException;
import io.casehub.ras.api.SituationContext;
import io.casehub.ras.api.SituationDefinition;
import io.casehub.ras.api.SituationRegistration;
import io.casehub.ras.api.SituationStore;
import io.casehub.ras.api.TriggerAction;
import io.casehub.ras.api.TriggerDecision;
import io.casehub.ras.api.TriggerMode;
import io.casehub.ras.persistence.memory.InMemorySituationStore;
import io.casehub.ras.testing.FixedDetectionResult;
import io.casehub.ras.testing.MockCaseTrigger;
import io.casehub.ras.testing.MockGanglion;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.NotificationOptions;
import jakarta.enterprise.util.TypeLiteral;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class SituationEvaluatorTest {

    private InMemorySituationStore store;
    private MockCaseTrigger caseTrigger;
    private DefaultRasTriggerPolicy policy;
    private SituationEvaluator evaluator;
    private TestChangeEvent changeEvent;
    private SimpleMeterRegistry meterRegistry;
    private RasMetrics metrics;

    private static final Instant T1 = Instant.parse("2026-06-25T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-06-25T10:01:00Z");
    private static final CaseTriggerConfig TRIGGER_CONFIG =
            new CaseTriggerConfig("ns", "case", "1.0", Map.of());

    @BeforeEach
    void setUp() {
        store = new InMemorySituationStore();
        caseTrigger = new MockCaseTrigger();
        policy = new DefaultRasTriggerPolicy();
        changeEvent = new TestChangeEvent();
        meterRegistry = new SimpleMeterRegistry();
    }

    private void buildEvaluator(List<Ganglion> ganglia, SituationDefinition def) {
        var reg = new SituationRegistration(def);
        var registry = new SituationDefinitionRegistry(
                List.of(() -> List.of(reg)), ganglia);
        initMetrics(registry);
        evaluator = new SituationEvaluator(store, policy, caseTrigger, registry, 3, changeEvent, metrics);
    }

    private void initMetrics(SituationDefinitionRegistry registry) {
        metrics = new RasMetrics(registry);
        metrics.setMeterRegistry(meterRegistry);
        metrics.init();
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
                Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")), new TriggerAction.CreateCase(TRIGGER_CONFIG), null);
        buildEvaluator(List.of(ganglion), def);

        evaluator.evaluate(event("temp.reading", T1), def, "key-1", "tenant-a");

        assertThat(caseTrigger.firedCases()).hasSize(1);
        assertThat(store.find("sit-1", "key-1", "tenant-a")).isPresent();
    }

    @Test
    void andModeAccumulatesUntilAllFired() {
        var g1 = new MockGanglion("g1", Set.of("temp.reading"),
                FixedDetectionResult.detected("g1", 0.9));
        var g2 = new MockGanglion("g2", Set.of("vibration.reading"),
                FixedDetectionResult.detected("g2", 0.8));
        var def = new SituationDefinition("sit-1",
                Set.of("temp.reading", "vibration.reading"),
                Duration.ofMinutes(5), null, new ChainMode.And(Set.of("g1", "g2")), new TriggerAction.CreateCase(TRIGGER_CONFIG), null);
        buildEvaluator(List.of(g1, g2), def);

        evaluator.evaluate(event("temp.reading", T1), def, "key-1", "tenant-a");
        assertThat(caseTrigger.firedCases()).isEmpty();
        assertThat(store.find("sit-1", "key-1", "tenant-a")).isPresent();

        evaluator.evaluate(event("vibration.reading", T2), def, "key-1", "tenant-a");
        assertThat(caseTrigger.firedCases()).hasSize(1);
        assertThat(store.find("sit-1", "key-1", "tenant-a")).isPresent();
    }

    @Test
    void noiseDetectionDoesNotTrigger() {
        var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
                FixedDetectionResult.noise("g1"));
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")), new TriggerAction.CreateCase(TRIGGER_CONFIG), null);
        buildEvaluator(List.of(ganglion), def);

        evaluator.evaluate(event("temp.reading", T1), def, "key-1", "tenant-a");

        assertThat(caseTrigger.firedCases()).isEmpty();
        assertThat(store.find("sit-1", "key-1", "tenant-a")).isPresent();
    }

    @Test
    void windowExpiryResetsContext() {
        var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
                FixedDetectionResult.detected("g1", 0.9));
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                Duration.ofMinutes(1), null, new ChainMode.Count("g1", 2), new TriggerAction.CreateCase(TRIGGER_CONFIG), null);
        buildEvaluator(List.of(ganglion), def);

        evaluator.evaluate(event("temp.reading", T1), def, "key-1", "tenant-a");
        assertThat(store.find("sit-1", "key-1", "tenant-a"))
                .isPresent().get().satisfies(ctx ->
                        assertThat(ctx.detections()).hasSize(1));

        // T1 + 2 minutes > 1 minute window → expired → fresh context
        Instant expired = T1.plus(Duration.ofMinutes(2));
        evaluator.evaluate(event("temp.reading", expired), def, "key-1", "tenant-a");
        assertThat(store.find("sit-1", "key-1", "tenant-a"))
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
                Duration.ofMinutes(5), null, new ChainMode.And(Set.of("g1", "g2")), new TriggerAction.CreateCase(TRIGGER_CONFIG), null);
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
                Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")), new TriggerAction.CreateCase(TRIGGER_CONFIG), null);
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
                Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")), new TriggerAction.CreateCase(TRIGGER_CONFIG), null);

        var failOnceTrigger = new CaseTrigger() {
            private int callCount = 0;
            @Override
            public java.util.UUID fire(CaseTriggerConfig config, SituationContext context) {
                callCount++;
                if (callCount == 1) {
                    throw new RuntimeException("Transient failure");
                }
                return java.util.UUID.randomUUID();
            }
        };

        var registry = new SituationDefinitionRegistry(
                List.of(() -> List.of(new SituationRegistration(def))),
                List.of(ganglion));
        initMetrics(registry);
        evaluator = new SituationEvaluator(store, policy, failOnceTrigger, registry, 3, changeEvent, metrics);

        evaluator.evaluate(event("temp.reading", T1), def, "key-1", "tenant-a");

        var saved = store.find("sit-1", "key-1", "tenant-a");
        assertThat(saved).isPresent();
        assertThat(saved.get().detections()).hasSize(1);
        assertThat(saved.get().detections().get(0).result().signal()).isEqualTo(DetectionSignal.DETECTED);

        evaluator.evaluate(event("temp.reading", T2), def, "key-1", "tenant-a");
        var afterRetry = store.find("sit-1", "key-1", "tenant-a");
        assertThat(afterRetry).isPresent();
        boolean claimTaken = !store.tryClaimTrigger("sit-1", "key-1", "tenant-a", T2)
                ;
        assertThat(claimTaken).isTrue();
    }

    @Test
    void compactInvokedForPersistentSituations() {
        var compactCalls = new AtomicInteger();
        var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
                FixedDetectionResult.detected("g1", 0.4)) {
            @Override
            public SituationContext compact(SituationContext context) {
                compactCalls.incrementAndGet();
                if (context.detections().size() > 1) {
                    var latest = context.detections().get(context.detections().size() - 1);
                    return new SituationContext(
                            context.situationId(), context.correlationKey(), context.tenancyId(),
                            context.firstSignal(), context.lastSignal(), List.of(latest),
                            context.storeVersion(), null, 0);
                }
                return context;
            }
        };
        // null correlationWindow → persistent situation
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                null, null, new ChainMode.Count("g1", 5), new TriggerAction.CreateCase(TRIGGER_CONFIG), null);
        buildEvaluator(List.of(ganglion), def);

        evaluator.evaluate(event("temp.reading", T1), def, "key-1", "tenant-a");
        evaluator.evaluate(event("temp.reading", T2), def, "key-1", "tenant-a");

        assertThat(compactCalls.get()).isEqualTo(2);
        var saved = store.find("sit-1", "key-1", "tenant-a");
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
            public SituationContext compact(SituationContext context) {
                compactCalls.incrementAndGet();
                return context;
            }
        };
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                Duration.ofMinutes(5), null, new ChainMode.Count("g1", 5), new TriggerAction.CreateCase(TRIGGER_CONFIG), null);
        buildEvaluator(List.of(ganglion), def);

        evaluator.evaluate(event("temp.reading", T1), def, "key-1", "tenant-a");
        evaluator.evaluate(event("temp.reading", T2), def, "key-1", "tenant-a");

        assertThat(compactCalls.get()).isZero();
        var saved = store.find("sit-1", "key-1", "tenant-a");
        assertThat(saved).isPresent();
        assertThat(saved.get().detections()).hasSize(2);
    }

    @Test
    void nullBufferDelayProcessesImmediately() {
        var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
                FixedDetectionResult.detected("g1", 0.9));
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")), new TriggerAction.CreateCase(TRIGGER_CONFIG), null);
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
            @Override public DetectionResult detect(
                    io.cloudevents.CloudEvent event, SituationContext context) {
                detections.add(event.getTime().toInstant());
                return FixedDetectionResult.detected("g1", 0.4);
            }
        };
        // 5-second buffer, Count(g1, 3) so it accumulates
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                Duration.ofMinutes(5), Duration.ofSeconds(5),
                new ChainMode.Count("g1", 3), new TriggerAction.CreateCase(TRIGGER_CONFIG), null);
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
                new ChainMode.Or(Set.of("g1")), new TriggerAction.CreateCase(TRIGGER_CONFIG), null);
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
            @Override public DetectionResult detect(
                    io.cloudevents.CloudEvent event, SituationContext context) {
                callCount.incrementAndGet();
                return FixedDetectionResult.detected("g1", 0.9);
            }
        };
        // Or mode with 1 ganglion → first detection triggers TRIGGER
        // Buffer with large delay so all events stay buffered until a late event releases them
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                Duration.ofMinutes(5), Duration.ofSeconds(2),
                new ChainMode.Or(Set.of("g1")), new TriggerAction.CreateCase(TRIGGER_CONFIG), null);
        buildEvaluator(List.of(ganglion), def);

        var t1 = Instant.parse("2026-06-25T10:00:01Z");
        var t2 = Instant.parse("2026-06-25T10:00:02Z");
        var t10 = Instant.parse("2026-06-25T10:00:10Z");

        // Buffer t1 and t2
        evaluator.evaluate(event("temp.reading", t1), def, "key-1", "tenant-a");
        evaluator.evaluate(event("temp.reading", t2), def, "key-1", "tenant-a");
        assertThat(callCount.get()).isZero();

        // t10 arrives — watermark = 10-2 = 8. All three events released.
        // t1 triggers TRIGGER → loop stops → t2 and t10 not processed.
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
                new ChainMode.Or(Set.of("g1")), new TriggerAction.CreateCase(TRIGGER_CONFIG), null);
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

    // --- TRIGGER_AND_CONTINUE tests ---

    @Test
    void repeatingModeFiresAndContinues() {
        var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
                FixedDetectionResult.detected("g1", 0.9));
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")), new TriggerAction.CreateCase(TRIGGER_CONFIG), new TriggerMode.Repeating(Duration.ofMinutes(1)));
        buildEvaluator(List.of(ganglion), def);

        evaluator.evaluate(event("temp.reading", T1), def, "key-1", "tenant-a");

        assertThat(caseTrigger.firedCases()).hasSize(1);
        // Situation retained for continued accumulation
        var saved = store.find("sit-1", "key-1", "tenant-a");
        assertThat(saved).isPresent();
        // Claim reset — reclaimable after cooldown
        boolean reclaimable = store.tryClaimTrigger("sit-1", "key-1", "tenant-a", T2)
                ;
        assertThat(reclaimable).isTrue();
    }

    @Test
    void repeatingModeCooldownSuppressesTrigger() {
        var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
                FixedDetectionResult.detected("g1", 0.9));
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")), new TriggerAction.CreateCase(TRIGGER_CONFIG), new TriggerMode.Repeating(Duration.ofMinutes(10)));
        buildEvaluator(List.of(ganglion), def);

        evaluator.evaluate(event("temp.reading", T1), def, "key-1", "tenant-a");
        assertThat(caseTrigger.firedCases()).hasSize(1);

        // T2 is within cooldown (1 minute < 10 minute cooldown)
        evaluator.evaluate(event("temp.reading", T2), def, "key-1", "tenant-a");
        assertThat(caseTrigger.firedCases()).hasSize(1);
    }

    @Test
    void repeatingModeLoserContinuesAccumulating() {
        var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
                FixedDetectionResult.detected("g1", 0.9));
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")), new TriggerAction.CreateCase(TRIGGER_CONFIG), new TriggerMode.Repeating(Duration.ofMinutes(1)));

        // ClaimTrackingStore: first claim succeeds, subsequent claims fail
        var claimOnceStore = new ClaimTrackingStore(store);
        var reg = new SituationRegistration(def);
        var registry = new SituationDefinitionRegistry(
                List.of(() -> List.of(reg)), List.of(ganglion));
        initMetrics(registry);
        evaluator = new SituationEvaluator(claimOnceStore, policy, caseTrigger, registry, 3, changeEvent, metrics);

        evaluator.evaluate(event("temp.reading", T1), def, "key-1", "tenant-a");
        assertThat(caseTrigger.firedCases()).hasSize(1);

        // Second event: claim fails (loser), but NOT terminated — continues accumulating
        evaluator.evaluate(event("temp.reading", T2), def, "key-1", "tenant-a");
        var saved = store.find("sit-1", "key-1", "tenant-a");
        assertThat(saved).isPresent();
        assertThat(saved.get().detections()).hasSizeGreaterThanOrEqualTo(1);
    }

    @Test
    void repeatingModeCompactsAfterFire() {
        var compactCalls = new AtomicInteger();
        var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
                FixedDetectionResult.detected("g1", 0.9)) {
            @Override
            public SituationContext compact(SituationContext context) {
                compactCalls.incrementAndGet();
                if (context.detections().size() > 1) {
                    var latest = context.detections().get(context.detections().size() - 1);
                    return new SituationContext(
                            context.situationId(), context.correlationKey(), context.tenancyId(),
                            context.firstSignal(), context.lastSignal(), List.of(latest),
                            context.storeVersion(), null, 0);
                }
                return context;
            }
        };
        // Persistent situation (null window) with repeating trigger
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                null, null, new ChainMode.Or(Set.of("g1")), new TriggerAction.CreateCase(TRIGGER_CONFIG), new TriggerMode.Repeating(Duration.ofMinutes(1)));
        buildEvaluator(List.of(ganglion), def);

        evaluator.evaluate(event("temp.reading", T1), def, "key-1", "tenant-a");

        // Compact called after fire for persistent situations
        assertThat(compactCalls.get()).isGreaterThanOrEqualTo(1);
        assertThat(caseTrigger.firedCases()).hasSize(1);
    }

    // --- RESOLVE tests ---

    @Test
    void resolveRemovesSituation() {
        var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
                FixedDetectionResult.detected("g1", 0.4));
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                null, null, new ChainMode.Count("g1", 5), new TriggerAction.CreateCase(TRIGGER_CONFIG), null);

        var resolvingPolicy = new RasTriggerPolicy() {
            private int calls = 0;
            @Override
            public PolicyDecision evaluate(SituationContext ctx, SituationDefinition d) {
                return (new PolicyDecision(++calls >= 3
                        ? TriggerDecision.RESOLVE : TriggerDecision.CONTINUE_ACCUMULATING));
            }
        };

        var reg = new SituationRegistration(def);
        var registry = new SituationDefinitionRegistry(
                List.of(() -> List.of(reg)), List.of(ganglion));
        initMetrics(registry);
        evaluator = new SituationEvaluator(store, resolvingPolicy, caseTrigger, registry, 3, changeEvent, metrics);

        evaluator.evaluate(event("temp.reading", T1), def, "key-1", "tenant-a");
        evaluator.evaluate(event("temp.reading", T1), def, "key-1", "tenant-a");
        assertThat(store.find("sit-1", "key-1", "tenant-a")).isPresent();

        evaluator.evaluate(event("temp.reading", T1), def, "key-1", "tenant-a");
        assertThat(store.find("sit-1", "key-1", "tenant-a")).isEmpty();
        assertThat(caseTrigger.firedCases()).isEmpty();
    }

    // --- CDI event emission tests ---

    @Test
    void createCaseEmitsTriggeredEvent() {
        var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
                FixedDetectionResult.detected("g1", 0.9));
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")), new TriggerAction.CreateCase(TRIGGER_CONFIG), null);
        buildEvaluator(List.of(ganglion), def);

        evaluator.evaluate(event("temp.reading", T1), def, "key-1", "tenant-a");

        assertThat(changeEvent.firedEvents()).hasSize(1);
        var evt = changeEvent.firedEvents().get(0);
        assertThat(evt.tenancyId()).isEqualTo("tenant-a");
        assertThat(evt.situationId()).isEqualTo("sit-1");
        assertThat(evt.correlationKey()).isEqualTo("key-1");
        assertThat(evt.changeType()).isEqualTo(SituationChangeEvent.ChangeType.TRIGGERED);
        assertThat(evt.context()).isNotNull();
        assertThat(evt.context().situationId()).isEqualTo("sit-1");
        assertThat(evt.context().correlationKey()).isEqualTo("key-1");
        assertThat(evt.context().tenancyId()).isEqualTo("tenant-a");
    }

    @Test
    void resolveEmitsResolvedEvent() {
        var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
                FixedDetectionResult.detected("g1", 0.9));
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                null, null, new ChainMode.Or(Set.of("g1")), new TriggerAction.CreateCase(TRIGGER_CONFIG), null);

        var resolvingPolicy = new RasTriggerPolicy() {
            @Override
            public PolicyDecision evaluate(SituationContext ctx, SituationDefinition d) {
                return (new PolicyDecision(TriggerDecision.RESOLVE));
            }
        };

        var reg = new SituationRegistration(def);
        var registry = new SituationDefinitionRegistry(
                List.of(() -> List.of(reg)), List.of(ganglion));
        initMetrics(registry);
        evaluator = new SituationEvaluator(store, resolvingPolicy, caseTrigger, registry, 3, changeEvent, metrics);

        evaluator.evaluate(event("temp.reading", T1), def, "key-1", "tenant-a");

        assertThat(changeEvent.firedEvents()).hasSize(1);
        var evt = changeEvent.firedEvents().get(0);
        assertThat(evt.changeType()).isEqualTo(SituationChangeEvent.ChangeType.RESOLVED);
        assertThat(evt.context()).isNotNull();
    }

    @Test
    void discardEmitsDiscardedEvent() {
        var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
                FixedDetectionResult.detected("g1", 0.9));
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                null, null, new ChainMode.Or(Set.of("g1")), new TriggerAction.CreateCase(TRIGGER_CONFIG), null);

        var discardingPolicy = new RasTriggerPolicy() {
            @Override
            public PolicyDecision evaluate(SituationContext ctx, SituationDefinition d) {
                return (new PolicyDecision(TriggerDecision.DISCARD));
            }
        };

        var reg = new SituationRegistration(def);
        var registry = new SituationDefinitionRegistry(
                List.of(() -> List.of(reg)), List.of(ganglion));
        initMetrics(registry);
        evaluator = new SituationEvaluator(store, discardingPolicy, caseTrigger, registry, 3, changeEvent, metrics);

        evaluator.evaluate(event("temp.reading", T1), def, "key-1", "tenant-a");

        assertThat(changeEvent.firedEvents()).hasSize(1);
        var evt = changeEvent.firedEvents().get(0);
        assertThat(evt.changeType()).isEqualTo(SituationChangeEvent.ChangeType.DISCARDED);
        assertThat(evt.context()).isNotNull();
    }

    @Test
    void suppressRemovesContextAndFiresSuppressedEvent() {
        var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
                                        FixedDetectionResult.detected("g1", 0.9));
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                                          Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")),
                                          new TriggerAction.CreateCase(TRIGGER_CONFIG), null);

        var suppressPolicy = new RasTriggerPolicy() {
            @Override
            public PolicyDecision evaluate(SituationContext ctx, SituationDefinition d) {
                return (new PolicyDecision(TriggerDecision.SUPPRESS,
                                                                Map.of("suppression.tier", "full", "suppression.dismissalRate", 0.92)));
            }
        };

        var reg = new SituationRegistration(def);
        var registry = new SituationDefinitionRegistry(
                List.of(() -> List.of(reg)), List.of(ganglion));
        initMetrics(registry);
        evaluator = new SituationEvaluator(store, suppressPolicy, caseTrigger, registry,
                                           3, changeEvent, metrics);

        evaluator.evaluate(event("temp.reading", T1), def, "key-1", "tenant-a");

        assertThat(caseTrigger.firedCases()).isEmpty();
        assertThat(store.find("sit-1", "key-1", "tenant-a")).isEmpty();
        assertThat(changeEvent.firedEvents()).hasSize(1);
        assertThat(changeEvent.firedEvents().get(0).changeType())
                .isEqualTo(SituationChangeEvent.ChangeType.SUPPRESSED);
        assertThat(changeEvent.firedEvents().get(0).metadata())
                .containsEntry("suppression.tier", "full")
                .containsEntry("suppression.dismissalRate", 0.92);
    }

    @Test
    void triggerWithMetadataMergesIntoCaseData() {
        var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
                                        FixedDetectionResult.detected("g1", 0.9));
        var configWithData = new CaseTriggerConfig("ns", "case", "1.0",
                                                   Map.of("existing", "value"));
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                                          Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")),
                                          new TriggerAction.CreateCase(configWithData), null);

        var annotatePolicy = new RasTriggerPolicy() {
            @Override
            public PolicyDecision evaluate(SituationContext ctx, SituationDefinition d) {
                return (new PolicyDecision(TriggerDecision.TRIGGER,
                                                                Map.of("suppression.tier", "annotate", "suppression.dismissalRate", 0.45)));
            }
        };

        var reg = new SituationRegistration(def);
        var registry = new SituationDefinitionRegistry(
                List.of(() -> List.of(reg)), List.of(ganglion));
        initMetrics(registry);
        evaluator = new SituationEvaluator(store, annotatePolicy, caseTrigger, registry,
                                           3, changeEvent, metrics);

        evaluator.evaluate(event("temp.reading", T1), def, "key-1", "tenant-a");

        assertThat(caseTrigger.firedCases()).hasSize(1);
        var firedConfig = caseTrigger.firedCases().get(0).triggerConfig();
        assertThat(firedConfig.baseCaseData()).containsEntry("existing", "value");
        assertThat(firedConfig.baseCaseData()).containsEntry("suppression.tier", "annotate");
        assertThat(firedConfig.baseCaseData()).containsEntry("suppression.dismissalRate", 0.45);
    }

    @Test
    void triggerWithEmptyMetadataDoesNotModifyCaseData() {
        var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
                                        FixedDetectionResult.detected("g1", 0.9));
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                                          Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")),
                                          new TriggerAction.CreateCase(TRIGGER_CONFIG), null);
        buildEvaluator(List.of(ganglion), def);

        evaluator.evaluate(event("temp.reading", T1), def, "key-1", "tenant-a");

        assertThat(caseTrigger.firedCases()).hasSize(1);
        var firedConfig = caseTrigger.firedCases().get(0).triggerConfig();
        assertThat(firedConfig.baseCaseData()).isEqualTo(TRIGGER_CONFIG.baseCaseData());
    }

    @Test
    void notifyOnlyWithMetadataPassesMetadataOnEvent() {
        var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
                                        FixedDetectionResult.detected("g1", 0.9));
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                                          Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")),
                                          new TriggerAction.NotifyOnly(), null);

        var annotatePolicy = new RasTriggerPolicy() {
            @Override
            public PolicyDecision evaluate(SituationContext ctx, SituationDefinition d) {
                return (new PolicyDecision(TriggerDecision.TRIGGER,
                                                                Map.of("suppression.tier", "annotate")));
            }
        };

        var reg = new SituationRegistration(def);
        var registry = new SituationDefinitionRegistry(
                List.of(() -> List.of(reg)), List.of(ganglion));
        initMetrics(registry);
        evaluator = new SituationEvaluator(store, annotatePolicy, caseTrigger, registry,
                                           3, changeEvent, metrics);

        evaluator.evaluate(event("temp.reading", T1), def, "key-1", "tenant-a");

        assertThat(caseTrigger.firedCases()).isEmpty();
        assertThat(changeEvent.firedEvents()).hasSize(1);
        var evt = changeEvent.firedEvents().get(0);
        assertThat(evt.changeType()).isEqualTo(SituationChangeEvent.ChangeType.TRIGGERED);
        assertThat(evt.metadata()).containsEntry("suppression.tier", "annotate");
    }


    @Test
    void createCaseAndContinueEmitsTriggeredEvent() {
        var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
                FixedDetectionResult.detected("g1", 0.9));
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")), new TriggerAction.CreateCase(TRIGGER_CONFIG), new TriggerMode.Repeating(Duration.ofMinutes(1)));
        buildEvaluator(List.of(ganglion), def);

        evaluator.evaluate(event("temp.reading", T1), def, "key-1", "tenant-a");

        assertThat(changeEvent.firedEvents()).hasSize(1);
        var evt = changeEvent.firedEvents().get(0);
        assertThat(evt.changeType()).isEqualTo(SituationChangeEvent.ChangeType.TRIGGERED);
    }

    // --- Conflict retry tests ---

    @Test
    void conflictOnSaveRetriesAndSucceeds() {
        var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
                FixedDetectionResult.detected("g1", 0.9));
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")), new TriggerAction.CreateCase(TRIGGER_CONFIG), null);

        var conflictStore = new ConflictSimulatingStore(store, 1);
        var reg = new SituationRegistration(def);
        var registry = new SituationDefinitionRegistry(
                List.of(() -> List.of(reg)), List.of(ganglion));
        initMetrics(registry);
        evaluator = new SituationEvaluator(conflictStore, policy, caseTrigger, registry, 3, changeEvent, metrics);

        evaluator.evaluate(event("temp.reading", T1), def, "key-1", "tenant-a");

        assertThat(caseTrigger.firedCases()).hasSize(1);
    }

    @Test
    void allRetriesExhaustedLosesEvent() {
        var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
                FixedDetectionResult.detected("g1", 0.4));
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                Duration.ofMinutes(5), null, new ChainMode.Count("g1", 3), new TriggerAction.CreateCase(TRIGGER_CONFIG), null);

        var alwaysConflict = new ConflictSimulatingStore(store, Integer.MAX_VALUE);
        var reg = new SituationRegistration(def);
        var registry = new SituationDefinitionRegistry(
                List.of(() -> List.of(reg)), List.of(ganglion));
        initMetrics(registry);
        evaluator = new SituationEvaluator(alwaysConflict, policy, caseTrigger, registry, 3, changeEvent, metrics);

        evaluator.evaluate(event("temp.reading", T1), def, "key-1", "tenant-a");

        // Event lost — nothing saved, no case triggered
        assertThat(caseTrigger.firedCases()).isEmpty();
        assertThat(store.find("sit-1", "key-1", "tenant-a")).isEmpty();
    }

    @Test
    void detectionNotRecomputedOnRetry() {
        var detectCount = new AtomicInteger();
        var ganglion = new Ganglion() {
            @Override public String ganglionId() { return "g1"; }
            @Override public Set<String> handledEventTypes() { return Set.of("temp.reading"); }
            @Override public DetectionResult detect(CloudEvent event, SituationContext context) {
                detectCount.incrementAndGet();
                return (FixedDetectionResult.detected("g1", 0.4));
            }
        };
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                Duration.ofMinutes(5), null, new ChainMode.Count("g1", 3), new TriggerAction.CreateCase(TRIGGER_CONFIG), null);

        var conflictStore = new ConflictSimulatingStore(store, 2);
        var reg = new SituationRegistration(def);
        var registry = new SituationDefinitionRegistry(
                List.of(() -> List.of(reg)), List.of(ganglion));
        initMetrics(registry);
        evaluator = new SituationEvaluator(conflictStore, policy, caseTrigger, registry, 3, changeEvent, metrics);

        evaluator.evaluate(event("temp.reading", T1), def, "key-1", "tenant-a");

        assertThat(detectCount.get()).isEqualTo(1);
    }

    @Test
    void compactionRerunOnRetry() {
        var compactCalls = new AtomicInteger();
        var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
                FixedDetectionResult.detected("g1", 0.4)) {
            @Override
            public SituationContext compact(SituationContext context) {
                compactCalls.incrementAndGet();
                return context;
            }
        };
        // null correlationWindow → persistent → compact invoked
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                null, null, new ChainMode.Count("g1", 5), new TriggerAction.CreateCase(TRIGGER_CONFIG), null);

        var conflictStore = new ConflictSimulatingStore(store, 1);
        var reg = new SituationRegistration(def);
        var registry = new SituationDefinitionRegistry(
                List.of(() -> List.of(reg)), List.of(ganglion));
        initMetrics(registry);
        evaluator = new SituationEvaluator(conflictStore, policy, caseTrigger, registry, 3, changeEvent, metrics);

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
                Duration.ofMinutes(5), null, new ChainMode.Count("g1", 3), new TriggerAction.CreateCase(TRIGGER_CONFIG), null);

        // Store that simulates: first save conflicts, AND winner removed the situation
        var conflictAndRemoveStore = new SituationStore() {
            private final SituationStore delegate = store;
            private boolean conflicted = false;

            @Override
            public Optional<SituationContext> find(String situationId, String correlationKey, String tenancyId) {
                return delegate.find(situationId, correlationKey, tenancyId);
            }

            @Override
            public SituationContext save(SituationContext context) {
                if (!conflicted) {
                    conflicted = true;
                    delegate.save(context);
                    delegate.remove(context.situationId(), context.correlationKey(), context.tenancyId());
                    throw new SituationConflictException("Simulated conflict", null);
                }
                return delegate.save(context);
            }

            @Override
            public void remove(String situationId, String correlationKey, String tenancyId) {
                delegate.remove(situationId, correlationKey, tenancyId);
            }

            @Override
            public int removeExpired(Instant cutoff) {
                return delegate.removeExpired(cutoff);
            }

            @Override
            public void removeAllForSituation(String situationId) {
                delegate.removeAllForSituation(situationId);
            }
        };

        var reg = new SituationRegistration(def);
        var registry = new SituationDefinitionRegistry(
                List.of(() -> List.of(reg)), List.of(ganglion));
        initMetrics(registry);
        evaluator = new SituationEvaluator(conflictAndRemoveStore, policy, caseTrigger, registry, 3, changeEvent, metrics);

        evaluator.evaluate(event("temp.reading", T1), def, "key-1", "tenant-a");

        // Retry created fresh context, applied detection, CONTINUE_ACCUMULATING → saved
        var saved = store.find("sit-1", "key-1", "tenant-a");
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
                Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")), new TriggerAction.CreateCase(TRIGGER_CONFIG), null);
        buildEvaluator(List.of(ganglion), def);

        evaluator.evaluate(event("temp.reading", T1), def, "key-1", "tenant-a");

        assertThat(caseTrigger.firedCases()).hasSize(1);
        var saved = store.find("sit-1", "key-1", "tenant-a");
        assertThat(saved).isPresent();
        boolean secondClaim = store.tryClaimTrigger("sit-1", "key-1", "tenant-a", T1)
                ;
        assertThat(secondClaim).isFalse();
    }

    @Test
    void claimPreventsDuplicateTrigger() {
        var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
                FixedDetectionResult.detected("g1", 0.9));
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")), new TriggerAction.CreateCase(TRIGGER_CONFIG), null);

        var claimOnceStore = new ClaimTrackingStore(store);
        var reg = new SituationRegistration(def);
        var registry = new SituationDefinitionRegistry(
                List.of(() -> List.of(reg)), List.of(ganglion));
        initMetrics(registry);
        evaluator = new SituationEvaluator(claimOnceStore, policy, caseTrigger, registry, 3, changeEvent, metrics);

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
                Duration.ofMinutes(5), null, new ChainMode.And(Set.of("g1", "g2")), new TriggerAction.CreateCase(TRIGGER_CONFIG), null);
        buildEvaluator(List.of(g1, g2), def);

        evaluator.evaluate(event("temp.reading", T1), def, "key-1", "tenant-a");
        var afterFirst = store.find("sit-1", "key-1", "tenant-a").orElseThrow();
        Instant firstLastSignal = afterFirst.lastSignal();

        store.tryClaimTrigger("sit-1", "key-1", "tenant-a", T1);

        evaluator.evaluate(event("vibration.reading", T2), def, "key-1", "tenant-a");
        assertThat(caseTrigger.firedCases()).isEmpty();

        var afterSecond = store.find("sit-1", "key-1", "tenant-a").orElseThrow();
        assertThat(afterSecond.lastSignal()).isEqualTo(firstLastSignal);
    }

    @Test
    void triggerFailureAfterClaimResets() {
        var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
                FixedDetectionResult.detected("g1", 0.9));
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")), new TriggerAction.CreateCase(TRIGGER_CONFIG), null);

        var failingTrigger = new CaseTrigger() {
            @Override
            public java.util.UUID fire(CaseTriggerConfig config, SituationContext context) {
                throw (new RuntimeException("Trigger failed"));
            }
        };

        var reg = new SituationRegistration(def);
        var registry = new SituationDefinitionRegistry(
                List.of(() -> List.of(reg)), List.of(ganglion));
        initMetrics(registry);
        evaluator = new SituationEvaluator(store, policy, failingTrigger, registry, 3, changeEvent, metrics);

        evaluator.evaluate(event("temp.reading", T1), def, "key-1", "tenant-a");

        var saved = store.find("sit-1", "key-1", "tenant-a");
        assertThat(saved).isPresent();
        boolean reclaimable = store.tryClaimTrigger("sit-1", "key-1", "tenant-a", T1)
                ;
        assertThat(reclaimable).isTrue();
    }

    @Test
    void triggerFailureRecoveryOnNextEvent() {
        var callCount = new AtomicInteger();
        var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
                FixedDetectionResult.detected("g1", 0.9));
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")), new TriggerAction.CreateCase(TRIGGER_CONFIG), null);

        var failOnceTrigger = new CaseTrigger() {
            @Override
            public java.util.UUID fire(CaseTriggerConfig config, SituationContext context) {
                if (callCount.incrementAndGet() == 1) {
                    throw (new RuntimeException("Transient"));
                }
                return (java.util.UUID.randomUUID());
            }
        };

        var reg = new SituationRegistration(def);
        var registry = new SituationDefinitionRegistry(
                List.of(() -> List.of(reg)), List.of(ganglion));
        initMetrics(registry);
        evaluator = new SituationEvaluator(store, policy, failOnceTrigger, registry, 3, changeEvent, metrics);

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
                Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")), new TriggerAction.CreateCase(TRIGGER_CONFIG), null);
        buildEvaluator(List.of(ganglion), def);

        evaluator.evaluate(event("temp.reading", T1), def, "key-1", "tenant-a");
        assertThat(caseTrigger.firedCases()).hasSize(1);

        var afterTrigger = store.find("sit-1", "key-1", "tenant-a").orElseThrow();
        Instant originalLastSignal = afterTrigger.lastSignal();

        evaluator.evaluate(event("temp.reading", T2), def, "key-1", "tenant-a");
        assertThat(caseTrigger.firedCases()).hasSize(1);

        var afterSecond = store.find("sit-1", "key-1", "tenant-a").orElseThrow();
        assertThat(afterSecond.lastSignal()).isEqualTo(originalLastSignal);
    }

    @Test
    void claimSucceedsSaveFailsResetsAndRetries() {
        var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
                FixedDetectionResult.detected("g1", 0.4));
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                Duration.ofMinutes(5), null, new ChainMode.Count("g1", 2), new TriggerAction.CreateCase(TRIGGER_CONFIG), null);

        var ctx = SituationContext.initial("sit-1", "key-1", "tenant-a", T1);
        store.save(ctx);

        var conflictOnSecondSave = new ClaimTrackingStore(store) {
            private int saveCount = 0;
            @Override
            public SituationContext save(SituationContext context) {
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
        initMetrics(registry);
        evaluator = new SituationEvaluator(conflictOnSecondSave, policy, caseTrigger, registry, 3, changeEvent, metrics);

        evaluator.evaluate(event("temp.reading", T1), def, "key-1", "tenant-a");

        boolean claimAvailable = store.tryClaimTrigger("sit-1", "key-1", "tenant-a", T1)
                ;
        assertThat(claimAvailable).isTrue();
    }

    private static class ConflictSimulatingStore implements SituationStore {
        private final SituationStore delegate;
        private       int            conflictsRemaining;

        ConflictSimulatingStore(SituationStore delegate, int conflictCount) {
            this.delegate           = delegate;
            this.conflictsRemaining = conflictCount;
        }

        @Override
        public Optional<SituationContext> find(String situationId, String correlationKey,
                                               String tenancyId) {
            return delegate.find(situationId, correlationKey, tenancyId);
        }

        @Override
        public SituationContext save(SituationContext context) {
            if (conflictsRemaining > 0) {
                conflictsRemaining--;
                throw new SituationConflictException("Simulated conflict", null);
            }
            return delegate.save(context);
        }

        @Override
        public void remove(String situationId, String correlationKey, String tenancyId) {
            delegate.remove(situationId, correlationKey, tenancyId);
        }

        @Override
        public int removeExpired(Instant cutoff) {
            return delegate.removeExpired(cutoff);
        }

        @Override
        public boolean tryClaimTrigger(String situationId, String correlationKey,
                                       String tenancyId, Instant triggerTime) {
            return delegate.tryClaimTrigger(situationId, correlationKey, tenancyId, triggerTime);
        }

        @Override
        public void resetTriggerClaim(String situationId, String correlationKey,
                                      String tenancyId) {
            delegate.resetTriggerClaim(situationId, correlationKey, tenancyId);
        }

        @Override
        public void removeAllForSituation(String situationId) {
            delegate.removeAllForSituation(situationId);
        }
    }

    private static class ClaimTrackingStore implements SituationStore {
        private final SituationStore delegate;

        ClaimTrackingStore(SituationStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public Optional<SituationContext> find(String situationId, String correlationKey,
                                               String tenancyId) {
            return delegate.find(situationId, correlationKey, tenancyId);
        }

        @Override
        public SituationContext save(SituationContext context) {
            return delegate.save(context);
        }

        @Override
        public void remove(String situationId, String correlationKey, String tenancyId) {
            delegate.remove(situationId, correlationKey, tenancyId);
        }

        @Override
        public int removeExpired(Instant cutoff) {
            return delegate.removeExpired(cutoff);
        }

        @Override
        public boolean tryClaimTrigger(String situationId, String correlationKey,
                                       String tenancyId, Instant triggerTime) {
            return delegate.tryClaimTrigger(situationId, correlationKey, tenancyId, triggerTime);
        }

        @Override
        public void resetTriggerClaim(String situationId, String correlationKey,
                                      String tenancyId) {
            delegate.resetTriggerClaim(situationId, correlationKey, tenancyId);
        }

        @Override
        public void removeAllForSituation(String situationId) {
            delegate.removeAllForSituation(situationId);
        }
    }

    @Test
    void notifyOnly_fires_enriched_event_without_case_creation() {
        var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
                FixedDetectionResult.detected("g1", 0.9));
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")),
                new TriggerAction.NotifyOnly(), null);
        buildEvaluator(List.of(ganglion), def);

        evaluator.evaluate(event("temp.reading", T1), def, "key-1", "tenant-a");

        assertThat(caseTrigger.firedCases()).isEmpty();
        assertThat(changeEvent.firedEvents()).hasSize(1);
        var evt = changeEvent.firedEvents().get(0);
        assertThat(evt.changeType()).isEqualTo(SituationChangeEvent.ChangeType.TRIGGERED);
        assertThat(evt.context()).isNotNull();
    }

    @Test
    void notifyOnly_resets_claim_on_event_delivery_failure() {
        var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
                FixedDetectionResult.detected("g1", 0.9));
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")),
                new TriggerAction.NotifyOnly(), null);

        var failingChangeEvent = new TestChangeEvent() {
            @Override
            public <U extends SituationChangeEvent> CompletionStage<U> fireAsync(U event) {
                super.fireAsync(event);
                return CompletableFuture.failedFuture(new RuntimeException("Event delivery failed"));
            }
        };

        var reg = new SituationRegistration(def);
        var registry = new SituationDefinitionRegistry(
                List.of(() -> List.of(reg)), List.of(ganglion));
        initMetrics(registry);
        evaluator = new SituationEvaluator(store, policy, caseTrigger, registry, 3, failingChangeEvent, metrics);

        evaluator.evaluate(event("temp.reading", T1), def, "key-1", "tenant-a");

        assertThat(caseTrigger.firedCases()).isEmpty();
        assertThat(failingChangeEvent.firedEvents()).hasSize(1);

        var saved = store.find("sit-1", "key-1", "tenant-a");
        assertThat(saved).isPresent();
        boolean reclaimable = store.tryClaimTrigger("sit-1", "key-1", "tenant-a", T1)
                ;
        assertThat(reclaimable).isTrue();
    }

    @Test
    void notifyOnly_repeating_fires_event_and_continues_accumulating() {
        var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
                FixedDetectionResult.detected("g1", 0.9));
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                null, null, new ChainMode.Or(Set.of("g1")),
                new TriggerAction.NotifyOnly(),
                new TriggerMode.Repeating(Duration.ofMinutes(5)));
        buildEvaluator(List.of(ganglion), def);

        evaluator.evaluate(event("temp.reading", T1), def, "key-1", "tenant-a");

        assertThat(caseTrigger.firedCases()).isEmpty();
        assertThat(changeEvent.firedEvents()).hasSize(1);
        var evt = changeEvent.firedEvents().get(0);
        assertThat(evt.changeType()).isEqualTo(SituationChangeEvent.ChangeType.TRIGGERED);
        assertThat(evt.context()).isNotNull();

        var saved = store.find("sit-1", "key-1", "tenant-a");
        assertThat(saved).isPresent();
    }

    @Test
    void notifyOnly_repeating_resets_claim_on_delivery_failure() {
        var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
                FixedDetectionResult.detected("g1", 0.9));
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                null, null, new ChainMode.Or(Set.of("g1")),
                new TriggerAction.NotifyOnly(),
                new TriggerMode.Repeating(Duration.ofMinutes(5)));

        var failingChangeEvent = new TestChangeEvent() {
            @Override
            public <U extends SituationChangeEvent> CompletionStage<U> fireAsync(U event) {
                super.fireAsync(event);
                return CompletableFuture.failedFuture(new RuntimeException("Event delivery failed"));
            }
        };

        var reg = new SituationRegistration(def);
        var registry = new SituationDefinitionRegistry(
                List.of(() -> List.of(reg)), List.of(ganglion));
        initMetrics(registry);
        evaluator = new SituationEvaluator(store, policy, caseTrigger, registry, 3, failingChangeEvent, metrics);

        evaluator.evaluate(event("temp.reading", T1), def, "key-1", "tenant-a");

        assertThat(caseTrigger.firedCases()).isEmpty();
        assertThat(failingChangeEvent.firedEvents()).hasSize(1);

        var saved = store.find("sit-1", "key-1", "tenant-a");
        assertThat(saved).isPresent();
        boolean reclaimable = store.tryClaimTrigger("sit-1", "key-1", "tenant-a", T1)
                ;
        assertThat(reclaimable).isTrue();
    }

    @Test
    void createCase_event_delivery_failure_does_not_reset_claim() {
        var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
                FixedDetectionResult.detected("g1", 0.9));
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")),
                new TriggerAction.CreateCase(TRIGGER_CONFIG), null);

        var failingChangeEvent = new TestChangeEvent() {
            @Override
            public <U extends SituationChangeEvent> CompletionStage<U> fireAsync(U event) {
                super.fireAsync(event);
                return CompletableFuture.failedFuture(new RuntimeException("Event delivery failed"));
            }
        };

        var reg = new SituationRegistration(def);
        var registry = new SituationDefinitionRegistry(
                List.of(() -> List.of(reg)), List.of(ganglion));
        initMetrics(registry);
        evaluator = new SituationEvaluator(store, policy, caseTrigger, registry, 3, failingChangeEvent, metrics);

        evaluator.evaluate(event("temp.reading", T1), def, "key-1", "tenant-a");

        assertThat(caseTrigger.firedCases()).hasSize(1);
        assertThat(failingChangeEvent.firedEvents()).hasSize(1);

        var saved = store.find("sit-1", "key-1", "tenant-a");
        assertThat(saved).isPresent();
        boolean reclaimable = store.tryClaimTrigger("sit-1", "key-1", "tenant-a", T1)
                ;
        assertThat(reclaimable).isFalse();
    }

    private static class TestChangeEvent implements Event<SituationChangeEvent> {
        private final CopyOnWriteArrayList<SituationChangeEvent> fired = new CopyOnWriteArrayList<>();

        List<SituationChangeEvent> firedEvents() { return List.copyOf(fired); }

        @Override
        public void fire(SituationChangeEvent event) {
            fired.add(event);
        }

        @Override
        public <U extends SituationChangeEvent> CompletionStage<U> fireAsync(U event) {
            fired.add(event);
            return CompletableFuture.completedFuture(event);
        }

        @Override
        public <U extends SituationChangeEvent> CompletionStage<U> fireAsync(U event, NotificationOptions options) {
            fired.add(event);
            return CompletableFuture.completedFuture(event);
        }

        @Override public Event<SituationChangeEvent> select(Annotation... qualifiers) { return this; }
        @Override public <U extends SituationChangeEvent> Event<U> select(Class<U> subtype, Annotation... qualifiers) { throw new UnsupportedOperationException(); }
        @Override public <U extends SituationChangeEvent> Event<U> select(TypeLiteral<U> subtype, Annotation... qualifiers) { throw new UnsupportedOperationException(); }
    }

    @Test
    void evaluatorContinuesWhenOneGanglionFails() {
        var failingGanglion = new Ganglion() {
            @Override public String ganglionId() { return "g-fail"; }
            @Override public Set<String> handledEventTypes() { return Set.of("test.event"); }
            @Override public DetectionResult detect(CloudEvent event, SituationContext context) {
                throw (new RuntimeException("ganglion failed"));
            }
        };
        var workingGanglion = new MockGanglion("g-ok", Set.of("test.event"),
                FixedDetectionResult.detected("g-ok", 0.9));

        var def = new SituationDefinition("sit-1", Set.of("test.event"),
                Duration.ofMinutes(5), null,
                new ChainMode.Or(Set.of("g-fail", "g-ok")),
                new TriggerAction.CreateCase(TRIGGER_CONFIG), null);
        buildEvaluator(List.of(failingGanglion, workingGanglion), def);

        evaluator.evaluate(event("test.event", T1), def, "key-1", "tenant-a");

        assertThat(caseTrigger.firedCases()).hasSize(1);
    }

    @Test
    void metricsProcessTimeRecorded() {
        var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
                                        FixedDetectionResult.detected("g1", 0.9));
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                                          Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")),
                                          new TriggerAction.CreateCase(TRIGGER_CONFIG), null);
        buildEvaluator(List.of(ganglion), def);

        evaluator.evaluate(event("temp.reading", T1), def, "key-1", "tenant-a");

        assertThat(meterRegistry.timer("ras.evaluator.process_time",
                                       "situation_id", "sit-1", "tenancy_id", "tenant-a").count()).isEqualTo(1);
    }

    @Test
    void metricsDecisionCounterTracksAllTypes() {
        var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
                                        FixedDetectionResult.detected("g1", 0.9));
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                                          Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")),
                                          new TriggerAction.CreateCase(TRIGGER_CONFIG), null);
        buildEvaluator(List.of(ganglion), def);

        evaluator.evaluate(event("temp.reading", T1), def, "key-1", "tenant-a");

        assertThat(meterRegistry.counter("ras.evaluator.decision",
                                         "situation_id", "sit-1", "tenancy_id", "tenant-a",
                                         "decision", "trigger").count()).isEqualTo(1.0);
    }

    @Test
    void metricsConflictRetryAndExhausted() {
        var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
                                        FixedDetectionResult.detected("g1", 0.4));
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                                          Duration.ofMinutes(5), null, new ChainMode.Count("g1", 3),
                                          new TriggerAction.CreateCase(TRIGGER_CONFIG), null);
        var reg = new SituationRegistration(def);
        var registry = new SituationDefinitionRegistry(
                List.of(() -> List.of(reg)), List.of(ganglion));
        initMetrics(registry);

        initMetrics(registry);
        var alwaysConflict = new ConflictSimulatingStore(store, 100);
        evaluator = new SituationEvaluator(alwaysConflict, policy, caseTrigger, registry, 3, changeEvent, metrics);

        evaluator.evaluate(event("temp.reading", T1), def, "key-1", "tenant-a");

        assertThat(meterRegistry.counter("ras.evaluator.conflict_retries",
                                         "situation_id", "sit-1", "tenancy_id", "tenant-a").count()).isEqualTo(3.0);
        assertThat(meterRegistry.counter("ras.evaluator.retries_exhausted",
                                         "situation_id", "sit-1", "tenancy_id", "tenant-a").count()).isEqualTo(1.0);
    }

    @Test
    void metricsContextExpiredOnWindowExpiry() {
        var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
                                        FixedDetectionResult.detected("g1", 0.4));
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                                          Duration.ofMinutes(5), null, new ChainMode.Count("g1", 3),
                                          new TriggerAction.CreateCase(TRIGGER_CONFIG), null);
        buildEvaluator(List.of(ganglion), def);

        evaluator.evaluate(event("temp.reading", T1), def, "key-1", "tenant-a");
        evaluator.evaluate(event("temp.reading", T1.plus(Duration.ofMinutes(10))),
                           def, "key-1", "tenant-a");

        assertThat(meterRegistry.counter("ras.evaluator.context_expired",
                                         "situation_id", "sit-1", "tenancy_id", "tenant-a").count()).isEqualTo(1.0);
    }

    @Test
    void metricsGanglionDetectFailed() {
        var failing = new MockGanglion("g-fail", Set.of("temp.reading"), null) {
            @Override
            public DetectionResult detect(
                    io.cloudevents.CloudEvent event, SituationContext context) {
                throw new RuntimeException("boom");
            }
        };
        var good = new MockGanglion("g-ok", Set.of("temp.reading"),
                                    FixedDetectionResult.detected("g-ok", 0.9));
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                                          Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g-fail", "g-ok")),
                                          new TriggerAction.CreateCase(TRIGGER_CONFIG), null);
        buildEvaluator(List.of(failing, good), def);

        evaluator.evaluate(event("temp.reading", T1), def, "key-1", "tenant-a");

        assertThat(meterRegistry.counter("ras.evaluator.ganglion.detect_failed",
                                         "ganglion_id", "g-fail", "situation_id", "sit-1").count()).isEqualTo(1.0);
    }

    @Test
    void metricsTriggerClaimedOnSuccessfulClaim() {
        var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
                                        FixedDetectionResult.detected("g1", 0.9));
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                                          Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")),
                                          new TriggerAction.CreateCase(TRIGGER_CONFIG), null);
        buildEvaluator(List.of(ganglion), def);

        evaluator.evaluate(event("temp.reading", T1), def, "key-1", "tenant-a");

        assertThat(meterRegistry.counter("ras.evaluator.trigger.claimed",
                                         "situation_id", "sit-1", "tenancy_id", "tenant-a").count()).isEqualTo(1.0);
    }

    @Test
    void metricsTriggerFiredWithCreateCaseTag() {
        var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
                                        FixedDetectionResult.detected("g1", 0.9));
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                                          Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")),
                                          new TriggerAction.CreateCase(TRIGGER_CONFIG), null);
        buildEvaluator(List.of(ganglion), def);

        evaluator.evaluate(event("temp.reading", T1), def, "key-1", "tenant-a");

        assertThat(meterRegistry.counter("ras.evaluator.trigger.fired",
                                         "situation_id", "sit-1", "tenancy_id", "tenant-a",
                                         "trigger_action", "create_case").count()).isEqualTo(1.0);
        assertThat(meterRegistry.timer("ras.evaluator.trigger.fire_time",
                                       "situation_id", "sit-1", "tenancy_id", "tenant-a",
                                       "trigger_action", "create_case").count()).isEqualTo(1);
    }

    @Test
    void metricsTriggerFiredWithNotifyOnlyTag() {
        var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
                                        FixedDetectionResult.detected("g1", 0.9));
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                                          Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")),
                                          new TriggerAction.NotifyOnly(), null);
        buildEvaluator(List.of(ganglion), def);

        evaluator.evaluate(event("temp.reading", T1), def, "key-1", "tenant-a");

        assertThat(meterRegistry.counter("ras.evaluator.trigger.fired",
                                         "situation_id", "sit-1", "tenancy_id", "tenant-a",
                                         "trigger_action", "notify_only").count()).isEqualTo(1.0);
    }

    @Test
    void metricsTriggerFailedOnCaseTriggerException() {
        var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
                                        FixedDetectionResult.detected("g1", 0.9));
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                                          Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")),
                                          new TriggerAction.CreateCase(TRIGGER_CONFIG), null);
        var reg = new SituationRegistration(def);
        var registry = new SituationDefinitionRegistry(
                List.of(() -> List.of(reg)), List.of(ganglion));
        initMetrics(registry);
        CaseTrigger failingTrigger = (config, ctx) -> {
            throw new RuntimeException("fire failed");
        };
        initMetrics(registry);
        evaluator = new SituationEvaluator(store, policy, failingTrigger, registry, 3, changeEvent, metrics);

        evaluator.evaluate(event("temp.reading", T1), def, "key-1", "tenant-a");

        assertThat(meterRegistry.counter("ras.evaluator.trigger.failed",
                                         "situation_id", "sit-1", "tenancy_id", "tenant-a",
                                         "trigger_action", "create_case").count()).isEqualTo(1.0);
    }

    @Test
    void metricsEventBufferedOnBufferedEvent() {
        var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
                                        FixedDetectionResult.detected("g1", 0.4));
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                                          Duration.ofMinutes(5), Duration.ofSeconds(2),
                                          new ChainMode.Count("g1", 3),
                                          new TriggerAction.CreateCase(TRIGGER_CONFIG), null);
        buildEvaluator(List.of(ganglion), def);

        evaluator.evaluate(event("temp.reading", T1), def, "key-1", "tenant-a");

        assertThat(meterRegistry.counter("ras.evaluator.buffer.events_buffered",
                                         "situation_id", "sit-1", "tenancy_id", "tenant-a").count()).isEqualTo(1.0);
    }

    @Test
    void triggerByDeadline_fires_case_and_change_event() {
        var ganglion = new MockGanglion("g1", Set.of("ras.situation.triggered"),
                                        FixedDetectionResult.detected("g1", 0.9));
        var def = new SituationDefinition("sit", Set.of("ras.situation.triggered"),
                                          null, null, new ChainMode.Count("g1", 3),
                                          new TriggerAction.CreateCase(TRIGGER_CONFIG),
                                          null, null, null, Map.of(), null, Duration.ofMinutes(30));
        buildEvaluator(List.of(ganglion), def);

        var context = SituationContext.initial("sit", "key", "tenant", T1);
        store.save(context);

        evaluator.triggerByDeadline("sit", "key", "tenant");

        assertThat(caseTrigger.firedCases()).hasSize(1);
        assertThat(changeEvent.firedEvents()).hasSize(1);
        assertThat(changeEvent.firedEvents().get(0).changeType())
                .isEqualTo(SituationChangeEvent.ChangeType.TRIGGERED);
    }

    @Test
    void triggerByDeadline_discards_when_correlationWindow_expired() {
        var ganglion = new MockGanglion("g1", Set.of("ras.situation.triggered"),
                                        FixedDetectionResult.detected("g1", 0.9));
        var def = new SituationDefinition("sit", Set.of("ras.situation.triggered"),
                                          Duration.ofMinutes(5), null, new ChainMode.Count("g1", 3),
                                          new TriggerAction.CreateCase(TRIGGER_CONFIG),
                                          null, null, null, Map.of(), null, Duration.ofMinutes(30));
        buildEvaluator(List.of(ganglion), def);

        var context = SituationContext.initial("sit", "key", "tenant",
                                               Instant.now().minus(Duration.ofHours(1)));
        store.save(context);

        evaluator.triggerByDeadline("sit", "key", "tenant");

        assertThat(caseTrigger.firedCases()).isEmpty();
        assertThat(store.find("sit", "key", "tenant")).isEmpty();
        assertThat(changeEvent.firedEvents()).hasSize(1);
        assertThat(changeEvent.firedEvents().get(0).changeType())
                .isEqualTo(SituationChangeEvent.ChangeType.DISCARDED);
    }

    @Test
    void triggerByDeadline_noop_when_no_context() {
        var ganglion = new MockGanglion("g1", Set.of("ras.situation.triggered"),
                                        FixedDetectionResult.detected("g1", 0.9));
        var def = new SituationDefinition("sit", Set.of("ras.situation.triggered"),
                                          null, null, new ChainMode.Count("g1", 1),
                                          new TriggerAction.NotifyOnly(),
                                          null, null, null, Map.of(), null, Duration.ofMinutes(30));
        buildEvaluator(List.of(ganglion), def);

        evaluator.triggerByDeadline("sit", "key", "tenant");

        assertThat(changeEvent.firedEvents()).isEmpty();
    }

    @Test
    void triggerByDeadline_noop_when_unknown_situation() {
        var ganglion = new MockGanglion("g1", Set.of("ras.situation.triggered"),
                                        FixedDetectionResult.detected("g1", 0.9));
        var def = new SituationDefinition("sit", Set.of("ras.situation.triggered"),
                                          null, null, new ChainMode.Count("g1", 1),
                                          new TriggerAction.NotifyOnly(),
                                          null, null, null, Map.of(), null, Duration.ofMinutes(30));
        buildEvaluator(List.of(ganglion), def);

        evaluator.triggerByDeadline("unknown-sit", "key", "tenant");

        assertThat(changeEvent.firedEvents()).isEmpty();
    }

    @Test
    void processEvent_forces_trigger_when_deadline_expired() {
        var ganglion = new MockGanglion("g1", Set.of("ras.situation.triggered"),
                                        FixedDetectionResult.detected("g1", 0.9));
        var def = new SituationDefinition("sit", Set.of("ras.situation.triggered"),
                                          null, null, new ChainMode.Count("g1", 3),
                                          new TriggerAction.CreateCase(TRIGGER_CONFIG),
                                          null, null, null, Map.of(), null, Duration.ofMinutes(5));
        buildEvaluator(List.of(ganglion), def);

        evaluator.evaluate(event("ras.situation.triggered", T1), def, "key", "tenant");
        assertThat(caseTrigger.firedCases()).isEmpty();

        Instant pastDeadline = T1.plus(Duration.ofMinutes(10));
        evaluator.evaluate(event("ras.situation.triggered", pastDeadline), def, "key", "tenant");
        assertThat(caseTrigger.firedCases()).hasSize(1);
        assertThat(changeEvent.firedEvents()).anyMatch(
                e -> e.changeType() == SituationChangeEvent.ChangeType.TRIGGERED);
    }


}
