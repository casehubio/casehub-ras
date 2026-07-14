# GanglionStateStore — Pluggable Persistence for Ganglion Computation State

**Issue:** casehubio/casehub-ras#36
**Date:** 2026-07-13
**Status:** Approved

## Problem

NaiveBayesGanglion holds running `double[] logPosteriors` per situation instance in
a `ConcurrentHashMap` — purely in-memory. On restart, all pre-restart evidence is lost
and Bayesian accumulation restarts from priors. The `compact()` method captures the latest
normalized posterior as a `TimestampedDetection` in `SituationContext` (which IS persisted),
but the incremental log-space accumulation state — the actual computation — is lost.

## Design Principle

Ganglia fall into three state categories:

1. **Stateless** (JavaSwitchGanglion) — no store needed
2. **Simple-state** (NaiveBayesGanglion) — state is a small serializable numeric array
3. **Complex-state** (DroolsGanglion) — state is a live framework object requiring
   domain-specific persistence (DroolsSessionStore)

DroolsSessionStore is domain-specific because KieSession recovery requires Drools-specific
APIs — the store's API IS the domain model. NaiveBayes state is `double[]` — generic numeric
accumulation. Moving averages, EMA, voting tallies, Bayesian posteriors are all `double[]`.
The SPI is therefore generic for simple-state ganglia, not NaiveBayes-specific.

## Module Layout

```
api/                          GanglionStateStore (interface)
                              GanglionStateKey (record)
                              GanglionState (record)
                              GanglionStateConflictException

runtime/                      InMemoryGanglionStateStore (@DefaultBean)
                              NaiveBayesGanglion — takes GanglionStateStore as ctor param

persistence-jpa/              JpaGanglionStateStore (@ApplicationScoped)
                              GanglionStateEntity (JPA entity)
                              GanglionStatePersistenceMetrics (module-local metrics)
                              Flyway migration V5
```

**Why SPI in `api/`:** `persistence-jpa/` must implement it and depends on `api/`, not
`runtime/`. Putting the SPI in `runtime/` inverts the dependency direction. And "ganglia
can persist computation state" is a core platform capability — same tier as SituationStore.

**Why in-memory in `runtime/`:** Needs `@DefaultBean` (Quarkus-specific). `api/` is pure
Java + standard CDI. `runtime/` already has `quarkus-arc`.

**CDI activation:** App with only `runtime/` gets in-memory (current behavior). Add
`persistence-jpa/` to classpath -> JPA wins automatically (`@ApplicationScoped` beats
`@DefaultBean`). Same pattern as DroolsSessionStore / ReliableDroolsSessionStore.

## API

### GanglionStateKey (api/)

```java
public record GanglionStateKey(
    String ganglionId,
    String situationId,
    String correlationKey,
    String tenancyId
) {}
```

Same 4-tuple as `DroolsSessionKey`. Lives in `api/` because the SPI is generic.

### GanglionState (api/)

```java
public record GanglionState(double[] values, OptionalLong storeVersion) {}
```

Carries the state array alongside an optional store version for optimistic locking.
`storeVersion` is populated by persistence implementations on load and checked on save.
In-memory stores leave it empty — the per-key `synchronized` lock in the evaluator
prevents intra-JVM conflicts. Follows `SituationContext.storeVersion()` pattern.

### GanglionStateConflictException (api/)

```java
public class GanglionStateConflictException extends RuntimeException {
    public GanglionStateConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

Thrown by `GanglionStateStore.save()` when the persisted version differs from the
expected version. Signals a concurrent modification from another JVM. Mirrors
`SituationConflictException` for ganglion state.

### GanglionStateStore (api/)

```java
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

- `load` returns defensive copy of state with store version
- `save` stores defensive copy, checks version for optimistic locking (JPA impl throws
  `GanglionStateConflictException` on conflict)
- `remove` cleans up on situation close
- `removeForSituation` purges all state for a situation (deregistration cleanup) —
  mirrors `SituationStore.removeAllForSituation(String situationId)`
- `removeOrphaned` removes entries whose situation key no longer exists in the
  situation store (default no-op; JPA overrides with join-based cleanup)
- Mutiny `Uni<>` return types — consistent with SituationStore (persistence operations)

### InMemoryGanglionStateStore (runtime/)

```java
@ApplicationScoped
@DefaultBean
public class InMemoryGanglionStateStore implements GanglionStateStore {
    private final ConcurrentHashMap<GanglionStateKey, double[]> store =
            new ConcurrentHashMap<>();
}
```

Extracted from NaiveBayesGanglion's current internal `ConcurrentHashMap`. Defensive copies
on both `load()` and `save()`. Returns `GanglionState` with `OptionalLong.empty()` —
version tracking is unnecessary since the evaluator's per-key `synchronized` lock
prevents intra-JVM concurrent access.

### JpaGanglionStateStore (persistence-jpa/)

```java
@ApplicationScoped
public class JpaGanglionStateStore implements GanglionStateStore {
    private final EntityManager em;
    private final ObjectMapper objectMapper;

    @Inject
    public JpaGanglionStateStore(EntityManager em, ObjectMapper objectMapper) { ... }
}
```

All methods annotated `@Transactional(TxType.REQUIRED)` — `detect()` is called from
`SituationEvaluator.runDetection()` outside any transactional context.

`save()` implements two-layer conflict detection matching `JpaSituationStore`:
1. Application-level `storeVersion` comparison
2. Hibernate `@Version` OLE as backup
Throws `GanglionStateConflictException` on conflict.

#### Entity

```java
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
}
```

Follows `SituationEntity` conventions: surrogate UUID PK, `@JdbcTypeCode(SqlTypes.JSON)`
for proper JDBC type mapping, `String` storage with explicit JSON serialization via
`ObjectMapper`, `@Version` for optimistic locking.

#### Flyway migration (V5)

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

Added to the existing `classpath:db/ras/migration` path.

`removeForSituation(situationId)` is a bulk `DELETE FROM ras_ganglion_state WHERE situation_id = ?`.

`removeOrphaned()` uses a NOT EXISTS join:
```sql
DELETE FROM ras_ganglion_state gs
WHERE NOT EXISTS (
    SELECT 1 FROM ras_situation s
    WHERE s.situation_id = gs.situation_id
    AND s.correlation_key = gs.correlation_key
    AND s.tenancy_id = gs.tenancy_id
)
```

## NaiveBayesGanglion Changes

### Constructor

```java
public NaiveBayesGanglion(NaiveBayesConfig config, GanglionStateStore stateStore) {
    this.config = config;
    this.stateStore = stateStore;
    this.logPriors = Arrays.stream(config.priors()).map(Math::log).toArray();
    this.targetIndex = config.outcomes().indexOf(config.signalMapping().targetOutcome());
}
```

The internal `ConcurrentHashMap<StateKey, double[]> states` field and `StateKey` record
are removed entirely.

**Consumer impact:** `NaiveBayesGanglion` is not a CDI bean — consuming applications
create it via `@Produces` methods (or equivalent CDI producers). Adding `GanglionStateStore`
as a constructor parameter requires every consumer's producer method to also inject and
pass `GanglionStateStore`. This is a compile-time breaking change — consumers will get a
compilation error on their producer method until updated.

### detect()

Load -> update -> save with optimistic locking retry:

```java
private static final int MAX_STATE_RETRIES = 3;

@Override
public Uni<DetectionResult> detect(CloudEvent event, SituationContext context) {
    var key = new GanglionStateKey(config.ganglionId(), context.situationId(),
                                    context.correlationKey(), context.tenancyId());

    double[] posteriors = null;
    for (int attempt = 0; attempt <= MAX_STATE_RETRIES; attempt++) {
        GanglionState loaded = stateStore.load(key)
            .await().indefinitely()
            .orElseGet(() -> new GanglionState(
                Arrays.copyOf(logPriors, logPriors.length), OptionalLong.empty()));

        double[] logPosteriors = Arrays.copyOf(loaded.values(), loaded.values().length);

        // ... update logPosteriors with observed evidence (unchanged Bayesian logic) ...

        posteriors = normalizeLogPosteriors(logPosteriors);

        try {
            stateStore.save(key, new GanglionState(logPosteriors, loaded.storeVersion()))
                .await().indefinitely();
            break;
        } catch (GanglionStateConflictException e) {
            if (attempt == MAX_STATE_RETRIES) throw e;
        }
    }

    // ... build DetectionResult from posteriors (unchanged signal mapping) ...
}
```

The retry loop ensures that concurrent updates from multiple JVMs during partition
rebalancing are not lost. On conflict, the latest committed state is reloaded and
this event's evidence is reapplied on top of it, preserving both JVMs' updates.

### close()

```java
var key = new GanglionStateKey(config.ganglionId(), situationId, correlationKey, tenancyId);
stateStore.remove(key).await().indefinitely();
return Uni.createFrom().voidItem();
```

### compact()

Unchanged — compact operates on `SituationContext.detections()`, not on the posteriors
array. The posteriors are managed by the state store independently.

## Concurrency

### Intra-JVM

`SituationEvaluator.evaluate()` acquires a per-key `synchronized` lock before calling
`ganglion.detect()`. No concurrent access to the same key's posteriors within a JVM.
The load -> modify -> save pattern is safe under this lock.

### Inter-JVM (Clustered)

In a clustered deployment, events for the same situation key are partitioned to the same
JVM via Kafka consumer group partition assignment. During partition rebalancing, brief
concurrent access from multiple JVMs is possible.

`JpaGanglionStateStore` provides optimistic locking via `@Version` on the entity and
application-level `storeVersion` comparison in `save()` — the same two-layer conflict
detection pattern as `JpaSituationStore`. On conflict, `GanglionStateConflictException`
is thrown.

`NaiveBayesGanglion.detect()` handles conflicts with an internal retry loop (max 3
retries). On conflict: reload latest state from DB -> reapply this event's evidence ->
retry save. This preserves updates from both JVMs — the reloaded state includes the
other JVM's modifications, and the current event's evidence is applied on top.

The retry is owned by the ganglion (not the evaluator) because ganglion state conflicts
are independent of situation state conflicts. The evaluator's Phase 1 (detect, never
retried) and Phase 2 (apply + persist, retried on `SituationConflictException`) remain
unchanged — ganglion state is a self-contained concern within `detect()`.

`InMemoryGanglionStateStore` never throws `GanglionStateConflictException` — version
tracking is unnecessary since the evaluator's per-key lock prevents intra-JVM conflicts,
and in-memory state is inherently JVM-local.

## Orphan Cleanup

`SituationExpiryJob.cleanup()` removes expired situations via bulk DELETE but does not
call `closeGanglia()` — it has no reference to the ganglion registry and doesn't know
which ganglia participate in which situation. With JPA persistence, this leaves orphaned
`ras_ganglion_state` rows.

Cleanup paths and their orphan behavior:

1. **Triggered situations (TRIGGER):** `executeDecision()` calls `closeGanglia()` before
   returning — ganglion state IS cleaned up. Expiry job later removes the guard entity.
2. **Expired situations during event processing:** `loadContext()` detects expiry, calls
   `closeGanglia()` — ganglion state IS cleaned up. But only if another event arrives.
3. **Expired situations via expiry job:** `SituationExpiryJob` bulk-removes expired
   contexts. No `closeGanglia()` call. **Ganglion state orphaned.**

`GanglionStateStore.removeOrphaned()` addresses path 3 with a join-based cleanup:
the JPA implementation deletes entries whose `(situation_id, correlation_key, tenancy_id)`
tuple has no matching row in `ras_situation`. The default interface implementation returns
0 (no-op for in-memory, where orphans are benign and cleared on restart).

`SituationExpiryJob` injects `GanglionStateStore` and calls `removeOrphaned()` after
existing situation cleanup:

```java
int orphanedRemoved = ganglionStateStore.removeOrphaned().await().indefinitely();
metrics.orphanedGanglionStateCleaned(orphanedRemoved);
```

**Related gap:** `DroolsSessionStore` has the same orphan problem with
`ReliableDroolsSessionStore`. Filed as casehubio/casehub-ras#38 for separate resolution.

## Metrics

### GanglionStatePersistenceMetrics (persistence-jpa/)

`persistence-jpa/` cannot depend on `runtime/` (where `RasMetrics` lives) — that would
invert the dependency direction. Instead, `JpaGanglionStateStore` uses a module-local
`GanglionStatePersistenceMetrics` bean following the `DroolsReliabilityMetrics` pattern:

```java
@ApplicationScoped
public class GanglionStatePersistenceMetrics {
    @Inject
    Instance<MeterRegistry> meterRegistryInstance;
    // ... null-guarding init, same as DroolsReliabilityMetrics
}
```

`io.micrometer:micrometer-core` is added to `persistence-jpa/pom.xml` as `provided` scope
(same as `drools-reliability/pom.xml`).

| Metric | Type | Description |
|--------|------|-------------|
| `ras.ganglion.state.load.timer` | Timer | Load latency per operation |
| `ras.ganglion.state.save.timer` | Timer | Save latency per operation |
| `ras.ganglion.state.load.hits` | Counter | State loaded from store (existing entry) |
| `ras.ganglion.state.load.misses` | Counter | No existing state (initialized from priors) |
| `ras.ganglion.state.remove` | Counter | Remove operations |
| `ras.ganglion.state.conflicts` | Counter | Optimistic lock conflicts detected |

Tags: `ganglion_id`, `situation_id`, `tenancy_id` where applicable.

### Orphan cleanup metric (runtime/)

The orphan cleanup counter lives in `RasMetrics` (runtime/) since it is called from
`SituationExpiryJob`:

| Metric | Type | Description |
|--------|------|-------------|
| `ras.ganglion.state.orphans.cleaned` | Counter | Orphaned entries removed per cleanup cycle |

These metrics are especially important because JPA persistence adds two DB operations
(load + save) to every `detect()` call — a latency change that must be observable.

## Consumer Integration

`NaiveBayesGanglion` is not a CDI bean — it has no CDI annotations. Consuming
applications create it via `@Produces` methods, injecting `NaiveBayesConfig`. Adding
`GanglionStateStore` as a constructor parameter changes the consumer contract.

Before:
```java
@Produces
NaiveBayesGanglion fraudGanglion(NaiveBayesConfig config) {
    return new NaiveBayesGanglion(config);
}
```

After:
```java
@Produces
NaiveBayesGanglion fraudGanglion(NaiveBayesConfig config, GanglionStateStore stateStore) {
    return new NaiveBayesGanglion(config, stateStore);
}
```

This is a compile-time breaking change. The migration is mechanical — add the
`GanglionStateStore` parameter to every `NaiveBayesGanglion` producer method.

## Testing

### Contract test (api/ test-jar)

`AbstractGanglionStateStoreContractTest` — verifies load/save/remove/removeForSituation
semantics:

- Save then load returns equal array with store version
- Load absent key returns empty
- Remove then load returns empty
- Save overwrites previous value
- removeForSituation removes only matching situationId
- Defensive copies: mutating returned array does not corrupt store

### Implementation tests

- `InMemoryGanglionStateStoreTest` extends contract test
- `JpaGanglionStateStoreTest` extends contract test (Quarkus @QuarkusTest with H2)
- JPA-specific: version conflict throws `GanglionStateConflictException`
- JPA-specific: `removeOrphaned()` removes entries with no matching situation

### NaiveBayesGanglion tests

- Existing tests updated to pass `InMemoryGanglionStateStore` to constructor
- New test: posteriors survive store round-trip (save -> new ganglion instance with same
  store -> load -> posteriors match)
- New test: detect() retries on `GanglionStateConflictException` and preserves evidence

## CLAUDE.md Updates

After implementation:
- Add `GanglionStateStore` to Core SPIs section
- Add `GanglionState`, `GanglionStateKey`, `GanglionStateConflictException` to Core Types table
- Add `InMemoryGanglionStateStore` to runtime/ module description
- Add `JpaGanglionStateStore`, `GanglionStateEntity`, and `GanglionStatePersistenceMetrics`
  to persistence-jpa/ description
- Update `SituationExpiryJob` description to mention `GanglionStateStore` orphan cleanup
- Update "Dynamic Situation Registration" deregistration guidance: consumers must call
  both `SituationStore.removeAllForSituation()` AND `GanglionStateStore.removeForSituation()`
  before deregistering to avoid orphaned entries. `removeOrphaned()` provides background
  cleanup as a safety net but explicit cleanup is the primary mechanism
