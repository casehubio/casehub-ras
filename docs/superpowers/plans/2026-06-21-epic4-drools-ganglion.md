# Epic 4: DroolsGanglion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement `DroolsGanglion` — an optional Ganglion implementation using classic Drools CEP (kie-api) for temporal pattern detection, plus add `close()` lifecycle method to the Ganglion SPI.

**Architecture:** `ras-drools/` is an optional Jandex library module. `DroolsGanglion` implements the `Ganglion` SPI, builds a `KieBase` from DRL rules (classpath + programmatic), manages `KieSession` lifecycle via a pluggable `DroolsSessionStore` SPI, and extracts domain objects from CloudEvents via `DroolsObjectExtractor` SPI. Two session modes: `LONG_LIVED` (stateful CEP) and `EPHEMERAL` (stateless pattern matching). Pseudo clock by default, driven by CloudEvent timestamps.

**Tech Stack:** Java 21, Drools 10.1.0 (kie-api, executable model), Quarkus Arc (CDI), Mutiny (Uni), CloudEvents 4.0.1, JUnit 5, AssertJ

## Global Constraints

- Drools 10.1.0 — `org.drools:drools-model-codegen` for executable model, `org.drools:drools-wiring-static` for static classloading
- No Rule Units, no `drools-quarkus` extension — classic kie-api only
- No dependency on `casehub-ras` (runtime), `casehub-engine-api`, or any transport library
- Package: `io.casehub.ras.drools`
- `buildAll(ExecutableModelProject.class)` — never plain `buildAll()`
- `EventProcessingOption.STREAM` unconditionally on all KieBases
- All tests use `mvn --batch-mode test` from repo root or `-pl ras-drools` / `-pl api`
- Spec: `docs/superpowers/specs/2026-06-21-epic4-drools-ganglion-design.md`

---

### Task 1: Ganglion SPI — add close() default method

Add the `close()` lifecycle method to the `Ganglion` interface in `casehub-ras-api`, update the contract test, and verify existing code still compiles.

**Files:**
- Modify: `api/src/main/java/io/casehub/ras/api/Ganglion.java`
- Modify: `api/src/test/java/io/casehub/ras/api/GanglionContractTest.java`

**Interfaces:**
- Produces: `Ganglion.close(String situationId, String tenancyId)` → `Uni<Void>` (default returns `Uni.createFrom().voidItem()`)

- [ ] **Step 1: Write the failing test for close() default**

Add to `api/src/test/java/io/casehub/ras/api/GanglionContractTest.java`:

```java
@Test
void closeDefaultReturnsCompletedUni() {
    Ganglion ganglion = new Ganglion() {
        @Override
        public String ganglionId() { return "test-ganglion"; }

        @Override
        public Set<String> handledEventTypes() { return Set.of("test.event"); }

        @Override
        public Uni<DetectionResult> detect(CloudEvent event, SituationContext context) {
            return Uni.createFrom().item(
                    new DetectionResult("test-ganglion", 0.5, DetectionSignal.DETECTED, null));
        }
    };

    Void result = ganglion.close("sit-1", "tenant-a").await().indefinitely();
    assertThat(result).isNull();
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn --batch-mode test -pl api -Dtest=GanglionContractTest#closeDefaultReturnsCompletedUni`
Expected: FAIL — `close()` method does not exist on `Ganglion`

- [ ] **Step 3: Add close() default method to Ganglion**

In `api/src/main/java/io/casehub/ras/api/Ganglion.java`, add after the `compact()` method:

```java
default Uni<Void> close(String situationId, String tenancyId) {
    return Uni.createFrom().voidItem();
}
```

- [ ] **Step 4: Run all api tests to verify everything passes**

Run: `mvn --batch-mode test -pl api`
Expected: ALL PASS — new test passes, existing `compactDefaultReturnsContextUnchanged` still passes, `MockGanglion` inherits default (no change needed)

- [ ] **Step 5: Commit**

```
feat(casehub-ras#4): add close() default method to Ganglion SPI

Lifecycle callback for situation termination. Returns Uni<Void> per
spi-reactive-blocking-io protocol. Default is no-op — stateless ganglia
inherit it. Stateful ganglia (DroolsGanglion, future Bayesian, LLM)
override to clean up per-situation resources.
```

---

### Task 2: ras-drools module setup — pom.xml + enums + config record

Update the skeleton `ras-drools/pom.xml` with Drools dependencies and add version management to the parent pom. Create the three simplest types: `SessionMode`, `ClockMode`, `DroolsGanglionConfig`.

**Files:**
- Modify: `pom.xml` (parent — add Drools version property and dependency management)
- Modify: `ras-drools/pom.xml` (add Drools + test deps)
- Create: `ras-drools/src/main/java/io/casehub/ras/drools/SessionMode.java`
- Create: `ras-drools/src/main/java/io/casehub/ras/drools/ClockMode.java`
- Create: `ras-drools/src/main/java/io/casehub/ras/drools/DroolsGanglionConfig.java`
- Create: `ras-drools/src/test/java/io/casehub/ras/drools/DroolsGanglionConfigTest.java`

**Interfaces:**
- Produces: `SessionMode.LONG_LIVED`, `SessionMode.EPHEMERAL`
- Produces: `ClockMode.PSEUDO`, `ClockMode.REALTIME`
- Produces: `DroolsGanglionConfig(String ganglionId, Set<String> handledEventTypes, SessionMode sessionMode, ClockMode clockMode, List<String> classpathRules, List<String> programmaticRules)`

- [ ] **Step 1: Update parent pom.xml — add Drools version property and dependency management**

In `pom.xml`, add property:
```xml
<version.drools>10.1.0</version.drools>
```

Add dependency management entries after the engine-api entry:
```xml
<!-- Drools CEP -->
<dependency>
    <groupId>org.drools</groupId>
    <artifactId>drools-model-codegen</artifactId>
    <version>${version.drools}</version>
</dependency>
<dependency>
    <groupId>org.drools</groupId>
    <artifactId>drools-wiring-static</artifactId>
    <version>${version.drools}</version>
    <scope>runtime</scope>
</dependency>
```

- [ ] **Step 2: Update ras-drools/pom.xml — add Drools + test dependencies**

Replace the `<dependencies>` section in `ras-drools/pom.xml`:

```xml
<dependencies>
    <dependency><groupId>io.casehub</groupId><artifactId>casehub-ras-api</artifactId></dependency>
    <dependency><groupId>io.quarkus</groupId><artifactId>quarkus-arc</artifactId></dependency>
    <dependency><groupId>io.smallrye.reactive</groupId><artifactId>mutiny</artifactId><scope>provided</scope></dependency>
    <dependency><groupId>org.drools</groupId><artifactId>drools-model-codegen</artifactId></dependency>
    <dependency><groupId>org.drools</groupId><artifactId>drools-wiring-static</artifactId></dependency>
    <dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><scope>test</scope></dependency>
    <dependency><groupId>org.assertj</groupId><artifactId>assertj-core</artifactId><scope>test</scope></dependency>
    <dependency><groupId>io.casehub</groupId><artifactId>casehub-ras-testing</artifactId></dependency>
</dependencies>
```

- [ ] **Step 3: Create SessionMode and ClockMode enums**

`ras-drools/src/main/java/io/casehub/ras/drools/SessionMode.java`:
```java
package io.casehub.ras.drools;

public enum SessionMode { LONG_LIVED, EPHEMERAL }
```

`ras-drools/src/main/java/io/casehub/ras/drools/ClockMode.java`:
```java
package io.casehub.ras.drools;

public enum ClockMode { PSEUDO, REALTIME }
```

- [ ] **Step 4: Write failing tests for DroolsGanglionConfig**

`ras-drools/src/test/java/io/casehub/ras/drools/DroolsGanglionConfigTest.java`:
```java
package io.casehub.ras.drools;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

class DroolsGanglionConfigTest {

    @Test
    void validConfigWithClasspathRules() {
        var config = new DroolsGanglionConfig(
                "test-ganglion", Set.of("temp.reading"),
                SessionMode.LONG_LIVED, ClockMode.PSEUDO,
                List.of("rules/temp.drl"), List.of());
        assertThat(config.ganglionId()).isEqualTo("test-ganglion");
        assertThat(config.handledEventTypes()).containsExactly("temp.reading");
        assertThat(config.sessionMode()).isEqualTo(SessionMode.LONG_LIVED);
        assertThat(config.clockMode()).isEqualTo(ClockMode.PSEUDO);
        assertThat(config.classpathRules()).containsExactly("rules/temp.drl");
        assertThat(config.programmaticRules()).isEmpty();
    }

    @Test
    void validConfigWithProgrammaticRules() {
        var config = new DroolsGanglionConfig(
                "test-ganglion", Set.of("temp.reading"),
                SessionMode.EPHEMERAL, ClockMode.REALTIME,
                List.of(), List.of("rule \"x\" when then end"));
        assertThat(config.programmaticRules()).hasSize(1);
    }

    @Test
    void nullGanglionIdThrows() {
        assertThatThrownBy(() -> new DroolsGanglionConfig(
                null, Set.of("e"), SessionMode.LONG_LIVED, ClockMode.PSEUDO,
                List.of("r.drl"), List.of()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void emptyHandledEventTypesThrows() {
        assertThatThrownBy(() -> new DroolsGanglionConfig(
                "g", Set.of(), SessionMode.LONG_LIVED, ClockMode.PSEUDO,
                List.of("r.drl"), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("handledEventTypes");
    }

    @Test
    void noRuleSourcesThrows() {
        assertThatThrownBy(() -> new DroolsGanglionConfig(
                "g", Set.of("e"), SessionMode.LONG_LIVED, ClockMode.PSEUDO,
                List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rule source");
    }

    @Test
    void collectionsAreDefensivelyCopied() {
        var types = new java.util.HashSet<>(Set.of("a", "b"));
        var rules = new java.util.ArrayList<>(List.of("r.drl"));
        var config = new DroolsGanglionConfig("g", types,
                SessionMode.LONG_LIVED, ClockMode.PSEUDO, rules, List.of());
        types.add("c");
        rules.add("s.drl");
        assertThat(config.handledEventTypes()).containsExactlyInAnyOrder("a", "b");
        assertThat(config.classpathRules()).containsExactly("r.drl");
    }
}
```

- [ ] **Step 5: Run test to verify it fails**

Run: `mvn --batch-mode test -pl ras-drools -Dtest=DroolsGanglionConfigTest`
Expected: FAIL — `DroolsGanglionConfig` class does not exist

- [ ] **Step 6: Implement DroolsGanglionConfig**

`ras-drools/src/main/java/io/casehub/ras/drools/DroolsGanglionConfig.java`:
```java
package io.casehub.ras.drools;

import java.util.List;
import java.util.Objects;
import java.util.Set;

public record DroolsGanglionConfig(
        String ganglionId,
        Set<String> handledEventTypes,
        SessionMode sessionMode,
        ClockMode clockMode,
        List<String> classpathRules,
        List<String> programmaticRules
) {
    public DroolsGanglionConfig {
        Objects.requireNonNull(ganglionId);
        if (handledEventTypes == null || handledEventTypes.isEmpty()) {
            throw new IllegalArgumentException("handledEventTypes must not be empty");
        }
        handledEventTypes = Set.copyOf(handledEventTypes);
        if (classpathRules == null) classpathRules = List.of();
        if (programmaticRules == null) programmaticRules = List.of();
        classpathRules = List.copyOf(classpathRules);
        programmaticRules = List.copyOf(programmaticRules);
        if (classpathRules.isEmpty() && programmaticRules.isEmpty()) {
            throw new IllegalArgumentException("At least one rule source required");
        }
    }
}
```

- [ ] **Step 7: Run tests and verify they pass**

Run: `mvn --batch-mode test -pl ras-drools`
Expected: ALL PASS

- [ ] **Step 8: Commit**

```
feat(casehub-ras#4): ras-drools module setup — pom, enums, config record

Drools 10.1.0 deps (drools-model-codegen, drools-wiring-static).
SessionMode (LONG_LIVED, EPHEMERAL), ClockMode (PSEUDO, REALTIME),
DroolsGanglionConfig record with compact-constructor validation.
```

---

### Task 3: DroolsObjectExtractor SPI + DroolsSessionStore SPI + InMemoryDroolsSessionStore

Create the two SPIs and the in-memory session store default implementation.

**Files:**
- Create: `ras-drools/src/main/java/io/casehub/ras/drools/DroolsObjectExtractor.java`
- Create: `ras-drools/src/main/java/io/casehub/ras/drools/DroolsSessionStore.java`
- Create: `ras-drools/src/main/java/io/casehub/ras/drools/InMemoryDroolsSessionStore.java`
- Create: `ras-drools/src/test/java/io/casehub/ras/drools/InMemoryDroolsSessionStoreTest.java`

**Interfaces:**
- Produces: `DroolsObjectExtractor` — `Set<String> handledEventTypes()`, `List<Object> extract(CloudEvent event)`
- Produces: `DroolsSessionStore` — `get(ganglionId, situationId, tenancyId)`, `put(ganglionId, situationId, tenancyId, session)`, `remove(ganglionId, situationId, tenancyId)`
- Produces: `InMemoryDroolsSessionStore` — `@DefaultBean @ApplicationScoped`

- [ ] **Step 1: Create DroolsObjectExtractor interface**

`ras-drools/src/main/java/io/casehub/ras/drools/DroolsObjectExtractor.java`:
```java
package io.casehub.ras.drools;

import io.cloudevents.CloudEvent;
import java.util.List;
import java.util.Set;

public interface DroolsObjectExtractor {

    Set<String> handledEventTypes();

    List<Object> extract(CloudEvent event);
}
```

- [ ] **Step 2: Create DroolsSessionStore interface**

`ras-drools/src/main/java/io/casehub/ras/drools/DroolsSessionStore.java`:
```java
package io.casehub.ras.drools;

import org.kie.api.runtime.KieSession;
import java.util.Optional;

public interface DroolsSessionStore {

    Optional<KieSession> get(String ganglionId, String situationId, String tenancyId);

    void put(String ganglionId, String situationId, String tenancyId, KieSession session);

    void remove(String ganglionId, String situationId, String tenancyId);
}
```

- [ ] **Step 3: Write failing tests for InMemoryDroolsSessionStore**

`ras-drools/src/test/java/io/casehub/ras/drools/InMemoryDroolsSessionStoreTest.java`:
```java
package io.casehub.ras.drools;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kie.api.KieBase;
import org.kie.api.KieServices;
import org.kie.api.runtime.KieSession;
import static org.assertj.core.api.Assertions.*;

class InMemoryDroolsSessionStoreTest {

    private InMemoryDroolsSessionStore store;
    private KieBase kieBase;

    @BeforeEach
    void setUp() {
        store = new InMemoryDroolsSessionStore();
        kieBase = KieServices.Factory.get().newKieClasspathContainer().getKieBase();
    }

    private KieSession freshSession() {
        return kieBase.newKieSession();
    }

    @Test
    void getReturnsEmptyForUnknownKey() {
        assertThat(store.get("g1", "sit-1", "tenant-a")).isEmpty();
    }

    @Test
    void putThenGetReturnsSameSession() {
        var session = freshSession();
        store.put("g1", "sit-1", "tenant-a", session);
        assertThat(store.get("g1", "sit-1", "tenant-a")).containsSame(session);
    }

    @Test
    void differentGanglionIdsSameKeysAreIndependent() {
        var session1 = freshSession();
        var session2 = freshSession();
        store.put("g1", "sit-1", "tenant-a", session1);
        store.put("g2", "sit-1", "tenant-a", session2);
        assertThat(store.get("g1", "sit-1", "tenant-a")).containsSame(session1);
        assertThat(store.get("g2", "sit-1", "tenant-a")).containsSame(session2);
    }

    @Test
    void removeDisposesAndEvictsSession() {
        var session = freshSession();
        store.put("g1", "sit-1", "tenant-a", session);
        store.remove("g1", "sit-1", "tenant-a");
        assertThat(store.get("g1", "sit-1", "tenant-a")).isEmpty();
    }

    @Test
    void removeNonExistentKeyIsNoOp() {
        assertThatNoException().isThrownBy(
                () -> store.remove("g1", "no-such", "tenant-a"));
    }

    @Test
    void putUpsertDisposesOldSession() {
        var session1 = freshSession();
        var session2 = freshSession();
        store.put("g1", "sit-1", "tenant-a", session1);
        store.put("g1", "sit-1", "tenant-a", session2);
        assertThat(store.get("g1", "sit-1", "tenant-a")).containsSame(session2);
    }
}
```

- [ ] **Step 4: Run test to verify it fails**

Run: `mvn --batch-mode test -pl ras-drools -Dtest=InMemoryDroolsSessionStoreTest`
Expected: FAIL — `InMemoryDroolsSessionStore` class does not exist

- [ ] **Step 5: Implement InMemoryDroolsSessionStore**

`ras-drools/src/main/java/io/casehub/ras/drools/InMemoryDroolsSessionStore.java`:
```java
package io.casehub.ras.drools;

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import org.kie.api.runtime.KieSession;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
@DefaultBean
public class InMemoryDroolsSessionStore implements DroolsSessionStore {

    private record SessionKey(String ganglionId, String situationId, String tenancyId) {}

    private final ConcurrentHashMap<SessionKey, KieSession> sessions = new ConcurrentHashMap<>();

    @Override
    public Optional<KieSession> get(String ganglionId, String situationId, String tenancyId) {
        return Optional.ofNullable(sessions.get(new SessionKey(ganglionId, situationId, tenancyId)));
    }

    @Override
    public void put(String ganglionId, String situationId, String tenancyId, KieSession session) {
        var key = new SessionKey(ganglionId, situationId, tenancyId);
        KieSession old = sessions.put(key, session);
        if (old != null && old != session) {
            old.dispose();
        }
    }

    @Override
    public void remove(String ganglionId, String situationId, String tenancyId) {
        KieSession removed = sessions.remove(new SessionKey(ganglionId, situationId, tenancyId));
        if (removed != null) {
            removed.dispose();
        }
    }
}
```

- [ ] **Step 6: Run tests and verify they pass**

Run: `mvn --batch-mode test -pl ras-drools -Dtest=InMemoryDroolsSessionStoreTest`
Expected: ALL PASS

- [ ] **Step 7: Commit**

```
feat(casehub-ras#4): DroolsObjectExtractor, DroolsSessionStore SPIs and InMemoryDroolsSessionStore

DroolsObjectExtractor — CloudEvent to domain object extraction SPI.
DroolsSessionStore — keyed by (ganglionId, situationId, tenancyId).
InMemoryDroolsSessionStore — @DefaultBean, ConcurrentHashMap-backed,
dispose() on remove and upsert.
```

---

### Task 4: ResultCollectorChannel + DroolsGanglion

The main class. Build `KieBase` from DRL, manage sessions, implement the full `detect()` flow with error handling, clock advancement, CloudEvent retraction, and `close()` override.

**Files:**
- Create: `ras-drools/src/main/java/io/casehub/ras/drools/ResultCollectorChannel.java`
- Create: `ras-drools/src/main/java/io/casehub/ras/drools/DroolsGanglion.java`
- Create: `ras-drools/src/test/java/io/casehub/ras/drools/DroolsGanglionTest.java`
- Create: `ras-drools/src/test/resources/io/casehub/ras/drools/test-threshold.drl`

**Interfaces:**
- Consumes: `DroolsGanglionConfig`, `DroolsSessionStore`, `DroolsObjectExtractor`, `Ganglion`, `DetectionResult`, `SituationContext`, `CloudEvent`
- Produces: `DroolsGanglion(DroolsGanglionConfig, DroolsSessionStore, List<DroolsObjectExtractor>)`, `DroolsGanglion.RESULT_CHANNEL`

- [ ] **Step 1: Create test DRL rule (classpath resource)**

`ras-drools/src/test/resources/io/casehub/ras/drools/test-threshold.drl`:
```drl
package io.casehub.ras.drools;

import io.cloudevents.CloudEvent;
import io.casehub.ras.api.DetectionResult;
import io.casehub.ras.api.DetectionSignal;
import java.util.Map;

rule "High value detected"
when
    $ce : CloudEvent(type == "test.event")
then
    channels["results"].send(new DetectionResult(
        "test-ganglion",
        0.9,
        DetectionSignal.DETECTED,
        Map.of("matched", true)));
end
```

- [ ] **Step 2: Write failing tests for DroolsGanglion**

`ras-drools/src/test/java/io/casehub/ras/drools/DroolsGanglionTest.java`:
```java
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
        return SituationContext.initial("sit-1", "tenant-a",
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
    void ephemeralModeDisposesSessionAfterDetect() {
        var ganglion = ganglionWithClasspathRule();
        var event = testEvent("test.event", Instant.parse("2026-06-21T10:00:00Z"));
        ganglion.detect(event, testContext()).await().indefinitely();
        assertThat(sessionStore.get("test-ganglion", "sit-1", "tenant-a")).isEmpty();
    }

    @Test
    void longLivedModeStoresSession() {
        var config = new DroolsGanglionConfig(
                "test-ganglion", Set.of("test.event"),
                SessionMode.LONG_LIVED, ClockMode.PSEUDO,
                List.of("io/casehub/ras/drools/test-threshold.drl"), List.of());
        var ganglion = new DroolsGanglion(config, sessionStore, List.of());
        var event = testEvent("test.event", Instant.parse("2026-06-21T10:00:00Z"));
        ganglion.detect(event, testContext()).await().indefinitely();
        assertThat(sessionStore.get("test-ganglion", "sit-1", "tenant-a")).isPresent();
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
        var session1 = sessionStore.get("test-ganglion", "sit-1", "tenant-a").orElseThrow();
        var event2 = testEvent("test.event", Instant.parse("2026-06-21T10:01:00Z"));
        ganglion.detect(event2, ctx).await().indefinitely();
        var session2 = sessionStore.get("test-ganglion", "sit-1", "tenant-a").orElseThrow();
        assertThat(session2).isSameAs(session1);
    }

    @Test
    void closeRemovesSessionFromStore() {
        var config = new DroolsGanglionConfig(
                "test-ganglion", Set.of("test.event"),
                SessionMode.LONG_LIVED, ClockMode.PSEUDO,
                List.of("io/casehub/ras/drools/test-threshold.drl"), List.of());
        var ganglion = new DroolsGanglion(config, sessionStore, List.of());
        var event = testEvent("test.event", Instant.parse("2026-06-21T10:00:00Z"));
        ganglion.detect(event, testContext()).await().indefinitely();
        assertThat(sessionStore.get("test-ganglion", "sit-1", "tenant-a")).isPresent();
        ganglion.close("sit-1", "tenant-a").await().indefinitely();
        assertThat(sessionStore.get("test-ganglion", "sit-1", "tenant-a")).isEmpty();
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
    void objectExtractorFactsInserted() {
        var drl = """
                package test;
                import io.casehub.ras.api.DetectionResult;
                import io.casehub.ras.api.DetectionSignal;
                import java.util.Map;
                declare TestFact value : double end
                rule "fact check"
                when $f : TestFact(value > 100.0)
                then channels["results"].send(new DetectionResult(
                    "ext-g", 0.8, DetectionSignal.DETECTED,
                    Map.of("value", $f.getValue())));
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
            public List<Object> extract(CloudEvent event) {
                // We need a fact the DRL can match — use a Map since TestFact is DRL-declared
                // DRL-declared types need to be inserted via the session, so use a simple Map
                return List.of();
            }
        };

        var ganglion = new DroolsGanglion(config, sessionStore, List.of(extractor));
        var event = testEvent("sensor.reading", Instant.parse("2026-06-21T10:00:00Z"));
        DetectionResult result = ganglion.detect(event, testContext())
                .await().indefinitely();
        // No extracted facts that match rule → NOISE
        assertThat(result.signal()).isEqualTo(DetectionSignal.NOISE);
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `mvn --batch-mode test -pl ras-drools -Dtest=DroolsGanglionTest`
Expected: FAIL — `DroolsGanglion` and `ResultCollectorChannel` do not exist

- [ ] **Step 4: Implement ResultCollectorChannel**

`ras-drools/src/main/java/io/casehub/ras/drools/ResultCollectorChannel.java`:
```java
package io.casehub.ras.drools;

import io.casehub.ras.api.DetectionResult;
import org.kie.api.runtime.Channel;

class ResultCollectorChannel implements Channel {

    private DetectionResult result;

    @Override
    public void send(Object object) {
        if (object instanceof DetectionResult dr) {
            result = dr;
        }
    }

    DetectionResult getResult() { return result; }
}
```

- [ ] **Step 5: Implement DroolsGanglion**

`ras-drools/src/main/java/io/casehub/ras/drools/DroolsGanglion.java`:
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
import org.kie.api.builder.Results;
import org.kie.api.conf.EventProcessingOption;
import org.kie.api.runtime.KieSession;
import org.kie.api.runtime.KieSessionConfiguration;
import org.kie.api.runtime.conf.ClockTypeOption;
import org.kie.api.runtime.rule.FactHandle;
import org.kie.api.time.SessionPseudoClock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class DroolsGanglion implements Ganglion {

    public static final String RESULT_CHANNEL = "results";

    private final DroolsGanglionConfig config;
    private final KieBase kieBase;
    private final DroolsSessionStore sessionStore;
    private final List<DroolsObjectExtractor> extractors;

    public DroolsGanglion(DroolsGanglionConfig config,
                          DroolsSessionStore sessionStore,
                          List<DroolsObjectExtractor> extractors) {
        this.config = config;
        this.sessionStore = sessionStore;
        this.extractors = List.copyOf(extractors);
        this.kieBase = buildKieBase(config);
    }

    @Override
    public String ganglionId() { return config.ganglionId(); }

    @Override
    public Set<String> handledEventTypes() { return config.handledEventTypes(); }

    @Override
    public Uni<DetectionResult> detect(CloudEvent event, SituationContext context) {
        String situationId = context.situationId();
        String tenancyId = context.tenancyId();
        boolean isNewSession = false;

        KieSession session;
        if (config.sessionMode() == SessionMode.LONG_LIVED) {
            session = sessionStore.get(config.ganglionId(), situationId, tenancyId)
                    .orElse(null);
            if (session == null) {
                session = createSession();
                isNewSession = true;
            }
        } else {
            session = createSession();
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
            session.dispose();
            if (!isNewSession) {
                sessionStore.remove(config.ganglionId(), situationId, tenancyId);
            }
            throw ex;
        }

        DetectionResult result = collector.getResult();
        if (result == null) {
            result = new DetectionResult(config.ganglionId(), 0.0, DetectionSignal.NOISE, Map.of());
        }

        session.unregisterChannel(RESULT_CHANNEL);
        if (config.sessionMode() == SessionMode.LONG_LIVED) {
            sessionStore.put(config.ganglionId(), situationId, tenancyId, session);
        } else {
            session.dispose();
        }

        return Uni.createFrom().item(result);
    }

    @Override
    public Uni<Void> close(String situationId, String tenancyId) {
        sessionStore.remove(config.ganglionId(), situationId, tenancyId);
        return Uni.createFrom().voidItem();
    }

    private void advanceClock(KieSession session, CloudEvent event) {
        if (config.clockMode() != ClockMode.PSEUDO) {
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
                    "Out-of-order event for ganglion '" + config.ganglionId()
                    + "': event time " + eventMs + " < clock time " + clockMs);
        }
        if (delta > 0) {
            clock.advanceTime(delta, TimeUnit.MILLISECONDS);
        }
    }

    private KieSession createSession() {
        KieSessionConfiguration ksc = KieServices.Factory.get()
                .newKieSessionConfiguration();
        if (config.clockMode() == ClockMode.PSEUDO) {
            ksc.setOption(ClockTypeOption.PSEUDO);
        }
        return kieBase.newKieSession(ksc, null);
    }

    private KieBase buildKieBase(DroolsGanglionConfig config) {
        KieServices ks = KieServices.Factory.get();
        KieFileSystem kfs = ks.newKieFileSystem();
        for (String path : config.classpathRules()) {
            kfs.write(ks.getResources().newClassPathResource(path));
        }
        for (int i = 0; i < config.programmaticRules().size(); i++) {
            kfs.write("src/main/resources/programmatic-" + i + ".drl",
                       config.programmaticRules().get(i));
        }
        KieBuilder kb = ks.newKieBuilder(kfs)
                .buildAll(ExecutableModelProject.class);
        Results results = kb.getResults();
        if (results.hasMessages(Message.Level.ERROR)) {
            throw new IllegalStateException(
                    "DRL compilation failed for ganglion '" + config.ganglionId()
                    + "': " + results.getMessages());
        }
        KieModule module = kb.getKieModule();
        var kbc = ks.newKieBaseConfiguration();
        kbc.setOption(EventProcessingOption.STREAM);
        return ks.newKieContainer(module.getReleaseId()).newKieBase(kbc);
    }
}
```

- [ ] **Step 6: Run tests and verify they pass**

Run: `mvn --batch-mode test -pl ras-drools`
Expected: ALL PASS

- [ ] **Step 7: Run full build from repo root**

Run: `mvn --batch-mode install`
Expected: BUILD SUCCESS — all modules compile and test

- [ ] **Step 8: Commit**

```
feat(casehub-ras#4): DroolsGanglion — CEP detection via classic kie-api

ResultCollectorChannel (package-private), DroolsGanglion implementing
Ganglion SPI. KieBase built from classpath + programmatic DRL via
ExecutableModelProject. detect() flow: session get/create, channel
register, clock advance with ordering guard, CloudEvent insert,
extractor facts, fireAllRules, CloudEvent retract, collect result,
channel unregister, session store/dispose. Error handling disposes
corrupted sessions. close() delegates to sessionStore.remove().
```

---

### Task 5: CLAUDE.md update + build verification

Update CLAUDE.md to reflect the `close()` SPI addition and verify the full build is green.

**Files:**
- Modify: `CLAUDE.md`

**Interfaces:**
- Consumes: all prior tasks

- [ ] **Step 1: Update CLAUDE.md — add close() to Ganglion SPI section**

In the `### Ganglion — detection strategy` section of CLAUDE.md, add after `compact`:

```java
default Uni<Void> close(String situationId, String tenancyId) { ... }
```

- [ ] **Step 2: Run full build**

Run: `mvn --batch-mode install`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```
docs(casehub-ras#4): update CLAUDE.md — Ganglion.close() SPI addition
```

---
