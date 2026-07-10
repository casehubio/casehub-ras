# DroolsSessionStore Production Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> subagent-driven-development (recommended) or executing-plans to
> implement this plan task-by-task. Each task follows TDD
> (test-driven-development) and uses ide-tooling for structural
> editing. Steps use checkbox (`- [ ]`) syntax for tracking.

**Focal issue:** #28 — DroolsSessionStore: production hardening (monitoring, graceful degradation)
**Issue group:** #28

**Goal:** Add observability (metrics, health check) and fail-fast error handling to
`ReliableDroolsSessionStore`, plus per-ganglion error isolation in `SituationEvaluator`.

**Architecture:** Inline instrumentation in `ReliableDroolsSessionStore` using Micrometer
counters/gauge/timer with optional injection (`Instance<MeterRegistry>`). A new
`DroolsSessionStoreException` in the SPI layer for typed error propagation.
`ReliableDroolsSessionStoreHealthCheck` as a readiness probe. Per-ganglion try-catch in
`SituationEvaluator.runDetection()` so one ganglion failure doesn't kill the entire evaluation.

**Tech Stack:** Micrometer (`micrometer-core`), MicroProfile Health (`microprofile-health-api`),
Quarkus CDI (`Instance<T>`), JUnit 5, AssertJ

## Global Constraints

- Library JARs depend on annotation-only libraries (`micrometer-core`, `microprofile-health-api`),
  not Quarkus extensions — per protocol PP-20260604-88f660
- `ReliableKieSession.dispose()` removes persisted data — never call in `@PreDestroy`
  (GE-20260706-d02c71)
- `H2MVStoreStorageManager` is a static singleton — never call `close()` in `@PreDestroy`
  (breaks dev-mode restarts)
- `Instance<MeterRegistry>.isResolvable()` guards all metric calls — store works without
  Micrometer on classpath

---

### Task 1: DroolsSessionStoreException and SPI error contract

**Files:**
- Create: `ras-drools/src/main/java/io/casehub/ras/drools/DroolsSessionStoreException.java`
- Test: `ras-drools/src/test/java/io/casehub/ras/drools/DroolsSessionStoreExceptionTest.java`

**Interfaces:**
- Consumes: nothing (new SPI type)
- Produces: `DroolsSessionStoreException extends RuntimeException` — used by Tasks 2, 3, 4

- [ ] **Step 1: Write failing test for exception construction**

```java
package io.casehub.ras.drools;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DroolsSessionStoreExceptionTest {

    @Test
    void constructsWithMessage() {
        var ex = new DroolsSessionStoreException("storage read failed");
        assertThat(ex).hasMessage("storage read failed");
        assertThat(ex).isInstanceOf(RuntimeException.class);
    }

    @Test
    void constructsWithMessageAndCause() {
        var cause = new RuntimeException("H2MVStore I/O error");
        var ex = new DroolsSessionStoreException("storage read failed", cause);
        assertThat(ex).hasMessage("storage read failed");
        assertThat(ex).hasCause(cause);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn --batch-mode test -pl ras-drools -Dtest=DroolsSessionStoreExceptionTest`
Expected: FAIL — `DroolsSessionStoreException` does not exist

- [ ] **Step 3: Implement DroolsSessionStoreException**

```java
package io.casehub.ras.drools;

public class DroolsSessionStoreException extends RuntimeException {

    public DroolsSessionStoreException(String message) {
        super(message);
    }

    public DroolsSessionStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn --batch-mode test -pl ras-drools -Dtest=DroolsSessionStoreExceptionTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add ras-drools/src/main/java/io/casehub/ras/drools/DroolsSessionStoreException.java ras-drools/src/test/java/io/casehub/ras/drools/DroolsSessionStoreExceptionTest.java
git commit -m "feat(casehub-ras#28): add DroolsSessionStoreException — typed SPI error contract"
```

---

### Task 2: ReliableDroolsSessionStore — error handling and lifecycle hardening

**Files:**
- Modify: `drools-reliability/src/main/java/io/casehub/ras/drools/reliability/ReliableDroolsSessionStore.java`
- Modify: `drools-reliability/src/test/java/io/casehub/ras/drools/reliability/ReliableDroolsSessionStoreTest.java`

**Interfaces:**
- Consumes: `DroolsSessionStoreException` from Task 1
- Produces: Updated `ReliableDroolsSessionStore` with error handling, lifecycle logging,
  and `int activeSessionCount()` (package-private) — used by Task 3 (health check) and Task 4 (metrics)

This task covers three changes: (a) wrap storage reads in try-catch that throws
`DroolsSessionStoreException`, (b) wrap storage writes in try-catch that logs and continues,
(c) fix `@PreDestroy` to NOT call `dispose()` or `StorageManager.close()`, and (d) add
`activeSessionCount()`.

- [ ] **Step 1: Write failing test — storage read failure throws DroolsSessionStoreException**

Add to `ReliableDroolsSessionStoreTest.java`:

```java
@Test
void storageReadFailureThrowsDroolsSessionStoreException() {
    var k = key("g1", "sit-1");

    // Corrupt storage to cause read failure: close the underlying MVStore
    var sm = StorageManagerFactory.get().getStorageManager();
    sm.close();

    assertThatThrownBy(() -> store.computeIfAbsent(k, kieBase, config, 0))
            .isInstanceOf(DroolsSessionStoreException.class)
            .hasMessageContaining("storage read failed");
}
```

Add import: `import io.casehub.ras.drools.DroolsSessionStoreException;`

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn --batch-mode test -pl drools-reliability -Dtest=ReliableDroolsSessionStoreTest#storageReadFailureThrowsDroolsSessionStoreException`
Expected: FAIL — untyped exception propagates instead of `DroolsSessionStoreException`

- [ ] **Step 3: Implement storage read error handling in computeIfAbsent**

In `ReliableDroolsSessionStore.computeIfAbsent()`, wrap the `sessionIds.get(storageKey)` call:

```java
Long savedId;
try {
    savedId = sessionIds.get(storageKey);
} catch (RuntimeException ex) {
    throw new DroolsSessionStoreException(
            "storage read failed for key '" + storageKey + "'", ex);
}
```

Add import: `import io.casehub.ras.drools.DroolsSessionStoreException;`

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn --batch-mode test -pl drools-reliability -Dtest=ReliableDroolsSessionStoreTest#storageReadFailureThrowsDroolsSessionStoreException`
Expected: PASS

- [ ] **Step 5: Write failing test — storage write failure logs but does not throw**

Add to `ReliableDroolsSessionStoreTest.java`:

```java
@Test
void storageWriteFailureDoesNotThrow() {
    var k = key("g1", "sit-1");

    // First call succeeds — creates session and writes to storage
    KieSession session = store.computeIfAbsent(k, kieBase, config, 0);
    assertThat(session).isNotNull();

    // Corrupt storage for writes, then force a generation bump (triggers new write)
    var sm = StorageManagerFactory.get().getStorageManager();
    sm.close();

    // Generation bump forces eviction + new session + write attempt
    // Should not throw — write failure is degraded, not fatal
    assertThatNoException().isThrownBy(() ->
            store.computeIfAbsent(key("g1", "sit-2"), kieBase, config, 0));
}
```

Note: This test verifies that when storage writes fail after session creation, the session
is still returned. The write failure is logged but not thrown.

- [ ] **Step 6: Implement storage write error handling**

Wrap the two storage writes (`sessionIds.put`, `sessionGenerations.put`) in a single try-catch:

```java
try {
    sessionIds.put(storageKey, session.getIdentifier());
    sessionGenerations.put(storageKey, generation);
} catch (RuntimeException ex) {
    log.error("Storage write failed for key '{}' — session usable but not durable", storageKey, ex);
}
```

- [ ] **Step 7: Run storage write test**

Run: `mvn --batch-mode test -pl drools-reliability -Dtest=ReliableDroolsSessionStoreTest#storageWriteFailureDoesNotThrow`
Expected: PASS

- [ ] **Step 8: Write failing test — destroy does NOT call dispose (sessions survive restart)**

This tests the primary value proposition: sessions survive graceful restarts.

```java
@Test
void gracefulRestartPreservesSessions() {
    var k = key("g1", "sit-1");
    KieSession session = store.computeIfAbsent(k, kieBase, config, 0);
    session.insert("survive-restart");
    session.fireAllRules();

    // Graceful shutdown — calls destroy() which should NOT dispose sessions
    store.destroy();

    // Re-initialize (simulates new CDI lifecycle)
    store = new ReliableDroolsSessionStore();
    store.init();

    // Session should recover with data intact
    KieSession recovered = store.computeIfAbsent(k, kieBase, config, 0);
    assertThat(recovered).isNotSameAs(session);
    assertThat(recovered.getObjects()).anyMatch(o -> "survive-restart".equals(o));
}
```

- [ ] **Step 9: Fix @PreDestroy — remove dispose() calls, add logging**

Replace the current `destroy()` method:

```java
@PreDestroy
void destroy() {
    int count = hotCache.size();
    hotCache.clear();
    log.info("DroolsSessionStore shutdown: {} sessions released from hot cache (persisted data preserved)", count);
}
```

This removes the `dispose()` calls (which delete persisted data) and the explicit session
disposal loop. Sessions survive for recovery on next startup.

- [ ] **Step 10: Run graceful restart test**

Run: `mvn --batch-mode test -pl drools-reliability -Dtest=ReliableDroolsSessionStoreTest#gracefulRestartPreservesSessions`
Expected: PASS

- [ ] **Step 11: Add init() logging and activeSessionCount()**

In `init()`, add after the storage setup:

```java
log.info("DroolsSessionStore initialized: h2mvstore, {} persisted session(s)", sessionIds.size());
```

Add the package-private method:

```java
int activeSessionCount() {
    return hotCache.size();
}
```

- [ ] **Step 12: Run all existing tests to check for regressions**

Run: `mvn --batch-mode test -pl drools-reliability`
Expected: ALL PASS. The `restartSurvival` test should still pass since it uses
`clearHotCacheForTest()` (not `destroy()`) for crash simulation.

- [ ] **Step 13: Commit**

```bash
git add drools-reliability/src/main/java/io/casehub/ras/drools/reliability/ReliableDroolsSessionStore.java drools-reliability/src/test/java/io/casehub/ras/drools/reliability/ReliableDroolsSessionStoreTest.java
git commit -m "feat(casehub-ras#28): error handling and lifecycle hardening for ReliableDroolsSessionStore"
```

---

### Task 3: Health check

**Files:**
- Modify: `drools-reliability/pom.xml` (add `microprofile-health-api`)
- Create: `drools-reliability/src/main/java/io/casehub/ras/drools/reliability/ReliableDroolsSessionStoreHealthCheck.java`
- Test: `drools-reliability/src/test/java/io/casehub/ras/drools/reliability/ReliableDroolsSessionStoreHealthCheckTest.java`

**Interfaces:**
- Consumes: `ReliableDroolsSessionStore.activeSessionCount()` from Task 2
- Produces: `ReliableDroolsSessionStoreHealthCheck` — readiness probe. No downstream tasks depend on it.

- [ ] **Step 1: Add microprofile-health-api dependency to drools-reliability/pom.xml**

Add to `drools-reliability/pom.xml` `<dependencies>` section (after `drools-reliability-h2mvstore`):

```xml
<dependency>
    <groupId>org.eclipse.microprofile.health</groupId>
    <artifactId>microprofile-health-api</artifactId>
    <scope>provided</scope>
</dependency>
```

Version is managed by the casehub-parent BOM (via Quarkus BOM).

- [ ] **Step 2: Write failing test for health check UP**

```java
package io.casehub.ras.drools.reliability;

import org.drools.model.codegen.ExecutableModelProject;
import org.drools.reliability.core.StorageManagerFactory;
import org.drools.reliability.core.TestableStorageManager;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kie.api.KieBase;
import org.kie.api.KieServices;
import org.kie.api.conf.EventProcessingOption;
import org.kie.api.runtime.KieSessionConfiguration;
import org.kie.api.runtime.conf.ClockTypeOption;
import io.casehub.ras.drools.DroolsSessionKey;
import static org.assertj.core.api.Assertions.*;

class ReliableDroolsSessionStoreHealthCheckTest {

    private ReliableDroolsSessionStore store;
    private ReliableDroolsSessionStoreHealthCheck healthCheck;
    private KieBase kieBase;
    private KieSessionConfiguration config;

    @BeforeEach
    void setUp() {
        StorageManagerFactory.get("h2mvstore").getStorageManager().removeAllSessionStorages();
        store = new ReliableDroolsSessionStore();
        store.init();
        healthCheck = new ReliableDroolsSessionStoreHealthCheck(store);
        KieServices ks = KieServices.Factory.get();
        var kfs = ks.newKieFileSystem();
        var kb = ks.newKieBuilder(kfs).buildAll(ExecutableModelProject.class);
        var kbc = ks.newKieBaseConfiguration();
        kbc.setOption(EventProcessingOption.STREAM);
        kieBase = ks.newKieContainer(kb.getKieModule().getReleaseId()).newKieBase(kbc);
        config = ks.newKieSessionConfiguration();
        config.setOption(ClockTypeOption.PSEUDO);
    }

    @AfterEach
    void tearDown() {
        if (store != null) store.destroy();
        ((TestableStorageManager) StorageManagerFactory.get().getStorageManager()).restartWithCleanUp();
    }

    @Test
    void reportsUpWhenHealthy() {
        var response = healthCheck.call();
        assertThat(response.getStatus()).isEqualTo(HealthCheckResponse.Status.UP);
        assertThat(response.getData().get()).containsEntry("store", "h2mvstore");
        assertThat(response.getData().get()).containsEntry("activeSessions", 0L);
    }

    @Test
    void reportsActiveSessionCount() {
        store.computeIfAbsent(
                new DroolsSessionKey("g1", "sit-1", "key-1", "tenant-a"),
                kieBase, config, 0);
        store.computeIfAbsent(
                new DroolsSessionKey("g2", "sit-2", "key-2", "tenant-a"),
                kieBase, config, 0);

        var response = healthCheck.call();
        assertThat(response.getStatus()).isEqualTo(HealthCheckResponse.Status.UP);
        assertThat(response.getData().get()).containsEntry("activeSessions", 2L);
    }

    @Test
    void reportsDownWhenStorageFails() {
        StorageManagerFactory.get().getStorageManager().close();

        var response = healthCheck.call();
        assertThat(response.getStatus()).isEqualTo(HealthCheckResponse.Status.DOWN);
        assertThat(response.getData().get()).containsKey("error");
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `mvn --batch-mode test -pl drools-reliability -Dtest=ReliableDroolsSessionStoreHealthCheckTest`
Expected: FAIL — class does not exist

- [ ] **Step 4: Implement ReliableDroolsSessionStoreHealthCheck**

```java
package io.casehub.ras.drools.reliability;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.drools.reliability.core.StorageManagerFactory;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

@Readiness
@ApplicationScoped
public class ReliableDroolsSessionStoreHealthCheck implements HealthCheck {

    private static final String PROBE_KEY = "ras_drools_health_probe";

    private final ReliableDroolsSessionStore store;

    @Inject
    public ReliableDroolsSessionStoreHealthCheck(ReliableDroolsSessionStore store) {
        this.store = store;
    }

    @Override
    public HealthCheckResponse call() {
        try {
            StorageManagerFactory.get().getStorageManager()
                    .getOrCreateSharedStorage(PROBE_KEY);
            return HealthCheckResponse.named("drools-session-store")
                    .up()
                    .withData("store", "h2mvstore")
                    .withData("activeSessions", (long) store.activeSessionCount())
                    .build();
        } catch (RuntimeException ex) {
            return HealthCheckResponse.named("drools-session-store")
                    .down()
                    .withData("store", "h2mvstore")
                    .withData("error", ex.getMessage())
                    .build();
        }
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn --batch-mode test -pl drools-reliability -Dtest=ReliableDroolsSessionStoreHealthCheckTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add drools-reliability/pom.xml drools-reliability/src/main/java/io/casehub/ras/drools/reliability/ReliableDroolsSessionStoreHealthCheck.java drools-reliability/src/test/java/io/casehub/ras/drools/reliability/ReliableDroolsSessionStoreHealthCheckTest.java
git commit -m "feat(casehub-ras#28): add readiness health check for ReliableDroolsSessionStore"
```

---

### Task 4: Inline metrics instrumentation

**Files:**
- Modify: `drools-reliability/pom.xml` (add `micrometer-core`)
- Modify: `drools-reliability/src/main/java/io/casehub/ras/drools/reliability/ReliableDroolsSessionStore.java`
- Modify: `drools-reliability/src/test/java/io/casehub/ras/drools/reliability/ReliableDroolsSessionStoreTest.java`

**Interfaces:**
- Consumes: `DroolsSessionStoreException` from Task 1, error handling from Task 2
- Produces: Metrics emitted via `MeterRegistry` — no downstream tasks depend on these

- [ ] **Step 1: Add micrometer-core dependency to drools-reliability/pom.xml**

Add to `drools-reliability/pom.xml` `<dependencies>` section:

```xml
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-core</artifactId>
    <scope>provided</scope>
</dependency>
```

Also add test dependency for a simple in-memory registry:

```xml
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-test</artifactId>
    <scope>test</scope>
</dependency>
```

Versions are managed by the casehub-parent BOM.

- [ ] **Step 2: Write failing test — session.created counter increments**

Add to `ReliableDroolsSessionStoreTest.java`:

```java
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
```

Add a test that creates a metrics-enabled store:

```java
@Test
void createdCounterIncrements() {
    var registry = new SimpleMeterRegistry();
    store = createStoreWithMetrics(registry);

    store.computeIfAbsent(key("g1", "sit-1"), kieBase, config, 0);

    assertThat(registry.counter("ras.drools.session.created", "ganglion_id", "g1").count())
            .isEqualTo(1.0);
}

private ReliableDroolsSessionStore createStoreWithMetrics(MeterRegistry registry) {
    var metricsStore = new ReliableDroolsSessionStore();
    metricsStore.setMeterRegistry(registry);
    metricsStore.init();
    return metricsStore;
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `mvn --batch-mode test -pl drools-reliability -Dtest=ReliableDroolsSessionStoreTest#createdCounterIncrements`
Expected: FAIL — no `setMeterRegistry` method, no counter instrumentation

- [ ] **Step 4: Add MeterRegistry injection and counter instrumentation**

Add to `ReliableDroolsSessionStore`:

```java
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
```

Add fields:

```java
@Inject
Instance<MeterRegistry> meterRegistryInstance;

private MeterRegistry metrics;
```

Add setter for test injection:

```java
void setMeterRegistry(MeterRegistry registry) {
    this.metrics = registry;
}
```

In `@PostConstruct init()`, after storage setup, add:

```java
if (metrics == null && meterRegistryInstance != null && meterRegistryInstance.isResolvable()) {
    metrics = meterRegistryInstance.get();
}
if (metrics != null) {
    metrics.gaugeMapSize("ras.drools.session.active", List.of(), hotCache);
}
```

Add import: `import java.util.List;`

Add helper methods:

```java
private void incrementCounter(String name, String ganglionId) {
    if (metrics != null) {
        metrics.counter(name, "ganglion_id", ganglionId).increment();
    }
}

private Timer.Sample startTimer() {
    return metrics != null ? Timer.start(metrics) : null;
}

private void stopTimer(Timer.Sample sample, String ganglionId, String outcome) {
    if (sample != null) {
        sample.stop(metrics.timer("ras.drools.session.compute_time",
                "ganglion_id", ganglionId, "outcome", outcome));
    }
}
```

In `computeIfAbsent()`:
- At the top: `Timer.Sample sample = startTimer();`
- After hot cache hit return: `stopTimer(sample, key.ganglionId(), "hit");` before `return`
- After recovery success: `incrementCounter("ras.drools.session.recovered", key.ganglionId());` and `stopTimer(sample, key.ganglionId(), "recovered");`
- After recovery failure fallthrough: `incrementCounter("ras.drools.session.recovery_failed", key.ganglionId());` and `stopTimer(sample, key.ganglionId(), "recovery_failed");`
- After generation eviction: `incrementCounter("ras.drools.session.evicted", key.ganglionId());`
- After new session creation: `incrementCounter("ras.drools.session.created", key.ganglionId());` and `stopTimer(sample, key.ganglionId(), "created");`
- In the storage write catch block: `incrementCounter("ras.drools.store.write_failed", key.ganglionId());`

In `remove()`:
- After successful removal: `incrementCounter("ras.drools.session.removed", key.ganglionId());`

- [ ] **Step 5: Run created counter test**

Run: `mvn --batch-mode test -pl drools-reliability -Dtest=ReliableDroolsSessionStoreTest#createdCounterIncrements`
Expected: PASS

- [ ] **Step 6: Write remaining metric tests**

Add to `ReliableDroolsSessionStoreTest.java`:

```java
@Test
void hotCacheHitRecordsTimerWithHitOutcome() {
    var registry = new SimpleMeterRegistry();
    store = createStoreWithMetrics(registry);
    var k = key("g1", "sit-1");

    store.computeIfAbsent(k, kieBase, config, 0);  // creates
    store.computeIfAbsent(k, kieBase, config, 0);  // cache hit

    assertThat(registry.timer("ras.drools.session.compute_time",
            "ganglion_id", "g1", "outcome", "hit").count()).isEqualTo(1);
}

@Test
void recoveryIncrementRecoveredCounter() {
    var registry = new SimpleMeterRegistry();
    store = createStoreWithMetrics(registry);
    var k = key("g1", "sit-1");

    store.computeIfAbsent(k, kieBase, config, 0);

    // Simulate restart: clear hot cache, refresh counter
    store.clearHotCacheForTest();
    ((TestableStorageManager) StorageManagerFactory.get().getStorageManager()).restart();
    ReliableRuntimeComponentFactoryImpl.refreshCounterUsingStorage();
    store = createStoreWithMetrics(registry);

    store.computeIfAbsent(k, kieBase, config, 0);

    assertThat(registry.counter("ras.drools.session.recovered", "ganglion_id", "g1").count())
            .isEqualTo(1.0);
}

@Test
void evictionIncrementsEvictedCounter() {
    var registry = new SimpleMeterRegistry();
    store = createStoreWithMetrics(registry);
    var k = key("g1", "sit-1");

    store.computeIfAbsent(k, kieBase, config, 0);
    store.computeIfAbsent(k, kieBase, config, 1); // generation bump

    assertThat(registry.counter("ras.drools.session.evicted", "ganglion_id", "g1").count())
            .isEqualTo(1.0);
}

@Test
void removeIncrementsRemovedCounter() {
    var registry = new SimpleMeterRegistry();
    store = createStoreWithMetrics(registry);
    var k = key("g1", "sit-1");

    store.computeIfAbsent(k, kieBase, config, 0);
    store.remove(k);

    assertThat(registry.counter("ras.drools.session.removed", "ganglion_id", "g1").count())
            .isEqualTo(1.0);
}

@Test
void activeSessionGaugeReflectsHotCacheSize() {
    var registry = new SimpleMeterRegistry();
    store = createStoreWithMetrics(registry);

    assertThat(registry.get("ras.drools.session.active").gauge().value()).isEqualTo(0.0);

    store.computeIfAbsent(key("g1", "sit-1"), kieBase, config, 0);
    assertThat(registry.get("ras.drools.session.active").gauge().value()).isEqualTo(1.0);

    store.computeIfAbsent(key("g2", "sit-2"), kieBase, config, 0);
    assertThat(registry.get("ras.drools.session.active").gauge().value()).isEqualTo(2.0);
}

@Test
void worksWithoutMetrics() {
    // Default store (no MeterRegistry set) — should work without error
    var k = key("g1", "sit-1");
    assertThatNoException().isThrownBy(() -> store.computeIfAbsent(k, kieBase, config, 0));
    assertThatNoException().isThrownBy(() -> store.remove(k));
}
```

- [ ] **Step 7: Run all metric tests**

Run: `mvn --batch-mode test -pl drools-reliability`
Expected: ALL PASS

- [ ] **Step 8: Commit**

```bash
git add drools-reliability/pom.xml drools-reliability/src/main/java/io/casehub/ras/drools/reliability/ReliableDroolsSessionStore.java drools-reliability/src/test/java/io/casehub/ras/drools/reliability/ReliableDroolsSessionStoreTest.java
git commit -m "feat(casehub-ras#28): add Micrometer metrics instrumentation to ReliableDroolsSessionStore"
```

---

### Task 5: DroolsGanglion error handling and SituationEvaluator ganglion isolation

**Files:**
- Modify: `ras-drools/src/main/java/io/casehub/ras/drools/DroolsGanglion.java`
- Modify: `runtime/src/main/java/io/casehub/ras/runtime/SituationEvaluator.java`
- Modify: `ras-drools/src/test/java/io/casehub/ras/drools/DroolsGanglionTest.java`
- Modify: `runtime/src/test/java/io/casehub/ras/runtime/SituationEvaluatorTest.java`

**Interfaces:**
- Consumes: `DroolsSessionStoreException` from Task 1
- Produces: Error isolation in evaluator. No downstream tasks depend on this.

- [ ] **Step 1: Write failing test — DroolsGanglion wraps store exception with defensive cleanup**

Add to `DroolsGanglionTest.java`:

```java
@Test
void storeExceptionCausesDefensiveCleanupAndRethrow() {
    var failingStore = new DroolsSessionStore() {
        private boolean firstCall = true;
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

    var config = new DroolsGanglionConfig("g1", Set.of("test.event"),
            SessionMode.LONG_LIVED, ClockMode.PSEUDO,
            List.of(), List.of(MINIMAL_DRL));
    var ganglion = new DroolsGanglion(config, failingStore, List.of());

    var context = SituationContext.initial("sit-1", "key-1", "tenant-a",
            Instant.parse("2026-07-09T10:00:00Z"));

    assertThatThrownBy(() -> ganglion.detect(testEvent("test.event"), context)
            .await().indefinitely())
            .isInstanceOf(DroolsSessionStoreException.class)
            .hasMessageContaining("storage read failed")
            .satisfies(ex -> assertThat(ex.getSuppressed()).hasSize(1));
}
```

Add import: `import io.casehub.ras.drools.DroolsSessionStoreException;`
Add import: `import io.casehub.ras.api.SituationContext;`
Add import: `import java.time.Instant;`

The test requires a `MINIMAL_DRL` constant — a simple DRL string that compiles:

```java
private static final String MINIMAL_DRL =
        "package test; rule \"noop\" when then end";
```

And a `testEvent` helper:

```java
private CloudEvent testEvent(String type) {
    return CloudEventBuilder.v1()
            .withId("evt-1")
            .withSource(URI.create("/test"))
            .withType(type)
            .withTime(OffsetDateTime.ofInstant(
                    Instant.parse("2026-07-09T10:00:00Z"), ZoneOffset.UTC))
            .build();
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn --batch-mode test -pl ras-drools -Dtest=DroolsGanglionTest#storeExceptionCausesDefensiveCleanupAndRethrow`
Expected: FAIL — `DroolsSessionStoreException` is not caught, no defensive cleanup

- [ ] **Step 3: Add try-catch around computeIfAbsent in DroolsGanglion.detect()**

In `DroolsGanglion.detect()`, wrap the `sessionStore.computeIfAbsent` call (line 71-72):

```java
KieSession session;
if (sessionMode == SessionMode.LONG_LIVED) {
    var key = new DroolsSessionKey(ganglionId, situationId, correlationKey, tenancyId);
    try {
        session = sessionStore.computeIfAbsent(key, currentBase, buildSessionConfig(), currentGen);
    } catch (DroolsSessionStoreException ex) {
        try {
            sessionStore.remove(key);
        } catch (RuntimeException suppressed) {
            ex.addSuppressed(suppressed);
        }
        throw ex;
    }
} else {
    session = createSession(currentBase);
}
```

Add import: `import io.casehub.ras.drools.DroolsSessionStoreException;`

Note: `DroolsSessionStoreException` is in the same module (`ras-drools`), so no new dependency needed.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn --batch-mode test -pl ras-drools -Dtest=DroolsGanglionTest#storeExceptionCausesDefensiveCleanupAndRethrow`
Expected: PASS

- [ ] **Step 5: Write failing test — SituationEvaluator continues when one ganglion fails**

Add to `SituationEvaluatorTest.java`:

```java
@Test
void evaluatorContinuesWhenOneGanglionFails() {
    var failingGanglion = new Ganglion() {
        @Override public String ganglionId() { return "g-fail"; }
        @Override public Set<String> handledEventTypes() { return Set.of("test.event"); }
        @Override public Uni<DetectionResult> detect(CloudEvent event, SituationContext context) {
            return Uni.createFrom().failure(new RuntimeException("ganglion failed"));
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

    // Working ganglion's result should still trigger
    assertThat(caseTrigger.firedCases()).hasSize(1);
}
```

- [ ] **Step 6: Run test to verify it fails**

Run: `mvn --batch-mode test -pl runtime -Dtest=SituationEvaluatorTest#evaluatorContinuesWhenOneGanglionFails`
Expected: FAIL — exception from failing ganglion propagates, kills evaluation

- [ ] **Step 7: Add per-ganglion try-catch in SituationEvaluator.runDetection()**

Modify `runDetection()` in `SituationEvaluator.java`:

```java
private List<DetectionResult> runDetection(CloudEvent event,
                                            SituationDefinition definition,
                                            SituationContext context) {
    Set<String> gangliaForEvent = gangliaHandlingEventType(definition, event.getType());
    List<DetectionResult> results = new ArrayList<>();
    for (String ganglionId : gangliaForEvent) {
        try {
            Ganglion ganglion = registry.ganglion(ganglionId);
            DetectionResult result = ganglion.detect(event, context).await().indefinitely();
            results.add(result);
        } catch (RuntimeException ex) {
            LOG.warning("Ganglion '" + ganglionId + "' detect() failed, skipping: " + ex.getMessage());
        }
    }
    return results;
}
```

- [ ] **Step 8: Run test to verify it passes**

Run: `mvn --batch-mode test -pl runtime -Dtest=SituationEvaluatorTest#evaluatorContinuesWhenOneGanglionFails`
Expected: PASS

- [ ] **Step 9: Run full test suite for both modules**

Run: `mvn --batch-mode test -pl ras-drools,runtime`
Expected: ALL PASS

- [ ] **Step 10: Commit**

```bash
git add ras-drools/src/main/java/io/casehub/ras/drools/DroolsGanglion.java ras-drools/src/test/java/io/casehub/ras/drools/DroolsGanglionTest.java runtime/src/main/java/io/casehub/ras/runtime/SituationEvaluator.java runtime/src/test/java/io/casehub/ras/runtime/SituationEvaluatorTest.java
git commit -m "feat(casehub-ras#28): add DroolsGanglion error handling and per-ganglion isolation in SituationEvaluator"
```

---

### Task 6: Full build verification and CLAUDE.md update

**Files:**
- Modify: `CLAUDE.md` (no new content — design spec already added)

**Interfaces:**
- Consumes: all previous tasks
- Produces: verified green build

- [ ] **Step 1: Run full project build**

Run: `mvn --batch-mode install`
Expected: BUILD SUCCESS — all modules compile and all tests pass

- [ ] **Step 2: Commit any remaining changes**

If CLAUDE.md or other docs needed updating:

```bash
git add -A
git commit -m "docs(casehub-ras#28): final verification — full build green"
```
