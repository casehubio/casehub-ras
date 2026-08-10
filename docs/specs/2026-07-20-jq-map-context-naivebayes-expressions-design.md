# JQ Map Context Cleanup + NaiveBayes Expression Feature Extraction — Design Spec

**Issues:** casehubio/casehub-ras#49, casehubio/casehub-ras#47
**Date:** 2026-07-20
**Status:** Approved

## Problem

Two related gaps in the expression-driven detection pipeline:

1. **#49 — JqMapAdapter workaround in RAS.** The platform's `JQExpressionEngine` now
   handles `Map` context types natively (`MapAdaptedJQExpression` in `f66bba4`), but
   RAS still has a local `JqMapAdapter` and special-case branch in
   `SituationDefinitionRegistry.compileExpression()`. This workaround needs removing.
   The platform's adapter delegates raw JQ results without scalar unwrapping, so RAS
   needs a thin result unwrapper for String/Object result types.

2. **#47 — NaiveBayes feature extraction requires Java code.** `NaiveBayesFeatureExtractor`
   is a functional interface — every NaiveBayes ganglion needs a Java lambda or class to
   extract features from CloudEvents. No YAML-only path exists. Additionally, ganglia
   themselves are CDI-only — no declarative configuration mechanism.

## Scope

- Remove `JqMapAdapter` and JQ special-case from `SituationDefinitionRegistry`
- Add JQ result unwrapping for non-Boolean result types
- Add `ganglia:` section to `ras-situations.yaml` with `type` discriminator
- Implement `type: naive-bayes` YAML ganglion parsing with per-feature expression extraction
- Add `ExpressionFeatureExtractor` implementing `NaiveBayesFeatureExtractor`
- YAML ganglia coexist with CDI-declared ganglia in the registry

Out of scope (follow-on issues):
- Expression-rule ganglion (`type: expression-rules`) — filed separately
- Evidence extraction templates (#48)

## Prerequisites (completed)

- `StringExpressionEvaluator` sub-interface in `casehub-platform-api` (`48d82d6`)
- `MapAdaptedJQExpression` in `JQExpressionEngine` (`f66bba4`)
- Both installed locally via `mvn install`

## Design

### 1. JqMapAdapter Removal (#49 RAS cleanup)

**Delete:** `JqMapAdapter` inner class from `SituationDefinitionRegistry`.

**Simplify `compileExpression()`:** Remove the `"jq".equals(stringEval.type()) &&
Map.class.isAssignableFrom(contextType)` branch. All string expression evaluators
go through `expressionRegistry.compile()` with the requested contextType directly.

**JQ result unwrapping:** The platform's `MapAdaptedJQExpression` returns raw JQ
results — `Boolean` for boolean expressions (via `BooleanJQExpression`), `List<JsonNode>`
for everything else (via `ListJQExpression`). For RAS use cases where the result type
is `String` or `Object`, `List<JsonNode>` needs unwrapping to a scalar.

Add a `JqResultUnwrapper<R>` in RAS that wraps a `CompiledExpression<Map, List<JsonNode>>`
and extracts the first element, converting to the target type:
- `String` → `first.isNull() ? null : first.asText()`
- `Boolean` → not needed (handled by `BooleanJQExpression`)
- `Object` → `ObjectMapper.convertValue(first, resultType)`

Applied in `compileExpression()` when the expression type is `"jq"` and the result
type is not `Boolean` and not `List`.

**Workaround acknowledgement:** `JqResultUnwrapper` compensates for the platform's
`JQExpressionEngine.compile()` ignoring the `resultType` parameter for non-Boolean
types — all non-Boolean compilations produce `ListJQExpression` regardless of whether
`String.class` or `Object.class` was requested. Removing the prior `JqMapAdapter`
eliminates the Map→JsonNode context conversion (now handled by the platform's
`MapAdaptedJQExpression`), but result unwrapping remains a RAS-side workaround.

**Platform issue:** casehubio/platform#190 — native result type conversion
in `JQExpressionEngine.compile()` to honour `resultType` for scalar types. When
resolved, `JqResultUnwrapper` can be deleted.

### 2. YAML Ganglia Schema

A `ganglia:` section in `ras-situations.yaml`, parsed before `situations:`. The `type`
discriminator makes the schema extensible for future ganglion types.

```yaml
ganglia:
  - ganglionId: anomaly-detector
    type: naive-bayes
    handledEventTypes: [sensor.reading]
    outcomes: [NORMAL, ANOMALY]
    priors: [0.9, 0.1]
    features:
      severity:
        expression: ".data.severity"
        language: jq
        values: [LOW, MEDIUM, HIGH]
        likelihoods:
          - [0.7, 0.25, 0.05]
          - [0.1, 0.3, 0.6]
      source:
        expression: ".data.source"
        language: jq
        values: [TRUSTED, UNKNOWN]
        likelihoods:
          - [0.9, 0.1]
          - [0.3, 0.7]
    signalMapping:
      targetOutcome: ANOMALY
      detectedThreshold: 0.75
      weakThreshold: 0.30
      antiThreshold: 0.05

situations:
  - situationId: sensor-anomaly
    eventTypes: [sensor.reading]
    correlationWindow: PT1H
    chainMode:
      type: threshold
      ganglia: [anomaly-detector]
      minConfidence: 0.7
    triggerAction:
      type: create-case
      caseNamespace: ops
      caseName: anomaly-investigation
      caseVersion: "1"
```

Each feature co-locates its extraction expression (`expression` + `language`) with its
statistical model (`values` + `likelihoods`). The YAML author defines what a feature is,
how to extract it, and what it means in one block.

**Numeric type coercion:** SnakeYAML parses `0.7` as `Double` but `1` as `Integer`.
YAML like `likelihoods: [[1, 0], [0, 1]]` produces `List<List<Integer>>`, not
`List<List<Double>>`. All numeric values in the ganglia schema (`priors`, `likelihoods`,
`detectedThreshold`, `weakThreshold`, `antiThreshold`) are coerced via
`Number.doubleValue()` during parsing. Semantic validation (sum-to-one, positivity,
threshold ordering) is deferred to the `NaiveBayesConfig` and `FeatureLikelihood`
compact constructors, which produce clear error messages rather than `ClassCastException`.

### 3. ExpressionFeatureExtractor

New class in `runtime/` implementing `NaiveBayesFeatureExtractor`:

```java
class ExpressionFeatureExtractor implements NaiveBayesFeatureExtractor {
    private final String ganglionId;
    private final Map<String, CompiledExpression<Map, String>> featureExpressions;
    private final MeterRegistry meterRegistry;  // nullable

    ExpressionFeatureExtractor(String ganglionId,
                               Map<String, CompiledExpression<Map, String>> featureExpressions,
                               MeterRegistry meterRegistry) {
        this.ganglionId = ganglionId;
        this.featureExpressions = Map.copyOf(featureExpressions);
        this.meterRegistry = meterRegistry;
    }

    @Override
    public Map<String, String> extract(CloudEvent event) {
        Map<String, Object> ctx = CloudEventExpressionContext.build(event);
        Map<String, String> result = new LinkedHashMap<>();
        for (var entry : featureExpressions.entrySet()) {
            try {
                String value = entry.getValue().eval(ctx);
                if (value != null) {
                    result.put(entry.getKey(), value);
                }
            } catch (Exception e) {
                LOG.warning("Feature expression '" + entry.getKey()
                            + "' failed for ganglion '" + ganglionId + "': " + e.getMessage());
                if (meterRegistry != null) {
                    meterRegistry.counter("ras.expression.error",
                            "ganglion_id", ganglionId,
                            "feature_name", entry.getKey(),
                            "expression_point", "feature_extraction").increment();
                }
            }
        }
        return result;
    }
}
```

**Error handling:** Consistent with the expression error handling principle established
in the expression evaluator integration spec (§Expression Error Handling): expression
errors are non-fatal with degraded behavior. Per-feature catch skips the failed feature
— the posterior remains unchanged for that feature, same as if the feature were absent.
Successfully evaluated features are still included. Metric tags: `ganglion_id` +
`feature_name` + `expression_point=feature_extraction`.

**Metrics dependency:** `ExpressionFeatureExtractor` receives a nullable `MeterRegistry`
directly rather than going through `RasMetrics`. This avoids a circular CDI dependency:
`RasMetrics` injects `SituationDefinitionRegistry` in its constructor (for the
`ras.registry.definitions.active` gauge), so the registry cannot inject `RasMetrics`
without creating a cycle. The null-safety pattern (`if (meterRegistry != null)`) matches
`RasMetrics`'s own internal pattern. The registry resolves `Instance<MeterRegistry>` and
passes the result (or null) to `ExpressionFeatureExtractor` during construction.

**Dual tag set for `ras.expression.error`:** This metric now has two tag sets:
`{situation_id, expression_point}` for situation-level expression errors (event filter,
correlation key, dynamic case data — emitted by `RasMetrics.expressionError()`), and
`{ganglion_id, feature_name, expression_point}` for ganglion-level feature extraction
errors. Same metric name, different cardinality — dashboards and alerts should handle
both tag sets.

**Null semantics:** When a feature expression evaluates to `null` (e.g., JQ path
`.data.severity` on an event without a `severity` field), the feature is omitted from
the result map. `NaiveBayesGanglion.detect()` skips unknown features — the posterior
is not updated for that feature. This is safe: null means "no evidence", not "error".
YAML authors can rely on this: expressions against optional event fields do not crash
detection, they simply produce no evidence for that feature.

Expression compilation uses the platform's `ExpressionEngineRegistry` — same path as
correlation keys and event filters: `StringExpressionEvaluator` → `ExpressionEngineRegistry.compile()`
→ `CompiledExpression<Map, String>`. No custom expression handling.

The existing `NaiveBayesFeatureExtractor` interface is unchanged. Programmatic Java
extractors (lambdas, CDI beans) still work.

### 4. Ganglion Descriptors and Parsing

**SPI change:** `SituationDefinitionProvider` gains a default method:

```java
public interface SituationDefinitionProvider {
    List<SituationRegistration> registrations();
    default List<GanglionDescriptor> ganglionDescriptors() { return List.of(); }
}
```

Any provider can declare ganglia — not just the YAML provider. The registry iterates
all providers for descriptors generically with no `instanceof` checks. This matters
because `JpaRuntimeSituationDefinitionProvider` in casehub-iot-webapp already exists
and may want to declare ganglia from database or classpath YAML resources in the future.

**GanglionDescriptor** — sealed interface in `api/`:

```java
public sealed interface GanglionDescriptor {
    String ganglionId();
    Set<String> handledEventTypes();

    record NaiveBayes(
            String ganglionId,
            Set<String> handledEventTypes,
            List<String> outcomes,
            double[] priors,
            Map<String, Feature> features,
            SignalMapping signalMapping
    ) implements GanglionDescriptor {

        public record Feature(
                ExpressionEvaluator expression,
                List<String> values,
                double[][] likelihoods
        ) {}

        public record SignalMapping(
                String targetOutcome,
                double detectedThreshold,
                double weakThreshold,
                Double antiThreshold
        ) {}
    }
}
```

Sealed matches the existing pattern (`ChainMode`, `TriggerAction`). `Feature` carries
an `ExpressionEvaluator` marker (not yet compiled) alongside the statistical model.
`SignalMapping` mirrors the runtime `NaiveBayesSignalMapping` fields — the registry
maps descriptor fields to validated runtime types at construction time. No validation
in the descriptor records themselves; validation is centralised in the `NaiveBayesConfig`,
`FeatureLikelihood`, and `NaiveBayesSignalMapping` compact constructors, which run at
startup during registry construction (fail-fast).

**YAML parsing:** `YamlSituationDefinitionProvider.parse()` grows a `parseGanglia()`
method:

1. Parse the `ganglia:` section (if present) into `GanglionDescriptor.NaiveBayes` records
   with `ExpressionEvaluator` markers (e.g. `JQExpressionEvaluator`)
2. All numeric YAML values coerced via `Number.doubleValue()` (see §2 type coercion)
3. Return descriptors via `ganglionDescriptors()` override

The provider does NOT compile expressions or construct `NaiveBayesGanglion` instances.

### 5. Registry Integration

The constructor becomes three-phase to ensure YAML-declared ganglia are in `gangliaById`
before any situation validation runs. This is critical because YAML situations reference
YAML ganglia by ID (e.g. `sensor-anomaly` references `anomaly-detector`), and `validate()`
checks `gangliaById.get(ganglionId)`.

**Phase 1 — Descriptor ganglia.** Collect `GanglionDescriptor` records from all providers
via `ganglionDescriptors()`. For each `NaiveBayes` descriptor: compile feature expressions
via `ExpressionEngineRegistry`, construct `ExpressionFeatureExtractor`, build
`NaiveBayesConfig` (which validates priors, likelihoods, signal mapping via compact
constructors), construct `NaiveBayesGanglion`, add to `gangliaById`. Duplicate
`ganglionId` within descriptors is a startup error.

**Phase 2 — CDI ganglia.** Iterate `Instance<Ganglion>` (CDI-declared ganglia), add to
`gangliaById`. Duplicate `ganglionId` between descriptor and CDI ganglia is a startup
error with a message naming both sources.

**Phase 3 — Situation registrations.** Iterate all providers for `registrations()`,
validate each definition (all ganglion IDs now resolvable in `gangliaById`), compile
situation expressions, build snapshot.

```java
SituationDefinitionRegistry(List<SituationDefinitionProvider> providers,
                            List<Ganglion> cdiGanglia,
                            ExpressionEngineRegistry expressionRegistry,
                            GanglionStateStore stateStore,
                            MeterRegistry meterRegistry) {  // nullable
    this.expressionRegistry = expressionRegistry;
    this.gangliaById = new HashMap<>();

    // Phase 1: descriptor ganglia (before CDI ganglia and before situation validation)
    for (var provider : providers) {
        for (var descriptor : provider.ganglionDescriptors()) {
            try {
                Ganglion ganglion = constructGanglion(descriptor, stateStore, meterRegistry);
                if (gangliaById.putIfAbsent(ganglion.ganglionId(), ganglion) != null) {
                    throw new IllegalStateException(
                        "Duplicate ganglionId: " + ganglion.ganglionId());
                }
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(
                    "Invalid ganglion descriptor '" + descriptor.ganglionId()
                    + "': " + e.getMessage(), e);
            }
        }
    }

    // Phase 2: CDI ganglia
    for (Ganglion g : cdiGanglia) {
        if (gangliaById.putIfAbsent(g.ganglionId(), g) != null) {
            throw new IllegalStateException("Duplicate ganglionId '" + g.ganglionId()
                + "' — declared in both YAML descriptor and CDI: " + g.getClass().getName());
        }
    }

    // Phase 3: situation registrations (all ganglia now in gangliaById)
    List<SituationRegistration> allRegistrations = new ArrayList<>();
    for (var provider : providers) {
        for (var reg : provider.registrations()) {
            validate(reg.definition());  // gangliaById lookups succeed
            allRegistrations.add(compileRegistration(reg));
        }
    }
    this.snapshot = buildSnapshot(allRegistrations);
}
```

**GanglionStateStore injection:** The registry receives `GanglionStateStore` via CDI
(`Instance<GanglionStateStore>`) and passes it to descriptor-constructed
`NaiveBayesGanglion` instances. Same store instance used by CDI-declared ganglia —
`InMemoryGanglionStateStore` (`@DefaultBean`) or `JpaGanglionStateStore` depending
on classpath.

### 6. Type Changes Summary

| Location | Change |
|----------|--------|
| `SituationDefinitionProvider` (api/) | New default method `ganglionDescriptors()` returning `List<GanglionDescriptor>`. |
| `GanglionDescriptor` (new, api/) | Sealed interface with `NaiveBayes` variant. Nested `Feature` and `SignalMapping` records. |
| `SituationDefinitionRegistry` | Delete `JqMapAdapter`. Add `JqResultUnwrapper`. Three-phase constructor: descriptor ganglia → CDI ganglia → situation registrations. Compile feature expressions. Construct NaiveBayes ganglia. Inject `GanglionStateStore` and `Instance<MeterRegistry>`. Validation errors wrapped with ganglion context. |
| `YamlSituationDefinitionProvider` | Parse `ganglia:` section. Override `ganglionDescriptors()` to return `GanglionDescriptor.NaiveBayes` records. |
| `ExpressionFeatureExtractor` (new) | Implements `NaiveBayesFeatureExtractor` using compiled expressions. Per-feature error handling with `ras.expression.error` metric. Receives nullable `MeterRegistry` directly (not `RasMetrics` — avoids circular CDI dependency). |
| `JqResultUnwrapper` (new) | Wraps `CompiledExpression<Map, List<JsonNode>>`, unwraps first element to target type. Workaround for platform's incomplete `resultType` handling. |

No changes to: `NaiveBayesGanglion`, `NaiveBayesConfig`, `NaiveBayesFeatureExtractor`,
`FeatureLikelihood`, `NaiveBayesSignalMapping`, `Ganglion` SPI, `SituationDefinition`,
`ExpressionEvaluator`, platform expression engine.

### 7. Testing Strategy

**api/ unit tests:**
- `GanglionDescriptor.NaiveBayes` record construction and sealed interface contract
- `SituationDefinitionProvider.ganglionDescriptors()` default returns empty list

**runtime/ unit tests:**
- **JqResultUnwrapper:** String extraction from single-element list, null JsonNode → null,
  Object conversion via ObjectMapper, empty list handling
- **YAML ganglia parsing:** valid NaiveBayes ganglion, missing required fields (ganglionId,
  outcomes, priors), unknown `type` value, malformed likelihoods (wrong dimensions),
  numeric type coercion (`Integer` → `double` via `Number.doubleValue()`), missing
  features section
- **ExpressionFeatureExtractor:** multi-feature extraction from CloudEvent, null result
  skipping (feature absent from map), expression error per-feature catch (failed feature
  skipped, successful features included), `ras.expression.error` metric incremented on
  failure
- **Registry integration:** descriptor ganglion registered and findable via `ganglion()`,
  CDI + descriptor coexistence in `gangliaById`, duplicate ganglionId detection across
  descriptor and CDI sources, YAML situation referencing descriptor ganglion (three-phase
  ordering test), `GanglionStateStore` passed to descriptor-constructed ganglia
- **Constructor ordering:** YAML ganglion defined in same file as YAML situation
  referencing it — validates three-phase constructor resolves ganglionId before
  `validate()` runs

**Integration test:**
- End-to-end: YAML with `ganglia:` + `situations:` sections → CloudEvent → NaiveBayes
  feature extraction via JQ expressions → detection → threshold chain mode → trigger.
  Requires `casehub-platform-expression` on test classpath.
