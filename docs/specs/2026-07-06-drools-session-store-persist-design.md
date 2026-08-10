# DroolsSessionStore: Persistent Implementation for Restart Survival

**Issue:** casehubio/casehub-ras#7
**Date:** 2026-07-06
**Status:** Draft

## Problem

`InMemoryDroolsSessionStore` holds live `KieSession` objects in a `ConcurrentHashMap`.
When the JVM restarts, all long-lived sessions are lost. For CEP ganglia using sliding
windows, temporal correlations, and partial pattern matches, this means in-progress
detection state is silently dropped — events that arrived before the restart are invisible
to rules evaluated after it.

## Approach

Use Drools' own `drools-reliability` module with the H2MVStore backend. This is
experimental and temporary — a personal drools-reliability effort based on journaling
is underway separately (casehubio/casehub-ras#29). Nothing here goes to
production; the design prioritises encapsulation so the persistence mechanism can be
replaced without touching consumers.

`drools-reliability` intercepts every fact insert/update/delete on the `ObjectStore`,
persists `StoredObject` entries to a pluggable `Storage<K,V>` backend. On recovery,
it replays all stored facts into a fresh session, advancing the pseudo clock to each
event's timestamp, and uses `isPropagated` flags plus optional `ActivationKey` tracking
to avoid re-firing rules that already fired before the crash.

## SPI Redesign

### DroolsSessionKey

Promoted from `InMemoryDroolsSessionStore`'s private record to a shared type. Replaces
the four-string parameter splatter across all call sites.

```java
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

`toStorageKey()` provides a deterministic serialization format for persistent mapping
keys. Java record `toString()` format is implementation-defined and not guaranteed
across JVM versions — unsuitable for data that must survive restarts.

### DroolsSessionStore

Aligned with `Map` semantics. `computeIfAbsent` subsumes the old `get()` + `put()` —
the store controls session creation, caching, and recovery. The `generation` parameter
enables lazy invalidation without a bulk `removeAll()` operation.

```java
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

- **`computeIfAbsent`** — returns an existing session for the key if the session's
  recorded generation matches the passed `generation`. If the session is stale
  (recorded generation < passed generation), disposes it and creates a new one using
  the provided `kieBase`. On a full miss, creates and stores a new session.
  The `config` parameter is borrowed (read-only) — the store creates its own config
  internally when adding persistence options. Idempotent on cache hit with matching
  generation.
- **`remove`** — evicts and disposes a single session. Called by `DroolsGanglion.close()`.

### What was removed

- **`get()` → merged into `computeIfAbsent`** — callers never want a miss without
  a subsequent create; separating them leaked lifecycle control.
- **`put()` → eliminated** — the store owns the session from creation. With
  drools-reliability, persistence happens automatically on every fact insert;
  an explicit `put()` is redundant.
- **`removeAll()` → eliminated** — the hot reload spec (2026-06-26, §3) explicitly
  rejected `removeAll()` on the SPI: a bulk-remove operation races with in-flight
  `detect()` calls because SituationEvaluator's per-key serialization doesn't cover
  cross-key operations. Lazy generation-based invalidation via the `generation`
  parameter replaces it — sessions are only disposed by the thread that holds the
  per-key lock, within `computeIfAbsent`.

## DroolsGanglion Changes

Session lifecycle moves out of `DroolsGanglion` into the store, with one exception:
the ganglion retains `volatile long reloadGeneration` for the acquire-release ordering
guarantee required by the JMM (JLS §17.4.5). The `sessionGenerations` map is eliminated
— the store tracks per-session generation stamps internally.

- **`createSession(KieBase)`** — retained for EPHEMERAL mode only. LONG_LIVED session
  creation is the store's responsibility.
- **`sessionGenerations`** — removed. Per-session generation tracking moves into the
  store's `computeIfAbsent` implementation.
- **`reloadGeneration`** — retained (volatile). Required for the acquire-release chain:
  `reload()` writes DATA (kieBase) then FLAG (reloadGeneration); `detect()` reads FLAG
  then DATA. This guarantees that if `detect()` sees the new generation, it also sees
  the new KieBase.

### detect() — complete flow for both modes

```java
public Uni<DetectionResult> detect(CloudEvent event, SituationContext context) {
    long currentGen = this.reloadGeneration;  // volatile read: FLAG first
    KieBase currentBase = this.kieBase;       // volatile read: DATA second

    String situationId = context.situationId();
    String correlationKey = context.correlationKey();
    String tenancyId = context.tenancyId();

    KieSession session;
    if (sessionMode == SessionMode.LONG_LIVED) {
        var key = new DroolsSessionKey(ganglionId, situationId, correlationKey, tenancyId);
        session = sessionStore.computeIfAbsent(key, currentBase, buildSessionConfig(), currentGen);
    } else {
        // EPHEMERAL: create fresh, no store interaction
        session = createSession(currentBase);
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
        if (sessionMode == SessionMode.EPHEMERAL) {
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
```

- **`close()`** — calls `sessionStore.remove(key)` (unchanged semantically).
- **`reload()`** — rebuilds `KieBase`, then increments `reloadGeneration`. No store
  call — invalidation is lazy. The next `computeIfAbsent` call for each key detects
  the generation mismatch and disposes the stale session within SituationEvaluator's
  per-key synchronized context.

```java
public synchronized void reload(List<String> classpathRules, List<String> programmaticRules) {
    if (classpathRules.isEmpty() && programmaticRules.isEmpty()) {
        throw new IllegalArgumentException("At least one rule source required");
    }
    KieBase newBase = buildKieBase(classpathRules, programmaticRules);
    this.kieBase = newBase;       // volatile write: DATA
    this.reloadGeneration++;      // volatile write: FLAG
}
```

`DroolsGanglion` becomes thinner: detection logic only (insert event, fire rules,
collect results) plus EPHEMERAL session creation. The store owns long-lived session
lifecycle (create, cache, invalidate, recover, dispose).

## Implementations

### InMemoryDroolsSessionStore (updated)

`@ApplicationScoped @DefaultBean` — unchanged activation. Lives in `ras-drools`.

```
private record StampedSession(KieSession session, long generation) {}
ConcurrentHashMap<DroolsSessionKey, StampedSession> cache

computeIfAbsent(key, kieBase, config, generation):
  StampedSession cached = cache.get(key)
  if cached != null:
    if cached.generation < generation:
      cached.session.dispose()
      cache.remove(key)
      // fall through to create
    else:
      return cached.session

  KieSession session = kieBase.newKieSession(config, null)
  cache.put(key, new StampedSession(session, generation))
  return session

remove(key):
  StampedSession removed = cache.remove(key)
  if removed != null: removed.session.dispose()
```

Identical behavior to today, consolidated under the new SPI with generation-aware
invalidation. Thread safety: `computeIfAbsent` is called within SituationEvaluator's
per-key lock — no concurrent calls for the same key.

`removeAll(String ganglionId)` remains as a **concrete method** (not on the SPI) for
test cleanup, consistent with the hot reload spec (2026-06-26, §3).

### ReliableDroolsSessionStore (new)

`@ApplicationScoped` — lives in new `ras-drools-reliability` module. When this module
is on the classpath, `@DefaultBean` on `InMemoryDroolsSessionStore` yields automatically.
No `@Alternative`, no `@Priority`, no class loading tricks.

Two-layer cache:

1. **Hot cache** — `ConcurrentHashMap<DroolsSessionKey, StampedSession>`. Same as
   InMemory. Avoids replay cost on every `detect()` call.
2. **Persistent storage** — via H2MVStore shared storage:
   - `sessionIds: Storage<String, Long>` — `key.toStorageKey()` → `session.getIdentifier()`
   - `sessionGenerations: Storage<String, Long>` — `key.toStorageKey()` → generation at creation

**Startup generation reset:** On construction (`@PostConstruct`), clear the
`sessionGenerations` storage. The ganglion's `reloadGeneration` resets to 0 on JVM
restart (field initializer). If persisted generations from a previous JVM lifetime
were retained, sessions with generation > 0 would appear non-stale to the fresh
counter, preventing invalidation after reload. Clearing aligns persistent state with
the fresh generation counter. `sessionIds` is NOT cleared — session data must survive
for recovery.

```
@PostConstruct:
  sessionGenerations.clear()

computeIfAbsent(key, kieBase, config, generation):
  1. hot cache hit → check generation:
     if cached.generation < generation:
       dispose, remove from hot cache + persistent storage
       // fall through to create
     else: return cached.session

  2. persistent mapping hit → check persisted generation:
     Long savedId = sessionIds.get(key.toStorageKey())
     long savedGen = sessionGenerations.getOrDefault(key.toStorageKey(), 0L)
     if savedId != null:
       if savedGen < generation:
         // stale persistent session — remove and fall through to create
         removePersistedSession(key.toStorageKey(), savedId)
       else:
         // recover (with failure fallback):
         try:
           KieSessionConfiguration storeConfig = newKieSessionConfiguration()
           storeConfig.setOption(config.getOption(ClockTypeOption.class))
           storeConfig.setOption(PersistedSessionOption.fromSession(savedId)
               .withPersistenceStrategy(STORES_ONLY)
               .withSafepointStrategy(AFTER_FIRE)
               .withActivationStrategy(ACTIVATION_KEY))
           KieSession session = kieBase.newKieSession(storeConfig, null)
           → drools-reliability replays stored facts
           → store generation in sessionGenerations
           → cache in hot cache with generation, return
         catch RuntimeException:
           log.warn("Recovery failed for {}, creating fresh session", key)
           removePersistedSession(key.toStorageKey(), savedId)
           // fall through to create (step 3)

  3. full miss → create:
     KieSessionConfiguration storeConfig = newKieSessionConfiguration()
     storeConfig.setOption(config.getOption(ClockTypeOption.class))
     storeConfig.setOption(PersistedSessionOption.newSession()
         .withPersistenceStrategy(STORES_ONLY)
         .withSafepointStrategy(AFTER_FIRE)
         .withActivationStrategy(ACTIVATION_KEY))
     KieSession session = kieBase.newKieSession(storeConfig, null)
     → store session.getIdentifier() + generation in persistent storage
     → cache in hot cache with generation, return

remove(key):
  evict hot cache
  Long savedId = sessionIds.remove(key.toStorageKey())
  sessionGenerations.remove(key.toStorageKey())
  if savedId != null:
    StorageManagerFactory.get().getStorageManager()
        .removeStoragesBySessionId(String.valueOf(savedId))
```

**Recovery failure fallback:** If session recovery throws (corrupt H2MVStore data,
class deserialization failure, incompatible fact type after code change), the store
removes the corrupt persistent mapping and falls through to creating a fresh session.
Without this, every subsequent `computeIfAbsent` for the affected key would hit the
same corrupt `savedId` and fail permanently — a per-key failure loop with no
self-healing path.

**Config handling:** The `config` parameter is borrowed (read-only). The store creates
a fresh `KieSessionConfiguration` for each session, copying relevant options
(ClockTypeOption) from the caller's config and adding persistence-specific options.
This prevents mutation side-effects on the caller's config object.

**Persistence strategy:** `STORES_ONLY` — persists only inserted facts. On recovery,
facts are re-propagated through the rule network to rebuild internal state.
`PersistenceStrategy.FULL` is non-functional in Drools 10.1.0 — all FULL tests are
disabled with "ReliablePropagationList; no valid constructor" serialization errors.
Only STORES_ONLY works. Do not attempt to "upgrade" to FULL without first verifying
the fix has landed in the Drools version being used.

**Safepoint strategy:** `SafepointStrategy.AFTER_FIRE` — batches all insert/update/delete
operations and commits to H2MVStore once after `fireAllRules()` completes. This gives
one commit per `detect()` call rather than N+2+ commits (one per insert/delete).
`ALWAYS` (the default) would commit on every individual insert, creating a severe
performance bottleneck for events with multiple extractor objects. There is no durability
benefit to mid-detect commits — if the JVM crashes mid-detect, the entire detection is
lost regardless.

**Pseudo clock handling:** drools-reliability's `repropagateWithPseudoClock` advances
the clock to each stored event's timestamp during replay, then catches up to the
persisted time. This is built-in — no custom clock handling needed.

**Activation deduplication:** `ActivationStrategy.ACTIVATION_KEY` persists activation
keys so that rules which already fired before the crash don't re-fire on replay.

### H2MVStore operational configuration

- **File location:** H2MVStore writes to `h2mvstore.db` in the JVM working directory
  (drools-reliability default from `H2MVStoreStorageManager`). Override via the
  `drools.reliability.storage.location` system property.
- **Dev mode:** State persists across Quarkus dev-mode restarts within the same working
  directory. Use `-Ddrools.reliability.storage.location=/tmp/ras-sessions/` for isolation.
- **Cleanup:** `remove()` calls `removeStoragesBySessionId()` to clean MVMap entries.
  Crash-orphaned storage is tolerated at this scale — H2MVStore files are small for
  the session counts targeted by this experimental spec.
- **Single-process only:** H2MVStore file locking prevents concurrent JVM access.
  This is a stated scope exclusion.

### Recovery performance trade-off

With STORES_ONLY, every `session.insert()` persists a `StoredObject`. In the current
`detect()` flow, extractor objects are inserted but not explicitly deleted — only the
CloudEvent is deleted via `session.delete(ceHandle)`. For LONG_LIVED sessions, extractor
objects accumulate in storage across detect() calls.

After N events with M extractor objects each, recovery must re-propagate up to N×M
stored objects through the RETE network (less any that Drools has temporally expired
via `@expires` annotations — expired events are removed from storage through
`ObjectStore.removeHandle()`).

For most RAS use cases, this is bounded:
- Typical extractor count: 1–3 objects per event
- Temporal expiration reduces the stored set for event-typed extractors
- Session counts per ganglion are low (one per situation instance)

For ganglions with unbounded fact growth, the ganglion can capture extractor
`FactHandle`s and delete them after `fireAllRules()`, trading temporal reasoning
capability for bounded storage. This is a per-ganglion decision, not a store concern.

Full compaction/truncation strategies are deferred to the journal-based reliability
effort (casehubio/casehub-ras#29).

## Module Structure

New module `ras-drools-reliability` alongside `ras-drools`. Mirrors the established
pattern: `persistence-jpa/` (JpaSituationStore) and `persistence-memory/`
(InMemorySituationStore) as separate classpath-activated modules.

```
ras/
  ras-drools/              — DroolsSessionStore SPI + InMemoryDroolsSessionStore
  ras-drools-reliability/  — ReliableDroolsSessionStore (new)
  persistence-memory/      — InMemorySituationStore
  persistence-jpa/         — JpaSituationStore
  ...
```

### ras-drools-reliability/pom.xml

```xml
<dependencies>
    <dependency>
        <groupId>io.casehub</groupId>
        <artifactId>casehub-ras-drools</artifactId>
    </dependency>
    <dependency>
        <groupId>org.drools</groupId>
        <artifactId>drools-reliability-core</artifactId>
    </dependency>
    <dependency>
        <groupId>org.drools</groupId>
        <artifactId>drools-reliability-h2mvstore</artifactId>
    </dependency>
</dependencies>
```

All compile-scope (not optional). Version from existing `${version.drools}` property
(10.1.0) in the parent pom.

### File inventory

| File | Module | Action |
|------|--------|--------|
| `DroolsSessionKey.java` | ras-drools | New — shared key record with `toStorageKey()` |
| `DroolsSessionStore.java` | ras-drools | Modified — new SPI with generation parameter |
| `InMemoryDroolsSessionStore.java` | ras-drools | Modified — generation-aware `StampedSession` |
| `DroolsGanglion.java` | ras-drools | Modified — delegate lifecycle, keep `reloadGeneration` |
| `ReliableDroolsSessionStore.java` | ras-drools-reliability | New |
| `InMemoryDroolsSessionStoreTest.java` | ras-drools | Modified — new SPI |
| `ReliableDroolsSessionStoreTest.java` | ras-drools-reliability | New |
| `DroolsGanglionTest.java` | ras-drools | Modified — verify simplified detect() |
| `ras-drools-reliability/pom.xml` | ras-drools-reliability | New |

## Testing

### InMemoryDroolsSessionStoreTest (updated)

Same behavioral assertions, updated to new SPI:
- `computeIfAbsent` creates on first call, returns same on second (same generation)
- Different keys are independent
- Generation mismatch disposes old session and creates new
- `remove` disposes and evicts
- `removeAll` (concrete method) scoped to ganglionId

### ReliableDroolsSessionStoreTest (new)

1. **Create and retrieve** — `computeIfAbsent` creates session, second call returns same
2. **Restart survival** — insert a fact, clear hot cache (simulating restart via
   `StorageManager.restart()`), call `computeIfAbsent` → session recovered, fact present
3. **Pseudo clock survival** — advance clock, restart, verify clock position restored
4. **Generation invalidation** — create session at generation 0, call `computeIfAbsent`
   with generation 1 → old session disposed, new session created with new KieBase
5. **Generation invalidation across restart** — create at generation 0, restart, call
   `computeIfAbsent` with generation 1 → stale persistent entry removed, fresh session
6. **Cross-restart generation reset** — create at generation 5, simulate full restart
   (new store instance + `@PostConstruct`), call `computeIfAbsent` with generation 0 →
   recovery succeeds. Then call with generation 1 → session invalidated (not stuck at
   persisted generation 5)
7. **Recovery failure fallback** — corrupt the persistent session data, call
   `computeIfAbsent` → recovery fails → corrupt mapping removed → fresh session created
   successfully. Verify subsequent calls for the same key also succeed (no permanent
   failure loop)
8. **Remove cleans both layers** — remove key, restart, `computeIfAbsent` creates fresh
   (no stale recovery)
9. **Config not mutated** — verify caller's KieSessionConfiguration is unchanged after
   `computeIfAbsent`

### DroolsGanglionTest (updated)

Verify the detect() flow for both modes:
- LONG_LIVED: ganglion calls `computeIfAbsent` with current generation, no `get()`/`put()`
- EPHEMERAL: ganglion creates session directly, no store interaction
- Hot reload: `reload()` increments generation, next `detect()` for same key gets new session

## Scope exclusions

- **Production hardening** — no connection pooling, monitoring, or graceful degradation
  (casehubio/casehub-ras#28)
- **Alternative backends** — no JPA/JDBC backend; H2MVStore only
- **Journal-based reliability** — deferred to separate personal effort
  (casehubio/casehub-ras#29)
- **Clustered session sharing** — out of scope; H2MVStore is local-only
  (casehubio/casehub-ras#30)
- **Drools-reliability version tracking** — experimental API may change; accepted risk
  for temporary solution
