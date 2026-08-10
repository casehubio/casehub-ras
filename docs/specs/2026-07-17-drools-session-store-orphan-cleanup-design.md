# DroolsSessionStore Orphaned Session Cleanup

**Issue:** casehubio/casehub-ras#39 (supersedes #38)
**Date:** 2026-07-17

## Problem

`SituationExpiryJob.cleanup()` removes expired and triggered situations via bulk DELETE
(`removeExpired`, `removeTriggeredBefore`) but has no reference to `DroolsSessionStore`.
With `ReliableDroolsSessionStore`, orphaned H2MVStore entries (session IDs, generations,
and Drools persistence data) accumulate indefinitely.

The same gap exists for any removal path — deregistration via
`SituationDefinitionRegistry.deregister()`, manual `SituationStore.remove()`, or crash
recovery. No safety net catches orphans regardless of how the situation was removed.

**Relationship to #38:** Issue #38 proposed `removeOrphaned()` directly on
`DroolsSessionStore`. This spec takes a different approach — a generic
`OrphanedResourceCleaner` SPI — which also consolidates the existing
`GanglionStateStore.removeOrphaned()` under the same mechanism. #39 supersedes #38.

## Design Constraints

1. **Module dependency direction:** `SituationExpiryJob` is in `runtime/`. `DroolsSessionStore`
   is in `ras-drools/`. `runtime/` does NOT depend on `ras-drools/`. The job cannot call
   `DroolsSessionStore` directly.

2. **Storage heterogeneity:** `ReliableDroolsSessionStore` uses H2MVStore (key-value file store),
   not the same database as `JpaSituationStore`. SQL joins are not possible.

3. **SPI abstraction:** The solution must not leak Drools or H2MVStore details into `api/` or
   `runtime/`.

## Design

### New SPI: `OrphanedResourceCleaner` (api/)

```java
package io.casehub.ras.api;

import io.smallrye.mutiny.Uni;

public interface OrphanedResourceCleaner {
    String cleanerType();
    Uni<Integer> removeOrphaned();
}
```

A generic SPI for cleaning up derived resources whose parent situation no longer exists.
Implementations are discovered via CDI `Instance<OrphanedResourceCleaner>`.

**Why a new interface rather than extending existing types:**

- **Not on `Ganglion`:** ganglia aren't necessarily CDI beans (`DroolsGanglion` is created
  programmatically). CDI discovery wouldn't find them.
- **Not on `DroolsSessionStore`:** lives in `ras-drools/`, which `runtime/` doesn't depend on.
  `SituationExpiryJob` can't inject it.
- **In `api/`:** `runtime/`, `persistence-jpa/`, and `drools-reliability/` all depend on `api/`.
  CDI discovers implementations automatically.

### Consolidation: `GanglionStateStore.removeOrphaned()` migration

`GanglionStateStore.removeOrphaned()` (default method returning 0) is removed from the
interface. `JpaGanglionStateStore` implements `OrphanedResourceCleaner` instead, preserving
its existing SQL-based orphan cleanup logic. `InMemoryGanglionStateStore` does not implement
`OrphanedResourceCleaner` — its default was a no-op.

This eliminates the dual-pattern problem: `SituationExpiryJob.cleanup()` previously called
`ganglionStateStore.removeOrphaned()` directly and would separately iterate
`Instance<OrphanedResourceCleaner>`. Now all orphan cleanup goes through the single CDI
discovery loop.

**`JpaGanglionStateStore` additions:**

```java
@Override
public String cleanerType() {
    return "ganglion_state";
}

@Override
@Transactional(TxType.REQUIRED)
public Uni<Integer> removeOrphaned() {
    // existing SQL implementation unchanged
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
```

### Implementation: `ReliableDroolsSessionStore` (drools-reliability/)

`ReliableDroolsSessionStore` implements `OrphanedResourceCleaner` in addition to
`DroolsSessionStore`.

New CDI dependency: `SituationStore` (from `api/`, already on the dependency path via
`ras-drools/` → `api/`).

**Shutdown guard:** A `volatile boolean closed` flag is set in `@PreDestroy` and checked at
the start of `removeOrphaned()`. This prevents H2MVStore operations after store shutdown,
addressing the read/write asymmetry concern from GE-20260710-86e8d3. While Quarkus scheduler
lifecycle ensures `@Scheduled` jobs complete before `@PreDestroy`, the guard provides defense
in depth against edge cases (e.g., direct CDI calls during shutdown).

```java
private volatile boolean closed = false;

@PreDestroy
void destroy() {
    closed = true;
    int count = hotCache.size();
    hotCache.clear();
    log.info("DroolsSessionStore shutdown: {} sessions released from hot cache (persisted data preserved)", count);
}

@Override
public String cleanerType() {
    return "drools_session";
}

@Override
public Uni<Integer> removeOrphaned() {
    if (closed) {
        return Uni.createFrom().item(0);
    }
    int removed = 0;
    List<String> storageKeys = new ArrayList<>(sessionIds.keySet());
    for (String storageKey : storageKeys) {
        try {
            DroolsSessionKey key = DroolsSessionKey.fromStorageKey(storageKey);
            Optional<SituationContext> ctx = situationStore
                    .find(key.situationId(), key.correlationKey(), key.tenancyId())
                    .await().indefinitely();
            if (ctx.isEmpty()) {
                remove(key);
                removed++;
            }
        } catch (Exception e) {
            log.warn("Orphan cleanup failed for key '{}', skipping", storageKey, e);
        }
    }
    return Uni.createFrom().item(removed);
}
```

**Error isolation:** Each key's existence check and removal is wrapped in try-catch. One
failure (e.g., `SituationStore.find()` throws, malformed key, H2MVStore write failure) is
logged and skipped — remaining keys still process.

### DroolsSessionKey.fromStorageKey() (ras-drools/)

New static factory method on `DroolsSessionKey` to parse the `ganglionId|situationId|correlationKey|tenancyId`
format back into a key record. The inverse of `toStorageKey()`.

**Invariant:** Key field values (`ganglionId`, `situationId`, `correlationKey`, `tenancyId`)
must not contain the `|` delimiter character. All current field sources are identifiers
(ganglion class names, situation definition IDs, correlation keys, tenancy IDs) that do not
contain `|`.

```java
public static DroolsSessionKey fromStorageKey(String storageKey) {
    String[] parts = storageKey.split("\\|", 4);
    if (parts.length < 4) {
        throw new IllegalArgumentException(
                "Malformed storage key (expected 4 '|'-separated parts): " + storageKey);
    }
    return new DroolsSessionKey(parts[0], parts[1], parts[2], parts[3]);
}
```

### Invocation: `SituationExpiryJob` (runtime/)

```java
@Inject
Instance<OrphanedResourceCleaner> resourceCleaners;

// In cleanup(), replacing the direct ganglionStateStore.removeOrphaned() call:
for (OrphanedResourceCleaner cleaner : resourceCleaners) {
    try {
        int cleaned = cleaner.removeOrphaned().await().indefinitely();
        metrics.orphanedResourcesCleaned(cleaned, cleaner.cleanerType());
    } catch (Exception e) {
        log.warn("Orphan cleaner '{}' failed, skipping", cleaner.cleanerType(), e);
    }
}
```

**Per-cleaner error isolation:** Each cleaner invocation is wrapped in try-catch. A failure in
one cleaner (e.g., SQL exception in `JpaGanglionStateStore`) does not prevent other cleaners
from running. This mirrors the per-key isolation within `ReliableDroolsSessionStore.removeOrphaned()`.

The `GanglionStateStore` injection is removed from `SituationExpiryJob` — its only usage was
`removeOrphaned()`, which is now handled through the `OrphanedResourceCleaner` loop.

When only `persistence-jpa` is on the classpath, the `JpaGanglionStateStore` cleaner runs.
When `drools-reliability` is also present, both cleaners run.

### Metrics: `RasMetrics` (runtime/)

New metric: `ras.expiry.orphans_cleaned` (counter) tagged by `cleaner_type`. Replaces the
existing `ras.expiry.ganglion_state_orphans_cleaned` counter, which is removed.

```java
public void orphanedResourcesCleaned(int count, String cleanerType) {
    counterBy("ras.expiry.orphans_cleaned", count, "cleaner_type", cleanerType);
}
```

Tag values: `ganglion_state` (from `JpaGanglionStateStore`), `drools_session` (from
`ReliableDroolsSessionStore`). The existing `orphanedGanglionStateCleaned()` method is
removed.

### `InMemoryDroolsSessionStore` (ras-drools/)

Does not implement `OrphanedResourceCleaner`. Entries are ephemeral (lost on restart) and
the store is dev/test only (`@DefaultBean`). Memory leak exists at runtime but is acceptable
for the use case. Can be added later if needed.

## What Does Not Change

- `DroolsSessionStore` interface — no new methods. `removeOrphaned()` lives on
  `OrphanedResourceCleaner`, not on `DroolsSessionStore`.
- `DroolsGanglion.close()` — still the per-situation cleanup path for normal lifecycle.
  `OrphanedResourceCleaner` is the safety net for cases where `close()` was never called.
- `SituationStore` API — no new methods needed. `find()` is sufficient for existence checks.

## Performance

- Runs in `SituationExpiryJob` every 5 minutes (existing schedule), after situation cleanup
- O(stored_sessions) with one `SituationStore.find()` per key for the Drools cleaner
- O(1) SQL DELETE for the ganglion state cleaner (unchanged)
- Typical deployments: 10–100 active sessions → completes in milliseconds
- No hot-path impact — event processing is untouched
- When `drools-reliability` is absent: only ganglion state cleaner runs

## Test Plan

1. **Contract test for `OrphanedResourceCleaner`:** abstract test in `api/` test-jar verifying
   that implementations remove entries whose situation doesn't exist and preserve entries
   whose situation does exist.
2. **`ReliableDroolsSessionStore` orphan cleanup:** create sessions, remove their situations
   from the store, call `removeOrphaned()`, verify sessions are gone and count is correct.
3. **Error isolation:** mock `SituationStore.find()` to throw for one key, verify other keys
   still get cleaned up.
4. **Shutdown guard:** verify `removeOrphaned()` returns 0 after `destroy()` is called.
5. **`DroolsSessionKey.fromStorageKey()`:** round-trip test — `fromStorageKey(key.toStorageKey()) == key`.
   Validation test — malformed key throws `IllegalArgumentException` with informative message.
6. **`JpaGanglionStateStore` as `OrphanedResourceCleaner`:** existing `removeOrphaned()` test
   migrated to verify the `OrphanedResourceCleaner` contract.
7. **`SituationExpiryJob` integration:** verify all `OrphanedResourceCleaner` implementations are
   discovered and called during `cleanup()`, with per-cleaner metrics emitted.
8. **Per-cleaner error isolation:** register two cleaners, first throws — verify second still runs.
9. **Metric migration:** verify `ras.expiry.orphans_cleaned` counter tagged by `cleaner_type`
   replaces the removed `ras.expiry.ganglion_state_orphans_cleaned`.

## Garden Entries

- **GE-20260710-86e8d3:** H2MVStore read/write asymmetry after close() — addressed by
  `volatile boolean closed` shutdown guard in `removeOrphaned()`.
