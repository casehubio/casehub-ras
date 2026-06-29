# Duplicate Trigger Prevention + SituationStore Contract Test

**Issues:** #19 (duplicate case trigger prevention), #17 (AbstractSituationStoreContractTest)
**Date:** 2026-06-29
**Branch:** `issue-19-duplicate-trigger-contract-test`

## Problem

### #19 — Duplicate case trigger in clustered deployment

In `SituationEvaluator.executeDecision()`, the CREATE_CASE path fires the trigger
then removes the situation. In a clustered deployment, two JVMs processing concurrent
events for the same situation instance can both evaluate to CREATE_CASE and both call
`caseTrigger.fire()` before either removes the situation — producing a duplicate case.

The CONTINUE_ACCUMULATING path is already protected by OCC (`@Version` + `SituationConflictException`
from #18). The CREATE_CASE path bypasses this — it calls `store.remove()` (bulk JPQL DELETE,
no version check), so no `SituationConflictException` is thrown and no retry is triggered.

### #17 — Overlapping test suites with no shared contract

`InMemorySituationStoreTest` (10 tests, plain JUnit) and `JpaSituationStoreTest` (12 tests,
@QuarkusTest) have ~10 overlapping behavioral tests and ~6 JPA-specific tests. No shared
contract test exists to enforce behavioral parity — unlike `AbstractGanglionContractTest`
which is already published in the api/ test-jar.

## Design

### Part 1: AbstractSituationStoreContractTest (#17)

**Location:** `api/src/test/java/io/casehub/ras/api/AbstractSituationStoreContractTest.java`
Published via existing test-jar configuration (maven-jar-plugin test-jar goal in api/pom.xml).

**Structure:**

```java
public abstract class AbstractSituationStoreContractTest {
    protected SituationStore store;
    protected abstract SituationStore createStore();

    @BeforeEach
    void setUpStore() {
        store = createStore();
    }
}
```

JUnit 5 runs superclass `@BeforeEach` before subclass `@BeforeEach`. JPA subclass adds
DB cleanup after store is set.

**Shared tests (12 contractual behaviors):**

| # | Test | Verifies |
|---|------|----------|
| 1 | `findReturnsEmptyWhenNotPresent` | Lookup non-existent key → empty Optional |
| 2 | `saveAndFindRoundTrip` | Save initial context, find, verify six domain fields (`situationId`, `correlationKey`, `tenancyId`, `firstSignal`, `lastSignal`, `detections`) individually + assert `storeVersion` is present (exact value is implementation-specific — the bifurcated claim path depends on `storeVersion.isPresent()` to distinguish new from existing entities) |
| 3 | `saveUpdatesExisting` | Save, find, add detection, save, find — verifies upsert with find-between-saves pattern |
| 4 | `tenantIsolation` | Same situationId/correlationKey, different tenancyId — isolated |
| 5 | `correlationKeyIsolation` | Same situationId/tenancyId, different correlationKey — isolated |
| 6 | `removeDeletesEntry` | Save, remove, find → empty |
| 7 | `removeNonExistentIsNoOp` | Remove non-existent key → no exception |
| 8 | `removeExpiredEvictsOldEntries` | Old entries removed, recent entries survive |
| 9 | `removeExpiredIsCrossTenant` | Expiry is not tenant-scoped |
| 10 | `tryClaimTriggerSucceedsAfterSave` | Save entity → tryClaimTrigger → true |
| 11 | `tryClaimTriggerBlocksSecondClaim` | Save → claim → claim again → false |
| 12 | `resetTriggerClaimAllowsReClaim` | Save → claim → reset → claim → true |

**Anonymous-implementation verification (per PP-20260513-2ce9e1):** A separate test in
`api/src/test/` creates `new SituationStore() { /* only find/save/remove/removeExpired */ }`
and calls `tryClaimTrigger()` and `resetTriggerClaim()`, verifying both defaults compile and
return correct values. This proves the default methods live on the interface itself, not on
any concrete implementation.

**Test 3 design choice:** Uses find-between-saves pattern (`save → find → withDetection → save`)
rather than the InMemory shortcut (`save → withDetection → save`). This is the correct contractual
pattern because JPA's OCC requires the storeVersion from `find()`. InMemory also works with
this pattern — it ignores versions.

**InMemorySituationStoreTest:** Extends contract test. `createStore()` returns
`new InMemorySituationStore()` — fresh instance per test, clean state. All 12 shared tests
pass, including the claim tests (InMemory overrides with `ConcurrentHashMap`-based claim).

**JpaSituationStoreTest:** Extends contract test. `createStore()` returns injected CDI bean.
`@BeforeEach` cleanup via `removeExpired(FAR_FUTURE)`. Retains JPA-specific tests:

- `detectionsJsonRoundTrip` — JSONB serialization of multiple signals/evidence types
- `findPopulatesStoreVersion` — storeVersion set from entity @Version
- `saveIncrementsStoreVersion` — version bumps on update
- `saveThrowsConflictOnStaleVersion` — OCC conflict detection
- `saveThrowsConflictWhenEntityCreatedByAnotherWriter` — concurrent insert detection
- `saveThrowsConflictWhenEntityRemovedByAnotherWriter` — concurrent delete detection
- `tryClaimTriggerReturnsFalseForMissingSituation` — no entity row → false (JPA-specific: InMemory's claim is separate from entity existence)

### Part 2: SituationStore SPI Evolution (#19)

**Two new default methods on `SituationStore`:**

```java
default Uni<Boolean> tryClaimTrigger(String situationId, String correlationKey,
                                      String tenancyId) {
    return Uni.createFrom().item(true);
}

default Uni<Void> resetTriggerClaim(String situationId, String correlationKey,
                                     String tenancyId) {
    return Uni.createFrom().voidItem();
}
```

**Contract:**
- `tryClaimTrigger`: Atomically attempt to claim the trigger for this situation instance.
  Returns true if this caller won, false if already claimed. Default returns true — correct
  for single-JVM deployments where the evaluator's synchronized block prevents concurrent access.
- `resetTriggerClaim`: Reset the claim so the next event can re-trigger. Called only on trigger
  failure. Default is no-op.

**Why default methods:** Per `spi-evolution-default-methods` protocol. The defaults
(always-true claim, no-op reset) are safe for future third-party implementations. Both
`InMemorySituationStore` and `JpaSituationStore` override with real claim mechanisms —
InMemory uses a `ConcurrentHashMap<SituationKey, Boolean>`, JPA uses a conditional
JPQL UPDATE on the `policyTriggered` column.

### InMemorySituationStore Claim Implementation

**Why InMemory needs a real claim:** With deferred removal (entity stays after trigger),
the default `tryClaimTrigger` (always returns true) provides no guard against re-triggering.
Every post-trigger event would re-evaluate to CREATE_CASE and fire again — changing
Count/And/Threshold semantics from "fire once per cycle" to "fire on every event after
threshold."

**Implementation:**

```java
private final ConcurrentHashMap<SituationKey, Boolean> claims = new ConcurrentHashMap<>();

@Override
public Uni<Boolean> tryClaimTrigger(String situationId, String correlationKey, String tenancyId) {
    return Uni.createFrom().item(
        claims.putIfAbsent(new SituationKey(situationId, correlationKey, tenancyId),
                           Boolean.TRUE) == null);
}

@Override
public Uni<Void> resetTriggerClaim(String situationId, String correlationKey, String tenancyId) {
    claims.remove(new SituationKey(situationId, correlationKey, tenancyId));
    return Uni.createFrom().voidItem();
}
```

**Claim lifecycle with entity operations:** `remove()` and `removeExpired()` clear claims
for removed entries. When an entity is removed (DISCARD, expiry), the situation cycle is
over and the next cycle starts with a clean claim.

**storeVersion population:** `save()` populates `storeVersion` with `OptionalLong.of(version)`
(incrementing counter). This enables the evaluator's bifurcated claim path (§Part 4) to
distinguish new entities (storeVersion empty) from existing ones (storeVersion present)
across both store implementations. InMemory does not CHECK versions (no OCC needed) — it
only PROVIDES them.

**Why NOT on SituationContext:** `policyTriggered` is a coordination mechanism (preventing
duplicate side effects), not a domain state (describing detection state). The conditional UPDATE
operates independently of the find→modify→save cycle. Unlike `storeVersion` (which must flow
through save for OCC), the claim is a separate atomic store operation. Adding it to
SituationContext would mix operational concerns into the domain model and break all construction
sites for no architectural benefit.

### Part 3: JpaSituationStore Claim Implementation

**SituationEntity change:**

```java
@Column(name = "policy_triggered", nullable = false)
private boolean policyTriggered = false;
```

**Flyway migration V3:**

```sql
ALTER TABLE ras_situation ADD COLUMN policy_triggered BOOLEAN NOT NULL DEFAULT false;
```

**JpaSituationStore overrides:**

`tryClaimTrigger`: `@Transactional(TxType.REQUIRED)`. Conditional JPQL UPDATE — `SET
policyTriggered = true WHERE ... AND policyTriggered = false`. Returns `updated > 0`.

`resetTriggerClaim`: `@Transactional(TxType.REQUIRED)`. JPQL UPDATE — `SET policyTriggered =
false WHERE ...`.

Both methods carry `@Transactional(TxType.REQUIRED)`, consistent with all existing
`JpaSituationStore` methods. The claim must commit in its own transaction before
`caseTrigger.fire()` is called — since `SituationEvaluator` is not `@Transactional`, each
store method starts and commits its own transaction.

**Concurrency correctness under READ COMMITTED:** The conditional UPDATE acquires a row-level
lock. A concurrent UPDATE on the same row blocks until the first transaction commits. After
commit, the second re-evaluates the WHERE clause, sees `policyTriggered = true`, affects 0
rows → returns false. Exactly-once semantics.

**JPQL UPDATE bypasses @Version:** The claim does NOT increment the entity's @Version column.
This is deliberate — the claim path (`policyTriggered`) and the save path (`firstSignal`,
`lastSignal`, `detections`, `version`) are independent and don't interfere. `updateEntity()`
never touches `policyTriggered`; the claim never touches domain fields.

**JPA-specific claim test:**
- `tryClaimTriggerReturnsFalseForMissingSituation` — no entity row → false (JPA's
  conditional JPQL UPDATE requires an existing row; InMemory's ConcurrentHashMap claim
  operates independently of entity existence)

### Part 4: SituationEvaluator CREATE_CASE Path

**New flow (bifurcated claim path):**

```
CREATE_CASE:
  if storeVersion present (entity exists):
    tryClaimTrigger → false → return true (terminated — no save, no lastSignal refresh)
                    → true → save(context) → fire → closeGanglia → return true
                                                                    (no remove — entity stays as guard)
                           → save throws SituationConflictException: resetTriggerClaim → rethrow (retry loop)
                           → fire fails: resetTriggerClaim → return false
  else (new entity, storeVersion empty):
    save(context) → creates entity
    tryClaimTrigger → false → return true (terminated)
                    → true → fire → closeGanglia → return true
                           → fire fails: resetTriggerClaim → return false
```

**Why bifurcated:** The claim path depends on whether an entity row exists.

For **new entities** (storeVersion empty): save-before-claim is required. JPA's conditional
JPQL UPDATE needs an existing row to lock and update. For first-event CREATE_CASE scenarios
(single-ganglion OR mode, Count(g,1), post-expiry triggers), no entity exists — `loadContext()`
returned `SituationContext.initial()`. Saving first creates the entity, enabling the atomic
claim. The `save()` participates in the existing OCC retry loop — if it throws
`SituationConflictException` (concurrent insert or stale version), the retry re-reads and
retries, exactly as in the CONTINUE_ACCUMULATING path.

For **existing entities** (storeVersion present): claim-before-save avoids the lastSignal
refresh problem. With save-before-claim, every post-trigger event updates `lastSignal` via
`SituationContext.withDetection()` and persists it via `SituationMapper.updateEntity()`. This
prevents expiry for continuous event streams — `isExpired()` and `removeExpired(cutoff)` both
check `lastSignal`, so a continuously-refreshed entity never expires. Claiming first and
skipping the save when the claim fails avoids this: the entity retains its original `lastSignal`
and expires on schedule.

**Why deferred removal (no `store.remove()` on success):** The `policyTriggered` claim lives
on `SituationEntity`. If the winner removes the entity after firing, a retrying loser that
re-creates the entity gets a fresh claim (`policyTriggered = false`) and fires a duplicate
trigger. Deferring removal preserves the claim as a guard: the loser's retry finds the
entity with `policyTriggered = true`, and the claim fails. Cleanup is handled by existing
expiry mechanisms:
- **Windowed situations:** `loadContext()` checks `isExpired()` and removes stale entities on
  the next event after the correlation window passes. `SituationExpiryJob.cleanup()` removes
  via `removeExpired(cutoff)` in the background.
- **Persistent situations:** No automatic cleanup — see #21.

**Edge cases:**

1. **Claim fails (another JVM won):** Return true (terminated). The situation cycle is
   complete — the winner has claimed and fired the trigger. Returning true allows
   `evaluate()` to clean up in-memory resources (per-key lock and `EventReorderBuffer`).
   For existing entities (storeVersion present), no save occurs — `lastSignal` is not
   refreshed, and expiry timing is preserved. For new entities (storeVersion empty), the
   save already occurred (to create the entity for the claim); this is the first-event case
   where lastSignal is the current event time anyway.

2. **Trigger failure after claim:** Reset claim via `resetTriggerClaim`. For existing entities
   (claim-before-save), the save occurs between claim and fire — context is persisted. For
   new entities (save-before-claim), context is already persisted. In both cases, the entity
   remains with `policyTriggered = false` (after reset), ready for the next event to re-trigger.

2a. **Claim succeeds, then save fails (existing entity only):** The claim was set
   (`policyTriggered = true`) in its own transaction, but the subsequent save throws
   `SituationConflictException` (version mismatch from a concurrent writer). Reset the claim
   via `resetTriggerClaim` before rethrowing. The retry loop re-reads the context, re-applies
   detections, re-evaluates, and re-attempts the claim. Between reset and retry, another JVM
   may claim — exactly-one semantics are preserved.

3. **`resetTriggerClaim` failure:** Claim stays set. Log as SEVERE. Do NOT attempt to save the
   context — the situation is in an inconsistent state and saving could mask the underlying DB
   connectivity problem. Recovery depends on situation type:
   - **Windowed situations** (`correlationWindow != null`): `SituationExpiryJob.cleanup()` will
     eventually evict the stuck entity via `removeExpired()`.
   - **Persistent situations** (`correlationWindow = null`): No automatic cleanup.
     `SituationExpiryJob.cleanup()` returns immediately when `registry.maxCorrelationWindow()`
     is null. The situation is permanently stuck — manual intervention required. See #21.

4. **Situation removed between detection and save:** The `save()` detects the conflict:
   if the context has a `storeVersion` from the detection-phase `find()` but the entity no
   longer exists, `save()` throws `SituationConflictException` → caught by the retry loop.
   On retry, `find()` returns empty → fresh context → detections applied → re-evaluated.

5. **Post-trigger entity lifecycle:** Entity removal is deferred — the entity stays in the
   DB with `policyTriggered = true` after successful trigger. This is intentional: the entity
   acts as a guard against duplicate triggers from retrying losers. Cleanup:
   - **Windowed situations:** `loadContext()` removes expired entities on the next event.
     `SituationExpiryJob.cleanup()` removes in the background via `removeExpired(cutoff)`.
   - **Persistent situations:** Same as edge case 3 — see #21.
   Events arriving within the correlation window after trigger find the claimed entity.
   The bifurcated claim path (storeVersion present → claim first) means `tryClaimTrigger`
   returns false with NO save — `lastSignal` is not refreshed. This ensures the entity
   expires on schedule. After the window passes, the entity is removed → new cycle starts.
   For InMemory, `remove()` and `removeExpired()` also clear the claim from the
   `ConcurrentHashMap`, enabling a fresh cycle.

**SituationEvaluator tests to add:**

| Test | Verifies |
|------|----------|
| `firstEventCreateCaseSavesAndClaims` | First event (storeVersion empty), OR mode → save-before-claim → claim succeeds → trigger fires → entity stays (no remove) |
| `claimPreventsDuplicateTrigger` | tryClaimTrigger returns false on second call → fire called once |
| `existingEntityClaimFailsNoSave` | Existing entity (storeVersion present), claim fails → return true (no save, lastSignal unchanged) |
| `triggerFailureAfterClaimResets` | Trigger throws → resetTriggerClaim called, entity retained with policyTriggered=false |
| `triggerFailureRecoveryOnNextEvent` | Full cycle: fail → reset → next event claims → succeeds |
| `retryEscalatesToCreateCaseWithClaimFailure` | Conflict on save (CONTINUE_ACCUMULATING), retry re-evaluates to CREATE_CASE, claim fails → no trigger fired |
| `retryAfterWinnerCompletesFindsClaimedEntity` | Winner claims + fires (entity stays). Loser retries: loadContext finds claimed entity → tryClaimTrigger false → no save → no duplicate |
| `saveBeforeClaimConcurrentInsertRetriesSuccessfully` | save-before-claim throws SituationConflictException on concurrent insert → retry re-reads → claim succeeds |
| `claimSucceedsSaveFailsResetsAndRetries` | Existing entity: claim → true → save throws SituationConflictException → resetTriggerClaim → retry succeeds |
| `postTriggerEventsDoNotRefreshLastSignal` | After successful trigger, subsequent events for the same situation do not update lastSignal in the store |

## Implementation Order

1. **AbstractSituationStoreContractTest** — create contract test in api/, migrate
   InMemorySituationStoreTest and JpaSituationStoreTest to extend it
2. **SituationStore SPI** — add `tryClaimTrigger`/`resetTriggerClaim` default methods
3. **Anonymous-implementation verification test** — per PP-20260513-2ce9e1, verify default
   methods compile and return correct values on a raw `new SituationStore() { ... }` instance
4. **InMemorySituationStore** — override `tryClaimTrigger`/`resetTriggerClaim` with
   `ConcurrentHashMap`-based claim; populate `storeVersion` on save; clear claims in
   `remove()`/`removeExpired()`
5. **Contract test update** — add tests 10–12 (claim behavioral tests: succeed, block, reset)
6. **SituationEntity + migration** — add `policy_triggered` column
7. **JpaSituationStore** — override claim methods with conditional JPQL UPDATE
8. **JPA-specific tests** — `tryClaimTriggerReturnsFalseForMissingSituation`
9. **SituationEvaluator** — modify CREATE_CASE path (bifurcated claim, deferred removal)
10. **SituationEvaluator tests** — claim coordination tests (10 tests), update 3 existing
    tests that asserted entity removal after trigger
11. **CLAUDE.md update** — document new SPI methods

## References

- Garden: GE-20260512-e3e525 (OCC + policyTriggered flag pattern)
- Protocol: `spi-evolution-default-methods` — default method SPI evolution
- Protocol: `no-workarounds-fix-the-design` — no backward-compatibility shims
- Protocol: `platform-spi-contract` — SPI implementation patterns
- Spec: `2026-06-29-clustered-retry-logic-design.md` — #18 OCC infrastructure this builds on
