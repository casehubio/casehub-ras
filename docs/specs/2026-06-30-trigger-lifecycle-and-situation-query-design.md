# Trigger Lifecycle and Situation Query Design

**Issues:** #21 (stuck policyTriggered cleanup), #20 (long-lived situation lifecycle)
**Date:** 2026-06-30
**Status:** Approved

## Problem

Two coupled problems in the RAS situation lifecycle:

**#21 — Stuck policyTriggered entities.** After CREATE_CASE, the entity stays with
`policyTriggered=true` to guard against duplicate triggers from concurrent retries.
For windowed situations, `SituationExpiryJob` eventually removes the entity. For
persistent situations (`correlationWindow=null`), there is no cleanup path — the
entity persists forever, blocking all future events for that situation instance.

**#20 — No long-lived situation lifecycle.** The current lifecycle is strictly one-shot:
detect → accumulate → trigger → terminate. Consumers like `AdaptiveTopologyManager`
in casehub-ops-deployment need situations that persist beyond case trigger for
continuous monitoring. The consumer API (`SituationSource`, `ActiveSituation`) already
exists in `casehub-desiredstate-api` but belongs in `casehub-ras-api` (see Cross-Repo).

## Root Cause

The evaluator hardcodes post-trigger behavior: `closeGanglia`, `return true`. There is
no lifecycle declaration on `SituationDefinition` and no `TriggerDecision` variant for
"fire and continue." The `policyTriggered` flag was added (#19) as a distributed lock
for duplicate prevention, but without a cleanup mechanism for persistent situations.

## Design

### TriggerDecision — the instruction set

```java
public enum TriggerDecision {
    CREATE_CASE,              // fire + terminate (one-shot complete)
    CREATE_CASE_AND_CONTINUE, // fire + reset claim + compact + continue
    CONTINUE_ACCUMULATING,    // keep watching
    DISCARD,                  // false alarm — situation was never valid
    RESOLVE                   // condition over — situation was valid, now ended
}
```

Five variants completing the 2×2 matrix of (fire × continue) plus a semantic distinction
between DISCARD (never valid) and RESOLVE (was valid, condition ended). Each variant maps
to exactly one execution path in the evaluator — no nested conditionals.

Adding enum variants forces every `switch` expression to handle them. Compile-time
exhaustiveness prevents silent omission.

### TriggerMode — definition-level configuration

```java
public sealed interface TriggerMode {
    record FireOnce() implements TriggerMode {}
    record Repeating(Duration cooldown) implements TriggerMode {
        Repeating {
            Objects.requireNonNull(cooldown, "cooldown");
            if (cooldown.isZero() || cooldown.isNegative()) {
                throw new IllegalArgumentException(
                    "cooldown must be positive, got: " + cooldown);
            }
        }
    }
}
```

On `SituationDefinition`:

```java
public record SituationDefinition(
    String situationId,
    Set<String> eventTypes,
    Duration correlationWindow,
    Duration eventBufferDelay,
    ChainMode chainMode,
    CaseTriggerConfig triggerConfig,
    TriggerMode triggerMode
) {
    public SituationDefinition {
        // ... existing validation ...
        triggerMode = triggerMode != null ? triggerMode : new TriggerMode.FireOnce();
    }
}
```

`triggerMode` defaults to `FireOnce()` when null — applied in the compact constructor.
Existing 6-parameter construction sites must be updated to pass the 7th argument
(null for existing behavior). `Repeating` validates a positive, non-null cooldown
duration — prevents fire-every-event foot-gun.

**Why TriggerDecision, not SituationDefinition, carries the post-trigger instruction:**
The evaluator switches on TriggerDecision for its state transition. Splitting the
decision between TriggerDecision (fire?) and SituationDefinition (continue?) creates
nested conditionals. TriggerDecision is self-contained — each variant is a complete
instruction. The configuration (TriggerMode) drives the policy's *decision*; the
decision drives the evaluator's *action*. Data → Decision → Action.

Custom `RasTriggerPolicy` implementations can return different variants based on
runtime state (e.g., high confidence → CREATE_CASE, moderate → CREATE_CASE_AND_CONTINUE).
This composability is impossible if lifecycle is a static field read by the evaluator.

### SituationContext — trigger history

```java
public record SituationContext(
    String situationId, String correlationKey, String tenancyId,
    Instant firstSignal, Instant lastSignal,
    List<TimestampedDetection> detections,
    OptionalLong storeVersion,
    Instant lastTriggered,    // null = never triggered
    int triggerCount          // 0 = never triggered
)
```

New method:

```java
public SituationContext withStoreVersion(long version) {
    return new SituationContext(situationId, correlationKey, tenancyId,
        firstSignal, lastSignal, detections, OptionalLong.of(version),
        lastTriggered, triggerCount);
}
```

Used by `SituationStore.save()` to return the context with updated storeVersion.

`lastTriggered` and `triggerCount` are stamped atomically by
`SituationStore.tryClaimTrigger()`, not by the evaluator — trigger metadata is a
store-level concern. The context carries them as read-only state for the trigger
policy's cooldown evaluation.

`withDetection()` carries `lastTriggered` and `triggerCount` forward unchanged.
`SituationContext.initial()` sets `null, 0`.

### DefaultRasTriggerPolicy — TriggerMode mapping

```java
public Uni<TriggerDecision> evaluate(SituationContext context, SituationDefinition definition) {
    boolean satisfied = evaluateChainMode(context, definition.chainMode());
    if (!satisfied) return item(CONTINUE_ACCUMULATING);

    return item(switch (definition.triggerMode()) {
        case FireOnce() -> CREATE_CASE;
        case Repeating(var cooldown) -> {
            if (context.lastTriggered() != null
                    && context.lastSignal().isBefore(
                           context.lastTriggered().plus(cooldown))) {
                yield CONTINUE_ACCUMULATING;
            }
            yield CREATE_CASE_AND_CONTINUE;
        }
    });
}
```

Cooldown uses `context.lastSignal()` (event time) not wall clock — deterministic,
testable, consistent with expiry checks.

RESOLVE and DISCARD are not returned by the default policy. Custom policies use these
for domain-specific resolution logic (e.g., ANTI signals crossing a resolution threshold).

### SituationEvaluator — execution paths

CDI event injection:

```java
@Inject Event<SituationChangeEvent> changeEvent;
```

`SituationChangeEvent` is fired as a CDI async event via `changeEvent.fireAsync()`
AFTER the store operation commits successfully. Events are notifications to re-poll,
not data carriers — a crash between store commit and event fire means the consumer
misses the notification but discovers the state on the next periodic poll.

**CREATE_CASE (existing, modified):**

Bifurcated path (existing behavior). `tryClaimTrigger` atomically stamps
`lastTriggered` and `triggerCount` — no second save needed. Entity stays with
`policyTriggered=true`. Guard cleanup removes it later.

```
existing entity: claim(+stamp) → context = save(context) → fire → emit TRIGGERED → closeGanglia → return true
new entity:      context = save(context) → claim(+stamp) → fire → emit TRIGGERED → closeGanglia → return true
```

Loser returns `true` (terminated). Fire failure: `resetTriggerClaim` + `return false`
(unchanged from current code).

**CREATE_CASE_AND_CONTINUE (new):**

Save-first for both new and existing entities — eliminates bifurcated path. Detection
is always persisted before the claim, so losers retain their detection. `save()` returns
the context with updated storeVersion, enabling the second save after compact.

```
context = save(context) → claim(+stamp) → fire → emit TRIGGERED → resetClaim → compact(context) → save(compacted) → return false
```

Loser path: if `tryClaimTrigger` returns false, return `false` (continue accumulating).
Detection is already saved — no data loss.

Fire failure handling (same pattern as CREATE_CASE):

```java
try {
    caseTrigger.fire(definition.triggerConfig(), context).await().indefinitely();
} catch (RuntimeException ex) {
    LOG.severe("CaseTrigger.fire() failed for situation '"
               + situationId + "': " + ex.getMessage());
    store.resetTriggerClaim(situationId, correlationKey, tenancyId)
            .await().indefinitely();
    return false;
}
```

**RESOLVE (new):**

```
emit RESOLVED → closeGanglia → remove → return true
```

**DISCARD (existing, modified):**

```
emit DISCARDED → closeGanglia → remove → return true
```

Mechanically identical to RESOLVE. The distinction is carried by `SituationChangeEvent`
(RESOLVED vs DISCARDED) — consumers respond differently.

### Guard Cleanup (#21 fix)

**Problem:** For persistent + FireOnce, the `policyTriggered=true` entity has no cleanup
path. Immediate removal after trigger is unsafe — a concurrent retry delayed by a GC
pause can find the entity missing, re-insert, re-claim, and fire a duplicate.

**Race proof:** JVM-A fires and removes entity. JVM-B's retry is delayed by a 100ms
GC pause. JVM-B finds nothing, inserts fresh, claims, fires duplicate. The window is
narrow but GC pauses are routine.

**Solution:** Entity stays with `policyTriggered=true` after trigger. `lastTriggered`
is stamped by `tryClaimTrigger`. `SituationExpiryJob` gains a second cleanup pass and
no longer returns early when `maxCorrelationWindow` is null:

```java
@Inject
public SituationExpiryJob(SituationStore store, SituationDefinitionRegistry registry,
                           @ConfigProperty(name = "ras.evaluator.trigger-guard-period",
                                           defaultValue = "PT1M")
                           Duration triggerGuardPeriod) {
    this.store = store;
    this.registry = registry;
    this.triggerGuardPeriod = triggerGuardPeriod;
}

@Scheduled(every = "PT5M")
void cleanup() {
    Duration maxWindow = registry.maxCorrelationWindow();
    if (maxWindow != null) {
        store.removeExpired(Instant.now().minus(maxWindow)).await().indefinitely();
    }
    store.removeTriggeredBefore(Instant.now().minus(triggerGuardPeriod))
         .await().indefinitely();
}
```

Guard period default `PT1M` — orders of magnitude past the retry window (seconds).
During the guard period, new events for this situation instance hit
`tryClaimTrigger→false` and are silently dropped — correct for FireOnce (the situation
already fired).

The cleanup is TriggerMode-agnostic. For Repeating, triggered entities should not exist
(claim is reset after trigger). If they do (bug), cleanup is correct.

### SituationStore — changed and new methods

**Changed: `save()` return type**

```java
Uni<SituationContext> save(SituationContext context);
```

Returns the saved context with updated `storeVersion`. Enables chained saves in
multi-step execution paths (CREATE_CASE_AND_CONTINUE: save detection → fire → compact
→ save compacted). The returned context carries the correct version for the second save.

JPA implementation reads `entity.getVersion()` after `em.flush()` and returns
`context.withStoreVersion(entity.getVersion())`. Since `updateEntity()` does not
write `lastTriggered` or `triggerCount` (store-managed fields), the values stamped
by `tryClaimTrigger` are preserved across saves.

**Changed: `tryClaimTrigger` signature**

```java
Uni<Boolean> tryClaimTrigger(String situationId, String correlationKey,
                              String tenancyId, Instant triggerTime);
```

Atomically stamps `lastTriggered` and increments `triggerCount` alongside the
`policyTriggered` CAS. Eliminates the need for a separate save after trigger.

JPA implementation:
```sql
UPDATE SituationEntity s SET s.policyTriggered = true,
    s.lastTriggered = :triggerTime, s.triggerCount = s.triggerCount + 1
WHERE s.situationId = :sid AND s.correlationKey = :ck
    AND s.tenancyId = :tid AND s.policyTriggered = false
```

This is a JPQL bulk UPDATE — it does NOT increment `@Version`. The entity version
remains stable between the two saves in CREATE_CASE_AND_CONTINUE.

**New: `removeTriggeredBefore`**

```java
default Uni<Void> removeTriggeredBefore(Instant triggerCutoff) {
    return Uni.createFrom().voidItem();
}
```

Deletes entities where `policyTriggered = true AND lastTriggered <= triggerCutoff`.
No-op default — cleanup is an optimization, not a correctness requirement. Both
InMemory and JPA override.

JPA:
```sql
DELETE FROM SituationEntity s
WHERE s.policyTriggered = true AND s.lastTriggered <= :cutoff
```

**New: `findActive`**

```java
default Uni<List<SituationContext>> findActive(String tenancyId) {
    return Uni.createFrom().item(List.of());
}
```

Returns all non-triggered contexts for a tenancy. Default returns empty list. Both
InMemory and JPA override. Used by `DefaultSituationSource`.

JPA:
```sql
SELECT s FROM SituationEntity s
WHERE s.tenancyId = :tid AND s.policyTriggered = false
```

### InMemorySituationStore — new method implementations

`save()` returns context with updated storeVersion. Preserves store-managed trigger
metadata (`lastTriggered`, `triggerCount`) from the existing stored context on update,
mirroring the JPA mapper's exclusion of store-managed fields from `updateEntity()`:
```java
public Uni<SituationContext> save(SituationContext context) {
    var key = new SituationKey(context.situationId(), context.correlationKey(),
                               context.tenancyId());
    long version = versions.computeIfAbsent(key, k -> new AtomicLong(-1L))
                           .incrementAndGet();
    SituationContext existing = store.get(key);
    var versioned = new SituationContext(
        context.situationId(), context.correlationKey(), context.tenancyId(),
        context.firstSignal(), context.lastSignal(), context.detections(),
        OptionalLong.of(version),
        existing != null ? existing.lastTriggered() : context.lastTriggered(),
        existing != null ? existing.triggerCount() : context.triggerCount());
    store.put(key, versioned);
    return Uni.createFrom().item(versioned);
}
```

`tryClaimTrigger` stamps trigger metadata alongside the claim CAS:
```java
public Uni<Boolean> tryClaimTrigger(String sid, String ck, String tid,
                                     Instant triggerTime) {
    var key = new SituationKey(sid, ck, tid);
    if (claims.putIfAbsent(key, Boolean.TRUE) != null)
        return Uni.createFrom().item(false);
    store.computeIfPresent(key, (k, ctx) -> new SituationContext(
        ctx.situationId(), ctx.correlationKey(), ctx.tenancyId(),
        ctx.firstSignal(), ctx.lastSignal(), ctx.detections(), ctx.storeVersion(),
        triggerTime, ctx.triggerCount() + 1));
    return Uni.createFrom().item(true);
}
```

`resetTriggerClaim` clears the claim only — `lastTriggered` and `triggerCount` persist
on the stored context (correct for cooldown evaluation on next cycle).

`removeTriggeredBefore`: iterates entries, removes those with active claim and
`lastTriggered <= cutoff`, also removing from `versions` and `claims`.

`findActive`: filters entries where not claimed and matching `tenancyId`.

### Persistence — entity and migration

`SituationEntity` gains:

```java
@Column(name = "last_triggered")
private Instant lastTriggered;

@Column(name = "trigger_count", nullable = false)
private int triggerCount = 0;
```

Flyway V4 migration:
```sql
ALTER TABLE ras_situation ADD COLUMN last_triggered TIMESTAMP;
ALTER TABLE ras_situation ADD COLUMN trigger_count INTEGER NOT NULL DEFAULT 0;
```

`SituationMapper` mapping rules for new fields — same exclusion pattern as
`policyTriggered` and `version` (store-managed, not context-managed):

- `toContext()`: reads `lastTriggered` and `triggerCount` from entity (populates
  context for policy cooldown evaluation)
- `toEntity()`: writes `lastTriggered` (null) and `triggerCount` (0) for initial
  insert only
- `updateEntity()`: does NOT write `lastTriggered` or `triggerCount` — these are
  store-managed via `tryClaimTrigger`, same as `policyTriggered` and `version`

### Situation Query API (casehub-ras-api)

These types are the authoritative versions. Duplicates in `casehub-desiredstate-api`
are removed by #22.

```java
public record ActiveSituation(
    String situationId,
    String correlationKey,
    String tenancyId,
    double confidence,
    Map<String, Object> evidence,
    Instant since,
    Instant lastSignal,
    int triggerCount
)
```

```java
public interface SituationSource {
    Uni<List<ActiveSituation>> activeSituations(String tenancyId);
}
```

```java
public record SituationChangeEvent(
    String tenancyId,
    String situationId,
    String correlationKey,
    ChangeType changeType
) {
    public enum ChangeType { TRIGGERED, RESOLVED, DISCARDED }
}
```

**When change events fire:**
- CREATE_CASE → TRIGGERED
- CREATE_CASE_AND_CONTINUE → TRIGGERED
- RESOLVE → RESOLVED
- DISCARD → DISCARDED
- CONTINUE_ACCUMULATING → no event (internal accumulation, not a state transition)

Consumers poll `SituationSource.activeSituations()` for current state. Events are the
notification to re-poll, not the data carrier.

### DefaultSituationSource (runtime/)

```java
@ApplicationScoped
public class DefaultSituationSource implements SituationSource {
    @Inject SituationStore store;

    @Override
    public Uni<List<ActiveSituation>> activeSituations(String tenancyId) {
        return store.findActive(tenancyId)
            .map(contexts -> contexts.stream()
                .map(this::toActiveSituation)
                .toList());
    }
}
```

Reactive return type aligns with the RAS reactive API pattern. Consumers compose with
`Uni` pipelines or block with `.await().indefinitely()` on worker threads.

Confidence: max qualifying confidence (WEAK or DETECTED) across accumulated detections.
Projects `SituationContext` to `ActiveSituation` — consumers see a clean read-only view
without storeVersion, full detection lists, or internal state.

### YAML Support

`YamlSituationDefinitionProvider` gains `triggerMode` field support:

```yaml
situations:
  - situationId: volatility-spike
    eventTypes: [market.volatility]
    chainMode:
      type: threshold
      ganglia: [volatility-ganglion]
      minConfidence: 0.7
    triggerConfig:
      caseNamespace: fsitrading
      caseName: volatility-incident
      caseVersion: "1.0"
    triggerMode:
      type: repeating
      cooldown: PT5M
```

`type` discriminator: `fire-once` (default when absent) or `repeating` (requires `cooldown`).

### EndpointRegistry Integration

RAS runtime registers a QUERY endpoint at startup via `EndpointRegistry.register()`:

```java
EndpointDescriptor descriptor = new EndpointDescriptor(
    Path.of("ras", "situations"),
    TenancyConstants.PLATFORM_TENANT_ID,
    EndpointType.SERVICE,
    EndpointProtocol.HTTP,
    Map.of(),
    null,
    Set.of(EndpointCapability.QUERY)
);
registry.register(descriptor);
```

RAS is a platform-wide service. Registered with `PLATFORM_TENANT_ID` — visible to
all tenants via `EndpointRegistry.discover()` (platform-global endpoints are always
included alongside tenant-specific endpoints).

### Contract Test Extensions

`AbstractSituationStoreContractTest` gains:
- `removeTriggeredBeforeRemovesOldTriggeredEntities()`
- `removeTriggeredBeforeKeepsRecentTriggeredEntities()`
- `removeTriggeredBeforeKeepsNonTriggeredEntities()`
- `findActiveReturnsByTenancy()`
- `findActiveExcludesTriggeredEntities()`
- `findActiveEmptyForUnknownTenant()`
- `saveAndFindRoundTripWithTriggerFields()`
- `tryClaimTriggerStampsTriggerMetadata()` — save initial context, claim with triggerTime, find: `lastTriggered == triggerTime`, `triggerCount == 1`
- `tryClaimTriggerIncrementsCountOnSubsequentClaims()` — save, claim, resetClaim, claim again: `triggerCount == 2`
- `saveAfterClaimPreservesTriggerMetadata()` — save, claim, save again (different detections): `lastTriggered` and `triggerCount` unchanged from claim

## Cross-Repo

| Issue | Repo | What | Blocked by |
|-------|------|------|-----------|
| parent#327 | casehubio/parent | Reclassify casehub-ras as Foundation tier | — |
| #22 | casehubio/casehub-ras | Move ActiveSituation/SituationSource/SituationChangeEvent from desiredstate-api to ras-api, update ops imports and constructor call sites | parent#327, this work |

**#22 scope notes:** The type moves are not just import changes. `ActiveSituation`
gains 4 fields (8 total vs 4 original). `SituationChangeEvent` gains `situationId`,
`correlationKey`, and `ChangeType`. `SituationSource.activeSituations()` changes
from `List<>` to `Uni<List<>>`. All construction sites and consumers in
casehub-ops-deployment (including ~15 test locations) must be updated for the new
signatures. `AdaptiveTopologyManager.onSituationChange` should use `ChangeType` for
targeted re-evaluation rather than blanket re-polling.

## Behavior Matrix

| correlationWindow | TriggerMode | After trigger | Cleanup |
|---|---|---|---|
| set (windowed) | FireOnce | terminate | existing window expiry OR guard cleanup (whichever first) |
| null (persistent) | FireOnce | terminate | guard cleanup (resolves #21) |
| set (windowed) | Repeating | reset + continue | window expiry if no signals |
| null (persistent) | Repeating | reset + continue | explicit RESOLVE or DISCARD from policy |

## Non-Goals

- REST endpoint for situation queries (defer until cross-service consumer exists)
- ACTIVATED change event (defer — consumers poll via SituationSource)
- Max trigger count on Repeating mode (YAGNI — custom policy can return RESOLVE)
- Resolution API on SituationStore (RESOLVE from policy is sufficient)
