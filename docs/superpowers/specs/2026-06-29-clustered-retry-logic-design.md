# Clustered Retry Logic — SituationEvaluator Conflict Handling

**Issue:** [#18](https://github.com/casehubio/casehub-ras/issues/18)
**Date:** 2026-06-29
**Status:** Design

## Problem

In a clustered deployment, concurrent event processing for the same situation instance
(situationId, correlationKey, tenancyId) from different JVMs causes two failures:

**Duplicate insert:** Both JVMs call `store.find()`, both get `Optional.empty()`, both call
`store.save()` → `em.persist()`. The second persist hits the unique constraint
`uk_ras_situation(situation_id, correlation_key, tenancy_id)`. The event is lost — no retry.

**Lost update:** Both JVMs read the same entity (e.g. with 2 detections). Both add their
detection (each now has 3, but different 3rd entries). Both call `store.save()` → update.
Last writer silently overwrites the other's detection. `SituationEntity` has no `@Version`
field, so Hibernate cannot detect this. Data loss is silent.

The per-key `synchronized` lock in `SituationEvaluator` serializes within a single JVM but
provides no protection across JVMs.

## Approach

Optimistic concurrency control via `@Version` on `SituationEntity`, with a domain exception
(`SituationConflictException`) propagated from the JPA store to the evaluator. The evaluator
restructures `processEvent()` into two phases: detect once (Phase 1), retry
read-modify-write on conflict (Phase 2).

## Design

### 1. SituationEntity — @Version field

```java
@Version
private Long version = 0L;
```

Hibernate generates `UPDATE ... SET version = version + 1 WHERE id = ? AND version = ?` on
every flush. If another transaction has incremented the version, the WHERE matches 0 rows →
`OptimisticLockException`.

Hibernate's `@Version` catches conflicts when two transactions load the same entity version
concurrently (overlapping save). For non-overlapping transactions — the common case when
detection takes time — an application-level version check is needed (§2).

**Flyway V2 migration:**

```sql
ALTER TABLE ras_situation ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
```

Existing rows get version 0 (Hibernate's starting value). Safe for running systems.

### 2. SituationContext — storeVersion field

`SituationContext` gains an `OptionalLong storeVersion` field:

- `SituationContext.initial()` creates with `OptionalLong.empty()` (no prior version).
- `SituationStore.find()` populates `storeVersion` from the entity's `@Version` value.
- `withDetection()` preserves `storeVersion` through transformations.

A store version is a coordination primitive, not persistence infrastructure. It tracks how many
times this record has been written, enabling conflict detection across the `find()`–`save()` gap.
`InMemorySituationStore` ignores it — JVM-level synchronization prevents concurrent access.

### 3. SituationConflictException

New class in `api/`:

```java
public class SituationConflictException extends RuntimeException {
    public SituationConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

Lives in api/ because both the thrower (persistence-jpa/) and the catcher (runtime/) depend
on api/. The `SituationStore.save()` contract gains a documented exception condition — no
signature change.

### 4. JpaSituationStore — conflict detection and wrapping

`save()` performs a two-layer conflict check:

**Layer 1 — Application-level storeVersion comparison:** Before updating, `save()` compares
the context's `storeVersion` against the entity's current `@Version` value. A mismatch means
a competing transaction committed between `find()` and `save()` — throw
`SituationConflictException` without attempting the update. This catches non-overlapping
transaction conflicts (the common case).

Version comparison logic:

- **Existing entity, storeVersion present:** compare values. Mismatch → conflict.
- **Existing entity, storeVersion empty:** entity was created by another JVM after our `find()`
  returned empty → conflict.
- **No entity, storeVersion empty:** normal persist path.
- **No entity, storeVersion present:** entity was removed by another JVM (CREATE_CASE or
  DISCARD) → conflict. The retry will re-read and handle the empty result.

**Layer 2 — Hibernate `@Version` + constraint violation:** After updating, explicit
`em.flush()` catches two exception families:

- `OptimisticLockException` → wraps in `SituationConflictException` (overlapping transactions
  that loaded the same version concurrently)
- `PersistenceException` with `ConstraintViolationException` in cause chain → wraps in
  `SituationConflictException` (duplicate insert race)

The `em.flush()` is necessary: without it, the exception fires at transaction commit time —
after `save()` has already returned `Uni.createFrom().voidItem()`. With flush, the exception
fires within the try-catch and can be converted to the domain exception.

When flush throws, the JTA transaction is marked rollback-only. This is correct — the
evaluator has no ambient transaction (not `@Transactional`), so each `save()` call via
`TxType.REQUIRED` starts its own transaction. The next retry gets a fresh transaction.

`find()` maps the entity's `@Version` value to `SituationContext.storeVersion()` via the
`SituationMapper`.

`remove()` is unchanged — bulk JPQL DELETE, no version check. Duplicate trigger prevention
in the CREATE_CASE path is a separate concern ([#19](https://github.com/casehubio/casehub-ras/issues/19)).

`InMemorySituationStore` is unchanged — the per-key `synchronized` lock in
`SituationEvaluator` already prevents concurrent access within a single JVM.

### 5. SituationEvaluator — two-phase processEvent with retry

**Phase 1 — Detect (executed once, never retried):**

1. Load context from store (or create initial)
2. Check expiry (reset if expired)
3. Run `ganglion.detect()` for each matching ganglion
4. Collect `DetectionResult` list

Detection is not retried because ganglia mutate internal state. `DroolsGanglion` inserts the
event fact into its KieSession and fires rules — re-detecting would double-fire.
`NaiveBayesGanglion` accumulates log-likelihoods into a mutable `double[]` array keyed by
situation instance — re-detecting would double-count features. The `DetectionResult` is the
ganglion's output: computed once, applied as many times as needed.

The detection is computed against a possibly-stale context (if the winner modifies it
concurrently). This is acceptable: for `JavaSwitchGanglion` (stateless, event-driven),
`NaiveBayesGanglion` (primarily event-driven), and `DroolsGanglion` (event-driven rules),
the detection is driven by the CloudEvent, not the accumulated context.

**Design invariant — DetectionResult portability:** The "detect once, apply many" design
requires that a `DetectionResult` remains valid when applied to a different `SituationContext`
than the one used during detection. All current ganglia satisfy this:

- `JavaSwitchGanglion`: detection decision driven by the `CloudEvent`
- `NaiveBayesGanglion`: features extracted from the `CloudEvent` only
- `DroolsGanglion`: `CloudEvent` inserted into KieSession, not context state

Future `Ganglion` implementations must not make detection decisions based on
`SituationContext.detections()` or other accumulated context fields, as these may differ
between detection time and application time. This constraint will be documented on the
`Ganglion` interface Javadoc.

**Phase 2 — Apply + persist (retried on conflict):**

1. On retry (attempt > 0): re-read context from store, re-check expiry
2. Apply pre-computed `DetectionResult` list via `context.withDetection()`
3. Re-evaluate trigger policy against the merged context
4. Execute decision:
   - `CONTINUE_ACCUMULATING`: compact ganglia (if no correlation window), then save
   - `CREATE_CASE`: fire trigger, close ganglia, remove
   - `DISCARD`: close ganglia, remove
5. On `SituationConflictException`: loop back to step 1

Compaction is re-run on each retry with the freshly-read context. `Ganglion.compact()` must
be a pure function of `SituationContext` — no ganglion-internal side effects. All current
implementations satisfy this: `NaiveBayesGanglion.compact()` keeps only the latest detection
per ganglion; `DroolsGanglion` and `JavaSwitchGanglion` use the default no-op.

Extracted helpers:

- `loadContext()` — find + expiry check + optional reset. Used by both Phase 1 (initial
  load) and Phase 2 retries.
- `runDetection()` — iterate matching ganglia, collect DetectionResults.
- `executeDecision()` — switch on TriggerDecision, including compaction in the
  CONTINUE_ACCUMULATING path.

**Retry edge cases:**

- **Winner removed the situation (CREATE_CASE or DISCARD):** On retry, `find()` returns
  empty → fresh initial context (storeVersion empty). The pre-computed detections are applied,
  policy re-evaluated. If CONTINUE_ACCUMULATING, a new situation instance is created. This is
  correct — the previous cycle was resolved; this event starts a new one.
- **Retries exhausted:** Log SEVERE with situation ID, event type, and correlation key.
  Return false (event is lost). Ganglia are NOT closed — the situation is still active in
  the database. The next successfully-processed event will use the existing ganglion state.
  Sustained contention beyond max retries indicates a systemic problem, not a transient race.

### 6. Configuration

```
ras.evaluator.max-conflict-retries=3
```

Injected via `@ConfigProperty` in `SituationEvaluator`. Default 3 — sufficient for
transient races; sustained contention requires architectural intervention (partitioning,
sticky routing) rather than more retries.

## What does NOT change

- `SituationStore` SPI — same interface methods, new documented exception
- `InMemorySituationStore` — no changes needed (ignores storeVersion)
- `SituationStore.remove()` — stays as bulk DELETE (no version check)
- `RasEngine` — unchanged entry point

## Out of scope

- **Duplicate case trigger prevention ([#19](https://github.com/casehubio/casehub-ras/issues/19)):**
  Two JVMs both evaluate CREATE_CASE and both call `caseTrigger.fire()` before either
  removes the situation. Needs a conditional claim pattern (policyTriggered flag per
  GE-20260512-e3e525). Depends on the `@Version` and `SituationConflictException`
  infrastructure introduced here.

  **Interaction with retry:** The retry mechanism introduces an additional path to duplicate
  triggers. JVM B's `save()` in the CONTINUE_ACCUMULATING path throws
  `SituationConflictException`. On retry, JVM B re-reads — if JVM A already did CREATE_CASE +
  `remove()`, JVM B finds empty, creates fresh context, applies detections, and may
  re-evaluate to CREATE_CASE. This produces a second trigger. The #19 conditional claim
  pattern will handle this path — the loser's claim fails, and it retries or no-ops.

## Testing strategy

**Evaluator retry (unit tests with InMemorySituationStore):**

A decorator store that throws `SituationConflictException` on the first N `save()` calls,
then delegates to the real store:

- Conflict on save → retry succeeds (store throws once, succeeds on second)
- All retries exhausted (store always throws, verify event logged as lost)
- Detection not re-computed on retry (counting ganglion: detect() called exactly once)
- Compaction re-run on each retry (counting compact: called once per attempt)
- Winner removed situation → retry creates fresh context (storeVersion empty)

**SituationContext storeVersion (unit tests):**

- `initial()` creates with `OptionalLong.empty()`
- `withDetection()` preserves storeVersion through transformations

**JPA conflict detection and wrapping (@QuarkusTest with real database):**

- storeVersion mismatch (non-overlapping transaction) → `SituationConflictException`
- `OptimisticLockException` from concurrent update → wrapped in `SituationConflictException`
- Duplicate persist (constraint violation) → wrapped in `SituationConflictException`
- `find()` populates `storeVersion` from entity version

## Protocol coherence

| Protocol | Status |
|----------|--------|
| persistence-backend-cdi-priority | ✅ CDI tiers unchanged |
| reactive-vs-blocking-selection | ✅ Evaluator stays blocking |
| spi-default-method-contract-test | N/A — no SPI default method changes |
| flyway-version-range-allocation | ✅ V2 is safe — only V1 exists |
| entity-not-null-java-default-matches-sql-default | ✅ `version = 0L` matches `DEFAULT 0` |

## Garden context

- **GE-20260512-e3e525** — OCC + policyTriggered flag for M-of-N threshold completion.
  The `@Version` + retry pattern here is the foundation; the policyTriggered flag for
  CREATE_CASE dedup is deferred to #19.
