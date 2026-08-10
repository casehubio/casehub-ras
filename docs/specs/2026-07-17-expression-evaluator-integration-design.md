# ExpressionEvaluator Integration — Design Spec

**Issue:** casehubio/casehub-ras#46
**Date:** 2026-07-17
**Status:** Implemented

## Problem

RAS YAML situation definitions are purely declarative — no expression support.
Anything beyond static config (correlation key extraction, event filtering,
evidence building) requires dropping to Java. The platform already provides
`ExpressionEvaluator` — JQ, MVEL3, and Lambda behind a common interface.
Wiring it into the YAML path fills this gap.

## Scope

Three use cases from the issue, all extending the situation definition YAML schema:

1. **Correlation key extraction** — expression-derived key from CloudEvent data
2. **Event filtering** — pre-ganglion boolean filter on CloudEvent
3. **Dynamic baseCaseData** — expressions evaluated against SituationContext at trigger time

Out of scope (follow-on issues filed):
- NaiveBayes feature extraction (#47) — needs YAML ganglion config
- Evidence extraction templates (#48) — ganglion-level convenience

## Dependencies

- `casehub-platform-api` — `ExpressionEvaluator`, `StringExpressionEvaluator`,
  `ExpressionEngine`, `ExpressionEngineRegistry`, `CompiledExpression`
  (already a dependency of `api/`)
- `casehub-platform` — `NoOpExpressionEngineRegistry` (`@DefaultBean`, throws on
  `compile()`) — already a dependency of `runtime/`
- `casehub-platform-expression` — `DefaultExpressionEngineRegistry` and engine
  implementations (JQ, MVEL3). **Opt-in module**: RAS runtime does NOT depend on it
  directly. Deployers add it to the classpath when expressions are needed, consistent
  with the platform's opt-in module pattern. RAS integration tests depend on it at
  test scope. **Prerequisite:** `JQExpressionEngine` must support `Map` context type
  (#49) — currently it only accepts `JsonNode` context, incompatible with the
  `Map<String, Object>` expression contexts used by RAS.

## Design Decisions

### Expression descriptors on SituationDefinition

Expression configuration lives on `SituationDefinition`, not `SituationRegistration`.
`SituationDefinition` already carries operational config (`CaseTriggerConfig`,
`TriggerMode`, `eventBufferDelay`) — it's a complete declarative configuration, not
a pure declaration. Expression descriptors serialize with the definition, and dynamic
registration via `SituationDefinitionRegistry` gets expression support automatically.

### Registry compiles (Approach B)

`SituationDefinitionRegistry` owns expression compilation, not the YAML provider.
Any registration path — YAML, programmatic, dynamic — gets expressions compiled
automatically. Central compilation means consistent fail-fast behavior and one
place to maintain. The YAML provider just creates definitions with descriptors.

### CaseTrigger SPI unchanged

`DefaultCaseTrigger` looks up compiled dynamic-data expressions from
`SituationDefinitionRegistry` by situationId at trigger time. The `CaseTrigger`
SPI signature stays unchanged — no API break for alternative trigger implementations.

## Type Changes

### platform-api/

**New: StringExpressionEvaluator** — sub-interface for expression evaluators that
carry a string expression (as opposed to `LambdaExpression` which is pre-compiled):

```java
public interface StringExpressionEvaluator extends ExpressionEvaluator {
    String expression();
}
```

`JQExpressionEvaluator` and `MvelExpressionEvaluator` change to implement
`StringExpressionEvaluator` instead of `ExpressionEvaluator` directly. Both are
records with an `expression()` component, so no code changes are needed beyond
the `implements` clause.

### api/

**SituationDefinition** — three new nullable fields:

```java
record SituationDefinition(
    String situationId,
    Set<String> eventTypes,
    Duration correlationWindow,
    Duration eventBufferDelay,
    ChainMode chainMode,
    TriggerAction triggerAction,
    TriggerMode triggerMode,
    ExpressionEvaluator correlationKeyExpression,     // @Nullable
    ExpressionEvaluator eventFilter,                  // @Nullable
    Map<String, ExpressionEvaluator> dynamicCaseData  // defaults to empty
) { ... }
```

`ExpressionEvaluator` from `casehub-platform-api`. Concrete instances:
`JQExpressionEvaluator(expression)`, `MvelExpressionEvaluator(expression)`,
`LambdaExpression<C,R>(function)`.

All expression descriptors live on `SituationDefinition` — this keeps the pattern
consistent: definition carries descriptors, registration carries compiled results.
`dynamicCaseData` is only semantically relevant for `TriggerAction.CreateCase` but
is validated at compilation time (same as `correlationKeyExpression` being optional).

**CaseTriggerConfig** — unchanged. Static case data only:

```java
record CaseTriggerConfig(
    String caseNamespace,
    String caseName,
    String caseVersion,
    Map<String, Object> baseCaseData
) { ... }
```

**New: EventFilter** — functional interface:

```java
@FunctionalInterface
public interface EventFilter {
    boolean accepts(CloudEvent event);
}
```

**SituationRegistration** — gains compiled filter and compiled dynamic data:

```java
record SituationRegistration(
    SituationDefinition definition,
    CorrelationKeyExtractor correlationKeyExtractor,
    EventFilter eventFilter,                                      // @Nullable
    Map<String, CompiledExpression<Map, Object>> compiledDynamicData  // @Nullable
) { ... }
```

Existing single-arg and two-arg constructors continue to work (filter and
compiledDynamicData default to null). All compiled expression results live on
the registration — no separate cache needed.

### runtime/

**CloudEventExpressionContext** — builds expression context from CloudEvent:

```java
Map<String, Object> ctx = new LinkedHashMap<>();
ctx.put("type",      event.getType());
ctx.put("source",    event.getSource().toString());
ctx.put("subject",   event.getSubject());       // may be null
ctx.put("id",        event.getId());
ctx.put("time",      event.getTime());           // may be null
ctx.put("tenancyid", event.getExtension("tenancyid"));
ctx.put("data",      parseJsonData(event));      // Map<String,Object> from JSON
```

Uses `LinkedHashMap` (not `Map.of()`) because `subject`, `time`, and `tenancyid`
can be null. Data nested under `data` — no collision with CloudEvent metadata.

`parseJsonData(event)` checks `event.getDataContentType()` — only parses when the
content type is `application/json` or starts with `application/` and ends with `+json`.
Non-JSON data (binary, protobuf, XML, plain text) produces an empty map for `data`.
JSON parsing via Jackson ObjectMapper (already on runtime classpath).

Null handling for expression results: correlation key expressions that return null
fall back to `"_singleton"` (matching `DefaultCorrelationKeyExtractor` behavior).
Event filter expressions that return null are treated as `false` (event filtered out).

**SituationContextExpressionContext** — builds expression context from SituationContext:

```java
Map<String, Object> ctx = new LinkedHashMap<>();
ctx.put("situationId",    context.situationId());
ctx.put("correlationKey", context.correlationKey());
ctx.put("tenancyId",      context.tenancyId());
ctx.put("firstSignal",    context.firstSignal());
ctx.put("lastSignal",     context.lastSignal());
ctx.put("lastTriggered",  context.lastTriggered());  // nullable — null means never triggered
ctx.put("triggerCount",   context.triggerCount());
ctx.put("detections",     context.detections());
```

Uses `LinkedHashMap` because `lastTriggered` can be null. Includes `lastTriggered`
for expressions like "time since last trigger" or "is this the first trigger."
Excludes `storeVersion` (internal persistence concern).

## Compilation Flow

`SituationDefinitionRegistry` injects `ExpressionEngineRegistry` (always satisfied —
`NoOpExpressionEngineRegistry` is the `@DefaultBean` fallback).

On `register()` (and during startup aggregation), a private `compileExpression()`
method handles the three evaluator cases:

```java
private <C, R> CompiledExpression<C, R> compileExpression(
        ExpressionEvaluator evaluator, String situationId,
        Class<C> contextType, Class<R> resultType) {
    // Already compiled (LambdaExpression) — use directly
    if (evaluator instanceof CompiledExpression<?,?> compiled) {
        return (CompiledExpression<C, R>) compiled;
    }
    // String-based expression — compile via registry
    if (evaluator instanceof StringExpressionEvaluator stringEval) {
        if (registry.resolve(stringEval.type()).isEmpty()) {
            throw new IllegalStateException(
                "Situation '" + situationId + "' uses expression type '"
                + stringEval.type() + "' but no ExpressionEngine is registered for it");
        }
        return registry.compile(stringEval.type(), stringEval.expression(),
                                contextType, resultType);
    }
    throw new IllegalStateException(
        "Unknown ExpressionEvaluator type: " + evaluator.getClass().getName());
}
```

On `register()`:

1. If `definition.correlationKeyExpression()` is non-null:
   - Compile as `CompiledExpression<Map, String>`
   - Wrap in a `CorrelationKeyExtractor` using `CloudEventExpressionContext`
   - Replace the registration's extractor

2. If `definition.eventFilter()` is non-null:
   - Compile as `CompiledExpression<Map, Boolean>`
   - Wrap in an `EventFilter` using `CloudEventExpressionContext`
   - Set on the registration

3. If `definition.dynamicCaseData()` is non-empty:
   - Compile each as `CompiledExpression<Map, Object>`
   - Store on the `SituationRegistration` as `compiledDynamicData`
   - Accessor: `registry.getCompiledDynamicData(situationId)` reads from the
     registration in the snapshot

**JQ engine adaptation:** `JQExpressionEngine` currently creates
`CompiledExpression<JsonNode, ?>` regardless of `contextType` — it does not
support `Map` context (#49). This prerequisite platform change adds a
`MapAdapterJQExpression` (mirroring MVEL's `PojoAdapterMvelExpression`) that
converts `Map<String, Object>` → `JsonNode` via `ObjectMapper.valueToTree()`
and unwraps `List<JsonNode>` results into scalar values when `resultType` is not
`List`. With this fix, both engines honour the `contextType`/`resultType` contract,
and RAS compilation works identically for JQ and MVEL.

**Snapshot index:** `RegistrySnapshot` gains a `Map<String, SituationRegistration>
bySituationId` index, computed during `buildSnapshot()`. This enables O(1)
`getCompiledDynamicData(situationId)` lookups from `DefaultCaseTrigger` in the
hot path, rather than scanning all registrations across event type lists.

**Fail-fast:** `registry.resolve(type).isEmpty()` before compiling — produces a
precise error naming both the situation and the missing engine type. Works whether
the `NoOpExpressionEngineRegistry` or the real registry is active (both return
`Optional.empty()` for unregistered types).

**LambdaExpression type safety:** The unchecked cast from `CompiledExpression<?,?>`
to `CompiledExpression<C,R>` for `LambdaExpression` bypasses result type
verification due to Java type erasure. A Java DSL user could pass a
`LambdaExpression<Map, String>` as an `eventFilter` (expects `Boolean`). This is
a known limitation of the LambdaExpression path — string-based evaluators get type
validation from the engine's `compile()`, but lambdas are pre-compiled with
caller-specified types. A type mismatch surfaces as `ClassCastException` at first
evaluation, not at registration time. Documenting rather than adding a synthetic
`eval()` check at registration: there is no meaningful dummy context to evaluate
against, and the failure mode is immediate and obvious.

**Precedence:** if definition has `correlationKeyExpression` AND registration has
non-default extractor, expression wins (definition is the spec). Log warning.

**Deregistration:** `deregister(situationId)` removes the registration from the
snapshot — compiled expressions are garbage-collected with the registration. No
separate cache to clear.

## Processing Pipeline Changes

### RasEngine.onCloudEvent()

Event filter check added inside the registration loop, before correlation key
extraction. `CloudEventExpressionContext` is built **once per event** before the
loop and shared across registrations — avoids redundant JSON parsing of event data
when an event matches multiple situations:

```java
Map<String, Object> eventCtx = CloudEventExpressionContext.build(event);
for (SituationRegistration reg : registrations) {
    if (reg.eventFilter() != null && !reg.eventFilter().accepts(event)) {
        metrics.eventFiltered(reg.definition().situationId(), tenancyId);
        continue;
    }
    String correlationKey = reg.correlationKeyExtractor().extract(event);
    // ... existing evaluation flow
}
```

The `eventCtx` map is passed to the filter and correlation key expression wrappers
(both evaluate against the CloudEvent context). This is purely a performance concern
— the context is immutable and safe to share.

New metric: `ras.events.filtered` counter tagged by `situation_id`.

### DefaultCaseTrigger — new dependency and buildInputData()

`DefaultCaseTrigger` gains `SituationDefinitionRegistry` as a new constructor
parameter (in addition to existing `Instance<CaseHub>` and
`Instance<CaseInputContributor>`).

Dynamic data resolution after static data, before correlation metadata:

```java
Map<String, CompiledExpression<Map, Object>> dynamicExprs =
    registry.getCompiledDynamicData(context.situationId());
if (dynamicExprs != null) {
    Map<String, Object> exprCtx = SituationContextExpressionContext.build(context);
    for (var entry : dynamicExprs.entrySet()) {
        data.put(entry.getKey(), entry.getValue().eval(exprCtx));
    }
}
```

Merge order: static baseCaseData → dynamic expressions → correlation metadata →
CaseInputContributors. Correlation metadata (situationId, correlationKey,
tenancyId, detections) always overrides dynamic expressions — a dynamic expression
keyed `"situationId"` cannot corrupt system-generated values. CaseInputContributors
override everything (they are Java code with full control).

### SituationEvaluator

No changes. Filter is pre-evaluator (RasEngine), dynamic data is post-evaluator
(DefaultCaseTrigger).

## Expression Error Handling

Expression evaluation can throw at three hot-path points. The principle: expression
errors are **non-fatal with degraded behavior** and a specific error metric
(`ras.expression.error` tagged by `situation_id` and `expression_point`).
Configuration errors (wrong expression syntax) are caught at registration time
via fail-fast compilation. Runtime errors (unexpected data shape, null dereference
inside expression) degrade gracefully:

1. **Event filter throws** — treat as `true` (let the event through). A broken
   filter degrades to "no filter" rather than silently dropping all events for
   the situation. Log warning + increment error metric.

2. **Correlation key expression throws** — fall back to
   `DefaultCorrelationKeyExtractor` behavior (event subject or `"_singleton"`).
   Log warning + increment error metric.

3. **Dynamic case data expression throws** — skip the failed expression key,
   include all successfully evaluated keys. The case is still created with
   partial dynamic data. Log warning + increment error metric per failed key.

## YAML Schema

```yaml
situations:
  - situationId: sla-breach
    eventTypes: [order.status, order.escalation]
    correlationWindow: PT30M

    correlationKey:
      expression: ".data.orderId"
      language: jq

    eventFilter:
      expression: "data.severity >= 3 && data.source != \"test\""
      language: mvel

    dynamicCaseData:
      orderId:
        expression: ".correlationKey"
        language: jq
      lastSeverity:
        expression: ".detections[-1].result.evidence.severity"
        language: jq

    chainMode:
      type: threshold
      ganglia: [sla-detector, escalation-detector]
      minConfidence: 0.7

    triggerAction:
      type: create-case
      caseNamespace: ops
      caseName: sla-breach
      caseVersion: "1.0"
      baseCaseData:
        category: sla
```

All three expression fields optional. `{expression, language}` parsed into
ExpressionEvaluator instances: `jq` → `JQExpressionEvaluator`,
`mvel` → `MvelExpressionEvaluator`. Unknown language values fail at parse time.
`dynamicCaseData` at the situation level (not under `triggerAction`) — consistent
with the data model where all expression descriptors live on `SituationDefinition`.
Only relevant for `TriggerAction.CreateCase`; ignored for `NotifyOnly`.

## Module Impact

| Module | Changes |
|--------|---------|
| `platform-api/` | New `StringExpressionEvaluator` interface; `JQExpressionEvaluator` and `MvelExpressionEvaluator` implement it |
| `api/` | SituationDefinition (3 new fields), SituationRegistration (2 new fields); new EventFilter interface. CaseTriggerConfig unchanged |
| `runtime/` | SituationDefinitionRegistry (compilation), YamlSituationDefinitionProvider (YAML parsing), RasEngine (filter check), DefaultCaseTrigger (new registry dependency + dynamic data), CloudEventExpressionContext, SituationContextExpressionContext |
| `persistence-jpa/` | No changes (SituationContext/entity unchanged) |
| `ras-drools/` | No changes (Ganglion SPI unchanged) |
| `testing/` | No changes |

## Testing Strategy

**api/ unit tests:**
- SituationDefinition construction with expression fields (all three)
- SituationRegistration four-arg constructor, backwards-compatible two-arg and one-arg
- EventFilter functional interface contract (`accepts()`)

**runtime/ unit tests:**
- CloudEventExpressionContext — correct map from CloudEvent; non-JSON data produces empty `data` map
- SituationContextExpressionContext — correct map from SituationContext including `lastTriggered`
- YamlSituationDefinitionProvider — parses correlationKey, eventFilter, dynamicCaseData YAML;
  absent fields default to null; unknown language rejects
- SituationDefinitionRegistry — compiles expressions at registration, replaces
  extractor/filter, stores compiled dynamic data on registration, fail-fast on missing engine,
  LambdaExpression pass-through (no compilation), deregistration removes from snapshot
- RasEngine — filtered events skip evaluation (metric incremented), unfiltered pass through;
  filter expression error lets event through (metric incremented)
- DefaultCaseTrigger — dynamic expressions resolved, merged in correct order
  (static → dynamic → metadata → contributors), expression error skips key with metric

**Integration test:**
- End-to-end: YAML with all three features → CloudEvent → filter → correlate by expression →
  trigger case with dynamic data. Requires `casehub-platform-expression` on test classpath.

## Garden Context

- **GE-20260714-550161** — MVEL3 `contains` keyword shadows `String.contains()`. Use `indexOf()`.
- **GE-20260715-01a695** — MVEL3 single-quoted strings fail. Must use double-quoted strings.

Both relevant for documentation/examples — MVEL3 expressions in YAML must use
double quotes and avoid `contains`.

## Three-Tier Model Preserved

| Tier | Path | Dynamism |
|------|------|----------|
| Java DSL | `SituationDefinitionProvider`, `JavaSwitchGanglion`, custom `CorrelationKeyExtractor` | Static (classpath) |
| YAML | `YamlSituationDefinitionProvider` | Dynamic (runtime) |
| YAML + expressions | YAML with `ExpressionEvaluator` fields | Dynamic (runtime) — deployer chooses expression language |

Java DSL path untouched. Expression support extends the YAML path only. Dynamic
registration via `SituationDefinitionRegistry` gets expression support automatically.
