# DroolsGanglion Hot Rule Reload Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enable swapping DRL rules at runtime without restarting the application, with lazy session invalidation for long-lived sessions.

**Architecture:** `DroolsGanglion` gains a `synchronized reload()` method that compiles new rules into a fresh `KieBase` and swaps it via a volatile reference. A generation counter drives lazy invalidation — on next `detect()`, stale long-lived sessions are disposed and recreated from the new KieBase within the existing per-key lock. Acquire-release volatile ordering guarantees JMM-correct visibility.

**Tech Stack:** Java 21, Drools 10.1.0 (kie-api, executable model), Mutiny, JUnit 5, AssertJ

## Global Constraints

- No new module dependencies — all changes are within `ras-drools/`
- `DroolsGanglionConfig` record is unchanged — it remains the construction parameter
- `DroolsSessionStore` SPI is unchanged — no `removeAll()` on the interface
- All existing tests must continue passing after each task
- Spec: `docs/superpowers/specs/2026-06-26-drools-hot-reload-design.md`

---

### Task 1: Structural refactoring — decompose config, fix createSession, fix error handler, unique release IDs

This task preserves all existing behavior while restructuring internals for reload support. All existing tests must pass unchanged.

**Files:**
- Modify: `ras-drools/src/main/java/io/casehub/ras/drools/DroolsGanglion.java`
- Test: existing tests in `ras-drools/src/test/java/io/casehub/ras/drools/DroolsGanglionTest.java` and `DroolsGanglionContractTest.java`

**Interfaces:**
- Consumes: `DroolsGanglionConfig`, `DroolsSessionStore`, `DroolsObjectExtractor` (unchanged)
- Produces: Refactored `DroolsGanglion` with individual fields, `createSession(KieBase)`, `buildKieBase(List, List)`, unique release IDs. Public API unchanged — `detect()`, `close()`, `ganglionId()`, `handledEventTypes()`.

- [ ] **Step 1: Run existing tests to establish baseline**

Run: `/opt/homebrew/bin/mvn --batch-mode -pl ras-drools -am test`
Expected: All tests pass.

- [ ] **Step 2: Decompose config into individual fields**

Replace the `config` and `kieBase` fields with individual fields. The constructor extracts from config and does not store it. Add the volatile, generation, and session generation tracking fields for later use by reload.

In `DroolsGanglion.java`, replace the field declarations and constructor:

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
import org.kie.api.builder.KieModule;
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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
    private final ConcurrentHashMap<SessionGenKey, Long> sessionGenerations = new ConcurrentHashMap<>();

    private record SessionGenKey(String situationId, String correlationKey, String tenancyId) {}

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
```

- [ ] **Step 3: Update ganglionId() and handledEventTypes()**

```java
    @Override
    public String ganglionId() { return ganglionId; }

    @Override
    public Set<String> handledEventTypes() { return handledEventTypes; }
```

- [ ] **Step 4: Refactor createSession() to accept KieBase parameter**

```java
    private KieSession createSession(KieBase base) {
        KieSessionConfiguration ksc = KieServices.Factory.get()
                .newKieSessionConfiguration();
        if (clockMode == ClockMode.PSEUDO) {
            ksc.setOption(ClockTypeOption.PSEUDO);
        }
        return base.newKieSession(ksc, null);
    }
```

- [ ] **Step 5: Update detect() to capture volatile snapshot and use createSession(KieBase)**

Update `detect()` to capture `kieBase` at entry (acquire-release: flag first, data second) and pass it to `createSession`. Also fix the error handler double-dispose for non-new sessions.

```java
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
            session = sessionStore.get(ganglionId, situationId, correlationKey, tenancyId)
                    .orElse(null);
            if (session == null) {
                session = createSession(currentBase);
                isNewSession = true;
            }
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
                sessionStore.remove(ganglionId, situationId, correlationKey, tenancyId);
            }
            throw ex;
        }

        DetectionResult result = resultCollectionStrategy
                .resolve(collector.results(), ganglionId);

        session.unregisterChannel(RESULT_CHANNEL);
        if (sessionMode == SessionMode.LONG_LIVED) {
            sessionStore.put(ganglionId, situationId, correlationKey, tenancyId, session);
        } else {
            session.dispose();
        }

        return Uni.createFrom().item(result);
    }
```

- [ ] **Step 6: Refactor buildKieBase() — accept rule lists, unique release ID, correct error ordering**

```java
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
```

- [ ] **Step 7: Update close() (unchanged behavior, uses individual field)**

```java
    @Override
    public Uni<Void> close(String situationId, String correlationKey, String tenancyId) {
        sessionStore.remove(ganglionId, situationId, correlationKey, tenancyId);
        return Uni.createFrom().voidItem();
    }
```

- [ ] **Step 8: Update advanceClock() (uses individual field)**

```java
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
```

- [ ] **Step 9: Run existing tests to verify no behavioral change**

Run: `/opt/homebrew/bin/mvn --batch-mode -pl ras-drools -am test`
Expected: All tests pass. The refactoring is purely structural.

- [ ] **Step 10: Commit**

```bash
git add ras-drools/src/main/java/io/casehub/ras/drools/DroolsGanglion.java
git commit -m "refactor(casehub-ras#9): decompose config, unique release ID, createSession accepts KieBase

Structural preparation for hot rule reload:
- Extract individual fields from DroolsGanglionConfig (no stale state)
- createSession(KieBase) receives captured reference (no volatile re-read)
- buildKieBase() accepts rule lists directly, unique release ID per compilation
- Fix error handler double dispose (store owns disposal per Epic 4 §5)
- Acquire-release volatile read order in detect() (flag first, data second)

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: Implement reload() with lazy invalidation

Add the `reload()` method and lazy invalidation logic in `detect()`. This is the core feature.

**Files:**
- Modify: `ras-drools/src/main/java/io/casehub/ras/drools/DroolsGanglion.java`
- Test: `ras-drools/src/test/java/io/casehub/ras/drools/DroolsGanglionTest.java`

**Interfaces:**
- Consumes: Refactored `DroolsGanglion` from Task 1 (volatile kieBase, reloadGeneration, sessionGenerations, createSession(KieBase), buildKieBase(List, List))
- Produces: `DroolsGanglion.reload(List<String> classpathRules, List<String> programmaticRules)` — public synchronized method

- [ ] **Step 1: Write test — reload swaps KieBase for ephemeral sessions**

Add to `DroolsGanglionTest.java`:

```java
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
        assertThat(r2.signal()).isEqualTo(DetectionSignal.DETECTED);
    }
```

- [ ] **Step 2: Run test — verify it fails**

Run: `/opt/homebrew/bin/mvn --batch-mode -pl ras-drools -am test -Dtest="DroolsGanglionTest#reloadSwapsRulesForEphemeralSessions"`
Expected: Compilation error — `reload()` does not exist yet.

- [ ] **Step 3: Implement reload()**

Add to `DroolsGanglion.java`:

```java
    public synchronized void reload(List<String> classpathRules, List<String> programmaticRules) {
        if (classpathRules.isEmpty() && programmaticRules.isEmpty()) {
            throw new IllegalArgumentException("At least one rule source required");
        }
        KieBase newBase = buildKieBase(classpathRules, programmaticRules);
        this.kieBase = newBase;
        this.reloadGeneration++;
    }
```

- [ ] **Step 4: Run test — verify it passes**

Run: `/opt/homebrew/bin/mvn --batch-mode -pl ras-drools -am test -Dtest="DroolsGanglionTest#reloadSwapsRulesForEphemeralSessions"`
Expected: PASS

- [ ] **Step 5: Write test — compilation failure leaves old KieBase intact**

```java
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
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DRL compilation failed");

        var event = testEvent("test.event", Instant.parse("2026-06-21T10:00:00Z"));
        var result = ganglion.detect(event, testContext()).await().indefinitely();
        assertThat(result.confidence()).isEqualTo(0.5);
    }
```

- [ ] **Step 6: Write test — reload with empty rules throws**

```java
    @Test
    void reloadWithEmptyRulesThrows() {
        var ganglion = ganglionWithClasspathRule();
        assertThatThrownBy(() -> ganglion.reload(List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("At least one rule source required");
    }
```

- [ ] **Step 7: Run tests — verify they pass**

Run: `/opt/homebrew/bin/mvn --batch-mode -pl ras-drools -am test -Dtest="DroolsGanglionTest#reloadCompilationFailureLeavesOldRulesIntact+reloadWithEmptyRulesThrows"`
Expected: Both pass.

- [ ] **Step 8: Write test — lazy invalidation disposes stale long-lived session**

```java
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
        var r1 = ganglion.detect(event1, ctx).await().indefinitely();
        assertThat(r1.confidence()).isEqualTo(0.5);
        var sessionBefore = sessionStore.get("reload-g", "sit-1", "key-1", "tenant-a").orElseThrow();

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

        var sessionAfter = sessionStore.get("reload-g", "sit-1", "key-1", "tenant-a").orElseThrow();
        assertThat(sessionAfter).isNotSameAs(sessionBefore);
    }
```

- [ ] **Step 9: Run test — verify it fails**

Run: `/opt/homebrew/bin/mvn --batch-mode -pl ras-drools -am test -Dtest="DroolsGanglionTest#reloadInvalidatesLongLivedSessionOnNextDetect"`
Expected: FAIL — detect() doesn't check generation yet. The old session is reused.

- [ ] **Step 10: Add lazy invalidation to detect()**

Update the LONG_LIVED block in `detect()` to check session generation:

```java
        if (sessionMode == SessionMode.LONG_LIVED) {
            var genKey = new SessionGenKey(situationId, correlationKey, tenancyId);
            session = sessionStore.get(ganglionId, situationId, correlationKey, tenancyId)
                    .orElse(null);
            Long storedGen = sessionGenerations.get(genKey);
            if (session != null && (storedGen == null || storedGen < currentGen)) {
                sessionStore.remove(ganglionId, situationId, correlationKey, tenancyId);
                session = null;
            }
            if (session == null) {
                session = createSession(currentBase);
                isNewSession = true;
            }
        } else {
            session = createSession(currentBase);
            isNewSession = true;
        }
```

And update the session store put section to record the generation:

```java
        session.unregisterChannel(RESULT_CHANNEL);
        if (sessionMode == SessionMode.LONG_LIVED) {
            sessionStore.put(ganglionId, situationId, correlationKey, tenancyId, session);
            sessionGenerations.put(new SessionGenKey(situationId, correlationKey, tenancyId), currentGen);
        } else {
            session.dispose();
        }
```

And update `close()` to clean up the generation entry:

```java
    @Override
    public Uni<Void> close(String situationId, String correlationKey, String tenancyId) {
        sessionStore.remove(ganglionId, situationId, correlationKey, tenancyId);
        sessionGenerations.remove(new SessionGenKey(situationId, correlationKey, tenancyId));
        return Uni.createFrom().voidItem();
    }
```

- [ ] **Step 11: Run test — verify it passes**

Run: `/opt/homebrew/bin/mvn --batch-mode -pl ras-drools -am test -Dtest="DroolsGanglionTest#reloadInvalidatesLongLivedSessionOnNextDetect"`
Expected: PASS

- [ ] **Step 12: Write test — full lifecycle: detect → reload → close → new detect uses new rules**

```java
    @Test
    void fullDrainLifecycleUsesNewRulesAfterClose() {
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

        // Next detect invalidates and uses new rules
        var event2 = testEvent("test.event", Instant.parse("2026-06-21T10:01:00Z"));
        var r2 = ganglion.detect(event2, ctx).await().indefinitely();
        assertThat(r2.confidence()).isEqualTo(0.95);

        // Close the situation
        ganglion.close("sit-1", "key-1", "tenant-a").await().indefinitely();
        assertThat(sessionStore.get("reload-g", "sit-1", "key-1", "tenant-a")).isEmpty();

        // New detect for same situation key creates session from new rules
        var newCtx = SituationContext.initial("sit-1", "key-1", "tenant-a",
                Instant.parse("2026-06-21T11:00:00Z"));
        var event3 = testEvent("test.event", Instant.parse("2026-06-21T11:00:00Z"));
        var r3 = ganglion.detect(event3, newCtx).await().indefinitely();
        assertThat(r3.confidence()).isEqualTo(0.95);
    }
```

- [ ] **Step 13: Write test — generation counter boundary (gen-0 invalidated, gen-1 survives)**

```java
    @Test
    void generationBoundarySurvivesUntilNextReload() {
        var drl1 = """
                package test;
                import io.cloudevents.CloudEvent;
                import io.casehub.ras.api.DetectionResult;
                import io.casehub.ras.api.DetectionSignal;
                import java.util.Map;
                rule "v1"
                when $ce : CloudEvent(type == "test.event")
                then channels["results"].send(new DetectionResult(
                    "gen-g", 0.5, DetectionSignal.WEAK, Map.of()));
                end
                """;
        var config = new DroolsGanglionConfig(
                "gen-g", Set.of("test.event"),
                SessionMode.LONG_LIVED, ClockMode.PSEUDO,
                List.of(), List.of(drl1));
        var ganglion = new DroolsGanglion(config, sessionStore, List.of());
        var ctx = testContext();

        // Gen 0: detect creates session
        var event1 = testEvent("test.event", Instant.parse("2026-06-21T10:00:00Z"));
        ganglion.detect(event1, ctx).await().indefinitely();

        // Reload to gen 1
        var drl2 = drl1.replace("0.5", "0.7").replace("v1", "v2");
        ganglion.reload(List.of(), List.of(drl2));

        // Gen-0 session invalidated, new session at gen 1
        var event2 = testEvent("test.event", Instant.parse("2026-06-21T10:01:00Z"));
        var r2 = ganglion.detect(event2, ctx).await().indefinitely();
        assertThat(r2.confidence()).isEqualTo(0.7);
        var sessionAtGen1 = sessionStore.get("gen-g", "sit-1", "key-1", "tenant-a").orElseThrow();

        // Detect again without reload — gen-1 session survives
        var event3 = testEvent("test.event", Instant.parse("2026-06-21T10:02:00Z"));
        ganglion.detect(event3, ctx).await().indefinitely();
        var sessionStillGen1 = sessionStore.get("gen-g", "sit-1", "key-1", "tenant-a").orElseThrow();
        assertThat(sessionStillGen1).isSameAs(sessionAtGen1);
    }
```

- [ ] **Step 14: Write test — close() cleans up generation entry**

```java
    @Test
    void closeCleansUpGenerationEntry() {
        var config = new DroolsGanglionConfig(
                "test-ganglion", Set.of("test.event"),
                SessionMode.LONG_LIVED, ClockMode.PSEUDO,
                List.of("io/casehub/ras/drools/test-threshold.drl"), List.of());
        var ganglion = new DroolsGanglion(config, sessionStore, List.of());
        var ctx = testContext();

        var event1 = testEvent("test.event", Instant.parse("2026-06-21T10:00:00Z"));
        ganglion.detect(event1, ctx).await().indefinitely();

        ganglion.close("sit-1", "key-1", "tenant-a").await().indefinitely();

        // After close + new detect, session is created fresh (not invalidated as stale)
        var newCtx = SituationContext.initial("sit-1", "key-1", "tenant-a",
                Instant.parse("2026-06-21T11:00:00Z"));
        var event2 = testEvent("test.event", Instant.parse("2026-06-21T11:00:00Z"));
        var r = ganglion.detect(event2, newCtx).await().indefinitely();
        assertThat(r.signal()).isEqualTo(DetectionSignal.DETECTED);
        assertThat(sessionStore.get("test-ganglion", "sit-1", "key-1", "tenant-a")).isPresent();
    }
```

- [ ] **Step 15: Run all tests**

Run: `/opt/homebrew/bin/mvn --batch-mode -pl ras-drools -am test`
Expected: All tests pass — existing and new.

- [ ] **Step 16: Commit**

```bash
git add ras-drools/src/main/java/io/casehub/ras/drools/DroolsGanglion.java \
       ras-drools/src/test/java/io/casehub/ras/drools/DroolsGanglionTest.java
git commit -m "feat(casehub-ras#9): DroolsGanglion.reload() with lazy invalidation

Synchronized reload() compiles new rules, swaps volatile KieBase,
increments generation counter. detect() checks generation via
acquire-release volatile pattern and disposes stale long-lived
sessions on next use. No removeAll() on SPI — invalidation happens
within SituationEvaluator's existing per-key lock.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: InMemoryDroolsSessionStore.removeAll() test utility

Add `removeAll(String ganglionId)` as a concrete method on `InMemoryDroolsSessionStore` (not on the SPI). Test utility for cleaning up sessions in test teardown.

**Files:**
- Modify: `ras-drools/src/main/java/io/casehub/ras/drools/InMemoryDroolsSessionStore.java`
- Test: `ras-drools/src/test/java/io/casehub/ras/drools/InMemoryDroolsSessionStoreTest.java`

**Interfaces:**
- Consumes: nothing new
- Produces: `InMemoryDroolsSessionStore.removeAll(String ganglionId)` — concrete method, not on SPI

- [ ] **Step 1: Write test — removeAll disposes all sessions for a ganglion**

Add to `InMemoryDroolsSessionStoreTest.java`:

```java
    @Test
    void removeAllDisposesAllSessionsForGanglion() {
        var s1 = freshSession();
        var s2 = freshSession();
        var s3 = freshSession();
        store.put("g1", "sit-1", "key-1", "tenant-a", s1);
        store.put("g1", "sit-2", "key-2", "tenant-a", s2);
        store.put("g2", "sit-1", "key-1", "tenant-a", s3);

        store.removeAll("g1");

        assertThat(store.get("g1", "sit-1", "key-1", "tenant-a")).isEmpty();
        assertThat(store.get("g1", "sit-2", "key-2", "tenant-a")).isEmpty();
        assertThat(store.get("g2", "sit-1", "key-1", "tenant-a")).containsSame(s3);
    }

    @Test
    void removeAllNonExistentGanglionIsNoOp() {
        assertThatNoException().isThrownBy(() -> store.removeAll("no-such"));
    }
```

- [ ] **Step 2: Run test — verify compilation failure**

Run: `/opt/homebrew/bin/mvn --batch-mode -pl ras-drools -am test -Dtest="InMemoryDroolsSessionStoreTest#removeAllDisposesAllSessionsForGanglion"`
Expected: Compilation error — `removeAll()` does not exist.

- [ ] **Step 3: Implement removeAll()**

Add to `InMemoryDroolsSessionStore.java`:

```java
    public void removeAll(String ganglionId) {
        var iterator = sessions.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (entry.getKey().ganglionId().equals(ganglionId)) {
                entry.getValue().dispose();
                iterator.remove();
            }
        }
    }
```

- [ ] **Step 4: Run tests — verify they pass**

Run: `/opt/homebrew/bin/mvn --batch-mode -pl ras-drools -am test -Dtest="InMemoryDroolsSessionStoreTest"`
Expected: All tests pass.

- [ ] **Step 5: Run full build**

Run: `/opt/homebrew/bin/mvn --batch-mode install`
Expected: All modules build and all tests pass.

- [ ] **Step 6: Commit**

```bash
git add ras-drools/src/main/java/io/casehub/ras/drools/InMemoryDroolsSessionStore.java \
       ras-drools/src/test/java/io/casehub/ras/drools/InMemoryDroolsSessionStoreTest.java
git commit -m "feat(casehub-ras#9): InMemoryDroolsSessionStore.removeAll() test utility

Concrete method on InMemoryDroolsSessionStore (not on SPI) for
test cleanup. Scans ConcurrentHashMap for matching ganglionId,
disposes each session, removes from map.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"
```
