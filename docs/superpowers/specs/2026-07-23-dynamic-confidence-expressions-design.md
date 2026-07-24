# Dynamic Confidence Expressions — Design Spec

**Issue:** casehubio/casehub-ras#52
**Date:** 2026-07-23
**Status:** Draft

## Problem

Expression-rules ganglion requires a static `double` for each rule's confidence value.
This prevents confidence from varying based on event payload — e.g., deriving confidence
from a severity score field rather than hardcoding 0.9.

## Scope

- Add optional `confidenceExpression` field to `GanglionDescriptor.ExpressionRules.Rule`
- Add confidence expression compilation to `SituationDefinitionRegistry`
- Add dynamic confidence resolution to `ExpressionRulesGanglion`
- Extend YAML parsing for `confidenceExpression` on rules

Out of scope:
- NaiveBayesGanglion — Bayesian classification computes confidence from posteriors, not
  per-observation values. Dynamic confidence doesn't apply to that model.
- `DetectionResult`, `SituationContext`, `SituationEvaluator`, `ChainMode`, or any SPI —
  all dynamic computation is internal to `ExpressionRulesGanglion`.

## Design Constraint: Always-Valid Fallback

Every rule always carries a static `confidence` (a number, validated `0.0 ≤ x ≤ 1.0` at
parse time). The expression is an optional override. This guarantees every rule can always
produce a valid `DetectionResult` even if the expression fails, returns nonsense, or
encounters data it wasn't designed for. You cannot deploy a ganglion where a rule can only
produce invalid confidence.

## Design

### 1. Type Changes (api/)

`GanglionDescriptor.ExpressionRules.Rule` gains one nullable field:

```java
public record Rule(
        ExpressionEvaluator when,
        DetectionSignal signal,
        double confidence,
        ExpressionEvaluator confidenceExpression    // nullable — dynamic override
) {}
```

When `confidenceExpression` is null, behavior is identical to current (static only).
When present, the expression is evaluated first; the static `confidence` is the fallback.

### 2. Compiled Rule (runtime/)

`ExpressionRulesGanglion.CompiledRule` gains a matching compiled field:

```java
record CompiledRule(
        CompiledExpression<Map, Boolean> when,
        DetectionSignal signal,
        double confidence,
        CompiledExpression<Map, Object> confidenceExpression   // nullable
) {}
```

Result type is `Object` (not `Double`) because JQ returns wrapped types via
`JqResultUnwrapper` and MVEL returns whatever the expression evaluates to.
Runtime conversion handles `Number → doubleValue()`.

### 3. YAML Schema

```yaml
ganglia:
  - ganglionId: severity-checker
    type: expression-rules
    handledEventTypes: [sensor.reading]
    rules:
      # Static only (current behavior, unchanged)
      - when: { expression: ".data.severity == \"HIGH\"", language: jq }
        signal: DETECTED
        confidence: 0.9

      # Static + dynamic expression
      - when: { expression: ".data.severity == \"MEDIUM\"", language: jq }
        signal: WEAK
        confidence: 0.5
        confidenceExpression:
          expression: ".data.score / 100"
          language: jq

      # Otherwise with dynamic confidence
      - otherwise:
        signal: NOISE
        confidence: 0.0
        confidenceExpression:
          expression: ".data.baselineScore / 100"
          language: jq
```

**Parsing rules:**
- `confidence` remains required on every rule — always a number, validated `0.0 ≤ x ≤ 1.0`
  at parse time (unchanged)
- `confidenceExpression` is optional — parsed via existing `parseExpressionEntry()`,
  same `{expression, language}` convention as all other expression fields
- Omitting `confidenceExpression` → current behavior (static only)

### 4. Runtime Confidence Resolution

`ExpressionRulesGanglion` resolves confidence via a private method called from
`detect()` at both `DetectionResult` construction sites — the otherwise path
(`rule.when() == null`) and the matching-rule path (`Boolean.TRUE.equals(match)`).
Both change `rule.confidence()` to `resolveConfidence(rule, i, ctx)`. The implicit
NOISE fallback at the end of `detect()` (no rule matched, hardcoded `0.0`) is
unchanged.

```java
private double resolveConfidence(CompiledRule rule, int index, Map<String, Object> ctx) {
    if (rule.confidenceExpression() == null) {
        return rule.confidence();
    }
    try {
        Object result = rule.confidenceExpression().eval(ctx);
        if (result instanceof Number n) {
            double val = n.doubleValue();
            if (Double.isNaN(val) || Double.isInfinite(val)) {
                LOG.warning("Confidence expression returned " + val + " for rule " + index
                            + " in ganglion '" + ganglionId + "', using fallback");
                incrementErrorMetric(index);
                return rule.confidence();
            }
            if (val < 0.0 || val > 1.0) {
                LOG.warning("Confidence expression returned " + val + " for rule " + index
                            + " in ganglion '" + ganglionId + "', clamping to [0.0, 1.0]");
                incrementClampedMetric(index);
                return Math.max(0.0, Math.min(1.0, val));
            }
            return val;
        } else {
            if (result != null) {
                LOG.warning("Confidence expression returned non-numeric " + result.getClass()
                            + " for rule " + index + " in ganglion '" + ganglionId
                            + "', using fallback");
                incrementErrorMetric(index);
            }
            return rule.confidence();
        }
    } catch (RuntimeException ex) {
        LOG.warning("Confidence expression failed for rule " + index
                    + " in ganglion '" + ganglionId + "': " + ex.getMessage());
        incrementErrorMetric(index);
        return rule.confidence();
    }
}
```

**Decision table:**

| Expression result | Behavior |
|---|---|
| `Number` in `[0.0, 1.0]` | Use it |
| Finite `Number` outside range | Clamp to `[0.0, 1.0]`, metric |
| `NaN` or `Infinity (±)` | Fallback to static, metric |
| `null` | Fallback to static (silent — null means "no value") |
| Non-numeric (String, etc.) | Fallback to static, metric |
| Exception | Fallback to static, metric |
| No expression on rule | Static directly (no overhead) |

**Clamp vs. fallback rationale:** A finite out-of-range number means the expression
evaluated successfully — the computation is sound but the output range is unbounded
(e.g., a linear scale that produces `1.05`). Clamping preserves the dynamic signal.
`NaN`, `Infinity`, non-numeric results, and exceptions indicate the expression is
fundamentally broken — wrong type, undefined operation, or division by zero. Fallback
to the static value protects against silently masking expression bugs.

**Context reuse:** The `CloudEventExpressionContext.build(event)` map is already
constructed at the top of `detect()` for `when` evaluation. The confidence
expression evaluates against the same map — no additional JSON parsing.

### 5. Metrics

Reuses existing `ras.expression.error` counter with tags
`{ganglion_id, rule_index, expression_point}`:

| expression_point | When |
|---|---|
| `confidence_evaluation` | NaN, Infinity (±), non-numeric, exception |
| `confidence_clamped` | Finite out-of-range result clamped to `[0.0, 1.0]` |

These use the same tag structure as the existing `rule_evaluation` value in
`ExpressionRulesGanglion`. (The `evidence_extraction` value in
`EvidenceExtractingGanglion` shares the counter name but uses different tags —
`{ganglion_id, evidence_key, expression_point}` rather than
`{ganglion_id, rule_index, expression_point}`.)

### 6. Registry Compilation

`SituationDefinitionRegistry.constructExpressionRules()` — one addition to the
per-rule compilation loop:

```java
CompiledExpression<Map, Object> compiledConfidence = null;
if (rule.confidenceExpression() != null) {
    compiledConfidence = compileExpression(
            rule.confidenceExpression(), er.ganglionId(), Map.class, Object.class);
}
compiledRules.add(new ExpressionRulesGanglion.CompiledRule(
        compiled, rule.signal(), rule.confidence(),
        compiledConfidence));
```

Result type `Object.class` — same as evidence templates. JQ results go through
`JqResultUnwrapper` automatically. MVEL results pass through directly.

### 7. Changes Summary

| Location | Change |
|----------|--------|
| `GanglionDescriptor.ExpressionRules.Rule` (api/) | New `confidenceExpression` field (nullable `ExpressionEvaluator`) |
| `ExpressionRulesGanglion` (runtime/) | `resolveConfidence()` method, `CompiledRule` gains nullable compiled expression |
| `YamlSituationDefinitionProvider` (runtime/) | Parse optional `confidenceExpression` on rules |
| `SituationDefinitionRegistry` (runtime/) | Compile confidence expression in `constructExpressionRules()` |

No changes to: `DetectionResult`, `Ganglion` SPI, `SituationEvaluator`,
`SituationContext`, `ChainMode`, `EvidenceExtractingGanglion`, `NaiveBayesGanglion`,
`DefaultRasTriggerPolicy`, `CaseTrigger`, `SituationChangeEvent`, platform expression
engine.

## Testing Strategy

### api/ unit tests — GanglionDescriptorTest

- `ExpressionRules.Rule` construction with `confidenceExpression` present
- `ExpressionRules.Rule` construction with `confidenceExpression` null (backward compat)

### runtime/ unit tests — ExpressionRulesGanglionTest

- Static confidence only (no expression) — unchanged behavior
- Dynamic confidence — expression returns valid number in range → used
- Dynamic confidence — expression returns number > 1.0 → clamped to 1.0
- Dynamic confidence — expression returns number < 0.0 → clamped to 0.0
- Dynamic confidence — expression returns NaN → fallback to static, metric
- Dynamic confidence — expression returns null → fallback to static (silent)
- Dynamic confidence — expression returns non-numeric (String) → fallback to static, metric
- Dynamic confidence — expression throws → fallback to static, metric
- Dynamic confidence — expression returns `Double.POSITIVE_INFINITY` → fallback to static, metric
- Dynamic confidence — expression returns `Double.NEGATIVE_INFINITY` → fallback to static, metric
- Dynamic confidence on otherwise rule
- `ras.expression.error` metric incremented with `confidence_evaluation` tag
- `ras.expression.error` metric incremented with `confidence_clamped` tag

### runtime/ unit tests — YamlSituationDefinitionProviderTest

- `confidenceExpression` present on a rule → parsed correctly
- `confidenceExpression` absent → null (backward compat, existing tests unchanged)
- `confidenceExpression` with invalid language → startup error
- `confidenceExpression` with missing `expression` field → startup error

### runtime/ unit tests — SituationDefinitionRegistryTest

- ExpressionRules descriptor with confidence expressions → compiled and wired
- ExpressionRules descriptor without confidence expressions → null compiled (unchanged)

### Integration test (casehub-platform-expression on test classpath)

- End-to-end: YAML expression-rules ganglion with `confidenceExpression` + situation →
  CloudEvent with score field → rule matches → dynamic confidence computed → detection →
  chain mode → trigger → verify confidence value in DetectionResult
