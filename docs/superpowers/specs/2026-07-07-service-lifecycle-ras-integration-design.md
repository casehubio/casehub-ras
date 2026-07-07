# Service Lifecycle RAS Integration — Design Spec

Issue: casehubio/casehub-ras#6
Closes: casehubio/casehub-ras#6 — items 1 (dynamic registration) and 2 (child cases via bridge) are delivered; items 3 and 4 are tracked separately by #31 and ops#11
Validates: casehubio/casehub-ops#47
Deferred: casehubio/casehub-ras#31 (ganglion-as-case)

## Summary

Extends casehub-ras to support signaling existing cases — not just starting new ones. Three
changes: generalized trigger output (TriggerAction), dynamic situation registration, and
enriched SituationChangeEvent. Together these let any consuming app wire RAS detection into
long-lived case lifecycles.

The ops console app (casehub-ops#47) is the first consumer — validates the design end-to-end
but lives in casehub-ops, not here.

## Problem

RAS currently has one output mode: start a new case when a situation triggers. Service
lifecycle cases are long-lived — they already exist when RAS detects something. RAS needs
to announce detections without starting cases, carry enough data for consumers to act, and
support situations registered at runtime (services deploy and undeploy dynamically).

Three structural gaps:

1. **Case-start-only output** — `SituationDefinition` has a mandatory `CaseTriggerConfig`.
   `SituationEvaluator` always calls `CaseTrigger.fire()` → `CaseHub.startCase()`. No way
   to trigger without starting a case.

2. **Static registry** — `SituationDefinitionRegistry` is built once at startup from
   CDI-injected providers. Immutable maps, no add/remove. Service lifecycle needs dynamic
   registration tied to deploy/undeploy events.

3. **Thin change events** — `SituationChangeEvent` carries `(tenancyId, situationId,
   correlationKey, changeType)` only — no detection results, no confidence, no evidence.
   Consumers can't act on an event that doesn't say what was detected.

## Architecture — Approach B: Enriched CDI Events with Consumer Bridge

RAS detects and announces. The consuming app (ops, or any future app) observes CDI events
and decides what to do — including signaling existing cases via `CaseHubRuntime.signal()`.

```
CloudEvent → RAS → ganglia → trigger → SituationChangeEvent (enriched, with detection data)
                                              ↓
                                   Consuming app @ObservesAsync
                                              ↓
                                   CaseHubRuntime.signal(caseId, path, data)
                                              ↓
                                   Case binding evaluates → child case spawns
```

RAS stays decoupled from the engine's case model. No case resolver SPI. The bridge code is
inherently domain-specific (the consuming app knows its case structure) — so it belongs in
the consuming app.

### Why not RAS signals cases directly?

A `CaseResolver` SPI that maps `(situationId, correlationKey)` → `caseId` is inherently
domain-specific. The coupling doesn't disappear — it just moves from the consuming app to
a RAS SPI implementation. The consuming app still writes the same code, but now through an
indirection layer with no additional benefit. CDI events are the natural integration
boundary.

## Design

### 1. TriggerAction — replacing CaseTriggerConfig

Replace the mandatory `CaseTriggerConfig triggerConfig` field on `SituationDefinition` with
a `TriggerAction triggerAction` sealed interface.

```java
public sealed interface TriggerAction {
    record CreateCase(CaseTriggerConfig config) implements TriggerAction {
        public CreateCase {
            Objects.requireNonNull(config, "config");
        }
    }
    record NotifyOnly() implements TriggerAction {}
}
```

- `CreateCase` — current behavior: `CaseTrigger.fire()` + enriched `SituationChangeEvent`
- `NotifyOnly` — new: skip case creation, fire enriched `SituationChangeEvent` only

Both always fire `SituationChangeEvent`. The difference is whether a case is also started.

**SituationDefinition change:**

```java
public record SituationDefinition(
    String situationId,
    Set<String> eventTypes,
    Duration correlationWindow,
    Duration eventBufferDelay,
    ChainMode chainMode,
    TriggerAction triggerAction,   // was: CaseTriggerConfig triggerConfig
    TriggerMode triggerMode
) { ... }
```

Validation: `triggerAction` is non-null. For `CreateCase`, the inner `CaseTriggerConfig`
validation is unchanged.

**TriggerDecision rename:**

The enum values currently say "CREATE_CASE" which describes only one possible action. Rename
to describe the situation lifecycle:

| Current | New | Meaning |
|---------|-----|---------|
| `CREATE_CASE` | `TRIGGER` | Execute trigger action, close situation |
| `CREATE_CASE_AND_CONTINUE` | `TRIGGER_AND_CONTINUE` | Execute trigger action, continue accumulating |
| `CONTINUE_ACCUMULATING` | unchanged | |
| `DISCARD` | unchanged | |
| `RESOLVE` | unchanged | |

`RasTriggerPolicy` SPI and `DefaultRasTriggerPolicy` return the renamed values. Logic is
unchanged — they decide WHEN to trigger. `TriggerAction` decides WHAT happens when triggered.
Orthogonal concerns.

**YAML format change:**

From:
```yaml
triggerConfig:
  caseNamespace: ops
  caseName: incident-response
  caseVersion: "1"
```

To discriminated format (consistent with `chainMode` and `triggerMode`):
```yaml
triggerAction:
  type: create-case
  caseNamespace: ops
  caseName: incident-response
  caseVersion: "1"
```
or:
```yaml
triggerAction:
  type: notify-only
```

### 2. Enriched SituationChangeEvent

Add the `SituationContext` to the event. It's already a public API type in `casehub-ras-api`
and carries everything a bridge needs: detections, confidence, timestamps, trigger count.

```java
public record SituationChangeEvent(
    String tenancyId,
    String situationId,
    String correlationKey,
    ChangeType changeType,
    SituationContext context
) {
    public enum ChangeType { TRIGGERED, RESOLVED, DISCARDED }

    public SituationChangeEvent {
        Objects.requireNonNull(tenancyId, "tenancyId");
        Objects.requireNonNull(situationId, "situationId");
        Objects.requireNonNull(correlationKey, "correlationKey");
        Objects.requireNonNull(changeType, "changeType");
        Objects.requireNonNull(context, "context");
    }
}
```

The context is captured at the moment of the state change — before the situation is removed
from the store (for RESOLVED/DISCARD) or closed (for TRIGGERED). The event is self-contained:
the bridge never needs to query back to RAS.

`SituationContext.storeVersion()` is an internal persistence field exposed in the public type.
Observers ignore it. Creating a separate snapshot type to hide one field is unnecessary
complexity.

### 3. Dynamic Situation Registration

Add `register()` and `deregister()` to `SituationDefinitionRegistry`. Ganglia stay static
(CDI beans at startup). Only situation definitions are dynamic — ganglia are per-application,
situations are per-service-instance.

All mutable registry state is bundled into a single immutable snapshot. One volatile
reference swap gives readers a consistent view of all fields atomically.

```java
@ApplicationScoped
public class SituationDefinitionRegistry {

    private record RegistrySnapshot(
        Map<String, List<SituationRegistration>> byEventType,
        Set<String> situationIds,
        Duration maxCorrelationWindow
    ) {}

    private volatile RegistrySnapshot snapshot;
    private final Map<String, Ganglion> gangliaById;  // static, unchanged

    // Existing startup constructor builds the initial snapshot from providers

    public synchronized void register(SituationRegistration registration) {
        String sitId = registration.definition().situationId();
        if (snapshot.situationIds().contains(sitId)) {
            throw new IllegalStateException("Duplicate situationId: " + sitId);
        }
        validate(registration.definition());
        // Rebuild snapshot with new registration, swap volatile reference
    }

    public synchronized void deregister(String situationId) {
        // Rebuild snapshot without this situationId, swap volatile reference
        // No-op if situationId not found (idempotent)
    }

    public List<SituationRegistration> findByEventType(String eventType) {
        return snapshot.byEventType().getOrDefault(eventType, List.of());  // volatile read, lock-free
    }
}
```

**Thread safety:** Copy-on-write with atomic snapshot swap. `register()`/`deregister()` are
synchronized and rebuild the snapshot (registrations change infrequently — deploy/undeploy
events). `findByEventType()` and `maxCorrelationWindow()` read the single volatile
`snapshot` reference without locking — called on every CloudEvent, must be fast. No
multi-field race: `byEventType`, `situationIds`, and `maxCorrelationWindow` are always
consistent because they come from the same snapshot.

**Validation:** Same rules as startup — referenced ganglia must exist in `gangliaById`, no
duplicate situationIds (checked via `snapshot.situationIds()`). `register()` throws
`IllegalStateException` on validation failure.

**Deregister cleanup:** Removes the situation definition from the registry snapshot. Does
NOT automatically remove in-flight `SituationContext` entries from the store. For situations
with a `correlationWindow`, those expire naturally via `SituationExpiryJob`. For persistent
situations (`correlationWindow=null`), the consuming app must clean up store entries — see
§ Ops Integration for the pattern.

**New SituationStore primitive:** Add `removeAllForSituation(String situationId)` to
`SituationStore` — deletes all context entries matching the given situationId regardless
of correlationKey and tenancyId. Required for deregistering persistent situations where
the expiry job does not apply.

```java
Uni<Void> removeAllForSituation(String situationId);
```

This is a required method (no default) — both `InMemorySituationStore` and
`JpaSituationStore` must implement it.

### 4. SituationEvaluator Changes

`executeDecision()` checks `definition.triggerAction()` to decide whether to call
`caseTrigger.fire()`. All branches pass `context` to enriched `SituationChangeEvent`.

```java
case TRIGGER -> {
    // ... existing claim logic unchanged ...

    if (definition.triggerAction() instanceof TriggerAction.CreateCase createCase) {
        try {
            caseTrigger.fire(createCase.config(), context).await().indefinitely();
        } catch (RuntimeException ex) {
            LOG.severe("CaseTrigger.fire() failed for situation '"
                       + situationId + "': " + ex.getMessage());
            store.resetTriggerClaim(situationId, correlationKey, tenancyId)
                    .await().indefinitely();
            return false;
        }
        // CreateCase: event is supplementary — fire-and-forget
        changeEvent.fireAsync(new SituationChangeEvent(
                tenancyId, situationId, correlationKey,
                SituationChangeEvent.ChangeType.TRIGGERED, context));
    } else {
        // NotifyOnly: event IS the sole output — await delivery
        try {
            changeEvent.fireAsync(new SituationChangeEvent(
                    tenancyId, situationId, correlationKey,
                    SituationChangeEvent.ChangeType.TRIGGERED, context))
                    .toCompletableFuture().join();
        } catch (Exception ex) {
            LOG.severe("SituationChangeEvent delivery failed for situation '"
                       + situationId + "': " + ex.getMessage());
            store.resetTriggerClaim(situationId, correlationKey, tenancyId)
                    .await().indefinitely();
            return false;
        }
    }

    closeGanglia(definition, situationId, correlationKey, tenancyId);
    return true;
}
```

Same pattern for `TRIGGER_AND_CONTINUE`.

**Claim logic for NotifyOnly:** `tryClaimTrigger()` still runs for `NotifyOnly` situations.
It prevents duplicate `SituationChangeEvent` notifications across cluster nodes — the same
exactly-once guarantee as for case creation.

**Error handling:** For `CreateCase`, existing error handling (resetTriggerClaim on failure)
is unchanged; the change event is supplementary (the case already exists as a durable
artifact) so it remains fire-and-forget. For `NotifyOnly`, the change event IS the sole
output — the evaluator awaits the `CompletionStage` returned by `fireAsync()` and resets
the trigger claim on failure, matching the error-handling pattern of `caseTrigger.fire()`.
This means an observer failure allows the situation to re-trigger on the next qualifying
event. For ongoing monitoring, `TriggerMode.Repeating` provides natural recovery via its
cooldown cycle.

`DISCARD` and `RESOLVE` branches: add `context` parameter to their `SituationChangeEvent`
constructors. No other changes.

## Ops Integration Validation (casehub-ops#47)

This section validates the RAS primitives against a real consumer. All code described here
lives in `casehub-ops`, not in `casehub-ras`.

### At deployment time — ApplicationLifecycleService.deploy()

After creating the application case, register a RAS monitoring situation:

```java
SituationDefinition healthMonitor = new SituationDefinition(
    "ops:health:" + applicationId,
    Set.of("io.casehub.ops.health.check", "io.casehub.ops.health.metric"),
    null,                                      // correlationWindow — persistent
    null,                                      // eventBufferDelay
    new ChainMode.Threshold(Set.of("health-ganglion"), 0.7),
    new TriggerAction.NotifyOnly(),
    new TriggerMode.Repeating(Duration.ofMinutes(5))
);

CorrelationKeyExtractor extractor = event -> applicationId;
registry.register(new SituationRegistration(healthMonitor, extractor));
```

- `situationId` is unique per application: `"ops:health:" + applicationId`
- `correlationWindow = null` → persistent situation (long-lived, like the case)
- `NotifyOnly` → no case creation, bridge handles it
- `Repeating` with cooldown → re-triggerable for ongoing monitoring
- Custom `CorrelationKeyExtractor` → all health events for this app correlate together

### Bridge component — RasCaseBridge

```java
@ApplicationScoped
public class RasCaseBridge {

    @Inject CaseHubRuntime runtime;
    @Inject ApplicationLifecycleService appService;

    void onSituation(@ObservesAsync SituationChangeEvent event) {
        if (event.changeType() != SituationChangeEvent.ChangeType.TRIGGERED) return;
        if (!event.situationId().startsWith("ops:health:")) return;

        String applicationId = event.correlationKey();
        UUID caseId = appService.caseIdFor(applicationId);
        if (caseId == null) return;  // app decommissioned between detection and bridge

        runtime.signal(caseId, "healthAlert", Map.of(
            "detections", event.context().detections(),
            "situationId", event.situationId()
        ));
    }
}
```

The signal writes to the `healthAlert` context path on the application case. The case's
bindings (already defined in `ApplicationCaseDescriptor`) fire on this context change and
spawn the appropriate child case (incident, upgrade, etc.).

### At decommission time — ApplicationLifecycleService.decommission()

Clean up in-flight detection state, then remove the definition:

```java
String situationId = "ops:health:" + applicationId;
store.removeAllForSituation(situationId).await().indefinitely();  // persistent — won't expire
registry.deregister(situationId);
```

Order matters: store cleanup first (while the definition still exists for any in-flight
evaluations to reference), then definition removal.

### Ganglia

The ops app provides domain-specific ganglia as CDI beans. These are static — deployed once
with the app, available to any dynamically registered situation.

Example: `HealthCheckGanglion extends JavaSwitchGanglion` that evaluates K8s health check
CloudEvents and returns DETECTED/NOISE based on liveness/readiness probe results.

### End-to-end flow

```
K8s health event
  → platform-streams-webhook → CloudEvent CDI fireAsync()
    → RasEngine.onCloudEvent() routes to "ops:health:<appId>" situation
      → HealthCheckGanglion.detect() → DetectionResult(DETECTED, 0.9)
        → Threshold ChainMode: 0.9 ≥ 0.7 → TRIGGER decision
          → NotifyOnly → enriched SituationChangeEvent(TRIGGERED, context)
            → RasCaseBridge.onSituation() observes
              → CaseHubRuntime.signal(caseId, "healthAlert", detectionData)
                → Application case ContextChangeTrigger on "healthAlert" fires
                  → Binding when guard evaluates severity
                    → SubCaseTarget spawns ops:incident-response child case
```

## Deferred

| Item | Issue | Rationale |
|------|-------|-----------|
| Ganglion-as-case | casehubio/casehub-ras#31 | Separate state persistence concern. RAS already has purpose-built persistence (DroolsSessionStore, JpaSituationStore). |
| RAS as DesiredNode | casehubio/casehub-ops#11 | Desiredstate concern, not a RAS primitive. This spec unblocks ops#11 by providing the `register()`/`deregister()` API it depends on. |

## Breaking Changes

All breaking — no backward compatibility concerns (no external consumers).

| Change | Source | Consumers affected |
|--------|--------|--------------------|
| `SituationDefinition.triggerConfig` → `.triggerAction` (field + constructor) | api/ | runtime/ (`SituationEvaluator`, `YamlSituationDefinitionProvider`), desiredstate/ras-adapter/ (`DesiredStateSituationDefinitionProvider`), all `SituationDefinitionProvider` implementations |
| `TriggerDecision.CREATE_CASE` → `TRIGGER`, `CREATE_CASE_AND_CONTINUE` → `TRIGGER_AND_CONTINUE` | api/ | runtime/ (`DefaultRasTriggerPolicy`), all `RasTriggerPolicy` implementations |
| `SituationChangeEvent` gains `context` field (constructor change) | api/ | runtime/ (`SituationEvaluator`), all `@ObservesAsync SituationChangeEvent` observers |
| `SituationStore.removeAllForSituation(String)` — new required method | api/ | persistence-memory/ (`InMemorySituationStore`), persistence-jpa/ (`JpaSituationStore`) |
| YAML `triggerConfig:` → `triggerAction:` with `type:` discriminator | runtime/ | YAML situation definition files |
| `SituationDefinitionRegistry` internal fields change to `RegistrySnapshot` volatile/copy-on-write | runtime/ | internal — no external API change |
