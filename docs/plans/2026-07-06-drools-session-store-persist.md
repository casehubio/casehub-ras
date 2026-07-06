# DroolsSessionStore Persistent Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> subagent-driven-development (recommended) or executing-plans to
> implement this plan task-by-task. Each task follows TDD
> (test-driven-development) and uses ide-tooling for structural
> editing. Steps use checkbox (`- [ ]`) syntax for tracking.

**Focal issue:** #7 — DroolsSessionStore: persistent implementation for restart survival
**Issue group:** #7

**Goal:** Replace the volatile `DroolsSessionStore` SPI with a generation-aware
`computeIfAbsent` API, update `InMemoryDroolsSessionStore`, refactor `DroolsGanglion`
to delegate session lifecycle, and add a `ReliableDroolsSessionStore` backed by
drools-reliability + H2MVStore in a new `drools-reliability/` module.

**Architecture:** The store owns all session lifecycle (create, cache, invalidate, recover,
dispose). `DroolsGanglion` retains only detection logic and `reloadGeneration` for JMM
ordering. `InMemoryDroolsSessionStore` is `@DefaultBean` (Tier 1), `ReliableDroolsSessionStore`
is plain `@ApplicationScoped` (Tier 2) — classpath-driven activation per CDI priority ladder
protocol.

**Tech Stack:** Drools 10.1.0, drools-reliability-core, drools-reliability-h2mvstore,
Quarkus ARC CDI, JUnit 5, AssertJ

## Global Constraints

- Drools version: `${version.drools}` = 10.1.0 (parent pom)
- Persistence strategy: `STORES_ONLY` only — `FULL` is broken in 10.1.0
- Safepoint strategy: `AFTER_FIRE` — one commit per detect() call
- Activation strategy: `ACTIVATION_KEY` — prevent duplicate rule firings on replay
- New module folder: `drools-reliability/` (no repo prefix per PP-20260508-5c0e4b)
- New module artifactId: `casehub-ras-drools-reliability`
- CDI: InMemory = `@DefaultBean`, Reliable = `@ApplicationScoped` (PP-20260522-0cfa30)
- Root package: `io.casehub.ras.drools.reliability`
- Config parameter: `config` is borrowed read-only — store creates its own KieSessionConfiguration

---

### Task 1: SPI Redesign + InMemoryDroolsSessionStore

**Files:**
- Create: `ras-drools/src/main/java/io/casehub/ras/drools/DroolsSessionKey.java`
- Modify: `ras-drools/src/main/java/io/casehub/ras/drools/DroolsSessionStore.java`
- Modify: `ras-drools/src/main/java/io/casehub/ras/drools/InMemoryDroolsSessionStore.java`
- Modify: `ras-drools/src/test/java/io/casehub/ras/drools/InMemoryDroolsSessionStoreTest.java`

**Interfaces:**
- Produces: `DroolsSessionKey(String ganglionId, String situationId, String correlationKey, String tenancyId)` with `toStorageKey()` returning `ganglionId + "|" + situationId + "|" + correlationKey + "|" + tenancyId`
- Produces: `DroolsSessionStore.computeIfAbsent(DroolsSessionKey key, KieBase kieBase, KieSessionConfiguration config, long generation)` → `KieSession`
- Produces: `DroolsSessionStore.remove(DroolsSessionKey key)` → `void`
- Produces: `InMemoryDroolsSessionStore.removeAll(String ganglionId)` → `void` (concrete method, not on SPI)

- [ ] **Step 1: Create DroolsSessionKey record**

```java
// ras-drools/src/main/java/io/casehub/ras/drools/DroolsSessionKey.java
package io.casehub.ras.drools;

public record DroolsSessionKey(
    String ganglionId,
    String situationId,
    String correlationKey,
    String tenancyId
) {
    public String toStorageKey() {
        return ganglionId + "|" + situationId + "|" + correlationKey + "|" + tenancyId;
    }
}
```

- [ ] **Step 2: Rewrite DroolsSessionStore interface**

Replace the entire file:

```java
// ras-drools/src/main/java/io/casehub/ras/drools/DroolsSessionStore.java
package io.casehub.ras.drools;

import org.kie.api.KieBase;
import org.kie.api.runtime.KieSession;
import org.kie.api.runtime.KieSessionConfiguration;

public interface DroolsSessionStore {

    KieSession computeIfAbsent(DroolsSessionKey key,
                               KieBase kieBase,
                               KieSessionConfiguration config,
                               long generation);

    void remove(DroolsSessionKey key);
}
```

- [ ] **Step 3: Write failing InMemoryDroolsSessionStore tests**

Replace the entire test file with tests against the new SPI:

```java
// ras-drools/src/test/java/io/casehub/ras/drools/InMemoryDroolsSessionStoreTest.java
package io.casehub.ras.drools;

import org.drools.model.codegen.ExecutableModelProject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kie.api.KieBase;
import org.kie.api.KieServices;
import org.kie.api.builder.KieBuilder;
import org.kie.api.builder.KieFileSystem;
import org.kie.api.runtime.KieSession;
import org.kie.api.runtime.KieSessionConfiguration;
import static org.assertj.core.api.Assertions.*;

class InMemoryDroolsSessionStoreTest {

    private InMemoryDroolsSessionStore store;
    private KieBase kieBase;
    private KieSessionConfiguration config;

    @BeforeEach
    void setUp() {
        store = new InMemoryDroolsSessionStore();
        KieServices ks = KieServices.Factory.get();
        KieFileSystem kfs = ks.newKieFileSystem();
        KieBuilder kb = ks.newKieBuilder(kfs);
        kb.buildAll(ExecutableModelProject.class);
        kieBase = ks.newKieContainer(kb.getKieModule().getReleaseId()).getKieBase();
        config = ks.newKieSessionConfiguration();
    }

    private DroolsSessionKey key(String ganglionId, String situationId) {
        return new DroolsSessionKey(ganglionId, situationId, "key-1", "tenant-a");
    }

    @Test
    void computeIfAbsentCreatesOnFirstCall() {
        KieSession session = store.computeIfAbsent(key("g1", "sit-1"), kieBase, config, 0);
        assertThat(session).isNotNull();
    }

    @Test
    void computeIfAbsentReturnsSameOnSecondCall() {
        var k = key("g1", "sit-1");
        KieSession s1 = store.computeIfAbsent(k, kieBase, config, 0);
        KieSession s2 = store.computeIfAbsent(k, kieBase, config, 0);
        assertThat(s2).isSameAs(s1);
    }

    @Test
    void differentKeysAreIndependent() {
        KieSession s1 = store.computeIfAbsent(key("g1", "sit-1"), kieBase, config, 0);
        KieSession s2 = store.computeIfAbsent(key("g2", "sit-1"), kieBase, config, 0);
        assertThat(s2).isNotSameAs(s1);
    }

    @Test
    void generationMismatchDisposesOldAndCreatesNew() {
        var k = key("g1", "sit-1");
        KieSession old = store.computeIfAbsent(k, kieBase, config, 0);
        KieSession fresh = store.computeIfAbsent(k, kieBase, config, 1);
        assertThat(fresh).isNotSameAs(old);
    }

    @Test
    void sameGenerationReturnsCached() {
        var k = key("g1", "sit-1");
        KieSession s1 = store.computeIfAbsent(k, kieBase, config, 5);
        KieSession s2 = store.computeIfAbsent(k, kieBase, config, 5);
        assertThat(s2).isSameAs(s1);
    }

    @Test
    void removeEvictsSession() {
        var k = key("g1", "sit-1");
        store.computeIfAbsent(k, kieBase, config, 0);
        store.remove(k);
        KieSession fresh = store.computeIfAbsent(k, kieBase, config, 0);
        assertThat(fresh).isNotNull();
    }

    @Test
    void removeNonExistentKeyIsNoOp() {
        assertThatNoException().isThrownBy(() -> store.remove(key("g1", "no-such")));
    }

    @Test
    void removeAllScopedToGanglion() {
        var k1 = key("g1", "sit-1");
        var k2 = new DroolsSessionKey("g1", "sit-2", "key-2", "tenant-a");
        var k3 = key("g2", "sit-1");
        KieSession s1 = store.computeIfAbsent(k1, kieBase, config, 0);
        KieSession s2 = store.computeIfAbsent(k2, kieBase, config, 0);
        KieSession s3 = store.computeIfAbsent(k3, kieBase, config, 0);

        store.removeAll("g1");

        // g1 sessions gone — computeIfAbsent creates fresh
        KieSession s1b = store.computeIfAbsent(k1, kieBase, config, 0);
        assertThat(s1b).isNotSameAs(s1);
        // g2 session survives
        KieSession s3b = store.computeIfAbsent(k3, kieBase, config, 0);
        assertThat(s3b).isSameAs(s3);
    }

    @Test
    void removeAllNonExistentGanglionIsNoOp() {
        assertThatNoException().isThrownBy(() -> store.removeAll("no-such"));
    }
}
```

- [ ] **Step 4: Run tests to verify they fail**

Run: `mvn --batch-mode test -pl ras-drools -Dtest=InMemoryDroolsSessionStoreTest`
Expected: compilation failure — InMemoryDroolsSessionStore does not implement new SPI.

- [ ] **Step 5: Implement InMemoryDroolsSessionStore**

Replace the entire file:

```java
// ras-drools/src/main/java/io/casehub/ras/drools/InMemoryDroolsSessionStore.java
package io.casehub.ras.drools;

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import org.kie.api.KieBase;
import org.kie.api.runtime.KieSession;
import org.kie.api.runtime.KieSessionConfiguration;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
@DefaultBean
public class InMemoryDroolsSessionStore implements DroolsSessionStore {

    private record StampedSession(KieSession session, long generation) {}

    private final ConcurrentHashMap<DroolsSessionKey, StampedSession> cache = new ConcurrentHashMap<>();

    @Override
    public KieSession computeIfAbsent(DroolsSessionKey key,
                                      KieBase kieBase,
                                      KieSessionConfiguration config,
                                      long generation) {
        StampedSession cached = cache.get(key);
        if (cached != null) {
            if (cached.generation < generation) {
                cached.session.dispose();
                cache.remove(key);
            } else {
                return cached.session;
            }
        }
        KieSession session = kieBase.newKieSession(config, null);
        cache.put(key, new StampedSession(session, generation));
        return session;
    }

    @Override
    public void remove(DroolsSessionKey key) {
        StampedSession removed = cache.remove(key);
        if (removed != null) {
            removed.session.dispose();
        }
    }

    public void removeAll(String ganglionId) {
        var iterator = cache.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (entry.getKey().ganglionId().equals(ganglionId)) {
                entry.getValue().session.dispose();
                iterator.remove();
            }
        }
    }
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `mvn --batch-mode test -pl ras-drools -Dtest=InMemoryDroolsSessionStoreTest`
Expected: all 9 tests PASS.

- [ ] **Step 7: Commit**

```
feat(casehub-ras#7): redesign DroolsSessionStore SPI — computeIfAbsent with generation

Introduces DroolsSessionKey record, replaces get()/put() with Map-aligned
computeIfAbsent(key, kieBase, config, generation). InMemoryDroolsSessionStore
updated with StampedSession for generation-aware lazy invalidation.
```

---

### Task 2: DroolsGanglion Refactor

**Files:**
- Modify: `ras-drools/src/main/java/io/casehub/ras/drools/DroolsGanglion.java`
- Modify: `ras-drools/src/test/java/io/casehub/ras/drools/DroolsGanglionTest.java`
- Modify: `ras-drools/src/test/java/io/casehub/ras/drools/DroolsGanglionContractTest.java`

**Interfaces:**
- Consumes: `DroolsSessionStore.computeIfAbsent(DroolsSessionKey, KieBase, KieSessionConfiguration, long)` from Task 1
- Consumes: `DroolsSessionStore.remove(DroolsSessionKey)` from Task 1
- Consumes: `DroolsSessionKey` from Task 1

- [ ] **Step 1: Update DroolsGanglionTest to use new SPI**

The test file needs significant changes:
1. `sessionStore.get(...)` calls → `sessionStore.computeIfAbsent(...)` calls (where tests verify session identity)
2. Constructor calls remain the same (DroolsGanglion still takes DroolsSessionStore)
3. Tests that check `sessionStore.get()` for presence/absence need rethinking — with the new SPI, there is no bare `get()`. Tests that verified "session was stored" by calling `get()` should instead verify behavior through the ganglion's detect() call.

Replace the entire test file. Key changes from the original:
- Remove all `sessionStore.get(...)` assertions — the store no longer has a `get()` method
- Tests that verified "session stored after detect" now verify "second detect reuses session" by checking the ganglion returns consistent results without creating a new session
- Tests that verified "session removed after close" now verify "detect after close creates fresh session"
- Reload tests verify that `detect()` after `reload()` produces results from new rules (which proves the old session was invalidated)

```java
// ras-drools/src/test/java/io/casehub/ras/drools/DroolsGanglionTest.java
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
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn --batch-mode test -pl ras-drools -Dtest=DroolsGanglionTest`
Expected: compilation failure — DroolsGanglion still uses old SPI.

- [ ] **Step 3: Refactor DroolsGanglion**

Key changes to `DroolsGanglion.java`:
1. Remove `sessionGenerations` field and `SessionGenKey` record
2. Keep `volatile long reloadGeneration`
3. Add `buildSessionConfig()` private method
4. Refactor `detect()` — LONG_LIVED uses `sessionStore.computeIfAbsent()`, EPHEMERAL uses `createSession()` directly
5. Refactor `close()` — use `DroolsSessionKey`
6. `reload()` — no store call, just increment `reloadGeneration`
7. Remove the old `createSession(KieBase)` method, add `buildSessionConfig()` instead

The complete refactored `DroolsGanglion.java`:

```java
package io.casehub.ras.drools;

import io.casehub.ras.api.*;
import io.cloudevents.CloudEvent;
import io.smallrye.mutiny.Uni;
import org.drools.model.codegen.ExecutableModelProject;
import org.kie.api.KieBase;
import org.kie.api.KieServices;
import org.kie.api.builder.KieBuilder;
import org.kie.api.builder.KieFileSystem;
import org.kie.api.builder.Message;
import org.kie.api.builder.ReleaseId;
import org.kie.api.builder.Results;
import org.kie.api.conf.EventProcessingOption;
import org.kie.api.runtime.KieSession;
import org.kie.api.runtime.KieSessionConfiguration;
import org.kie.api.runtime.conf.ClockTypeOption;
import org.kie.api.runtime.rule.FactHandle;
import org.kie.api.time.SessionPseudoClock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class DroolsGanglion implements Ganglion {

    public static final String RESULT_CHANNEL = "results";

    private final String ganglionId;
    private final Set<String> handledEventTypes;
    private final SessionMode sessionMode;
    private final ClockMode clockMode;
    private final ResultCollectionStrategy resultCollectionStrategy;
    private final DroolsSessionStore sessionStore;
    private final List<DroolsObjectExtractor> extractors;
    private volatile KieBase kieBase;
    private volatile long reloadGeneration = 0;
    private ReleaseId currentReleaseId;

    public DroolsGanglion(DroolsGanglionConfig config,
                          DroolsSessionStore sessionStore,
                          List<DroolsObjectExtractor> extractors) {
        this.ganglionId = config.ganglionId();
        this.handledEventTypes = config.handledEventTypes();
        this.sessionMode = config.sessionMode();
        this.clockMode = config.clockMode();
        this.resultCollectionStrategy = config.resultCollectionStrategy();
        this.sessionStore = sessionStore;
        this.extractors = List.copyOf(extractors);
        this.kieBase = buildKieBase(config.classpathRules(), config.programmaticRules());
    }

    @Override
    public String ganglionId() { return ganglionId; }

    @Override
    public Set<String> handledEventTypes() { return handledEventTypes; }

    @Override
    public Uni<DetectionResult> detect(CloudEvent event, SituationContext context) {
        long currentGen = this.reloadGeneration;
        KieBase currentBase = this.kieBase;

        String situationId = context.situationId();
        String correlationKey = context.correlationKey();
        String tenancyId = context.tenancyId();
        boolean isNewSession = false;

        KieSession session;
        if (sessionMode == SessionMode.LONG_LIVED) {
            var key = new DroolsSessionKey(ganglionId, situationId, correlationKey, tenancyId);
            session = sessionStore.computeIfAbsent(key, currentBase, buildSessionConfig(), currentGen);
        } else {
            session = createSession(currentBase);
            isNewSession = true;
        }

        var collector = new ResultCollectorChannel();
        session.registerChannel(RESULT_CHANNEL, collector);
        try {
            advanceClock(session, event);
            FactHandle ceHandle = session.insert(event);
            for (var extractor : extractors) {
                for (Object obj : extractor.extract(event)) {
                    session.insert(obj);
                }
            }
            session.fireAllRules();
            session.delete(ceHandle);
        } catch (RuntimeException ex) {
            session.unregisterChannel(RESULT_CHANNEL);
            if (isNewSession) {
                session.dispose();
            } else {
                sessionStore.remove(new DroolsSessionKey(ganglionId, situationId, correlationKey, tenancyId));
            }
            throw ex;
        }

        DetectionResult result = resultCollectionStrategy
                .resolve(collector.results(), ganglionId);

        session.unregisterChannel(RESULT_CHANNEL);
        if (sessionMode == SessionMode.EPHEMERAL) {
            session.dispose();
        }

        return Uni.createFrom().item(result);
    }

    @Override
    public Uni<Void> close(String situationId, String correlationKey, String tenancyId) {
        sessionStore.remove(new DroolsSessionKey(ganglionId, situationId, correlationKey, tenancyId));
        return Uni.createFrom().voidItem();
    }

    public synchronized void reload(List<String> classpathRules, List<String> programmaticRules) {
        if (classpathRules.isEmpty() && programmaticRules.isEmpty()) {
            throw new IllegalArgumentException("At least one rule source required");
        }
        KieBase newBase = buildKieBase(classpathRules, programmaticRules);
        this.kieBase = newBase;
        this.reloadGeneration++;
    }

    private KieSessionConfiguration buildSessionConfig() {
        KieSessionConfiguration ksc = KieServices.Factory.get().newKieSessionConfiguration();
        if (clockMode == ClockMode.PSEUDO) {
            ksc.setOption(ClockTypeOption.PSEUDO);
        }
        return ksc;
    }

    private KieSession createSession(KieBase base) {
        return base.newKieSession(buildSessionConfig(), null);
    }

    private void advanceClock(KieSession session, CloudEvent event) {
        if (clockMode != ClockMode.PSEUDO) {
            return;
        }
        OffsetDateTime eventTime = event.getTime();
        if (eventTime == null) {
            return;
        }
        SessionPseudoClock clock = session.getSessionClock();
        long eventMs = eventTime.toInstant().toEpochMilli();
        long clockMs = clock.getCurrentTime();
        long delta = eventMs - clockMs;
        if (delta < 0) {
            throw new IllegalStateException(
                    "Out-of-order event for ganglion '" + ganglionId
                    + "': event time " + eventMs + " < clock time " + clockMs);
        }
        if (delta > 0) {
            clock.advanceTime(delta, TimeUnit.MILLISECONDS);
        }
    }

    private KieBase buildKieBase(List<String> classpathRules, List<String> programmaticRules) {
        KieServices ks = KieServices.Factory.get();
        KieFileSystem kfs = ks.newKieFileSystem();
        ReleaseId rid = ks.newReleaseId("io.casehub.ras.drools", ganglionId,
                String.valueOf(System.nanoTime()));
        kfs.generateAndWritePomXML(rid);
        for (String path : classpathRules) {
            kfs.write(ks.getResources().newClassPathResource(path));
        }
        for (int i = 0; i < programmaticRules.size(); i++) {
            kfs.write("src/main/resources/programmatic-" + i + ".drl",
                       programmaticRules.get(i));
        }
        KieBuilder kb = ks.newKieBuilder(kfs)
                .buildAll(ExecutableModelProject.class);
        Results results = kb.getResults();
        if (results.hasMessages(Message.Level.ERROR)) {
            throw new IllegalStateException(
                    "DRL compilation failed for ganglion '" + ganglionId
                    + "': " + results.getMessages());
        }
        var kbc = ks.newKieBaseConfiguration();
        kbc.setOption(EventProcessingOption.STREAM);
        KieBase result = ks.newKieContainer(rid).newKieBase(kbc);

        ReleaseId oldRid = this.currentReleaseId;
        this.currentReleaseId = rid;
        if (oldRid != null) {
            ks.getRepository().removeKieModule(oldRid);
        }
        return result;
    }
}
```

- [ ] **Step 4: Update DroolsGanglionContractTest**

```java
// ras-drools/src/test/java/io/casehub/ras/drools/DroolsGanglionContractTest.java
// No change needed — constructor signature is unchanged. Verify compilation.
```

- [ ] **Step 5: Run full ras-drools test suite**

Run: `mvn --batch-mode test -pl ras-drools`
Expected: all tests PASS.

- [ ] **Step 6: Commit**

```
feat(casehub-ras#7): refactor DroolsGanglion — delegate session lifecycle to store

DroolsGanglion now uses computeIfAbsent for LONG_LIVED sessions,
eliminating sessionGenerations map. Session creation, caching, and
generation-based invalidation are the store's responsibility.
```

---

### Task 3: New Module — drools-reliability + ReliableDroolsSessionStore

**Files:**
- Create: `drools-reliability/pom.xml`
- Create: `drools-reliability/src/main/java/io/casehub/ras/drools/reliability/ReliableDroolsSessionStore.java`
- Create: `drools-reliability/src/test/java/io/casehub/ras/drools/reliability/ReliableDroolsSessionStoreTest.java`
- Create: `drools-reliability/src/test/resources/io/casehub/ras/drools/reliability/test-threshold.drl`
- Modify: `pom.xml` (parent — add module + dependency management)

**Interfaces:**
- Consumes: `DroolsSessionStore` from Task 1
- Consumes: `DroolsSessionKey` from Task 1

- [ ] **Step 1: Create module pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>io.casehub</groupId>
        <artifactId>casehub-ras-parent</artifactId>
        <version>0.2-SNAPSHOT</version>
    </parent>
    <artifactId>casehub-ras-drools-reliability</artifactId>
    <name>CaseHub RAS :: Drools Reliability</name>
    <description>Persistent DroolsSessionStore backed by drools-reliability + H2MVStore.
        Activates by classpath presence — plain @ApplicationScoped beats
        InMemoryDroolsSessionStore's @DefaultBean. Experimental, temporary.</description>
    <dependencies>
        <dependency><groupId>io.casehub</groupId><artifactId>casehub-ras-drools</artifactId></dependency>
        <dependency><groupId>io.quarkus</groupId><artifactId>quarkus-arc</artifactId></dependency>
        <dependency><groupId>org.drools</groupId><artifactId>drools-reliability-core</artifactId></dependency>
        <dependency><groupId>org.drools</groupId><artifactId>drools-reliability-h2mvstore</artifactId></dependency>
        <dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><scope>test</scope></dependency>
        <dependency><groupId>org.assertj</groupId><artifactId>assertj-core</artifactId><scope>test</scope></dependency>
        <dependency><groupId>org.drools</groupId><artifactId>drools-model-codegen</artifactId><scope>test</scope></dependency>
        <dependency><groupId>org.drools</groupId><artifactId>drools-wiring-dynamic</artifactId><scope>test</scope></dependency>
    </dependencies>
    <build>
        <plugins>
            <plugin>
                <groupId>io.smallrye</groupId>
                <artifactId>jandex-maven-plugin</artifactId>
                <version>${jandex-maven-plugin.version}</version>
                <executions><execution><id>make-index</id><goals><goal>jandex</goal></goals></execution></executions>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 2: Add module and dependencies to parent pom.xml**

Add `<module>drools-reliability</module>` to the `<modules>` section.

Add dependency management entries:

```xml
<dependency>
    <groupId>io.casehub</groupId>
    <artifactId>casehub-ras-drools-reliability</artifactId>
    <version>${project.version}</version>
</dependency>
<dependency>
    <groupId>org.drools</groupId>
    <artifactId>drools-reliability-core</artifactId>
    <version>${version.drools}</version>
</dependency>
<dependency>
    <groupId>org.drools</groupId>
    <artifactId>drools-reliability-h2mvstore</artifactId>
    <version>${version.drools}</version>
</dependency>
```

- [ ] **Step 3: Create test DRL resource**

Copy `ras-drools/src/test/resources/io/casehub/ras/drools/test-threshold.drl`
to `drools-reliability/src/test/resources/io/casehub/ras/drools/reliability/test-threshold.drl`
(same content).

- [ ] **Step 4: Write failing ReliableDroolsSessionStoreTest**

```java
package io.casehub.ras.drools.reliability;

import io.casehub.ras.drools.DroolsSessionKey;
import org.drools.model.codegen.ExecutableModelProject;
import org.drools.reliability.core.StorageManagerFactory;
import org.drools.reliability.core.TestableStorageManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kie.api.KieBase;
import org.kie.api.KieServices;
import org.kie.api.builder.KieBuilder;
import org.kie.api.builder.KieFileSystem;
import org.kie.api.conf.EventProcessingOption;
import org.kie.api.runtime.KieSession;
import org.kie.api.runtime.KieSessionConfiguration;
import org.kie.api.runtime.conf.ClockTypeOption;
import org.kie.api.time.SessionPseudoClock;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

class ReliableDroolsSessionStoreTest {

    private ReliableDroolsSessionStore store;
    private KieBase kieBase;
    private KieSessionConfiguration config;

    @BeforeEach
    void setUp() {
        StorageManagerFactory.get("h2mvstore").getStorageManager().removeAllSessionStorages();
        store = new ReliableDroolsSessionStore();
        store.init();
        KieServices ks = KieServices.Factory.get();
        KieFileSystem kfs = ks.newKieFileSystem();
        KieBuilder kb = ks.newKieBuilder(kfs).buildAll(ExecutableModelProject.class);
        var kbc = ks.newKieBaseConfiguration();
        kbc.setOption(EventProcessingOption.STREAM);
        kieBase = ks.newKieContainer(kb.getKieModule().getReleaseId()).newKieBase(kbc);
        config = ks.newKieSessionConfiguration();
        config.setOption(ClockTypeOption.PSEUDO);
    }

    @AfterEach
    void tearDown() {
        if (store != null) {
            store.destroy();
        }
        ((TestableStorageManager) StorageManagerFactory.get().getStorageManager()).restartWithCleanUp();
    }

    private DroolsSessionKey key(String ganglionId, String situationId) {
        return new DroolsSessionKey(ganglionId, situationId, "key-1", "tenant-a");
    }

    @Test
    void computeIfAbsentCreatesOnFirstCall() {
        KieSession session = store.computeIfAbsent(key("g1", "sit-1"), kieBase, config, 0);
        assertThat(session).isNotNull();
    }

    @Test
    void computeIfAbsentReturnsSameOnSecondCall() {
        var k = key("g1", "sit-1");
        KieSession s1 = store.computeIfAbsent(k, kieBase, config, 0);
        KieSession s2 = store.computeIfAbsent(k, kieBase, config, 0);
        assertThat(s2).isSameAs(s1);
    }

    @Test
    void restartSurvival() {
        var k = key("g1", "sit-1");
        KieSession session = store.computeIfAbsent(k, kieBase, config, 0);
        session.insert("test-fact");
        session.fireAllRules();

        simulateRestart();

        KieSession recovered = store.computeIfAbsent(k, kieBase, config, 0);
        assertThat(recovered).isNotSameAs(session);
        assertThat(recovered.getObjects()).anyMatch(o -> "test-fact".equals(o));
    }

    @Test
    void pseudoClockSurvivesRestart() {
        var k = key("g1", "sit-1");
        KieSession session = store.computeIfAbsent(k, kieBase, config, 0);
        SessionPseudoClock clock = session.getSessionClock();
        clock.advanceTime(60_000, TimeUnit.MILLISECONDS);
        session.insert("clock-test");
        session.fireAllRules();

        long clockBefore = clock.getCurrentTime();
        simulateRestart();

        KieSession recovered = store.computeIfAbsent(k, kieBase, config, 0);
        SessionPseudoClock recoveredClock = recovered.getSessionClock();
        assertThat(recoveredClock.getCurrentTime()).isGreaterThanOrEqualTo(clockBefore);
    }

    @Test
    void generationInvalidation() {
        var k = key("g1", "sit-1");
        KieSession old = store.computeIfAbsent(k, kieBase, config, 0);
        old.insert("old-fact");
        old.fireAllRules();
        KieSession fresh = store.computeIfAbsent(k, kieBase, config, 1);
        assertThat(fresh).isNotSameAs(old);
        assertThat(fresh.getObjects()).noneMatch(o -> "old-fact".equals(o));
    }

    @Test
    void generationInvalidationAcrossRestart() {
        var k = key("g1", "sit-1");
        store.computeIfAbsent(k, kieBase, config, 0);

        simulateRestart();

        KieSession fresh = store.computeIfAbsent(k, kieBase, config, 1);
        assertThat(fresh.getObjects()).isEmpty();
    }

    @Test
    void crossRestartGenerationReset() {
        var k = key("g1", "sit-1");
        store.computeIfAbsent(k, kieBase, config, 5);

        simulateRestart();

        KieSession recovered = store.computeIfAbsent(k, kieBase, config, 0);
        assertThat(recovered).isNotNull();
        KieSession invalidated = store.computeIfAbsent(k, kieBase, config, 1);
        assertThat(invalidated).isNotSameAs(recovered);
    }

    @Test
    void removeCleansUpBothLayers() {
        var k = key("g1", "sit-1");
        store.computeIfAbsent(k, kieBase, config, 0);
        store.remove(k);

        simulateRestart();

        KieSession fresh = store.computeIfAbsent(k, kieBase, config, 0);
        assertThat(fresh.getObjects()).isEmpty();
    }

    @Test
    void removeNonExistentKeyIsNoOp() {
        assertThatNoException().isThrownBy(() -> store.remove(key("g1", "no-such")));
    }

    @Test
    void configNotMutated() {
        var k = key("g1", "sit-1");
        KieSessionConfiguration callerConfig = KieServices.Factory.get().newKieSessionConfiguration();
        callerConfig.setOption(ClockTypeOption.PSEUDO);
        String configBefore = callerConfig.toString();
        store.computeIfAbsent(k, kieBase, callerConfig, 0);
        assertThat(callerConfig.toString()).isEqualTo(configBefore);
    }

    private void simulateRestart() {
        store.destroy();
        ((TestableStorageManager) StorageManagerFactory.get().getStorageManager()).restart();
        store = new ReliableDroolsSessionStore();
        store.init();
    }
}
```

- [ ] **Step 5: Run tests to verify they fail**

Run: `mvn --batch-mode test -pl drools-reliability -Dtest=ReliableDroolsSessionStoreTest`
Expected: compilation failure — ReliableDroolsSessionStore does not exist.

- [ ] **Step 6: Implement ReliableDroolsSessionStore**

```java
package io.casehub.ras.drools.reliability;

import io.casehub.ras.drools.DroolsSessionKey;
import io.casehub.ras.drools.DroolsSessionStore;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import org.drools.core.common.Storage;
import org.drools.reliability.core.StorageManagerFactory;
import org.kie.api.KieBase;
import org.kie.api.KieServices;
import org.kie.api.runtime.KieSession;
import org.kie.api.runtime.KieSessionConfiguration;
import org.kie.api.runtime.conf.ClockTypeOption;
import org.kie.api.runtime.conf.PersistedSessionOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class ReliableDroolsSessionStore implements DroolsSessionStore {

    private static final Logger log = LoggerFactory.getLogger(ReliableDroolsSessionStore.class);

    private record StampedSession(KieSession session, long generation) {}

    private final ConcurrentHashMap<DroolsSessionKey, StampedSession> hotCache = new ConcurrentHashMap<>();
    private Storage<String, Long> sessionIds;
    private Storage<String, Long> sessionGenerations;

    @PostConstruct
    void init() {
        var sm = StorageManagerFactory.get("h2mvstore").getStorageManager();
        sessionIds = sm.getOrCreateSharedStorage("ras_drools_session_ids");
        sessionGenerations = sm.getOrCreateSharedStorage("ras_drools_session_gens");
        sessionGenerations.clear();
    }

    @PreDestroy
    void destroy() {
        hotCache.values().forEach(s -> {
            try { s.session.dispose(); } catch (Exception ignored) {}
        });
        hotCache.clear();
    }

    @Override
    public KieSession computeIfAbsent(DroolsSessionKey key,
                                      KieBase kieBase,
                                      KieSessionConfiguration config,
                                      long generation) {
        String storageKey = key.toStorageKey();

        StampedSession cached = hotCache.get(key);
        if (cached != null) {
            if (cached.generation < generation) {
                cached.session.dispose();
                hotCache.remove(key);
                removePersistedSession(storageKey);
            } else {
                return cached.session;
            }
        }

        Long savedId = sessionIds.get(storageKey);
        if (savedId != null) {
            Long savedGen = sessionGenerations.getOrDefault(storageKey, 0L);
            if (savedGen < generation) {
                removePersistedSession(storageKey);
            } else {
                try {
                    KieSession recovered = createRecoveredSession(kieBase, config, savedId);
                    sessionGenerations.put(storageKey, generation);
                    hotCache.put(key, new StampedSession(recovered, generation));
                    return recovered;
                } catch (RuntimeException ex) {
                    log.warn("Recovery failed for {}, creating fresh session", key, ex);
                    removePersistedSession(storageKey);
                }
            }
        }

        KieSession session = createNewPersistedSession(kieBase, config);
        sessionIds.put(storageKey, session.getIdentifier());
        sessionGenerations.put(storageKey, generation);
        hotCache.put(key, new StampedSession(session, generation));
        return session;
    }

    @Override
    public void remove(DroolsSessionKey key) {
        StampedSession removed = hotCache.remove(key);
        if (removed != null) {
            removed.session.dispose();
        }
        String storageKey = key.toStorageKey();
        Long savedId = sessionIds.remove(storageKey);
        sessionGenerations.remove(storageKey);
        if (savedId != null) {
            StorageManagerFactory.get().getStorageManager()
                    .removeStoragesBySessionId(String.valueOf(savedId));
        }
    }

    private KieSession createNewPersistedSession(KieBase kieBase, KieSessionConfiguration callerConfig) {
        KieSessionConfiguration storeConfig = buildStoreConfig(callerConfig);
        storeConfig.setOption(PersistedSessionOption.newSession()
                .withPersistenceStrategy(PersistedSessionOption.PersistenceStrategy.STORES_ONLY)
                .withSafepointStrategy(PersistedSessionOption.SafepointStrategy.AFTER_FIRE)
                .withActivationStrategy(PersistedSessionOption.ActivationStrategy.ACTIVATION_KEY));
        return kieBase.newKieSession(storeConfig, null);
    }

    private KieSession createRecoveredSession(KieBase kieBase, KieSessionConfiguration callerConfig, long savedId) {
        KieSessionConfiguration storeConfig = buildStoreConfig(callerConfig);
        storeConfig.setOption(PersistedSessionOption.fromSession(savedId)
                .withPersistenceStrategy(PersistedSessionOption.PersistenceStrategy.STORES_ONLY)
                .withSafepointStrategy(PersistedSessionOption.SafepointStrategy.AFTER_FIRE)
                .withActivationStrategy(PersistedSessionOption.ActivationStrategy.ACTIVATION_KEY));
        return kieBase.newKieSession(storeConfig, null);
    }

    private KieSessionConfiguration buildStoreConfig(KieSessionConfiguration callerConfig) {
        KieSessionConfiguration storeConfig = KieServices.Factory.get().newKieSessionConfiguration();
        storeConfig.setOption(callerConfig.getOption(ClockTypeOption.KEY));
        return storeConfig;
    }

    private void removePersistedSession(String storageKey) {
        Long savedId = sessionIds.remove(storageKey);
        sessionGenerations.remove(storageKey);
        if (savedId != null) {
            StorageManagerFactory.get().getStorageManager()
                    .removeStoragesBySessionId(String.valueOf(savedId));
        }
    }
}
```

- [ ] **Step 7: Run tests to verify they pass**

Run: `mvn --batch-mode test -pl drools-reliability`
Expected: all 10 tests PASS.

- [ ] **Step 8: Run full build**

Run: `mvn --batch-mode install`
Expected: BUILD SUCCESS across all modules.

- [ ] **Step 9: Commit**

```
feat(casehub-ras#7): add ReliableDroolsSessionStore — drools-reliability + H2MVStore

New drools-reliability/ module provides persistent DroolsSessionStore backed
by drools-reliability STORES_ONLY strategy with H2MVStore. Activates by
classpath presence via plain @ApplicationScoped (CDI Tier 2). Two-layer
cache: hot ConcurrentHashMap + persistent session ID mapping. Recovery
failure fallback prevents permanent per-key failure loops.
```

---

### Task 4: CLAUDE.md + Documentation Sync

**Files:**
- Modify: `CLAUDE.md`

**Interfaces:**
- Consumes: all prior tasks

- [ ] **Step 1: Update CLAUDE.md module table**

Add the new module row to the Module Structure table:

```
| `drools-reliability/` | `casehub-ras-drools-reliability` | `io.casehub.ras.drools.reliability` | ReliableDroolsSessionStore — drools-reliability + H2MVStore. Experimental. |
```

- [ ] **Step 2: Update CLAUDE.md DroolsSessionStore documentation**

Update any SPI documentation in CLAUDE.md to reflect the new `computeIfAbsent` signature
and `DroolsSessionKey`. Remove references to `get()`/`put()`. Add a note about the
generation-based lazy invalidation replacing `removeAll()` on the SPI.

- [ ] **Step 3: Verify CLAUDE.md coherence**

Read through the full CLAUDE.md and verify:
- Module table matches actual `pom.xml` modules
- SPI descriptions match actual interfaces
- CDI activation patterns are documented correctly
- Key rules are not contradicted

- [ ] **Step 4: Commit**

```
docs(casehub-ras#7): update CLAUDE.md — new drools-reliability module and SPI changes
```

---

## Self-Review

**Spec coverage check:**
- DroolsSessionKey with `toStorageKey()` — Task 1 Step 1 ✓
- DroolsSessionStore SPI with `computeIfAbsent(key, kieBase, config, generation)` — Task 1 Step 2 ✓
- `removeAll()` eliminated from SPI, kept as concrete method — Task 1 Step 5 ✓
- InMemoryDroolsSessionStore with StampedSession — Task 1 Step 5 ✓
- DroolsGanglion refactored with `buildSessionConfig()`, EPHEMERAL retained — Task 2 Step 3 ✓
- `reloadGeneration` retained on ganglion, `sessionGenerations` removed — Task 2 Step 3 ✓
- New `drools-reliability/` module (folder naming per protocol) — Task 3 Step 1 ✓
- ReliableDroolsSessionStore: `@ApplicationScoped` Tier 2 — Task 3 Step 6 ✓
- Two-layer cache (hot + persistent) — Task 3 Step 6 ✓
- `@PostConstruct` clears sessionGenerations — Task 3 Step 6 ✓
- `STORES_ONLY`, `AFTER_FIRE`, `ACTIVATION_KEY` — Task 3 Step 6 ✓
- Config borrowed read-only — Task 3 Step 6 (`buildStoreConfig`) ✓
- Recovery failure fallback — Task 3 Step 6 ✓
- All 9 test scenarios from spec — Task 3 Step 4 ✓
- CLAUDE.md updated — Task 4 ✓

**Placeholder scan:** No TBDs, TODOs, or incomplete sections found.

**Type consistency:** `DroolsSessionKey`, `computeIfAbsent`, `StampedSession`, `toStorageKey()` — all consistent across tasks.
