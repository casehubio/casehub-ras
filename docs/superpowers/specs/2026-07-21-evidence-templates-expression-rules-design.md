# Evidence Extraction Templates + Expression-Rule Ganglion — Design Spec

**Issues:** casehubio/casehub-ras#48, casehubio/casehub-ras#50
**Date:** 2026-07-21
**Status:** Draft

## Problem

Two gaps in the declarative ganglion pipeline:

1. **#48 — Evidence extraction boilerplate.** Simple ganglia (especially JavaSwitchGanglion
   subclasses) often just pull fields from event data into evidence maps. No YAML-only path
   exists — every ganglion that wants evidence needs Java code. YAML-declared ganglia
   (NaiveBayes) produce hardcoded evidence keys; there's no way to add application-specific
   evidence fields without writing a custom ganglion.

2. **#50 — No declarative condition→signal ganglion.** The `ganglia:` section in
   `ras-situations.yaml` supports `type: naive-bayes` for Bayesian classification, but
   simple if/then detection (e.g., "severity HIGH → DETECTED, MEDIUM → WEAK") requires
   a Java `JavaSwitchGanglion` subclass. An expression-rule ganglion would allow YAML-only
   detection for the common case.

## Scope

- Add `evidenceTemplates` as a cross-cutting feature on `GanglionDescriptor` (any variant)
- Add `EvidenceExtractingGanglion` wrapper in runtime/
- Add `GanglionDescriptor.ExpressionRules` sealed variant in api/
- Add `ExpressionRulesGanglion` in runtime/
- Extend YAML parsing for `evidenceTemplates` on all types and `type: expression-rules`
- Extend `SituationDefinitionRegistry.constructGanglion()` for both features

Out of scope:
- Per-rule evidence — ganglion-level templates cover the use case (#51)
- Dynamic confidence expressions — static double per rule (#52)
- SituationContext-based evidence — violates DetectionResult portability invariant
  (not tracked: explicitly precluded by the design constraint below, documented in
  Ganglion interface Javadoc)

## Design Constraint: DetectionResult Portability

`Ganglion.detect()` carries a documented invariant: DetectionResults may be applied to a
different `SituationContext` than the one passed to `detect()` (concurrent-modification
retry in `SituationEvaluator`). Evidence is part of DetectionResult. Therefore evidence
templates must only extract from `CloudEvent` (invariant across retries), never from
`SituationContext` (changes between retries). `dynamicCaseData` handles SituationContext
extraction at trigger time where the context is stable.

## Implementation Order

#48 first (evidence templates), then #50 (expression-rules). Evidence templates are
foundational infrastructure; expression-rules builds on it.

## Design

### 1. GanglionDescriptor Changes (api/)

The sealed interface gains a default method and a new variant:

```java
public sealed interface GanglionDescriptor {

    String ganglionId();
    Set<String> handledEventTypes();
    default Map<String, ExpressionEvaluator> evidenceTemplates() { return Map.of(); }

    record NaiveBayes(
            String ganglionId,
            Set<String> handledEventTypes,
            List<String> outcomes,
            double[] priors,
            Map<String, Feature> features,
            SignalMapping signalMapping,
            Map<String, ExpressionEvaluator> evidenceTemplates
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

    record ExpressionRules(
            String ganglionId,
            Set<String> handledEventTypes,
            List<Rule> rules,
            Map<String, ExpressionEvaluator> evidenceTemplates
    ) implements GanglionDescriptor {

        public record Rule(
                ExpressionEvaluator when,
                DetectionSignal signal,
                double confidence
        ) {}
    }
}
```

**NaiveBayes:** gains `evidenceTemplates` field. Breaks existing callers — pre-release,
acceptable. The record accessor overrides the default method.

**ExpressionRules:** new variant. `Rule.when` is null for "otherwise" clauses — simpler
than a separate sealed variant. `confidence` is static double, not an expression.
`signal` is `DetectionSignal` directly, not a String.

### 2. EvidenceExtractingGanglion (runtime/)

Wrapper that decorates any descriptor-constructed `Ganglion`:

```java
class EvidenceExtractingGanglion implements Ganglion {
    private final Ganglion delegate;
    private final Map<String, CompiledExpression<Map, Object>> templates;
    private final MeterRegistry meterRegistry;  // nullable
}
```

**`detect()` flow:**
1. Call `delegate.detect(event, context)`
2. `.map()` on the Uni: build `CloudEventExpressionContext.build(event)`, evaluate each
   template, merge into DetectionResult's evidence

**Merge semantics:** Start with delegate's evidence, overlay template results. Template
keys overwrite inner ganglion keys on clash — the user explicitly declared those keys.

**Error handling:** Per-template catch. Failed template is skipped, other templates
still evaluate. Metric: `ras.expression.error` with tags
`{ganglion_id, evidence_key, expression_point=evidence_extraction}`.

**Null semantics:** Template expression evaluating to `null` → key omitted from evidence.
Same principle as `ExpressionFeatureExtractor` — null = no evidence, not error.

**Always extracts:** Evidence templates run regardless of signal (NOISE, WEAK, DETECTED,
ANTI). Consistent and useful for debugging.

**Delegation:** `ganglionId()`, `handledEventTypes()`, `compact()`, `close()` delegate
directly to the inner ganglion. The wrapper is transparent.

**Double-parse tradeoff:** When wrapping a ganglion that also evaluates expressions
(e.g., ExpressionRulesGanglion), `CloudEventExpressionContext.build(event)` is called
twice per event — once by the inner ganglion and once by the wrapper. Each call
deserializes the CloudEvent JSON payload. This is a deliberate cost of the wrapper
pattern's clean separation. For typical event payloads (KB-range JSON), the parsing
is sub-millisecond. If profiling reveals this matters for a high-throughput deployment,
`CloudEventExpressionContext` can be optimised with a single-entry cache keyed on
CloudEvent identity.

**Evidence key collision warning:** At construction time (`constructGanglion()`),
if any evidence template key matches a known automatic evidence key for the ganglion
type (ExpressionRules: `matchedRuleIndex`; NaiveBayes: `posterior`, `features`), log
a warning. The merge semantics are unchanged — template keys intentionally overwrite.
The warning catches accidental shadowing of diagnostic evidence.

### 3. ExpressionRulesGanglion (runtime/)

Stateless ganglion — the declarative equivalent of `JavaSwitchGanglion`:

```java
class ExpressionRulesGanglion implements Ganglion {
    private final String ganglionId;
    private final Set<String> handledEventTypes;
    private final List<CompiledRule> rules;
    private final MeterRegistry meterRegistry;  // nullable

    record CompiledRule(
            CompiledExpression<Map, Boolean> when,
            DetectionSignal signal,
            double confidence
    ) {}
}
```

**`detect()` flow:**
1. Build `CloudEventExpressionContext.build(event)`
2. Iterate rules in declaration order
3. For each rule:
   - `when == null` (otherwise): match immediately
   - `when.eval(ctx)` returns `Boolean.TRUE`: match
   - `null` result: no match, try next
   - Exception: skip rule, try next, metric `ras.expression.error` with
     `{ganglion_id, rule_index, expression_point=rule_evaluation}`
4. First match: return `DetectionResult(ganglionId, rule.confidence, rule.signal, Map.of("matchedRuleIndex", ruleIndex))`
5. No match, no otherwise: return `DetectionResult(ganglionId, 0.0, NOISE, Map.of("matchedRuleIndex", -1))`

**Automatic evidence:** `"matchedRuleIndex"` — zero-based index of the matched rule,
`-1` for the implicit NOISE fallback. Always present. `EvidenceExtractingGanglion`
merges template evidence on top of this.

**Stateless contract:** `compact()` returns context unchanged, `close()` is no-op
(default implementations). No `GanglionStateStore` dependency.

### 4. YAML Schema

**Evidence templates on any ganglion type (optional):**

```yaml
ganglia:
  - ganglionId: anomaly-detector
    type: naive-bayes
    handledEventTypes: [sensor.reading]
    # ... existing NaiveBayes fields ...
    evidenceTemplates:
      raw_severity: { expression: ".data.severity", language: jq }
      sensor_id: { expression: ".data.sensorId", language: jq }
```

NaiveBayes's built-in evidence (`posterior`, `features`) is preserved. Templates add
fields alongside.

**New `type: expression-rules`:**

```yaml
  - ganglionId: severity-checker
    type: expression-rules
    handledEventTypes: [sensor.reading]
    rules:
      - when: { expression: ".data.severity == \"HIGH\"", language: jq }
        signal: DETECTED
        confidence: 0.9
      - when: { expression: ".data.severity == \"MEDIUM\"", language: jq }
        signal: WEAK
        confidence: 0.5
      - otherwise:
        signal: NOISE
        confidence: 0.0
    evidenceTemplates:
      severity: { expression: ".data.severity", language: jq }
      source: { expression: ".data.source", language: jq }
```

**Parsing rules:**
- `evidenceTemplates` uses same `{expression, language}` syntax as existing expression fields
- `rules` is an ordered list; each entry has `when` (expression map) or `otherwise`
- `signal` is required per rule — startup error on absence (uses `requireString`).
  Parsed via `DetectionSignal.valueOf(value.toUpperCase())`
- `confidence` is required per rule — startup error on absence (uses `requireNumber`).
  Parsed via `Number.doubleValue()` — validated `0.0 ≤ confidence ≤ 1.0` at parse time
  (startup error on violation, consistent with `DetectionResult` compact constructor
  validation)
- `otherwise` must be last if present — parser validates
- `otherwise` is a presence check — its YAML value is ignored (`otherwise:` → null)
- A rule with both `when` and `otherwise` keys is a startup error (mutual exclusivity)
- A rule with neither `when` nor `otherwise` is a startup error (malformed rule)
- `rules` must be non-empty — startup error otherwise
- `handledEventTypes` must be non-empty — startup error otherwise (consistent with
  NaiveBayes parsing validation)
- All `{expression, language}` map parsing — evidence templates, rule `when` conditions,
  NaiveBayes features, correlationKey, eventFilter, dynamicCaseData — uses a shared
  `parseExpressionEntry(Map<String, Object> exprMap, String context)` method, eliminating
  the current 3-way duplication (5-way after this spec) and providing a single extension
  point for new expression languages

### 5. Registry Integration

`SituationDefinitionRegistry.constructGanglion()` changes:

```java
private Ganglion constructGanglion(GanglionDescriptor descriptor,
                                    GanglionStateStore stateStore,
                                    MeterRegistry meterRegistry) {
    Ganglion ganglion = switch (descriptor) {
        case GanglionDescriptor.NaiveBayes nb ->
                constructNaiveBayes(nb, stateStore, meterRegistry);
        case GanglionDescriptor.ExpressionRules er ->
                constructExpressionRules(er, meterRegistry);
    };

    if (!descriptor.evidenceTemplates().isEmpty()) {
        Map<String, CompiledExpression<Map, Object>> compiled = new LinkedHashMap<>();
        for (var entry : descriptor.evidenceTemplates().entrySet()) {
            compiled.put(entry.getKey(), compileExpression(
                    entry.getValue(), descriptor.ganglionId(), Map.class, Object.class));
        }
        ganglion = new EvidenceExtractingGanglion(
                ganglion, Map.copyOf(compiled), meterRegistry);
    }

    return ganglion;
}
```

- Pattern match is exhaustive — adding a third variant is a compile error
- Evidence wrapping is outside the switch — applied generically to any descriptor
- `constructExpressionRules()` compiles rule expressions with `resultType=Boolean.class`,
  validates otherwise-last ordering, constructs `ExpressionRulesGanglion`
- Evidence templates compile with `resultType=Object.class` — goes through
  `JqResultUnwrapper` for JQ, passes through directly for MVEL

### 6. Type Changes Summary

| Location | Change |
|----------|--------|
| `GanglionDescriptor` (api/) | New default method `evidenceTemplates()`. New `ExpressionRules` variant with nested `Rule` record. |
| `GanglionDescriptor.NaiveBayes` (api/) | New `evidenceTemplates` field (breaks existing callers). |
| `EvidenceExtractingGanglion` (new, runtime/) | Ganglion wrapper — evaluates evidence template expressions, merges into DetectionResult. |
| `ExpressionRulesGanglion` (new, runtime/) | Stateless ganglion — evaluates boolean rules in order, first match wins. |
| `SituationDefinitionRegistry` (runtime/) | `constructGanglion()` handles ExpressionRules, wraps with EvidenceExtractingGanglion. |
| `YamlSituationDefinitionProvider` (runtime/) | Parses `evidenceTemplates` on all types, parses `type: expression-rules`. |

No changes to: `Ganglion` SPI, `SituationEvaluator`, `DefaultCaseTrigger`,
`SituationContext`, `NaiveBayesGanglion`, `ExpressionFeatureExtractor`,
platform expression engine.

### 7. Testing Strategy

**api/ unit tests:**
- `GanglionDescriptor.ExpressionRules` record construction and sealed interface contract
- `ExpressionRules.Rule` with null `when` (otherwise)
- `GanglionDescriptor.evidenceTemplates()` default returns empty map
- NaiveBayes constructor updated for new `evidenceTemplates` parameter

**runtime/ unit tests — EvidenceExtractingGanglion:**
- Merges template evidence with delegate's evidence
- Template keys overwrite delegate keys on clash
- Null expression result → key omitted
- Per-template error isolation
- `ras.expression.error` metric incremented with correct tags
- Delegates ganglionId(), handledEventTypes(), compact(), close()

**runtime/ unit tests — ExpressionRulesGanglion:**
- First matching rule wins (declaration order)
- "Otherwise" matches when no prior rule matches
- No rule matches, no otherwise → NOISE 0.0
- `matchedRuleIndex` in evidence
- Expression returning null → no match, try next
- Expression throwing → skip, try next, metric
- All four signal types: DETECTED, WEAK, NOISE, ANTI

**runtime/ unit tests — YAML parsing:**
- `type: expression-rules` with rules and evidence templates
- Missing/empty rules → startup error
- Otherwise not last → startup error
- Invalid signal string → startup error
- `evidenceTemplates` on `naive-bayes` type
- `evidenceTemplates` absent → empty map
- Confidence out of range (`1.5`, `-0.1`) → startup error
- Both `when` and `otherwise` on same rule → startup error
- Neither `when` nor `otherwise` on a rule → startup error
- `handledEventTypes` empty or missing → startup error
- Missing `signal` in rule → startup error (not raw NPE)
- Missing `confidence` in rule → startup error (not raw NPE)

**runtime/ unit tests — Registry integration:**
- ExpressionRules descriptor → ganglion registered and findable
- Evidence templates → wrapped in EvidenceExtractingGanglion
- NaiveBayes with evidence templates → merged evidence
- ExpressionRules + NaiveBayes coexistence
- Duplicate ganglionId across ExpressionRules and CDI → startup error

**Contract tests (api/ AbstractGanglionContractTest):**
- `ExpressionRulesGanglionContractTest extends AbstractGanglionContractTest` — validates
  ganglionId non-null, handledEventTypes non-empty, detect returns completing Uni,
  compact returns non-null context, close completes
- `EvidenceExtractingGanglionContractTest extends AbstractGanglionContractTest` — validates
  delegation correctness through the wrapper for all SPI contract methods

**Integration test (casehub-platform-expression on test classpath):**
- End-to-end: YAML expression-rules ganglion + evidence templates + situation →
  CloudEvent → rule evaluation → detection → chain mode → trigger → verify evidence
