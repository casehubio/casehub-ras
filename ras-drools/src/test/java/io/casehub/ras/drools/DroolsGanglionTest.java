package io.casehub.ras.drools;

import io.casehub.ras.api.*;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.kie.api.KieBase;
import org.kie.api.runtime.KieSession;
import org.kie.api.runtime.KieSessionConfiguration;
import static org.assertj.core.api.Assertions.*;

class DroolsGanglionTest {

    private InMemoryDroolsSessionStore sessionStore;

    @BeforeEach
    void setUp() {
        sessionStore = new InMemoryDroolsSessionStore();
    }

    private DroolsGanglion ganglionWithClasspathRule() {
        var config = new DroolsGanglionConfig(
                "test-ganglion", Set.of("test.event"),
                SessionMode.EPHEMERAL, ClockMode.PSEUDO,
                List.of("io/casehub/ras/drools/test-threshold.drl"), List.of());
        return new DroolsGanglion(config, sessionStore, List.of());
    }

    private CloudEvent testEvent(String type, Instant time) {
        return CloudEventBuilder.v1()
                .withId("evt-1")
                .withSource(URI.create("/test"))
                .withType(type)
                .withTime(OffsetDateTime.ofInstant(time, ZoneOffset.UTC))
                .build();
    }

    private SituationContext testContext() {
        return SituationContext.initial("sit-1", "key-1", "tenant-a",
                Instant.parse("2026-06-21T10:00:00Z"));
    }

    @Test
    void ganglionIdAndHandledEventTypes() {
        var ganglion = ganglionWithClasspathRule();
        assertThat(ganglion.ganglionId()).isEqualTo("test-ganglion");
        assertThat(ganglion.handledEventTypes()).containsExactly("test.event");
    }

    @Test
    void detectMatchingEventReturnsDetected() {
        var ganglion = ganglionWithClasspathRule();
        var event = testEvent("test.event", Instant.parse("2026-06-21T10:00:00Z"));
        DetectionResult result = ganglion.detect(event, testContext())
                .await().indefinitely();
        assertThat(result.signal()).isEqualTo(DetectionSignal.DETECTED);
        assertThat(result.confidence()).isEqualTo(0.9);
        assertThat(result.ganglionId()).isEqualTo("test-ganglion");
    }

    @Test
    void detectNonMatchingEventReturnsNoise() {
        var ganglion = ganglionWithClasspathRule();
        var event = testEvent("other.event", Instant.parse("2026-06-21T10:00:00Z"));
        DetectionResult result = ganglion.detect(event, testContext())
                .await().indefinitely();
        assertThat(result.signal()).isEqualTo(DetectionSignal.NOISE);
        assertThat(result.confidence()).isEqualTo(0.0);
    }

    @Test
    void longLivedModeReusesSession() {
        var config = new DroolsGanglionConfig(
                "test-ganglion", Set.of("test.event"),
                SessionMode.LONG_LIVED, ClockMode.PSEUDO,
                List.of("io/casehub/ras/drools/test-threshold.drl"), List.of());
        var ganglion = new DroolsGanglion(config, sessionStore, List.of());
        var ctx = testContext();
        var event1 = testEvent("test.event", Instant.parse("2026-06-21T10:00:00Z"));
        ganglion.detect(event1, ctx).await().indefinitely();
        var event2 = testEvent("test.event", Instant.parse("2026-06-21T10:01:00Z"));
        DetectionResult r2 = ganglion.detect(event2, ctx).await().indefinitely();
        assertThat(r2.signal()).isEqualTo(DetectionSignal.DETECTED);
    }

    @Test
    void closeAllowsFreshSessionOnNextDetect() {
        var config = new DroolsGanglionConfig(
                "test-ganglion", Set.of("test.event"),
                SessionMode.LONG_LIVED, ClockMode.PSEUDO,
                List.of("io/casehub/ras/drools/test-threshold.drl"), List.of());
        var ganglion = new DroolsGanglion(config, sessionStore, List.of());
        var event = testEvent("test.event", Instant.parse("2026-06-21T10:00:00Z"));
        ganglion.detect(event, testContext()).await().indefinitely();
        ganglion.close("sit-1", "key-1", "tenant-a").await().indefinitely();
        var newCtx = SituationContext.initial("sit-1", "key-1", "tenant-a",
                Instant.parse("2026-06-21T11:00:00Z"));
        var event2 = testEvent("test.event", Instant.parse("2026-06-21T11:00:00Z"));
        DetectionResult r = ganglion.detect(event2, newCtx).await().indefinitely();
        assertThat(r.signal()).isEqualTo(DetectionSignal.DETECTED);
    }

    @Test
    void pseudoClockOutOfOrderThrows() {
        var config = new DroolsGanglionConfig(
                "test-ganglion", Set.of("test.event"),
                SessionMode.LONG_LIVED, ClockMode.PSEUDO,
                List.of("io/casehub/ras/drools/test-threshold.drl"), List.of());
        var ganglion = new DroolsGanglion(config, sessionStore, List.of());
        var ctx = testContext();
        var event1 = testEvent("test.event", Instant.parse("2026-06-21T10:01:00Z"));
        ganglion.detect(event1, ctx).await().indefinitely();
        var event2 = testEvent("test.event", Instant.parse("2026-06-21T10:00:00Z"));
        assertThatThrownBy(() -> ganglion.detect(event2, ctx).await().indefinitely())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Out-of-order");
    }

    @Test
    void programmaticRulesWork() {
        var drl = """
                package test;
                import io.cloudevents.CloudEvent;
                import io.casehub.ras.api.DetectionResult;
                import io.casehub.ras.api.DetectionSignal;
                import java.util.Map;
                rule "inline"
                when $ce : CloudEvent(type == "inline.event")
                then channels["results"].send(new DetectionResult(
                    "inline-g", 0.75, DetectionSignal.DETECTED, Map.of()));
                end
                """;
        var config = new DroolsGanglionConfig(
                "inline-g", Set.of("inline.event"),
                SessionMode.EPHEMERAL, ClockMode.PSEUDO,
                List.of(), List.of(drl));
        var ganglion = new DroolsGanglion(config, sessionStore, List.of());
        var event = testEvent("inline.event", Instant.parse("2026-06-21T10:00:00Z"));
        DetectionResult result = ganglion.detect(event, testContext())
                .await().indefinitely();
        assertThat(result.signal()).isEqualTo(DetectionSignal.DETECTED);
        assertThat(result.confidence()).isEqualTo(0.75);
    }

    @Test
    void invalidDrlThrowsAtConstruction() {
        var config = new DroolsGanglionConfig(
                "bad-g", Set.of("e"),
                SessionMode.EPHEMERAL, ClockMode.PSEUDO,
                List.of(), List.of("this is not valid DRL"));
        assertThatThrownBy(() -> new DroolsGanglion(config, sessionStore, List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DRL compilation failed");
    }

    @Test
    void objectExtractorFactsMatchRules() {
        var drl = """
                package test;
                import io.casehub.ras.api.DetectionResult;
                import io.casehub.ras.api.DetectionSignal;
                import java.util.Map;
                rule "map check"
                when $m : Map(this["value"] > 100.0)
                then channels["results"].send(new DetectionResult(
                    "ext-g", 0.8, DetectionSignal.DETECTED,
                    Map.of("matched", true)));
                end
                """;
        var config = new DroolsGanglionConfig(
                "ext-g", Set.of("sensor.reading"),
                SessionMode.EPHEMERAL, ClockMode.PSEUDO,
                List.of(), List.of(drl));

        DroolsObjectExtractor extractor = new DroolsObjectExtractor() {
            @Override
            public Set<String> handledEventTypes() { return Set.of("sensor.reading"); }
            @Override
            public java.util.List<Object> extract(CloudEvent event) {
                return java.util.List.of(java.util.Map.of("value", 150.0));
            }
        };

        var ganglion = new DroolsGanglion(config, sessionStore, List.of(extractor));
        var event = testEvent("sensor.reading", Instant.parse("2026-06-21T10:00:00Z"));
        var result = ganglion.detect(event, testContext()).await().indefinitely();
        assertThat(result.signal()).isEqualTo(DetectionSignal.DETECTED);
        assertThat(result.confidence()).isEqualTo(0.8);
    }

    @Test
    void highestConfidenceStrategyPicksHigherConfidence() {
        var config = new DroolsGanglionConfig(
                "test-ganglion", Set.of("test.event"),
                SessionMode.EPHEMERAL, ClockMode.PSEUDO,
                List.of("io/casehub/ras/drools/test-multi-rule.drl"), List.of(),
                ResultCollectionStrategy.HIGHEST_CONFIDENCE);
        var ganglion = new DroolsGanglion(config, sessionStore, List.of());
        var event = testEvent("test.event", Instant.parse("2026-06-21T10:00:00Z"));
        DetectionResult result = ganglion.detect(event, testContext())
                .await().indefinitely();
        assertThat(result.confidence()).isEqualTo(0.9);
        assertThat(result.signal()).isEqualTo(DetectionSignal.DETECTED);
    }

    @Test
    void accumulateStrategyMergesResults() {
        var config = new DroolsGanglionConfig(
                "test-ganglion", Set.of("test.event"),
                SessionMode.EPHEMERAL, ClockMode.PSEUDO,
                List.of("io/casehub/ras/drools/test-multi-rule.drl"), List.of(),
                ResultCollectionStrategy.ACCUMULATE);
        var ganglion = new DroolsGanglion(config, sessionStore, List.of());
        var event = testEvent("test.event", Instant.parse("2026-06-21T10:00:00Z"));
        DetectionResult result = ganglion.detect(event, testContext())
                .await().indefinitely();
        assertThat(result.signal()).isEqualTo(DetectionSignal.DETECTED);
        assertThat(result.confidence()).isEqualTo(0.9);
        assertThat(result.evidence()).containsKeys("rule");
    }

    @Test
    void closeOnEphemeralGanglionIsNoOp() {
        var ganglion = ganglionWithClasspathRule();
        assertThatNoException().isThrownBy(() ->
                ganglion.close("sit-1", "key-1", "tenant-a").await().indefinitely());
    }

    @Test
    void nullEventTimeDoesNotAdvanceClock() {
        var config = new DroolsGanglionConfig(
                "test-ganglion", Set.of("test.event"),
                SessionMode.LONG_LIVED, ClockMode.PSEUDO,
                List.of("io/casehub/ras/drools/test-threshold.drl"), List.of());
        var ganglion = new DroolsGanglion(config, sessionStore, List.of());
        var ctx = testContext();
        var event1 = testEvent("test.event", Instant.parse("2026-06-21T10:00:00Z"));
        ganglion.detect(event1, ctx).await().indefinitely();
        var nullTimeEvent = CloudEventBuilder.v1()
                .withId("evt-null")
                .withSource(URI.create("/test"))
                .withType("test.event")
                .build();
        DetectionResult r2 = ganglion.detect(nullTimeEvent, ctx).await().indefinitely();
        assertThat(r2.signal()).isEqualTo(DetectionSignal.DETECTED);
        var event3 = testEvent("test.event", Instant.parse("2026-06-21T10:01:00Z"));
        DetectionResult r3 = ganglion.detect(event3, ctx).await().indefinitely();
        assertThat(r3.signal()).isEqualTo(DetectionSignal.DETECTED);
    }

    @Test
    void reloadSwapsRulesForEphemeralSessions() {
        var initialDrl = """
                package test;
                import io.cloudevents.CloudEvent;
                import io.casehub.ras.api.DetectionResult;
                import io.casehub.ras.api.DetectionSignal;
                import java.util.Map;
                rule "initial"
                when $ce : CloudEvent(type == "test.event")
                then channels["results"].send(new DetectionResult(
                    "reload-g", 0.5, DetectionSignal.WEAK, Map.of()));
                end
                """;
        var config = new DroolsGanglionConfig(
                "reload-g", Set.of("test.event"),
                SessionMode.EPHEMERAL, ClockMode.PSEUDO,
                List.of(), List.of(initialDrl));
        var ganglion = new DroolsGanglion(config, sessionStore, List.of());
        var event = testEvent("test.event", Instant.parse("2026-06-21T10:00:00Z"));
        var r1 = ganglion.detect(event, testContext()).await().indefinitely();
        assertThat(r1.confidence()).isEqualTo(0.5);

        var newDrl = """
                package test;
                import io.cloudevents.CloudEvent;
                import io.casehub.ras.api.DetectionResult;
                import io.casehub.ras.api.DetectionSignal;
                import java.util.Map;
                rule "updated"
                when $ce : CloudEvent(type == "test.event")
                then channels["results"].send(new DetectionResult(
                    "reload-g", 0.95, DetectionSignal.DETECTED, Map.of()));
                end
                """;
        ganglion.reload(List.of(), List.of(newDrl));
        var r2 = ganglion.detect(event, testContext()).await().indefinitely();
        assertThat(r2.confidence()).isEqualTo(0.95);
    }

    @Test
    void reloadCompilationFailureLeavesOldRulesIntact() {
        var initialDrl = """
                package test;
                import io.cloudevents.CloudEvent;
                import io.casehub.ras.api.DetectionResult;
                import io.casehub.ras.api.DetectionSignal;
                import java.util.Map;
                rule "initial"
                when $ce : CloudEvent(type == "test.event")
                then channels["results"].send(new DetectionResult(
                    "reload-g", 0.5, DetectionSignal.WEAK, Map.of()));
                end
                """;
        var config = new DroolsGanglionConfig(
                "reload-g", Set.of("test.event"),
                SessionMode.EPHEMERAL, ClockMode.PSEUDO,
                List.of(), List.of(initialDrl));
        var ganglion = new DroolsGanglion(config, sessionStore, List.of());
        assertThatThrownBy(() -> ganglion.reload(List.of(), List.of("not valid DRL")))
                .isInstanceOf(IllegalStateException.class);
        var event = testEvent("test.event", Instant.parse("2026-06-21T10:00:00Z"));
        var result = ganglion.detect(event, testContext()).await().indefinitely();
        assertThat(result.confidence()).isEqualTo(0.5);
    }

    @Test
    void reloadWithEmptyRulesThrows() {
        var ganglion = ganglionWithClasspathRule();
        assertThatThrownBy(() -> ganglion.reload(List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("At least one rule source required");
    }

    @Test
    void reloadInvalidatesLongLivedSessionOnNextDetect() {
        var initialDrl = """
                package test;
                import io.cloudevents.CloudEvent;
                import io.casehub.ras.api.DetectionResult;
                import io.casehub.ras.api.DetectionSignal;
                import java.util.Map;
                rule "initial"
                when $ce : CloudEvent(type == "test.event")
                then channels["results"].send(new DetectionResult(
                    "reload-g", 0.5, DetectionSignal.WEAK, Map.of()));
                end
                """;
        var config = new DroolsGanglionConfig(
                "reload-g", Set.of("test.event"),
                SessionMode.LONG_LIVED, ClockMode.PSEUDO,
                List.of(), List.of(initialDrl));
        var ganglion = new DroolsGanglion(config, sessionStore, List.of());
        var ctx = testContext();
        var event1 = testEvent("test.event", Instant.parse("2026-06-21T10:00:00Z"));
        ganglion.detect(event1, ctx).await().indefinitely();

        var newDrl = """
                package test;
                import io.cloudevents.CloudEvent;
                import io.casehub.ras.api.DetectionResult;
                import io.casehub.ras.api.DetectionSignal;
                import java.util.Map;
                rule "updated"
                when $ce : CloudEvent(type == "test.event")
                then channels["results"].send(new DetectionResult(
                    "reload-g", 0.95, DetectionSignal.DETECTED, Map.of()));
                end
                """;
        ganglion.reload(List.of(), List.of(newDrl));
        var event2 = testEvent("test.event", Instant.parse("2026-06-21T10:01:00Z"));
        var r2 = ganglion.detect(event2, ctx).await().indefinitely();
        assertThat(r2.confidence()).isEqualTo(0.95);
    }

    @Test
    void storeExceptionCausesDefensiveCleanupAndRethrow() {
        var failingStore = new DroolsSessionStore() {
            @Override
            public KieSession computeIfAbsent(DroolsSessionKey key, KieBase kieBase,
                                               KieSessionConfiguration config, long generation) {
                throw new DroolsSessionStoreException("storage read failed");
            }
            @Override
            public void remove(DroolsSessionKey key) {
                throw new RuntimeException("remove also fails");
            }
        };

        var drl = "package test; rule \"noop\" when then end";
        var config = new DroolsGanglionConfig("g1", Set.of("test.event"),
                SessionMode.LONG_LIVED, ClockMode.PSEUDO,
                List.of(), List.of(drl));
        var ganglion = new DroolsGanglion(config, failingStore, List.of());

        var context = SituationContext.initial("sit-1", "key-1", "tenant-a",
                Instant.parse("2026-07-09T10:00:00Z"));

        assertThatThrownBy(() -> ganglion.detect(testEvent("test.event",
                Instant.parse("2026-07-09T10:00:00Z")), context)
                .await().indefinitely())
                .isInstanceOf(DroolsSessionStoreException.class)
                .hasMessageContaining("storage read failed")
                .satisfies(ex -> assertThat(ex.getSuppressed()).hasSize(1));
    }
}
