# Per-Decision-Path Evidence Templates — Design Spec

**Issues:** casehubio/casehub-ras#51 (per-rule evidence), casehubio/casehub-ras#53 (per-outcome evidence)
**Date:** 2026-07-22
**Status:** Draft
**Builds on:** `2026-07-21-evidence-templates-expression-rules-design.md` (#48, #50)

## Problem

Ganglion-level `evidenceTemplates` extract the same fields from every CloudEvent regardless of
the detection decision. For both expression-rules and NaiveBayes ganglia, different decision
paths often need different evidence — a severity-HIGH rule should capture `escalationReason`,
while severity-MEDIUM should capture `category`. Currently this requires a Java
`JavaSwitchGanglion` subclass — no YAML-only path exists.

## Scope

- Add per-rule `evidenceTemplates` to `GanglionDescriptor.ExpressionRules.Rule`
- Add per-outcome `outcomeEvidenceTemplates` to `GanglionDescriptor.NaiveBayes`
- Evaluate per-rule evidence inside `ExpressionRulesGanglion`
- Evaluate per-outcome evidence inside `NaiveBayesGanglion` (+ add `winningOutcome` to auto evidence)
- Extend YAML parsing for both
- Extend `SituationDefinitionRegistry` compilation for both
- Extend `NaiveBayesConfig` to carry compiled per-outcome templates

Out of scope:
- Dynamic confidence expressions — static double per rule (#52)
- SituationContext-based evidence — violates DetectionResult portability invariant
- Per-signal evidence (cross-cutting on GanglionDescriptor) — rejected, see Design Rationale

## Design Constraint: DetectionResult Portability

Unchanged from the #48/#50 spec. Evidence template EXPRESSIONS must only extract from
`CloudEvent` (invariant across retries). WHICH templates to evaluate can depend on the
ganglion's internal decision — the selection logic is an internal concern, the extraction
source is the invariant.

## Design Rationale: Why Variant-Specific, Not Per-Signal

Per-signal evidence (`Map<DetectionSignal, Map<String, ExpressionEvaluator>>` on
`GanglionDescriptor`) was considered as a cross-cutting abstraction. Rejected because
it's too coarse for both variants:

- **ExpressionRules:** Multiple rules can produce the same signal (three rules all
  producing DETECTED for different conditions need different evidence per rule).
- **NaiveBayes:** Signal mapping maps ONE target outcome to thresholds. All non-target
  outcomes produce NOISE/ANTI indistinguishably. Per-signal can't distinguish
  "normal won" from "malfunction won" when both produce NOISE.

Each variant's structural decision unit carries evidence templates. Rules and outcomes
are fundamentally different structures — no common type makes sense as a cross-cutting
method. Future sealed variants follow the same principle: identify the decision unit,
attach evidence templates to it.

## Design

### 1. GanglionDescriptor.ExpressionRules.Rule (api/)

```java
public record Rule(
        ExpressionEvaluator when,
        DetectionSignal signal,
        double confidence,
        Map<String, ExpressionEvaluator> evidenceTemplates
) {}
```

New `evidenceTemplates` field. Breaking change — pre-release, acceptable. Defaults to
`Map.of()` in YAML parsing when absent.

### 2. GanglionDescriptor.NaiveBayes (api/)

```java
record NaiveBayes(
        String ganglionId,
        Set<String> handledEventTypes,
        List<String> outcomes,
        double[] priors,
        Map<String, Feature> features,
        SignalMapping signalMapping,
        Map<String, ExpressionEvaluator> evidenceTemplates,
        Map<String, Map<String, ExpressionEvaluator>> outcomeEvidenceTemplates
) implements GanglionDescriptor
```

New `outcomeEvidenceTemplates` field. Keyed by outcome name. Validated at parse time:
all keys must exist in `outcomes`. Missing outcomes = no per-outcome evidence for that
outcome. Defaults to `Map.of()` when absent in YAML.

### 3. ExpressionRulesGanglion Changes (runtime/)

**CompiledRule** gains compiled evidence templates:

```java
record CompiledRule(
        CompiledExpression<Map, Boolean> when,
        DetectionSignal signal,
        double confidence,
        Map<String, CompiledExpression<Map, Object>> evidenceTemplates
) {}
```

**`detect()` changes:** After finding the matched rule, evaluate its evidence templates.
The `CloudEventExpressionContext` is already built at the top of `detect()` for rule
evaluation — reused for evidence extraction, no additional parse.

```java
public Uni<DetectionResult> detect(CloudEvent event, SituationContext context) {
    Map<String, Object> ctx = CloudEventExpressionContext.build(event);
    for (int i = 0; i < rules.size(); i++) {
        CompiledRule rule = rules.get(i);
        if (rule.when() == null) {
            return Uni.createFrom().item(buildResult(rule, i, ctx));
        }
        try {
            Boolean match = rule.when().eval(ctx);
            if (Boolean.TRUE.equals(match)) {
                return Uni.createFrom().item(buildResult(rule, i, ctx));
            }
        } catch (RuntimeException ex) {
            // existing error handling
        }
    }
    return Uni.createFrom().item(new DetectionResult(
            ganglionId, 0.0, DetectionSignal.NOISE,
            Map.of("matchedRuleIndex", -1)));
}

private DetectionResult buildResult(CompiledRule rule, int index, Map<String, Object> ctx) {
    Map<String, Object> evidence = new LinkedHashMap<>();
    evidence.put("matchedRuleIndex", index);
    if (!rule.evidenceTemplates().isEmpty()) {
        for (var entry : rule.evidenceTemplates().entrySet()) {
            try {
                Object value = entry.getValue().eval(ctx);
                if (value != null) {
                    evidence.put(entry.getKey(), value);
                }
            } catch (Exception e) {
                LOG.warning("Rule " + index + " evidence template '" + entry.getKey()
                            + "' failed for ganglion '" + ganglionId + "': " + e.getMessage());
                if (meterRegistry != null) {
                    meterRegistry.counter("ras.expression.error",
                            "ganglion_id", ganglionId,
                            "evidence_key", entry.getKey(),
                            "rule_index", String.valueOf(index),
                            "expression_point", "rule_evidence_extraction").increment();
                }
            }
        }
    }
    return new DetectionResult(ganglionId, rule.confidence(), rule.signal(), evidence);
}
```

**Error handling:** Per-template catch. Failed template is skipped, other templates
still evaluate. Metric: `ras.expression.error` with tags
`{ganglion_id, evidence_key, rule_index, expression_point=rule_evidence_extraction}`.

**Null semantics:** Template expression evaluating to `null` → key omitted from evidence.

### 4. NaiveBayesGanglion Changes (runtime/)

Two changes:

**a) Add `winningOutcome` to automatic evidence.** After computing normalized posteriors,
determine the winning outcome (highest posterior):

```java
double[] posteriors = normalizeLogPosteriors(logPosteriors);
int winnerIndex = 0;
for (int i = 1; i < posteriors.length; i++) {
    if (posteriors[i] > posteriors[winnerIndex]) { winnerIndex = i; }
}
String winningOutcome = config.outcomes().get(winnerIndex);
```

**Tied posteriors:** Equal posteriors → first outcome by declaration order wins
(deterministic, `>` not `>=`). Floating-point ties are unlikely but possible with
symmetric priors and identical likelihoods.

Evidence becomes: `"posterior"`, `"features"`, `"winningOutcome"`.

**b) Evaluate per-outcome evidence templates.** When the config carries per-outcome
templates and the winning outcome has templates:

```java
Map<String, Object> evidence = new LinkedHashMap<>();
evidence.put("posterior", targetPosterior);
evidence.put("features", Map.copyOf(observed));
evidence.put("winningOutcome", winningOutcome);

var outcomeTemplates = config.outcomeEvidenceTemplates().get(winningOutcome);
if (outcomeTemplates != null && !outcomeTemplates.isEmpty()) {
    Map<String, Object> exprCtx = CloudEventExpressionContext.build(event);
    for (var entry : outcomeTemplates.entrySet()) {
        try {
            Object value = entry.getValue().eval(exprCtx);
            if (value != null) {
                evidence.put(entry.getKey(), value);
            }
        } catch (Exception e) {
            LOG.warning("Outcome '" + winningOutcome + "' evidence template '"
                        + entry.getKey() + "' failed for ganglion '"
                        + config.ganglionId() + "': " + e.getMessage());
            if (meterRegistry != null) {
                meterRegistry.counter("ras.expression.error",
                        "ganglion_id", config.ganglionId(),
                        "evidence_key", entry.getKey(),
                        "outcome", winningOutcome,
                        "expression_point", "outcome_evidence_extraction").increment();
            }
        }
    }
}
```

`CloudEventExpressionContext.build(event)` is only called when per-outcome templates
exist for the winning outcome — no cost when the feature is unused.

**Triple-parse tradeoff:** For a NaiveBayes ganglion with BOTH per-outcome evidence
templates AND ganglion-level evidence templates, `CloudEventExpressionContext.build(event)`
is called three times per detection: (1) `ExpressionFeatureExtractor.extract()` for feature
extraction, (2) per-outcome evidence evaluation here, (3) `EvidenceExtractingGanglion.enrichEvidence()`
for ganglion-level evidence. This extends the double-parse tradeoff documented in the
predecessor spec (#48/#50). The same `CloudEventExpressionContext` caching solution proposed
there (single-entry cache keyed on CloudEvent identity) would address all three layers.

**MeterRegistry threading:** `NaiveBayesGanglion` currently has no `MeterRegistry` field.
Replace the 2-arg constructor with a 3-arg constructor `(config, stateStore, meterRegistry)`
with nullable MeterRegistry, following the same pattern as `ExpressionRulesGanglion`.
Breaking change — pre-release, acceptable. Callers (including tests) must pass `null`
explicitly when they don't need metrics, which forces every caller to be explicit about
whether they want metrics. `NaiveBayesConfig` does not carry the `MeterRegistry` — it's
a constructor parameter on the ganglion itself. The registry passes it from
`constructNaiveBayes()`.

### 5. NaiveBayesConfig Changes (runtime/)

```java
public record NaiveBayesConfig(
        String ganglionId,
        Set<String> handledEventTypes,
        List<String> outcomes,
        double[] priors,
        Map<String, FeatureLikelihood> features,
        NaiveBayesFeatureExtractor featureExtractor,
        NaiveBayesSignalMapping signalMapping,
        Map<String, Map<String, CompiledExpression<Map, Object>>> outcomeEvidenceTemplates
) {
    public NaiveBayesConfig {
        // ... existing validation ...
        outcomeEvidenceTemplates = outcomeEvidenceTemplates != null
                ? Map.copyOf(outcomeEvidenceTemplates) : Map.of();
        for (String outcomeKey : outcomeEvidenceTemplates.keySet()) {
            if (!outcomes.contains(outcomeKey)) {
                throw new IllegalArgumentException(
                        "outcomeEvidenceTemplates key '" + outcomeKey
                        + "' is not in outcomes " + outcomes);
            }
        }
    }
}
```

Breaking change — pre-release, acceptable. Callers must pass `Map.of()` explicitly
when they have no per-outcome evidence templates, which forces every caller to be
explicit about the field's existence.

### 6. YAML Schema

**Per-rule evidence on expression-rules:**

```yaml
ganglia:
  - ganglionId: severity-checker
    type: expression-rules
    handledEventTypes: [sensor.reading]
    rules:
      - when: { expression: ".data.severity == \"HIGH\"", language: jq }
        signal: DETECTED
        confidence: 0.9
        evidenceTemplates:
          escalation_reason: { expression: ".data.escalationReason", language: jq }
      - when: { expression: ".data.severity == \"MEDIUM\"", language: jq }
        signal: WEAK
        confidence: 0.5
        evidenceTemplates:
          category: { expression: ".data.category", language: jq }
      - otherwise:
        signal: NOISE
        confidence: 0.0
    evidenceTemplates:                              # ganglion-level — always extracted
      severity: { expression: ".data.severity", language: jq }
```

**Per-outcome evidence on naive-bayes:**

```yaml
  - ganglionId: anomaly-detector
    type: naive-bayes
    handledEventTypes: [sensor.reading]
    outcomes: [anomaly, normal, malfunction]
    priors: [0.2, 0.6, 0.2]
    features:
      severity:
        expression: ".data.severity"
        language: jq
        values: [LOW, MEDIUM, HIGH]
        likelihoods:
          - [0.7, 0.25, 0.05]
          - [0.8, 0.15, 0.05]
          - [0.3, 0.3, 0.4]
    signalMapping:
      targetOutcome: anomaly
      detectedThreshold: 0.7
      weakThreshold: 0.4
    outcomeEvidenceTemplates:
      anomaly:
        anomaly_type: { expression: ".data.anomalyType", language: jq }
      malfunction:
        error_code: { expression: ".data.errorCode", language: jq }
    evidenceTemplates:                              # ganglion-level — always extracted
      sensor_id: { expression: ".data.sensorId", language: jq }
```

**Parsing rules:**
- Per-rule `evidenceTemplates` uses same `parseEvidenceTemplates()` method — reuses
  `{expression, language}` syntax via shared `parseExpressionEntry()`. The existing
  Rule constructor call in `parseExpressionRulesGanglion()` becomes:

  ```java
  rules.add(new GanglionDescriptor.ExpressionRules.Rule(
          when, signal, confidence, parseEvidenceTemplates(ruleMap)));
  ```

- `outcomeEvidenceTemplates` is a two-level map: outcome name → evidence key → expression entry.
  Parsed by a new `parseOutcomeEvidenceTemplates()` method:

  ```java
  @SuppressWarnings("unchecked")
  private static Map<String, Map<String, ExpressionEvaluator>> parseOutcomeEvidenceTemplates(
          Map<String, Object> map, List<String> outcomes, String ganglionId) {
      Map<String, Object> raw = (Map<String, Object>) map.get("outcomeEvidenceTemplates");
      if (raw == null) { return Map.of(); }
      Map<String, Map<String, ExpressionEvaluator>> result = new LinkedHashMap<>();
      for (var entry : raw.entrySet()) {
          String outcomeName = entry.getKey();
          if (!outcomes.contains(outcomeName)) {
              throw new IllegalArgumentException(
                      "outcomeEvidenceTemplates key '" + outcomeName
                      + "' is not in outcomes " + outcomes
                      + " for ganglion '" + ganglionId + "'");
          }
          Map<String, Object> templates = (Map<String, Object>) entry.getValue();
          Map<String, ExpressionEvaluator> parsed = new LinkedHashMap<>();
          for (var tmpl : templates.entrySet()) {
              parsed.put(tmpl.getKey(), parseExpressionEntry(
                      (Map<String, Object>) tmpl.getValue(),
                      "outcomeEvidenceTemplate '" + tmpl.getKey()
                      + "' for outcome '" + outcomeName + "'"));
          }
          result.put(outcomeName, Map.copyOf(parsed));
      }
      return Map.copyOf(result);
  }
  ```

  The NaiveBayes constructor call in `parseNaiveBayesGanglion()` becomes:

  ```java
  return new GanglionDescriptor.NaiveBayes(
          ganglionId, new LinkedHashSet<>(eventTypes), outcomes, priors,
          features, parseSignalMapping(sigMap),
          parseEvidenceTemplates(map),
          parseOutcomeEvidenceTemplates(map, outcomes, ganglionId));
  ```

- Outcome keys validated against `outcomes` list at parse time — unknown outcome is a startup error
- Both fields default to `Map.of()` when absent in YAML

### 7. Registry Integration

**`constructExpressionRules()`** — compile per-rule evidence templates:

```java
for (int i = 0; i < er.rules().size(); i++) {
    var rule = er.rules().get(i);
    CompiledExpression<Map, Boolean> compiled = rule.when() != null
            ? compileExpression(rule.when(), er.ganglionId(), Map.class, Boolean.class)
            : null;
    Map<String, CompiledExpression<Map, Object>> compiledEvidence = Map.of();
    if (!rule.evidenceTemplates().isEmpty()) {
        var evidenceMap = new LinkedHashMap<String, CompiledExpression<Map, Object>>();
        for (var entry : rule.evidenceTemplates().entrySet()) {
            evidenceMap.put(entry.getKey(), compileExpression(
                    entry.getValue(), er.ganglionId(), Map.class, Object.class));
        }
        compiledEvidence = Map.copyOf(evidenceMap);
    }
    compiledRules.add(new ExpressionRulesGanglion.CompiledRule(
            compiled, rule.signal(), rule.confidence(), compiledEvidence));
}
```

**`constructNaiveBayes()`** — compile per-outcome evidence templates and pass to config:

```java
Map<String, Map<String, CompiledExpression<Map, Object>>> compiledOutcomeEvidence = Map.of();
if (!nb.outcomeEvidenceTemplates().isEmpty()) {
    var outcomeMap = new LinkedHashMap<String, Map<String, CompiledExpression<Map, Object>>>();
    for (var entry : nb.outcomeEvidenceTemplates().entrySet()) {
        var templateMap = new LinkedHashMap<String, CompiledExpression<Map, Object>>();
        for (var tmpl : entry.getValue().entrySet()) {
            templateMap.put(tmpl.getKey(), compileExpression(
                    tmpl.getValue(), nb.ganglionId(), Map.class, Object.class));
        }
        outcomeMap.put(entry.getKey(), Map.copyOf(templateMap));
    }
    compiledOutcomeEvidence = Map.copyOf(outcomeMap);
}

var config = new NaiveBayesConfig(
        nb.ganglionId(), nb.handledEventTypes(),
        nb.outcomes(), nb.priors(),
        features, featureExtractor, signalMapping,
        compiledOutcomeEvidence);

return new NaiveBayesGanglion(config,
        stateStore != null ? stateStore : new InMemoryGanglionStateStore(),
        meterRegistry);
```

**Evidence key collision warnings** — three locations:

1. **`constructGanglion()`** (existing, updated): Ganglion-level evidence template keys
   checked against auto keys. Update NaiveBayes auto key set from
   `Set.of("posterior", "features")` to `Set.of("posterior", "features", "winningOutcome")`
   to reflect the new auto evidence field.

2. **`constructExpressionRules()`** (new): Per-rule template key matching auto evidence
   key (`matchedRuleIndex`) → warn at construction. Check inside the per-rule compilation
   loop:

   ```java
   if (compiledEvidence.containsKey("matchedRuleIndex")) {
       LOG.warning("Per-rule evidence template key 'matchedRuleIndex' in rule " + i
                   + " of ganglion '" + er.ganglionId()
                   + "' shadows automatic evidence key — template will overwrite");
   }
   ```

3. **`constructNaiveBayes()`** (new): Per-outcome template key matching auto evidence
   keys (`posterior`, `features`, `winningOutcome`) → warn at construction. Check inside
   the per-outcome compilation loop:

   ```java
   Set<String> nbAutoKeys = Set.of("posterior", "features", "winningOutcome");
   for (String templateKey : templateMap.keySet()) {
       if (nbAutoKeys.contains(templateKey)) {
           LOG.warning("Per-outcome evidence template key '" + templateKey
                       + "' for outcome '" + entry.getKey()
                       + "' in ganglion '" + nb.ganglionId()
                       + "' shadows automatic evidence key — template will overwrite");
       }
   }
   ```

### 8. Merge Order

**ExpressionRules:**
1. `matchedRuleIndex` (automatic — ExpressionRulesGanglion)
2. Per-rule evidence templates (ExpressionRulesGanglion, matched rule only)
3. Ganglion-level evidence templates (EvidenceExtractingGanglion wrapper)

**NaiveBayes:**
1. `posterior` + `features` + `winningOutcome` (automatic — NaiveBayesGanglion)
2. Per-outcome evidence templates (NaiveBayesGanglion, winning outcome only)
3. Ganglion-level evidence templates (EvidenceExtractingGanglion wrapper)

Each layer overwrites the previous on key clash — consistent with existing semantics.
Ganglion-level always wins (outermost, most explicit declaration).

### 9. Type Changes Summary

| Location | Change |
|----------|--------|
| `GanglionDescriptor.ExpressionRules.Rule` (api/) | New `evidenceTemplates` field |
| `GanglionDescriptor.NaiveBayes` (api/) | New `outcomeEvidenceTemplates` field |
| `ExpressionRulesGanglion.CompiledRule` (runtime/) | New `evidenceTemplates` field |
| `ExpressionRulesGanglion` (runtime/) | New `buildResult()` method; `detect()` delegates to it |
| `NaiveBayesConfig` (runtime/) | New `outcomeEvidenceTemplates` field (breaking — 8-arg constructor) |
| `NaiveBayesGanglion` (runtime/) | New `MeterRegistry` field (breaking — 3-arg constructor replaces 2-arg); `detect()` adds `winningOutcome`, evaluates per-outcome templates |
| `YamlSituationDefinitionProvider` (runtime/) | Parse per-rule `evidenceTemplates`, parse `outcomeEvidenceTemplates` |
| `SituationDefinitionRegistry` (runtime/) | Compile per-rule + per-outcome evidence templates; extend collision warnings |

No changes to: `Ganglion` SPI, `EvidenceExtractingGanglion`, `SituationEvaluator`,
`DetectionResult`, `SituationContext`, platform expression engine.

### 10. Testing Strategy

**ExpressionRulesGanglion unit tests:**
- Matched rule's evidence templates are evaluated and merged with `matchedRuleIndex`
- Per-rule evidence key overwrites `matchedRuleIndex` on clash
- Null expression result → key omitted from evidence
- Per-template error isolation — one template fails, others still evaluate
- `ras.expression.error` metric with `expression_point=rule_evidence_extraction`
- Rules without evidence templates produce only `matchedRuleIndex`
- Implicit NOISE fallback (no match, no otherwise) has only `matchedRuleIndex: -1`
- Otherwise rule with evidence templates works correctly

**NaiveBayesGanglion unit tests:**
- `winningOutcome` present in evidence (new automatic field)
- `winningOutcome` reflects the outcome with highest posterior
- Per-outcome evidence templates evaluated for winning outcome
- Non-winning outcome's templates are NOT evaluated
- Null expression result → key omitted
- Per-template error isolation
- `ras.expression.error` metric with `expression_point=outcome_evidence_extraction` and `outcome` tag
- Outcomes without templates produce only auto evidence (`posterior`, `features`, `winningOutcome`)
- MeterRegistry=null does not NPE (existing pattern)
- Tied posteriors → first outcome by declaration order wins (deterministic `>` semantics)

**NaiveBayesConfig unit tests:**
- Unknown outcome key in `outcomeEvidenceTemplates` → `IllegalArgumentException`
- Empty `outcomeEvidenceTemplates` → `Map.of()` (default)

**YAML parsing unit tests:**
- Per-rule `evidenceTemplates` on expression-rules: parsed, correct expressions
- Per-rule `evidenceTemplates` absent → `Map.of()` (default per rule)
- `outcomeEvidenceTemplates` on naive-bayes: parsed, correct expressions, correct nesting
- `outcomeEvidenceTemplates` absent → `Map.of()` (default)
- `outcomeEvidenceTemplates` with unknown outcome key → startup error
- `outcomeEvidenceTemplates` with invalid expression → startup error (via `parseExpressionEntry`)

**Registry integration tests:**
- ExpressionRules with per-rule evidence → templates compiled and ganglion functional
- NaiveBayes with per-outcome evidence → templates compiled and ganglion functional
- Per-rule template key matching `matchedRuleIndex` → construction warning logged
- Per-outcome template key matching `posterior`/`features`/`winningOutcome` → construction warning logged
- ExpressionRules with per-rule + ganglion-level evidence → correct merge order
- NaiveBayes with per-outcome + ganglion-level evidence → correct merge order

**Contract tests (api/ AbstractGanglionContractTest):**
- Existing `ExpressionRulesGanglionContractTest` and `EvidenceExtractingGanglionContractTest`
  continue to pass — no contract changes, only evidence content enrichment

**Integration test (casehub-platform-expression on test classpath):**
- End-to-end: YAML expression-rules with per-rule evidence + ganglion-level evidence →
  CloudEvent → rule match → per-rule evidence in result → ganglion-level evidence merged
- End-to-end: YAML naive-bayes with per-outcome evidence + ganglion-level evidence →
  CloudEvent → posterior computation → per-outcome evidence for winning outcome →
  ganglion-level evidence merged
