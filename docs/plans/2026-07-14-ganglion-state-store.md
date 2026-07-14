# GanglionStateStore Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> subagent-driven-development (recommended) or executing-plans to
> implement this plan task-by-task. Each task follows TDD
> (test-driven-development) and uses ide-tooling for structural
> editing. Steps use checkbox (`- [ ]`) syntax for tracking.

**Focal issue:** #36 — NaiveBayesGanglion: persist log-posteriors across restarts
**Issue group:** #36

**Goal:** Create a pluggable `GanglionStateStore` SPI for ganglion computation state
persistence, with in-memory and JPA implementations, and refactor NaiveBayesGanglion
to use it instead of its internal ConcurrentHashMap.

**Architecture:** New SPI in `api/` (`GanglionStateStore`, `GanglionStateKey`,
`GanglionState`, `GanglionStateConflictException`). In-memory `@DefaultBean` impl
in `runtime/`. JPA `@ApplicationScoped` impl in `persistence-jpa/` with entity, Flyway
migration, and module-local metrics. NaiveBayesGanglion refactored to accept the store
as a constructor parameter, with optimistic-locking retry in `detect()`.

**Tech Stack:** Java 21, Quarkus CDI, Mutiny, Hibernate ORM, Flyway, JUnit 5, AssertJ,
Micrometer

## Global Constraints

- All new types in `api/` must be pure Java + standard CDI — no Quarkus-specific annotations
- `@DefaultBean` requires `quarkus-arc` — only in `runtime/`
- `persistence-jpa/` must NOT depend on `runtime/` — metrics are module-local
- Flyway migration version V5 in `classpath:db/ras/migration`
- Jackson `@JsonTypeInfo` annotations follow existing sealed type conventions
- Breaking changes to NaiveBayesGanglion constructor are acceptable (pre-release)

---

### Task 1: SPI Types in api/

**Files:**
- Create: `api/src/main/java/io/casehub/ras/api/GanglionStateKey.java`
- Create: `api/src/main/java/io/casehub/ras/api/GanglionState.java`
- Create: `api/src/main/java/io/casehub/ras/api/GanglionStateConflictException.java`
- Create: `api/src/main/java/io/casehub/ras/api/GanglionStateStore.java`
- Create: `api/src/test/java/io/casehub/ras/api/AbstractGanglionStateStoreContractTest.java`

**Interfaces:**
- Produces: `GanglionStateKey(String ganglionId, String situationId, String correlationKey, String tenancyId)` — record
- Produces: `GanglionState(double[] values, OptionalLong storeVersion)` — record
- Produces: `GanglionStateConflictException(String message, Throwable cause)` — extends RuntimeException
- Produces: `GanglionStateStore` — interface with `load`, `save`, `remove`, `removeForSituation`, `removeOrphaned`
- Produces: `AbstractGanglionStateStoreContractTest` — in test-jar

- [ ] **Step 1: Create GanglionStateKey record**

```java
package io.casehub.ras.api;

public record GanglionStateKey(
    String ganglionId,
    String situationId,
    String correlationKey,
    String tenancyId
) {}
```

- [ ] **Step 2: Create GanglionState record**

```java
package io.casehub.ras.api;

import java.util.OptionalLong;

public record GanglionState(double[] values, OptionalLong storeVersion) {}
```

- [ ] **Step 3: Create GanglionStateConflictException**

```java
package io.casehub.ras.api;

public class GanglionStateConflictException extends RuntimeException {
    public GanglionStateConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

- [ ] **Step 4: Create GanglionStateStore interface**

```java
package io.casehub.ras.api;

import io.smallrye.mutiny.Uni;
import java.util.Optional;

public interface GanglionStateStore {
    Uni<Optional<GanglionState>> load(GanglionStateKey key);
    Uni<Void> save(GanglionStateKey key, GanglionState state);
    Uni<Void> remove(GanglionStateKey key);
    Uni<Void> removeForSituation(String situationId);
    default Uni<Integer> removeOrphaned() {
        return Uni.createFrom().item(0);
    }
}
```

- [ ] **Step 5: Write AbstractGanglionStateStoreContractTest**

```java
package io.casehub.ras.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.OptionalLong;
import static org.assertj.core.api.Assertions.*;

public abstract class AbstractGanglionStateStoreContractTest {

    protected GanglionStateStore store;

    protected abstract GanglionStateStore createStore();

    @BeforeEach
    void setUpStore() {
        store = createStore();
    }

    @Test
    void loadAbsentKeyReturnsEmpty() {
        var key = new GanglionStateKey("g1", "sit-1", "key-1", "tenant-a");
        var result = store.load(key).await().indefinitely();
        assertThat(result).isEmpty();
    }

    @Test
    void saveAndLoadRoundTrip() {
        var key = new GanglionStateKey("g1", "sit-1", "key-1", "tenant-a");
        var state = new GanglionState(new double[]{-0.105, -2.303}, OptionalLong.empty());
        store.save(key, state).await().indefinitely();

        var loaded = store.load(key).await().indefinitely();
        assertThat(loaded).isPresent();
        assertThat(loaded.get().values()).containsExactly(-0.105, -2.303);
    }

    @Test
    void saveOverwritesPreviousValue() {
        var key = new GanglionStateKey("g1", "sit-1", "key-1", "tenant-a");
        store.save(key, new GanglionState(new double[]{1.0}, OptionalLong.empty()))
                .await().indefinitely();

        var loaded1 = store.load(key).await().indefinitely().orElseThrow();
        store.save(key, new GanglionState(new double[]{2.0}, loaded1.storeVersion()))
                .await().indefinitely();

        var loaded2 = store.load(key).await().indefinitely();
        assertThat(loaded2).isPresent();
        assertThat(loaded2.get().values()).containsExactly(2.0);
    }

    @Test
    void removeThenLoadReturnsEmpty() {
        var key = new GanglionStateKey("g1", "sit-1", "key-1", "tenant-a");
        store.save(key, new GanglionState(new double[]{1.0}, OptionalLong.empty()))
                .await().indefinitely();
        store.remove(key).await().indefinitely();

        assertThat(store.load(key).await().indefinitely()).isEmpty();
    }

    @Test
    void removeNonExistentIsNoOp() {
        var key = new GanglionStateKey("g1", "nonexistent", "k", "t");
        assertThatNoException().isThrownBy(
                () -> store.remove(key).await().indefinitely());
    }

    @Test
    void removeForSituationRemovesOnlyMatchingSituationId() {
        var keyA = new GanglionStateKey("g1", "sit-A", "key-1", "tenant-a");
        var keyB = new GanglionStateKey("g2", "sit-A", "key-2", "tenant-a");
        var keyC = new GanglionStateKey("g1", "sit-B", "key-1", "tenant-a");

        store.save(keyA, new GanglionState(new double[]{1.0}, OptionalLong.empty()))
                .await().indefinitely();
        store.save(keyB, new GanglionState(new double[]{2.0}, OptionalLong.empty()))
                .await().indefinitely();
        store.save(keyC, new GanglionState(new double[]{3.0}, OptionalLong.empty()))
                .await().indefinitely();

        store.removeForSituation("sit-A").await().indefinitely();

        assertThat(store.load(keyA).await().indefinitely()).isEmpty();
        assertThat(store.load(keyB).await().indefinitely()).isEmpty();
        assertThat(store.load(keyC).await().indefinitely()).isPresent();
    }

    @Test
    void removeForSituationNoopWhenNoMatches() {
        assertThatNoException().isThrownBy(
                () -> store.removeForSituation("nonexistent").await().indefinitely());
    }

    @Test
    void defensiveCopyOnSave() {
        var key = new GanglionStateKey("g1", "sit-1", "key-1", "tenant-a");
        double[] original = {1.0, 2.0};
        store.save(key, new GanglionState(original, OptionalLong.empty()))
                .await().indefinitely();

        original[0] = 999.0;

        var loaded = store.load(key).await().indefinitely().orElseThrow();
        assertThat(loaded.values()[0]).isEqualTo(1.0);
    }

    @Test
    void defensiveCopyOnLoad() {
        var key = new GanglionStateKey("g1", "sit-1", "key-1", "tenant-a");
        store.save(key, new GanglionState(new double[]{1.0, 2.0}, OptionalLong.empty()))
                .await().indefinitely();

        var loaded = store.load(key).await().indefinitely().orElseThrow();
        loaded.values()[0] = 999.0;

        var reloaded = store.load(key).await().indefinitely().orElseThrow();
        assertThat(reloaded.values()[0]).isEqualTo(1.0);
    }

    @Test
    void isolationByGanglionId() {
        var key1 = new GanglionStateKey("g1", "sit-1", "key-1", "tenant-a");
        var key2 = new GanglionStateKey("g2", "sit-1", "key-1", "tenant-a");

        store.save(key1, new GanglionState(new double[]{1.0}, OptionalLong.empty()))
                .await().indefinitely();
        store.save(key2, new GanglionState(new double[]{2.0}, OptionalLong.empty()))
                .await().indefinitely();

        assertThat(store.load(key1).await().indefinitely().orElseThrow().values())
                .containsExactly(1.0);
        assertThat(store.load(key2).await().indefinitely().orElseThrow().values())
                .containsExactly(2.0);
    }

    @Test
    void isolationByTenancyId() {
        var key1 = new GanglionStateKey("g1", "sit-1", "key-1", "tenant-a");
        var key2 = new GanglionStateKey("g1", "sit-1", "key-1", "tenant-b");

        store.save(key1, new GanglionState(new double[]{1.0}, OptionalLong.empty()))
                .await().indefinitely();
        store.save(key2, new GanglionState(new double[]{2.0}, OptionalLong.empty()))
                .await().indefinitely();

        assertThat(store.load(key1).await().indefinitely().orElseThrow().values())
                .containsExactly(1.0);
        assertThat(store.load(key2).await().indefinitely().orElseThrow().values())
                .containsExactly(2.0);
    }
}
```

- [ ] **Step 6: Build api/ module and verify tests pass**

Run: `mvn --batch-mode -pl api -am install`
Expected: BUILD SUCCESS

- [ ] **Step 7: Commit**

```
feat(casehub-ras#36): GanglionStateStore SPI — types, interface, contract test
```

---

### Task 2: InMemoryGanglionStateStore in runtime/

**Files:**
- Create: `runtime/src/main/java/io/casehub/ras/runtime/InMemoryGanglionStateStore.java`
- Create: `runtime/src/test/java/io/casehub/ras/runtime/InMemoryGanglionStateStoreTest.java`

**Interfaces:**
- Consumes: `GanglionStateStore`, `GanglionStateKey`, `GanglionState` from Task 1
- Produces: `InMemoryGanglionStateStore` — `@ApplicationScoped @DefaultBean`, ConcurrentHashMap-backed

- [ ] **Step 1: Write InMemoryGanglionStateStoreTest extending contract test**

```java
package io.casehub.ras.runtime;

import io.casehub.ras.api.AbstractGanglionStateStoreContractTest;
import io.casehub.ras.api.GanglionStateStore;

class InMemoryGanglionStateStoreTest extends AbstractGanglionStateStoreContractTest {
    @Override
    protected GanglionStateStore createStore() {
        return new InMemoryGanglionStateStore();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn --batch-mode -pl runtime test -Dtest=InMemoryGanglionStateStoreTest`
Expected: COMPILATION_ERROR — `InMemoryGanglionStateStore` does not exist

- [ ] **Step 3: Implement InMemoryGanglionStateStore**

```java
package io.casehub.ras.runtime;

import io.casehub.ras.api.GanglionState;
import io.casehub.ras.api.GanglionStateKey;
import io.casehub.ras.api.GanglionStateStore;
import io.quarkus.arc.DefaultBean;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Arrays;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
@DefaultBean
public class InMemoryGanglionStateStore implements GanglionStateStore {

    private final ConcurrentHashMap<GanglionStateKey, double[]> store = new ConcurrentHashMap<>();

    @Override
    public Uni<Optional<GanglionState>> load(GanglionStateKey key) {
        double[] values = store.get(key);
        if (values == null) {
            return Uni.createFrom().item(Optional.empty());
        }
        return Uni.createFrom().item(Optional.of(
                new GanglionState(Arrays.copyOf(values, values.length), OptionalLong.empty())));
    }

    @Override
    public Uni<Void> save(GanglionStateKey key, GanglionState state) {
        store.put(key, Arrays.copyOf(state.values(), state.values().length));
        return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<Void> remove(GanglionStateKey key) {
        store.remove(key);
        return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<Void> removeForSituation(String situationId) {
        store.keySet().removeIf(key -> key.situationId().equals(situationId));
        return Uni.createFrom().voidItem();
    }
}
```

- [ ] **Step 4: Run tests and verify they pass**

Run: `mvn --batch-mode -pl runtime test -Dtest=InMemoryGanglionStateStoreTest`
Expected: PASS — all contract tests green

- [ ] **Step 5: Commit**

```
feat(casehub-ras#36): InMemoryGanglionStateStore — @DefaultBean in runtime
```

---

### Task 3: Refactor NaiveBayesGanglion to use GanglionStateStore

**Files:**
- Modify: `runtime/src/main/java/io/casehub/ras/runtime/NaiveBayesGanglion.java`
- Modify: `runtime/src/test/java/io/casehub/ras/runtime/NaiveBayesGanglionTest.java`
- Modify: `runtime/src/test/java/io/casehub/ras/runtime/NaiveBayesGanglionContractTest.java`

**Interfaces:**
- Consumes: `GanglionStateStore`, `GanglionStateKey`, `GanglionState`, `GanglionStateConflictException` from Task 1
- Consumes: `InMemoryGanglionStateStore` from Task 2
- Produces: `NaiveBayesGanglion(NaiveBayesConfig config, GanglionStateStore stateStore)` — new constructor

- [ ] **Step 1: Write test for posteriors surviving store round-trip**

Add to `NaiveBayesGanglionTest`:

```java
@Test
void posteriorsSurviveStoreRoundTrip() {
    var stateStore = new InMemoryGanglionStateStore();
    var ganglion1 = new NaiveBayesGanglion(twoOutcomeConfig(), stateStore);
    var ctx = testContext();
    var event = testEvent("test.event", Instant.parse("2026-06-26T10:00:00Z"));

    ganglion1.detect(event, ctx).await().indefinitely();
    ganglion1.detect(event, ctx).await().indefinitely();
    DetectionResult r1 = ganglion1.detect(event, ctx).await().indefinitely();
    double p1 = (double) r1.evidence().get("posterior");

    var ganglion2 = new NaiveBayesGanglion(twoOutcomeConfig(), stateStore);
    DetectionResult r2 = ganglion2.detect(event, ctx).await().indefinitely();
    double p2 = (double) r2.evidence().get("posterior");

    assertThat(p2).isGreaterThan(p1);
}
```

- [ ] **Step 2: Write test for conflict retry preserving evidence**

Add to `NaiveBayesGanglionTest`:

```java
@Test
void detectRetriesOnConflictException() {
    var callCount = new java.util.concurrent.atomic.AtomicInteger();
    var delegate = new InMemoryGanglionStateStore();
    var conflictingStore = new GanglionStateStore() {
        public Uni<Optional<GanglionState>> load(GanglionStateKey key) {
            return delegate.load(key);
        }
        public Uni<Void> save(GanglionStateKey key, GanglionState state) {
            if (callCount.getAndIncrement() == 0) {
                return Uni.createFrom().failure(
                        new GanglionStateConflictException("test conflict", null));
            }
            return delegate.save(key, state);
        }
        public Uni<Void> remove(GanglionStateKey key) { return delegate.remove(key); }
        public Uni<Void> removeForSituation(String situationId) {
            return delegate.removeForSituation(situationId);
        }
    };

    var ganglion = new NaiveBayesGanglion(twoOutcomeConfig(), conflictingStore);
    var ctx = testContext();
    var event = testEvent("test.event", Instant.parse("2026-06-26T10:00:00Z"));

    DetectionResult result = ganglion.detect(event, ctx).await().indefinitely();

    assertThat(result).isNotNull();
    assertThat(callCount.get()).isEqualTo(2);
}
```

- [ ] **Step 3: Run new tests to verify they fail**

Run: `mvn --batch-mode -pl runtime test -Dtest="NaiveBayesGanglionTest#posteriorsSurviveStoreRoundTrip+detectRetriesOnConflictException"`
Expected: FAIL — constructor `NaiveBayesGanglion(NaiveBayesConfig, GanglionStateStore)` does not exist

- [ ] **Step 4: Refactor NaiveBayesGanglion**

Use `ide_edit_member` to replace the class declaration, removing `StateKey` record and
`states` field. Replace constructor. Replace `detect()` with load→update→save+retry.
Replace `close()` to delegate to store.

Changes:
1. Remove `StateKey` record (use `ide_refactor_safe_delete`)
2. Remove `states` field
3. Replace `config` field — keep it, add `stateStore` field
4. Replace constructor to accept `GanglionStateStore`
5. Replace `detect()` with retry loop
6. Replace `close()` to use store

Constructor:
```java
public NaiveBayesGanglion(NaiveBayesConfig config, GanglionStateStore stateStore) {
    this.config = config;
    this.stateStore = stateStore;
    this.logPriors = Arrays.stream(config.priors()).map(Math::log).toArray();
    this.targetIndex = config.outcomes().indexOf(config.signalMapping().targetOutcome());
}
```

Field `stateStore`:
```java
private final GanglionStateStore stateStore;
```

Remove field `states` (the ConcurrentHashMap).

`detect()`:
```java
@Override
public Uni<DetectionResult> detect(CloudEvent event, SituationContext context) {
    var key = new GanglionStateKey(config.ganglionId(), context.situationId(),
                                    context.correlationKey(), context.tenancyId());

    for (int attempt = 0; attempt <= MAX_STATE_RETRIES; attempt++) {
        GanglionState loaded = stateStore.load(key)
            .await().indefinitely()
            .orElseGet(() -> new GanglionState(
                Arrays.copyOf(logPriors, logPriors.length), OptionalLong.empty()));

        double[] logPosteriors = Arrays.copyOf(loaded.values(), loaded.values().length);

        Map<String, String> observed = config.featureExtractor().extract(event);
        for (var entry : observed.entrySet()) {
            FeatureLikelihood fl = config.features().get(entry.getKey());
            if (fl == null) continue;
            int valueIndex = fl.values().indexOf(entry.getValue());
            if (valueIndex < 0) continue;
            for (int i = 0; i < logPosteriors.length; i++) {
                logPosteriors[i] += Math.log(fl.likelihoods()[i][valueIndex]);
            }
        }

        try {
            stateStore.save(key, new GanglionState(logPosteriors, loaded.storeVersion()))
                .await().indefinitely();
        } catch (GanglionStateConflictException e) {
            if (attempt == MAX_STATE_RETRIES) throw e;
            continue;
        }

        double[] posteriors = normalizeLogPosteriors(logPosteriors);
        double targetPosterior = posteriors[targetIndex];

        DetectionSignal signal;
        double confidence;
        NaiveBayesSignalMapping mapping = config.signalMapping();

        if (targetPosterior >= mapping.detectedThreshold()) {
            signal = DetectionSignal.DETECTED;
            confidence = targetPosterior;
        } else if (targetPosterior >= mapping.weakThreshold()) {
            signal = DetectionSignal.WEAK;
            confidence = targetPosterior;
        } else if (mapping.antiThreshold() != null
                   && targetPosterior <= mapping.antiThreshold()) {
            signal = DetectionSignal.ANTI;
            confidence = 1.0 - targetPosterior;
        } else {
            signal = DetectionSignal.NOISE;
            confidence = 0.0;
        }

        var evidence = Map.<String, Object>of(
                "posterior", targetPosterior, "features", Map.copyOf(observed));
        return Uni.createFrom().item(
                new DetectionResult(config.ganglionId(), confidence, signal, evidence));
    }
    throw new IllegalStateException("Exhausted retries without success or conflict");
}
```

Add constant:
```java
private static final int MAX_STATE_RETRIES = 3;
```

`close()`:
```java
@Override
public Uni<Void> close(String situationId, String correlationKey, String tenancyId) {
    stateStore.remove(new GanglionStateKey(config.ganglionId(), situationId,
                                            correlationKey, tenancyId))
              .await().indefinitely();
    return Uni.createFrom().voidItem();
}
```

- [ ] **Step 5: Update all existing NaiveBayesGanglionTest methods**

Extract `twoOutcomeConfig()` from `twoOutcomeGanglion()` — tests need the config
separately to pass different stores. Update `twoOutcomeGanglion()` to use
`new NaiveBayesGanglion(twoOutcomeConfig(), new InMemoryGanglionStateStore())`.
Do the same for all inline `new NaiveBayesGanglion(new NaiveBayesConfig(...))` calls.

- [ ] **Step 6: Update NaiveBayesGanglionContractTest**

Replace `createGanglion()` to pass `InMemoryGanglionStateStore`.

- [ ] **Step 7: Run full test suite for runtime/**

Run: `mvn --batch-mode -pl runtime test`
Expected: ALL PASS

- [ ] **Step 8: Commit**

```
feat(casehub-ras#36): NaiveBayesGanglion uses GanglionStateStore — load/save/retry
```

---

### Task 4: JPA Implementation in persistence-jpa/

**Files:**
- Create: `persistence-jpa/src/main/java/io/casehub/ras/persistence/jpa/GanglionStateEntity.java`
- Create: `persistence-jpa/src/main/java/io/casehub/ras/persistence/jpa/GanglionStateId.java`
- Create: `persistence-jpa/src/main/java/io/casehub/ras/persistence/jpa/JpaGanglionStateStore.java`
- Create: `persistence-jpa/src/main/resources/db/ras/migration/V5__create_ras_ganglion_state.sql`
- Create: `persistence-jpa/src/test/java/io/casehub/ras/persistence/jpa/JpaGanglionStateStoreTest.java`
- Modify: `persistence-jpa/pom.xml` — add `micrometer-core` provided dep

**Interfaces:**
- Consumes: `GanglionStateStore`, `GanglionStateKey`, `GanglionState`, `GanglionStateConflictException` from Task 1
- Produces: `JpaGanglionStateStore` — `@ApplicationScoped`, beats `@DefaultBean`
- Produces: `GanglionStateEntity` — JPA entity with JSONB state column

- [ ] **Step 1: Create Flyway migration V5**

```sql
-- V5__create_ras_ganglion_state.sql
CREATE TABLE ras_ganglion_state (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    ganglion_id VARCHAR(255) NOT NULL,
    situation_id VARCHAR(255) NOT NULL,
    correlation_key VARCHAR(255) NOT NULL,
    tenancy_id VARCHAR(255) NOT NULL,
    state JSONB NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_ganglion_state UNIQUE (ganglion_id, situation_id, correlation_key, tenancy_id)
);

CREATE INDEX idx_ganglion_state_situation_id ON ras_ganglion_state (situation_id);
```

- [ ] **Step 2: Create GanglionStateId embeddable**

```java
package io.casehub.ras.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

@Embeddable
public class GanglionStateId implements Serializable {

    @Column(name = "ganglion_id", nullable = false)
    private String ganglionId;

    @Column(name = "situation_id", nullable = false)
    private String situationId;

    @Column(name = "correlation_key", nullable = false)
    private String correlationKey;

    @Column(name = "tenancy_id", nullable = false)
    private String tenancyId;

    protected GanglionStateId() {}

    public GanglionStateId(String ganglionId, String situationId,
                           String correlationKey, String tenancyId) {
        this.ganglionId = ganglionId;
        this.situationId = situationId;
        this.correlationKey = correlationKey;
        this.tenancyId = tenancyId;
    }

    // getters, equals, hashCode
}
```

Note: The spec shows a surrogate UUID PK + unique constraint (matching SituationEntity
pattern). Use that pattern — the `@EmbeddedId` in the spec was superseded by the
entity design in the reviewed spec. Use surrogate `UUID id` + separate columns.

- [ ] **Step 3: Create GanglionStateEntity**

Follow `SituationEntity` conventions: surrogate UUID PK, `@JdbcTypeCode(SqlTypes.JSON)`,
`@Version`, separate columns for the business key.

```java
package io.casehub.ras.persistence.jpa;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.util.UUID;

@Entity
@Table(name = "ras_ganglion_state",
       uniqueConstraints = @UniqueConstraint(
           columnNames = {"ganglion_id", "situation_id", "correlation_key", "tenancy_id"}))
public class GanglionStateEntity {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "ganglion_id", nullable = false)
    private String ganglionId;

    @Column(name = "situation_id", nullable = false)
    private String situationId;

    @Column(name = "correlation_key", nullable = false)
    private String correlationKey;

    @Column(name = "tenancy_id", nullable = false)
    private String tenancyId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "state", columnDefinition = "jsonb", nullable = false)
    private String state;

    @Version
    private Long version = 0L;

    protected GanglionStateEntity() {}

    public GanglionStateEntity(String ganglionId, String situationId,
                               String correlationKey, String tenancyId, String state) {
        this.ganglionId = ganglionId;
        this.situationId = situationId;
        this.correlationKey = correlationKey;
        this.tenancyId = tenancyId;
        this.state = state;
    }

    public UUID getId() { return id; }
    public String getGanglionId() { return ganglionId; }
    public String getSituationId() { return situationId; }
    public String getCorrelationKey() { return correlationKey; }
    public String getTenancyId() { return tenancyId; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public Long getVersion() { return version; }
}
```

- [ ] **Step 4: Write JpaGanglionStateStoreTest**

```java
package io.casehub.ras.persistence.jpa;

import io.casehub.ras.api.*;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import java.util.OptionalLong;
import static org.assertj.core.api.Assertions.*;

@QuarkusTest
class JpaGanglionStateStoreTest extends AbstractGanglionStateStoreContractTest {

    @Inject
    JpaGanglionStateStore jpaStore;

    @Override
    protected GanglionStateStore createStore() {
        return jpaStore;
    }

    @Test
    void loadReturnsStoreVersion() {
        var key = new GanglionStateKey("g1", "sit-1", "key-1", "tenant-a");
        store.save(key, new GanglionState(new double[]{1.0}, OptionalLong.empty()))
                .await().indefinitely();

        var loaded = store.load(key).await().indefinitely().orElseThrow();
        assertThat(loaded.storeVersion()).isPresent();
    }

    @Test
    void saveWithStaleVersionThrowsConflictException() {
        var key = new GanglionStateKey("g1", "sit-1", "key-1", "tenant-a");
        store.save(key, new GanglionState(new double[]{1.0}, OptionalLong.empty()))
                .await().indefinitely();

        var loaded = store.load(key).await().indefinitely().orElseThrow();
        store.save(key, new GanglionState(new double[]{2.0}, loaded.storeVersion()))
                .await().indefinitely();

        assertThatThrownBy(() ->
                store.save(key, new GanglionState(new double[]{3.0}, loaded.storeVersion()))
                        .await().indefinitely())
                .isInstanceOf(GanglionStateConflictException.class);
    }

    @Test
    void removeOrphanedRemovesEntriesWithNoMatchingSituation() {
        var key = new GanglionStateKey("g1", "orphan-sit", "key-1", "tenant-a");
        store.save(key, new GanglionState(new double[]{1.0}, OptionalLong.empty()))
                .await().indefinitely();

        int removed = store.removeOrphaned().await().indefinitely();

        assertThat(removed).isEqualTo(1);
        assertThat(store.load(key).await().indefinitely()).isEmpty();
    }
}
```

- [ ] **Step 5: Run test to verify it fails**

Run: `mvn --batch-mode -pl persistence-jpa test -Dtest=JpaGanglionStateStoreTest`
Expected: COMPILATION_ERROR — `JpaGanglionStateStore` does not exist

- [ ] **Step 6: Implement JpaGanglionStateStore**

Follow `JpaSituationStore` patterns: `@Transactional(TxType.REQUIRED)` on all methods,
JPQL queries, two-layer conflict detection.

```java
package io.casehub.ras.persistence.jpa;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.ras.api.*;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;
import java.util.Arrays;
import java.util.Optional;
import java.util.OptionalLong;

@ApplicationScoped
public class JpaGanglionStateStore implements GanglionStateStore {

    private final EntityManager em;
    private final ObjectMapper objectMapper;

    @Inject
    public JpaGanglionStateStore(EntityManager em, ObjectMapper objectMapper) {
        this.em = em;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(TxType.REQUIRED)
    public Uni<Optional<GanglionState>> load(GanglionStateKey key) {
        GanglionStateEntity entity = findEntity(key);
        if (entity == null) {
            return Uni.createFrom().item(Optional.empty());
        }
        double[] values = deserializeState(entity.getState());
        return Uni.createFrom().item(Optional.of(
                new GanglionState(values, OptionalLong.of(entity.getVersion()))));
    }

    @Override
    @Transactional(TxType.REQUIRED)
    public Uni<Void> save(GanglionStateKey key, GanglionState state) {
        GanglionStateEntity existing = findEntity(key);

        if (existing != null && state.storeVersion().isEmpty()) {
            throw new GanglionStateConflictException(
                    "Entity exists but state has no storeVersion — concurrent insert", null);
        }
        if (existing == null && state.storeVersion().isPresent()) {
            throw new GanglionStateConflictException(
                    "Entity removed but state has storeVersion — concurrent delete", null);
        }
        if (existing != null && state.storeVersion().isPresent()
                && existing.getVersion() != state.storeVersion().getAsLong()) {
            throw new GanglionStateConflictException(
                    "storeVersion mismatch: state=" + state.storeVersion().getAsLong()
                    + " entity=" + existing.getVersion(), null);
        }

        try {
            String serialized = serializeState(state.values());
            if (existing != null) {
                existing.setState(serialized);
                em.flush();
            } else {
                GanglionStateEntity newEntity = new GanglionStateEntity(
                        key.ganglionId(), key.situationId(),
                        key.correlationKey(), key.tenancyId(), serialized);
                em.persist(newEntity);
                em.flush();
            }
        } catch (jakarta.persistence.OptimisticLockException e) {
            throw new GanglionStateConflictException("Concurrent modification detected", e);
        } catch (jakarta.persistence.PersistenceException e) {
            if (isConstraintViolation(e)) {
                throw new GanglionStateConflictException("Concurrent insert detected", e);
            }
            throw e;
        }

        return Uni.createFrom().voidItem();
    }

    @Override
    @Transactional(TxType.REQUIRED)
    public Uni<Void> remove(GanglionStateKey key) {
        em.createQuery("DELETE FROM GanglionStateEntity e " +
                       "WHERE e.ganglionId = :gid AND e.situationId = :sid " +
                       "AND e.correlationKey = :ck AND e.tenancyId = :tid")
                .setParameter("gid", key.ganglionId())
                .setParameter("sid", key.situationId())
                .setParameter("ck", key.correlationKey())
                .setParameter("tid", key.tenancyId())
                .executeUpdate();
        return Uni.createFrom().voidItem();
    }

    @Override
    @Transactional(TxType.REQUIRED)
    public Uni<Void> removeForSituation(String situationId) {
        em.createQuery("DELETE FROM GanglionStateEntity e WHERE e.situationId = :sid")
                .setParameter("sid", situationId)
                .executeUpdate();
        return Uni.createFrom().voidItem();
    }

    @Override
    @Transactional(TxType.REQUIRED)
    public Uni<Integer> removeOrphaned() {
        int removed = em.createNativeQuery(
                "DELETE FROM ras_ganglion_state gs " +
                "WHERE NOT EXISTS (" +
                "  SELECT 1 FROM ras_situation s " +
                "  WHERE s.situation_id = gs.situation_id " +
                "  AND s.correlation_key = gs.correlation_key " +
                "  AND s.tenancy_id = gs.tenancy_id)")
                .executeUpdate();
        return Uni.createFrom().item(removed);
    }

    private GanglionStateEntity findEntity(GanglionStateKey key) {
        return em.createQuery(
                "SELECT e FROM GanglionStateEntity e " +
                "WHERE e.ganglionId = :gid AND e.situationId = :sid " +
                "AND e.correlationKey = :ck AND e.tenancyId = :tid",
                GanglionStateEntity.class)
                .setParameter("gid", key.ganglionId())
                .setParameter("sid", key.situationId())
                .setParameter("ck", key.correlationKey())
                .setParameter("tid", key.tenancyId())
                .getResultStream().findFirst().orElse(null);
    }

    private String serializeState(double[] values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize ganglion state", e);
        }
    }

    private double[] deserializeState(String json) {
        try {
            return objectMapper.readValue(json, double[].class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize ganglion state: " + json, e);
        }
    }

    private boolean isConstraintViolation(Throwable t) {
        while (t != null) {
            if (t instanceof org.hibernate.exception.ConstraintViolationException) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }
}
```

- [ ] **Step 7: Run JPA tests**

Run: `mvn --batch-mode -pl persistence-jpa test -Dtest=JpaGanglionStateStoreTest`
Expected: ALL PASS

- [ ] **Step 8: Commit**

```
feat(casehub-ras#36): JpaGanglionStateStore — entity, migration V5, optimistic locking
```

---

### Task 5: SituationExpiryJob Orphan Cleanup + RasMetrics + CLAUDE.md

**Files:**
- Modify: `runtime/src/main/java/io/casehub/ras/runtime/SituationExpiryJob.java`
- Modify: `runtime/src/main/java/io/casehub/ras/runtime/RasMetrics.java`
- Modify: `runtime/src/test/java/io/casehub/ras/runtime/SituationExpiryJobTest.java`
- Modify: `CLAUDE.md`

**Interfaces:**
- Consumes: `GanglionStateStore.removeOrphaned()` from Task 1
- Consumes: `RasMetrics.orphanedGanglionStateCleaned(int)` — added this task

- [ ] **Step 1: Write test for orphan cleanup in SituationExpiryJobTest**

Add test that verifies `SituationExpiryJob.cleanup()` calls
`ganglionStateStore.removeOrphaned()` and records the metric.

```java
@Test
void cleanupRemovesOrphanedGanglionState() {
    // Verify removeOrphaned() is called during cleanup
    // and metric is recorded via RasMetrics
}
```

Specifics depend on existing test setup — use the existing mock/stub pattern from
`SituationExpiryJobTest`.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn --batch-mode -pl runtime test -Dtest=SituationExpiryJobTest#cleanupRemovesOrphanedGanglionState`
Expected: FAIL

- [ ] **Step 3: Add orphanedGanglionStateCleaned metric to RasMetrics**

Use `ide_insert_member` to add after `expiredCleaned`:

```java
public void orphanedGanglionStateCleaned(int count) {
    counterBy("ras.expiry.ganglion_state_orphans_cleaned", count);
}
```

- [ ] **Step 4: Add GanglionStateStore injection to SituationExpiryJob**

Add `GanglionStateStore` field and constructor parameter. Add call in `cleanup()`:

```java
int orphanedRemoved = ganglionStateStore.removeOrphaned().await().indefinitely();
metrics.orphanedGanglionStateCleaned(orphanedRemoved);
```

- [ ] **Step 5: Run tests**

Run: `mvn --batch-mode -pl runtime test -Dtest=SituationExpiryJobTest`
Expected: ALL PASS

- [ ] **Step 6: Update CLAUDE.md**

Add `GanglionStateStore` to Core SPIs section.
Add `GanglionState`, `GanglionStateKey`, `GanglionStateConflictException` to Core Types table.
Add `InMemoryGanglionStateStore` to runtime/ module description.
Add `JpaGanglionStateStore`, `GanglionStateEntity` to persistence-jpa/ description.
Update deregistration guidance in Dynamic Situation Registration section.

- [ ] **Step 7: Run full build**

Run: `mvn --batch-mode install`
Expected: BUILD SUCCESS — all modules green

- [ ] **Step 8: Commit**

```
feat(casehub-ras#36): SituationExpiryJob orphan cleanup, metrics, CLAUDE.md updates
```

---

### Task 6: File Drools orphan issue

**Files:**
- None (GitHub issue only)

- [ ] **Step 1: File GitHub issue for DroolsSessionStore orphan gap**

The design review identified that `DroolsSessionStore` has the same orphan problem
with `ReliableDroolsSessionStore` as `GanglionStateStore`. File as casehubio/casehub-ras#38.

```bash
gh issue create --repo casehubio/casehub-ras \
  --title "DroolsSessionStore: orphaned session cleanup for ReliableDroolsSessionStore" \
  --body "$(cat <<'BODY'
## Context

Surfaced during design review of #36 (GanglionStateStore).

SituationExpiryJob removes expired situations via bulk DELETE but does not call
closeGanglia() — it has no reference to the ganglion registry. With
ReliableDroolsSessionStore, this leaves orphaned persisted Drools sessions
in H2MVStore.

Same gap as GanglionStateStore orphans (fixed in #36 via removeOrphaned()).

## Proposed approach

Add removeOrphaned() to DroolsSessionStore (default no-op).
ReliableDroolsSessionStore overrides to clean up persisted sessions
whose situation key no longer exists.

Scale: S
Complexity: Med
BODY
)"
```

- [ ] **Step 2: Commit**

No code changes — issue filing only. No commit needed.
