# DroolsGanglion: Result Collection Strategy & Test Coverage

**Issues:** #8 (configurable result collection strategy), #10 (minor test gaps)
**Date:** 2026-06-22 (revised 2026-06-23, third pass)

---

## Problem

During `DroolsGanglion.detect()`, multiple DRL rules can fire against the same event,
each emitting a `DetectionResult` to the `"results"` channel. The Ganglion SPI returns
`Uni<DetectionResult>` — one result. The current `ResultCollectorChannel` does last-write-wins
(`result = dr`), an implicit choice with no configuration.

Separately, three minor test gaps exist from Epic 4.

## Design — Result Collection Strategy (#8)

### Core insight

The N→1 reduction from multiple rule firings to one `DetectionResult` is a configuration
concern of the DroolsGanglion, not a Drools-level concern and not a Ganglion SPI concern.

### Two fundamental modes

All strategies fall into two categories:

1. **Select** — one rule's result wins, the rest are discarded
   - HIGHEST_CONFIDENCE: most confident assessment dominates
   - FIRST_MATCH: first emission kept, rest ignored
   - LAST_WINS: last emission overwrites (current behaviour)

2. **Merge** — all results combine into one
   - ACCUMULATE: strongest signal, max confidence, merged evidence

### DetectionSignal strength ordering (api/ change)

Signal strength is a property of the signal type, not an implementation detail of a
collection strategy. The ordering reflects the semantic hierarchy from Epic 1 §4.1:

- `DETECTED` — clear positive signal (strongest)
- `WEAK` — ambiguous, worth accumulating
- `ANTI` — counter-evidence, reduces situation confidence
- `NOISE` — nothing meaningful (weakest)

Positive detections of any strength rank above counter-evidence. A WEAK+ANTI co-occurrence
must propagate the positive signal upward for the trigger policy to evaluate — squashing it
into ANTI at the collection level would hide a legitimate detection from the situation engine.
The trigger policy (which sees the full `SituationContext.detections` list including ANTI results
from other ganglia) is the correct place to weigh competing signals.

**Change to `DetectionSignal`:** reorder enum constants to ascending strength and add
`isAtLeast()`:

```java
public enum DetectionSignal {
    NOISE,
    ANTI,
    WEAK,
    DETECTED;

    public boolean isAtLeast(DetectionSignal threshold) {
        return this.ordinal() >= threshold.ordinal();
    }
}
```

Declaration order = strength order. This is idiomatic Java (cf. `java.time.Month`,
`java.time.DayOfWeek`). `isAtLeast()` provides readable comparisons for trigger policies:
`signal.isAtLeast(DetectionSignal.WEAK)`. Nothing in the codebase currently depends on
DetectionSignal's ordinal ordering.

### Type: `ResultCollectionStrategy` enum

All variants are parameterless → enum, consistent with `SessionMode` and `ClockMode`.

```java
public enum ResultCollectionStrategy {
    HIGHEST_CONFIDENCE,
    FIRST_MATCH,
    LAST_WINS,
    ACCUMULATE
}
```

HIGHEST_CONFIDENCE is listed first — it is the default (see below).

### Collection model: accumulate-then-reduce

`ResultCollectorChannel` changes from holding a single result to accumulating a list:

```java
class ResultCollectorChannel implements Channel {
    private final List<DetectionResult> results = new ArrayList<>();

    @Override
    public void send(Object object) {
        if (object instanceof DetectionResult dr) {
            results.add(dr);
        }
    }

    List<DetectionResult> results() {
        return List.copyOf(results);
    }
}
```

After `fireAllRules()`, `DroolsGanglion.detect()` applies the strategy. `resolve()` subsumes
the current null-to-NOISE fallback; the explicit null check in `detect()` is removed.

```java
List<DetectionResult> results = collector.results();
DetectionResult result = config.resultCollectionStrategy().resolve(results, config.ganglionId());
```

### `resolve()` method on the enum

```java
DetectionResult resolve(List<DetectionResult> results, String ganglionId)
```

Returns a NOISE `DetectionResult` when the list is empty — `new DetectionResult(ganglionId,
0.0, DetectionSignal.NOISE, Map.of())`. The method always returns a valid result; callers
never handle null.

| Strategy | Behaviour |
|----------|-----------|
| HIGHEST_CONFIDENCE | Return the result with the highest `confidence`. Ties: first encountered wins. |
| FIRST_MATCH | Return `results.get(0)`. |
| LAST_WINS | Return `results.get(results.size() - 1)`. |
| ACCUMULATE | Merge all — see below. |

### ACCUMULATE semantics

- **Signal:** strongest wins per DetectionSignal declaration order (DETECTED > WEAK > ANTI > NOISE)
- **Confidence:** max of all results, regardless of their individual signal — the merged
  result represents the most optimistic collective assessment, not any single rule's assertion
- **Evidence:** union of all evidence maps (see evidence merge contract below)
- **GanglionId:** taken from the `ganglionId` parameter (the config's ID, not individual results)

**Evidence merge contract:** Evidence key collisions follow rule activation order — the last
rule to fire wins on a shared key. This is an explicit, documented contract, not a hidden
fragility. Rule authors using ACCUMULATE should use distinct evidence keys across rules in the
same ganglion. Overlapping keys are not an error — they produce deterministic results governed
by Drools' conflict resolution order — but they indicate rules that could be merged or given
distinct key namespaces. This is a weaker firing-order dependency than LAST_WINS (which makes
the entire result order-dependent); here only evidence key collisions are affected.

### Default strategy: HIGHEST_CONFIDENCE

LAST_WINS (the current implicit behaviour) depends on Drools' internal conflict resolution
order, which is fragile unless the rule author controls it with salience. HIGHEST_CONFIDENCE
is invariant to firing order and semantically correct for detection: if multiple rules assess
the same event, the most confident assessment should dominate.

### DroolsGanglionConfig change

Add `resultCollectionStrategy` as a required field in the canonical constructor.
Add a convenience constructor without it that defaults to HIGHEST_CONFIDENCE.
Normalize all `Objects.requireNonNull` calls to include the parameter name as the
message string (the existing `ganglionId` validation omits it — fix while touching
the constructor).

```java
public record DroolsGanglionConfig(
        String ganglionId,
        Set<String> handledEventTypes,
        SessionMode sessionMode,
        ClockMode clockMode,
        List<String> classpathRules,
        List<String> programmaticRules,
        ResultCollectionStrategy resultCollectionStrategy
) {
    public DroolsGanglionConfig {
        Objects.requireNonNull(ganglionId, "ganglionId");
        Objects.requireNonNull(sessionMode, "sessionMode");
        Objects.requireNonNull(clockMode, "clockMode");
        Objects.requireNonNull(resultCollectionStrategy, "resultCollectionStrategy");
        if (handledEventTypes == null || handledEventTypes.isEmpty()) {
            throw new IllegalArgumentException("handledEventTypes must not be empty");
        }
        handledEventTypes = Set.copyOf(handledEventTypes);
        if (classpathRules == null) classpathRules = List.of();
        if (programmaticRules == null) programmaticRules = List.of();
        classpathRules = List.copyOf(classpathRules);
        programmaticRules = List.copyOf(programmaticRules);
        if (classpathRules.isEmpty() && programmaticRules.isEmpty()) {
            throw new IllegalArgumentException("At least one rule source required");
        }
    }

    public DroolsGanglionConfig(String ganglionId, Set<String> handledEventTypes,
            SessionMode sessionMode, ClockMode clockMode,
            List<String> classpathRules, List<String> programmaticRules) {
        this(ganglionId, handledEventTypes, sessionMode, clockMode,
             classpathRules, programmaticRules, ResultCollectionStrategy.HIGHEST_CONFIDENCE);
    }
}
```

The convenience constructor is not a backward-compatibility shim — it is API surface that
provides the sensible default. Callers that care about the strategy use the canonical constructor.

### SPI impact

`Ganglion.detect()` returns `Uni<DetectionResult>` unchanged. The N→1 reduction is
internal to `DroolsGanglion`.

`DetectionSignal` enum constant reordering is a breaking change for any code using `ordinal()`
or `values()` array indexing. No code in the codebase does this. The `isAtLeast()` method is
a new addition available to all consumers of `casehub-ras-api`, including future
`RasTriggerPolicy` implementations.

## Test Gaps (#10)

### 1. close() on ephemeral ganglion

Create an ephemeral ganglion, call `detect()` (session disposed after), then call `close()`.
`sessionStore.remove()` on a non-existent key is a no-op. Test documents this intent.

### 2. advanceClock with null event time — sequence test

Test the multi-event sequence: real(T1) → null → real(T2 > T1).

- First event at T1 advances the pseudo clock to T1
- Second event with null time: clock stays at T1, detection still works
- Third event at T2 (> T1): clock advances to T2, detection still works

This documents the non-obvious behaviour: null-time events don't regress the clock, and
subsequent real-time events advance correctly from where the clock was left.

### 3. InMemoryDroolsSessionStoreTest buildAll() consistency

One-line change: `kieBuilder.buildAll()` → `kieBuilder.buildAll(ExecutableModelProject.class)`.
Consistency fix, not a test gap — no spec-level treatment needed beyond this note.

## Out of scope

- Changing `Ganglion.detect()` return type — the SPI is correct
- Custom/user-supplied reduction functions — if the four strategies don't fit, handle it in DRL
