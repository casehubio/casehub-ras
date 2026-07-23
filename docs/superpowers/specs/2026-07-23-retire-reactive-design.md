# Retire Reactive (Mutiny) from casehub-ras

**Issue:** casehubio/parent#384
**Date:** 2026-07-23
**Status:** Approved

## Context

RAS uses Mutiny `Uni<T>` as the return type for all 7 SPIs (21 methods total).
Analysis reveals this is pure ceremony — every `Uni` is either:

- Created via `Uni.createFrom().item(synchronousResult)` — wrapping a value already computed
- Consumed via `.await().indefinitely()` — immediately blocking to unwrap

Zero actual reactive composition exists. RAS uses blocking JPA (`EntityManager`,
`@Transactional`), processes CDI events synchronously (`@ObservesAsync`), and has
no reactive I/O. Unlike qhorus/platform/neocortex which have dual-stack
(blocking + reactive pairs to delete), RAS has only Uni-returning SPIs with no
blocking counterparts. The SPIs themselves must change.

The one genuine async boundary — `CaseHub.startCase()` returning
`CompletionStage<UUID>` from engine-api — is immediately blocked on by the caller.
Virtual threads make this blocking free.

## Approach

Full blocking retirement. Every `Uni<T>` → `T`, every `Uni<Void>` → `void`.
Remove Mutiny dependency entirely. Scope: RAS + consumer repos (ops, desiredstate,
iot) in work-slot 30.

## SPI Signature Changes

### Ganglion (api/)

| Before | After |
|--------|-------|
| `Uni<DetectionResult> detect(CloudEvent, SituationContext)` | `DetectionResult detect(CloudEvent, SituationContext)` |
| `default Uni<SituationContext> compact(SituationContext)` | `default SituationContext compact(SituationContext)` |
| `default Uni<Void> close(String, String, String)` | `default void close(String, String, String)` |

Default bodies: `compact()` returns `context`, `close()` is empty.

### SituationStore (api/)

| Before | After |
|--------|-------|
| `Uni<Optional<SituationContext>> find(...)` | `Optional<SituationContext> find(...)` |
| `Uni<SituationContext> save(...)` | `SituationContext save(...)` |
| `Uni<Void> remove(...)` | `void remove(...)` |
| `Uni<Integer> removeExpired(Instant)` | `int removeExpired(Instant)` |
| `default Uni<Boolean> tryClaimTrigger(...)` | `default boolean tryClaimTrigger(...)` |
| `default Uni<Void> resetTriggerClaim(...)` | `default void resetTriggerClaim(...)` |
| `default Uni<Integer> removeTriggeredBefore(...)` | `default int removeTriggeredBefore(...)` |
| `default Uni<List<SituationContext>> findActive(...)` | `default List<SituationContext> findActive(...)` |
| `Uni<Void> removeAllForSituation(String)` | `void removeAllForSituation(String)` |

### GanglionStateStore (api/)

| Before | After |
|--------|-------|
| `Uni<Optional<GanglionState>> load(GanglionStateKey)` | `Optional<GanglionState> load(GanglionStateKey)` |
| `Uni<Void> save(GanglionStateKey, GanglionState)` | `void save(GanglionStateKey, GanglionState)` |
| `Uni<Void> remove(GanglionStateKey)` | `void remove(GanglionStateKey)` |
| `Uni<Void> removeForSituation(String)` | `void removeForSituation(String)` |

### RasTriggerPolicy (api/)

| Before | After |
|--------|-------|
| `Uni<PolicyDecision> evaluate(SituationContext, SituationDefinition)` | `PolicyDecision evaluate(SituationContext, SituationDefinition)` |

### CaseTrigger (api/)

| Before | After |
|--------|-------|
| `Uni<UUID> fire(CaseTriggerConfig, SituationContext)` | `UUID fire(CaseTriggerConfig, SituationContext)` |

### SituationSource (api/)

| Before | After |
|--------|-------|
| `Uni<List<ActiveSituation>> activeSituations(String)` | `List<ActiveSituation> activeSituations(String)` |

### OrphanedResourceCleaner (api/)

| Before | After |
|--------|-------|
| `Uni<Integer> removeOrphaned()` | `int removeOrphaned()` |

## JavaSwitchGanglion (api/)

`detect()` remains `final`. Body changes from
`return Uni.createFrom().item(evaluate(event, context))` to
`return evaluate(event, context)`. The abstract `evaluate()` method and helper
factories (`detected()`, `weak()`, `noise()`, `anti()`) are unchanged.
Subclassers (in consumer repos) are unaffected — they override `evaluate()`,
not `detect()`.

## Implementation Changes

All implementations follow the same mechanical pattern: remove
`Uni.createFrom().item(x)` wrapping, return `x` directly. Remove
`Uni.createFrom().voidItem()`, return nothing.

### Ganglion implementations

| Class | Module | Notes |
|-------|--------|-------|
| `NaiveBayesGanglion` | runtime/ | Internal `stateStore.load()/save()` calls drop `.await().indefinitely()` |
| `ExpressionRulesGanglion` | runtime/ | Returns `DetectionResult` directly |
| `EvidenceExtractingGanglion` | runtime/ | `detect()` calls `delegate.detect()` directly, applies `enrichEvidence()`. No `.map()` |
| `DroolsGanglion` | ras-drools/ | Returns values directly |
| `MockGanglion` | testing/ | Returns `fixedResult` directly |

### Store implementations

| Class | Module | Notes |
|-------|--------|-------|
| `JpaSituationStore` | persistence-jpa/ | Remove wrapping on all 9 methods |
| `InMemorySituationStore` | persistence-memory/ | Remove wrapping on all 9 methods |
| `JpaGanglionStateStore` | persistence-jpa/ | Remove wrapping. `removeOrphaned()` returns `int` |
| `InMemoryGanglionStateStore` | runtime/ | Remove wrapping on all 4 methods |

### Other implementations

| Class | Module | Notes |
|-------|--------|-------|
| `DefaultRasTriggerPolicy` | runtime/ | Returns `PolicyDecision` directly |
| `DefaultCaseTrigger` | runtime/ | Returns `UUID`. Bridges `CaseHub.startCase()` via `.toCompletableFuture().join()` |
| `DefaultSituationSource` | runtime/ | Calls `store.findActive()` directly, maps with `.stream().map().toList()` |
| `MockCaseTrigger` | testing/ | Returns `UUID` directly |
| `ReliableDroolsSessionStore` | drools-reliability/ | `removeOrphaned()` returns `int` |

## Caller Changes

Every `.await().indefinitely()` is deleted. No logic changes.

**SituationEvaluator** (~20 sites): `store.find(...).await().indefinitely()` →
`store.find(...)`. Same for `store.save()`, `store.remove()`,
`ganglion.detect()`, `ganglion.compact()`, `ganglion.close()`,
`triggerPolicy.evaluate()`, `caseTrigger.fire()`, `store.tryClaimTrigger()`,
`store.resetTriggerClaim()`.

**SituationExpiryJob** (~4 sites): `store.removeTriggeredBefore().await().indefinitely()`
→ `store.removeTriggeredBefore()`. Same for `store.removeExpired()`,
`cleaner.removeOrphaned()`.

**DefaultSituationSource**: `store.findActive(tenancyId).map(...)` →
`store.findActive(tenancyId).stream().map(this::toActiveSituation).toList()`.

## Test Changes

**Contract tests** (api/ test-jar): Drop `.await().indefinitely()` from all
assertions. `AbstractGanglionContractTest.detectReturnsCompletingUni` renamed
to reflect blocking contract.

**Unit tests**: Mock setups change from
`when(x).thenReturn(Uni.createFrom().item(y))` to `when(x).thenReturn(y)`.
Inline anonymous impls drop Uni wrapping. No test logic changes.

## Dependency Cleanup

Remove `io.smallrye.mutiny:mutiny` from all module POMs. The dependency
originates in `api/` and is transitive to all other modules.

`casehub-engine-api` in `runtime/pom.xml` — retained. Still needed for
`CaseHub` in `DefaultCaseTrigger`.

## Cross-Repo Consumer Fixes

Three repos in slot 30 depend on `casehub-ras-api`:

| Repo | Module | Dependency | Expected impact |
|------|--------|-----------|-----------------|
| ops | deployment/ | `casehub-ras-api` | Minimal — `JavaSwitchGanglion` subclassers override `evaluate()` (unchanged) |
| desiredstate | ras-adapter/ | `casehub-ras-api` + `casehub-ras-testing` (test) | Custom ganglion/provider impls + test code |
| iot | webapp-api/ | `casehub-ras-api` + `casehub-ras` (runtime) | Custom ganglion/provider impls + test code |

**Execution order:**
1. Change RAS, `mvn install` locally
2. Fix each consumer — likely just removing Uni imports and `.await().indefinitely()` from tests
3. Each consumer `mvn install` to verify

## CaseHub Bridge — Temporary State

`DefaultCaseTrigger.fire()` will use `.toCompletableFuture().join()` to block on
`CaseHub.startCase()` which returns `CompletionStage<UUID>`. This is a temporary
bridge — when parent#381 retires engine's `CompletionStage` returns, the `.join()`
wrapper disappears and `fire()` calls `hub.startCase()` directly.
