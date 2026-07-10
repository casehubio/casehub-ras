# DroolsSessionStore Production Hardening Design

**Issue:** casehubio/casehub-ras#28
**Date:** 2026-07-09
**Status:** Draft

## Context

`ReliableDroolsSessionStore` is the persistent `DroolsSessionStore` implementation backed by
drools-reliability + H2MVStore. It was introduced as experimental in #7. The drools-reliability
layer is temporary — it will be replaced by journal-based reliability (#29) or clustered session
sharing (#30). This hardening adds pragmatic production observability and error handling without
over-investing in infrastructure that gets replaced.

The project currently has zero Micrometer or health check dependencies.

### Garden Context

Five garden entries inform this design (all Drools 10.1.0):

- **GE-20260706-0a5c4e:** Session ID counter resets to 0 after `TestableStorageManager.restart()` — causes silent recovery failures. Counter refresh required.
- **GE-20260706-27365d:** `PersistenceStrategy.FULL` is non-functional — only `STORES_ONLY` works.
- **GE-20260706-7ac642:** drools-reliability requires three factory initializations — only one documented.
- **GE-20260706-d02c71:** `ReliableKieSession.dispose()` removes persisted data, not just in-memory state.
- **GE-20260621-069194:** `KieHelper` removed from Drools 10 — no replacement utility class.

## Scope

**In scope:**
1. Dependency strategy — annotation-only library dependencies per protocol PP-20260604-88f660
2. Inline instrumentation — counters, gauge, timer in `ReliableDroolsSessionStore`
3. Fail-fast error handling — typed exception, nuanced response by failure category
4. Resource management — `@PostConstruct`/`@PreDestroy` hardening with logging
5. Health check — readiness probe in `drools-reliability` module

**Out of scope:**
- Connection pooling — permanently N/A for H2MVStore (embedded, no connections). Future backends
  (#29, #30) will address connection management if needed.
- Elaborate caching layer — deferred to replacement module (#29, #30)
- Metrics instrumentation in `runtime/` — tracked as #32
- File-corruption recovery — tracked as #33

**Issue #28 completion:** This spec delivers monitoring/metrics and graceful degradation — the two
applicable items from #28. Connection pooling is permanently N/A for H2MVStore. #28 can be closed
after implementation.

## Design

### 1. Dependency Strategy

Library JARs in this project depend on annotation-only libraries, not Quarkus extensions, per
protocol PP-20260604-88f660. The consuming Quarkus application provides the extensions that wire
up Prometheus scraping and `/q/health` endpoints.

| Need | Library JAR depends on | App provides |
|------|----------------------|--------------|
| Metrics | `io.micrometer:micrometer-core` | `quarkus-micrometer-registry-prometheus` |
| Health | `org.eclipse.microprofile.health:microprofile-health-api` | `quarkus-smallrye-health` |

No dependency changes to `runtime/` in this issue. Future instrumentation of `SituationEvaluator`
and other runtime components (#32) will add `micrometer-core` to `runtime/` directly.

### 2. Inline Instrumentation (`ReliableDroolsSessionStore`)

Inject `MeterRegistry` into `ReliableDroolsSessionStore` via `@Inject Instance<MeterRegistry>`.
`Instance.isResolvable()` guards all metric calls — the store works without Micrometer on the
classpath. Resolve once in `@PostConstruct` to a nullable field; null-check on each metric call.

**Counters:**

| Metric | Description | Tags |
|--------|-------------|------|
| `ras.drools.session.created` | New persisted session created | `ganglion_id` |
| `ras.drools.session.recovered` | Session recovered from H2MVStore | `ganglion_id` |
| `ras.drools.session.recovery_failed` | Recovery fell through to fresh session | `ganglion_id` |
| `ras.drools.session.removed` | Session explicitly removed | `ganglion_id` |
| `ras.drools.session.evicted` | Session replaced by generation invalidation | `ganglion_id` |
| `ras.drools.store.write_failed` | Storage write failed (session works but not durable) | `ganglion_id` |

**Gauge:**

| Metric | Description | Tags |
|--------|-------------|------|
| `ras.drools.session.active` | Current hot cache size | none (store-wide) |

**Timer:**

| Metric | Description | Tags |
|--------|-------------|------|
| `ras.drools.session.compute_time` | Duration of `computeIfAbsent` calls | `ganglion_id`, `outcome` (`hit`/`created`/`recovered`/`recovery_failed`) |

The `ganglion_id` tag is extracted from `DroolsSessionKey.ganglionId()`. The timer captures the
full cost including H2MVStore I/O for recovery.

### 3. Fail-Fast Error Handling

A new `DroolsSessionStoreException` (unchecked) in the `ras-drools` package — part of the SPI
contract since any `DroolsSessionStore` implementation could throw it.

Three failure categories with different responses:

| Failure | Current Behavior | New Behavior |
|---------|-----------------|--------------|
| **Recovery failure** (corrupt session) | Log warning, create fresh session | Unchanged. Increment `recovery_failed` counter. |
| **Storage write failure** (`sessionIds.put` / `sessionGenerations.put` throws) | Unhandled — exception propagates | Catch, log error, increment `store.write_failed` counter. Session is usable for this request but won't survive restart. Don't throw — detection can complete. |
| **Storage read failure** (`sessionIds.get` throws) | Unhandled — exception propagates | Catch in `computeIfAbsent`, wrap in `DroolsSessionStoreException`, rethrow. Can't proceed safely without knowing persisted state. |

**Storage read failure propagation — new code required:**

Currently, a `DroolsSessionStoreException` from `computeIfAbsent` propagates uncaught through
`DroolsGanglion.detect()` (the call is outside the existing try-catch block),
`SituationEvaluator.runDetection()` (no try-catch), and `processEvent()` (only catches
`SituationConflictException`). Two changes are needed:

1. **`DroolsGanglion.detect()`:** Add try-catch around the `computeIfAbsent` call. On
   `DroolsSessionStoreException`: attempt to remove the session key (prevents retrying corrupt
   recovery state), rethrow. The cleanup is itself wrapped defensively — if the storage backend
   is what's failing, `remove()` will also fail. The cleanup exception is added as a suppressed
   exception to preserve the original root cause:
   ```java
   try { sessionStore.remove(key); }
   catch (RuntimeException suppressed) { ex.addSuppressed(suppressed); }
   throw ex;
   ```

2. **`SituationEvaluator.runDetection()`:** Add per-ganglion try-catch around `ganglion.detect()`.
   On exception: log the failure, skip the failed ganglion, continue evaluation for remaining
   ganglia. Return partial results. One ganglion's storage failure must not kill evaluation for
   other ganglia (which may use in-memory stores or be ephemeral).

**Partial write tolerance:**

The two sequential writes in `computeIfAbsent` (`sessionIds.put` then `sessionGenerations.put`)
can fail independently. Both failure modes are bounded and self-healing:

- **sessionIds written, sessionGenerations not:** Recovery defaults to generation 0 — conservative,
  won't evict prematurely. Correct generation is written on the next successful `computeIfAbsent`.
- **sessionIds not written, sessionGenerations written:** Orphaned generation entry. Harmless —
  overwritten on next successful write for the same key.

Both writes are wrapped in a single try-catch. On failure: log error, increment
`store.write_failed` counter. No cleanup of partial writes — the bounded consequences
don't justify the complexity.

### 4. Resource Management

**`@PostConstruct` (`init()`):**
- Log INFO on success: store type, number of persisted session IDs found
- Log ERROR on failure before exception propagates
- No change to failure behavior — init failure prevents bean creation, app doesn't start (correct)

**`@PreDestroy` (`destroy()`):**
- Do NOT call `session.dispose()` — per GE-20260706-d02c71, `dispose()` removes persisted data
  from H2MVStore. Sessions should survive graceful restarts (rolling deploys, config changes).
- Do NOT call `StorageManager.close()` — `H2MVStoreStorageManager` is a static singleton
  (`static final INSTANCE`). Calling `close()` closes the underlying MVStore, but the singleton
  remains in `StorageManagerFactory.Holder.INSTANCE`. On Quarkus dev-mode restart (CDI tear-down
  and rebuild without JVM restart), `@PostConstruct` would call `getOrCreateSharedStorage()` on
  the closed singleton, throwing `IllegalStateException`. MVStore is crash-safe (write-ahead
  logging) and handles file cleanup via its own JVM shutdown hook.
- Clear the hot cache (releases in-memory references only — no persistence side effects)
- Log INFO: number of sessions in hot cache at shutdown

**Shutdown/restart behavior:** On graceful restart, sessions recover from persisted H2MVStore
state. `@PostConstruct` clears `sessionGenerations` — generation counters reset to 0 for all
recovered sessions, matching the initial `reloadGeneration` of 0 in `DroolsGanglion`. Crash
recovery follows the same path (hot cache lost, persisted data remains). The current
`destroy()` calls `dispose()` which creates the worst of both worlds: data is deleted but
`sessionIds` mappings linger, causing false `recovery_failed` spikes on every restart. This
design eliminates that by preserving data and recovering cleanly.

### 5. Health Check (`drools-reliability`)

**Class:** `ReliableDroolsSessionStoreHealthCheck` in `io.casehub.ras.drools.reliability`

**Annotation:** `@Readiness` — H2MVStore down stops traffic routing to the instance but doesn't
trigger restart.

**Dependency:** Add `org.eclipse.microprofile.health:microprofile-health-api` to
`drools-reliability/pom.xml` (annotation-only, per §1 dependency strategy).

**Activation:** By classpath presence, like the store itself.

**Probes:**
- `StorageManager` accessible — call `getOrCreateSharedStorage` with a probe key
- Hot cache initialized (store bean created)

**Cache size access:** Add package-private `int activeSessionCount()` method to
`ReliableDroolsSessionStore` — returns `hotCache.size()`. Both classes are in
`io.casehub.ras.drools.reliability`, so package-private visibility is sufficient.

**Probe key:** The storage probe key is `ras_drools_health_probe`. This creates a permanent
(but empty) MVMap in H2MVStore on first health check. The map is never written to —
`getOrCreateSharedStorage` with this key validates storage manager accessibility.

**Response:**
- **UP:** `{"store": "h2mvstore", "activeSessions": N}` — N from `activeSessionCount()`
- **DOWN:** `{"store": "h2mvstore", "error": "<exception message>"}`

**Injection:** `ReliableDroolsSessionStore` (for `activeSessionCount()`) + probes
`StorageManagerFactory` directly.

## Module Changes Summary

| Module | Changes |
|--------|---------|
| `ras-drools/` | Add `DroolsSessionStoreException` class. Add try-catch in `DroolsGanglion.detect()` around `computeIfAbsent`. |
| `drools-reliability/` | Add `io.micrometer:micrometer-core` + `org.eclipse.microprofile.health:microprofile-health-api` dependencies. Instrument `ReliableDroolsSessionStore` (metrics, error handling, lifecycle logging). Add `int activeSessionCount()`. Add `ReliableDroolsSessionStoreHealthCheck`. |
| `runtime/` | Add per-ganglion error isolation in `SituationEvaluator.runDetection()`. |

## Testing Strategy

- **Unit tests** for `DroolsSessionStoreException` construction and message formatting
- **`ReliableDroolsSessionStore` tests:** verify counters increment on create/recover/fail paths,
  verify timer records outcomes, verify `write_failed` counter on storage write error
  (inject a failing storage), verify `DroolsSessionStoreException` thrown on storage read error
- **Health check test:** verify UP when store healthy, DOWN when `StorageManager` probe throws
- **Lifecycle tests:** verify init logging, verify destroy does NOT call `dispose()`, verify
  destroy does NOT call `StorageManager.close()`
- **Graceful restart test:** create session → insert fact → fire rules → call `destroy()` →
  re-initialize store (`new ReliableDroolsSessionStore(); store.init()`) → `computeIfAbsent` for
  same key → verify session recovered with fact present. This tests the primary value proposition
  of the R1-04 change: sessions survive graceful restarts.
- **Ganglion isolation test:** verify `SituationEvaluator.runDetection()` continues evaluation
  for remaining ganglia when one ganglion's `detect()` throws
- **Defensive cleanup test:** verify that when `computeIfAbsent` throws
  `DroolsSessionStoreException` and `remove()` also fails, the original exception is preserved
  and the cleanup exception is added as suppressed
