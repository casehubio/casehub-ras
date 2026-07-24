# Dynamic Confidence Expressions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> subagent-driven-development (recommended) or executing-plans to
> implement this plan task-by-task. Each task follows TDD
> (test-driven-development) and uses ide-tooling for structural
> editing. Steps use checkbox (`- [ ]`) syntax for tracking.

**Focal issue:** #52 — Support dynamic confidence expressions in expression-rules ganglion
**Issue group:** #52

**Goal:** Allow expression-rules ganglion rules to compute confidence dynamically from
event data via an optional `confidenceExpression`, with the static `confidence` as fallback.

**Architecture:** One nullable `ExpressionEvaluator` field added to the `Rule` record (api/),
compiled to `CompiledExpression<Map, Object>` in the registry, resolved at detection time in
`ExpressionRulesGanglion`. Expression failures fall back to the static confidence value.

**Tech Stack:** Java 21 records, ExpressionEvaluator/CompiledExpression SPI,
CloudEventExpressionContext, JQ/MVEL expression engines, Micrometer metrics

## Global Constraints

- `confidence` field remains required on every rule (always a number, validated 0.0–1.0)
- `confidenceExpression` is optional (nullable) — parsed via existing `parseExpressionEntry()`
- Infinity/NaN fall back to static confidence (computational errors, not near-misses)
- Finite out-of-range values clamp to [0.0, 1.0] (preserves dynamic signal)
- No changes to DetectionResult, Ganglion SPI, SituationEvaluator, or ChainMode

---

### Task 1: Add `confidenceExpression` to Rule record (api/)

**Files:**
- Modify: `api/src/main/java/io/casehub/ras/api/GanglionDescriptor.java` — Rule record
- Modify: `api/src/test/java/io/casehub/ras/api/GanglionDescriptorTest.java` — add tests, fix call sites
- Modify: `runtime/src/main/java/io/casehub/ras/runtime/YamlSituationDefinitionProvider.java` — fix Rule construction (pass null)

**Interfaces:**
- Produces: `GanglionDescriptor.ExpressionRules.Rule(ExpressionEvaluator when, DetectionSignal signal, double confidence, ExpressionEvaluator confidenceExpression, Map<String, ExpressionEvaluator> evidenceTemplates)` — consumed by Tasks 2 and 3

- [ ] **Step 1: Write failing test for Rule with confidenceExpression**

Add to `GanglionDescriptorTest`:

```java
@Test
void ruleWithConfidenceExpression() {
    var rule = new GanglionDescriptor.ExpressionRules.Rule(
            new JQExpressionEvaluator(".data.severity == \"HIGH\""),
            DetectionSignal.DETECTED, 0.5,
            new JQExpressionEvaluator(".data.score / 100"),
            Map.of());
    assertThat(rule.confidenceExpression()).isNotNull();
    assertThat(rule.confidence()).isEqualTo(0.5);
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn --batch-mode test -pl api -Dtest=GanglionDescriptorTest#ruleWithConfidenceExpression`
Expected: compilation error — Rule record has no 5-arg constructor

- [ ] **Step 3: Add confidenceExpression field to Rule record**

In `GanglionDescriptor.java`, change the `Rule` record from:

```java
public record Rule(
        ExpressionEvaluator when,
        DetectionSignal signal,
        double confidence,
        Map<String, ExpressionEvaluator> evidenceTemplates
) {}
```

to:

```java
public record Rule(
        ExpressionEvaluator when,
        DetectionSignal signal,
        double confidence,
        ExpressionEvaluator confidenceExpression,
        Map<String, ExpressionEvaluator> evidenceTemplates
) {}
```

- [ ] **Step 4: Fix existing Rule constructor calls in GanglionDescriptorTest**

All existing 4-arg Rule calls need `null` inserted as the 4th arg (before `evidenceTemplates`).

In `expressionRulesRecordCarriesAllFields`:
```java
var rule = new GanglionDescriptor.ExpressionRules.Rule(
        new JQExpressionEvaluator(".data.severity == \"HIGH\""),
        DetectionSignal.DETECTED, 0.9, null, Map.of());
var otherwise = new GanglionDescriptor.ExpressionRules.Rule(
        null, DetectionSignal.NOISE, 0.0, null, Map.of());
```

In `expressionRulesWithEvidenceTemplates`:
```java
List.of(new GanglionDescriptor.ExpressionRules.Rule(null, DetectionSignal.NOISE, 0.0, null, Map.of())),
```

In `ruleWithEvidenceTemplates`:
```java
var rule = new GanglionDescriptor.ExpressionRules.Rule(
        new JQExpressionEvaluator(".data.severity == \"HIGH\""),
        DetectionSignal.DETECTED, 0.9, null,
        Map.of("reason", new JQExpressionEvaluator(".data.reason")));
```

Also add assertion to `expressionRulesRecordCarriesAllFields`:
```java
assertThat(descriptor.rules().get(0).confidenceExpression()).isNull();
```

- [ ] **Step 5: Fix Rule construction in YamlSituationDefinitionProvider**

In `parseExpressionRulesGanglion` (line ~219), change:
```java
rules.add(new GanglionDescriptor.ExpressionRules.Rule(when, signal, confidence, parseEvidenceTemplates(ruleMap)));
```
to:
```java
rules.add(new GanglionDescriptor.ExpressionRules.Rule(when, signal, confidence, null, parseEvidenceTemplates(ruleMap)));
```

This passes `null` for now — Task 3 wires up the actual parsing.

- [ ] **Step 6: Run all tests to verify everything compiles and passes**

Run: `mvn --batch-mode test -pl api,runtime`
Expected: all tests pass (including the new `ruleWithConfidenceExpression` test)

- [ ] **Step 7: Commit**

```bash
git add api/src/main/java/io/casehub/ras/api/GanglionDescriptor.java \
        api/src/test/java/io/casehub/ras/api/GanglionDescriptorTest.java \
        runtime/src/main/java/io/casehub/ras/runtime/YamlSituationDefinitionProvider.java
git commit -m "feat(api): add confidenceExpression field to ExpressionRules.Rule (#52)"
```

---

### Task 2: Dynamic confidence resolution in ExpressionRulesGanglion (runtime/)

**Files:**
- Modify: `runtime/src/main/java/io/casehub/ras/runtime/ExpressionRulesGanglion.java` — CompiledRule, resolveConfidence, buildResult
- Modify: `runtime/src/test/java/io/casehub/ras/runtime/ExpressionRulesGanglionTest.java` — fix call sites, add new tests
- Modify: `runtime/src/test/java/io/casehub/ras/runtime/ExpressionRulesGanglionContractTest.java` — fix call site

**Interfaces:**
- Consumes: `GanglionDescriptor.ExpressionRules.Rule` with `confidenceExpression` field (from Task 1)
- Produces: `ExpressionRulesGanglion.CompiledRule(CompiledExpression<Map, Boolean> when, DetectionSignal signal, double confidence, CompiledExpression<Map, Object> confidenceExpression, Map<String, CompiledExpression<Map, Object>> evidenceTemplates)` — consumed by Task 3

- [ ] **Step 1: Add test helper methods for confidence expressions**

Add to `ExpressionRulesGanglionTest`:

```java
private static CompiledExpression<Map, Object> confidenceReturning(Object value) {
    return new CompiledExpression<>() {
        @Override public String type() { return "test"; }
        @Override public Object eval(Map context) { return value; }
    };
}

private static CompiledExpression<Map, Object> confidenceThrowing() {
    return new CompiledExpression<>() {
        @Override public String type() { return "test"; }
        @Override public Object eval(Map context) { throw new RuntimeException("boom"); }
    };
}
```

- [ ] **Step 2: Write failing test — dynamic confidence returns valid number**

```java
@Test
void dynamicConfidenceUsedWhenExpressionReturnsValidNumber() {
    var ganglion = new ExpressionRulesGanglion("g1", Set.of("test.event"), List.of(
            new ExpressionRulesGanglion.CompiledRule(
                    matching(), DetectionSignal.DETECTED, 0.5,
                    confidenceReturning(0.85), Map.of())), null);
    DetectionResult result = ganglion.detect(event(), CTX).await().indefinitely();
    assertThat(result.confidence()).isEqualTo(0.85);
    assertThat(result.signal()).isEqualTo(DetectionSignal.DETECTED);
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `mvn --batch-mode test -pl runtime -Dtest=ExpressionRulesGanglionTest#dynamicConfidenceUsedWhenExpressionReturnsValidNumber`
Expected: compilation error — CompiledRule has no 5-arg constructor

- [ ] **Step 4: Add confidenceExpression to CompiledRule**

Change the `CompiledRule` record from:
```java
record CompiledRule(
        CompiledExpression<Map, Boolean> when,
        DetectionSignal signal,
        double confidence,
        Map<String, CompiledExpression<Map, Object>> evidenceTemplates
) {}
```
to:
```java
record CompiledRule(
        CompiledExpression<Map, Boolean> when,
        DetectionSignal signal,
        double confidence,
        CompiledExpression<Map, Object> confidenceExpression,
        Map<String, CompiledExpression<Map, Object>> evidenceTemplates
) {}
```

- [ ] **Step 5: Fix ALL existing CompiledRule constructor calls**

Every existing 4-arg `CompiledRule(when, signal, confidence, evidenceTemplates)` call needs `null` inserted as the 4th arg.

In `ExpressionRulesGanglionTest.java`, update all ~22 `new ExpressionRulesGanglion.CompiledRule(...)` calls. Pattern: insert `null,` after the `confidence` arg and before the evidence `Map.of()` or `Map.of("key", ...)` arg.

Examples of the change pattern:
```java
// Before:
new ExpressionRulesGanglion.CompiledRule(matching(), DetectionSignal.DETECTED, 0.9, Map.of())
// After:
new ExpressionRulesGanglion.CompiledRule(matching(), DetectionSignal.DETECTED, 0.9, null, Map.of())

// Before (with evidence):
new ExpressionRulesGanglion.CompiledRule(matching(), DetectionSignal.DETECTED, 0.9,
                                         Map.of("custom_key", evidenceReturning("extracted-value")))
// After:
new ExpressionRulesGanglion.CompiledRule(matching(), DetectionSignal.DETECTED, 0.9, null,
                                         Map.of("custom_key", evidenceReturning("extracted-value")))
```

In `ExpressionRulesGanglionContractTest.java` (line 22), same pattern — insert `null,` as 4th arg.

In `SituationDefinitionRegistry.constructExpressionRules()` (line 374), change:
```java
compiledRules.add(new ExpressionRulesGanglion.CompiledRule(
        compiled, rule.signal(), rule.confidence(), compiledEvidence));
```
to:
```java
compiledRules.add(new ExpressionRulesGanglion.CompiledRule(
        compiled, rule.signal(), rule.confidence(), null, compiledEvidence));
```

This passes `null` for now — Task 3 wires up the actual compilation.

- [ ] **Step 6: Implement resolveConfidence and wire it into buildResult**

Add `resolveConfidence` method to `ExpressionRulesGanglion`:

```java
@SuppressWarnings("unchecked")
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
                if (meterRegistry != null) {
                    meterRegistry.counter("ras.expression.error",
                                          "ganglion_id", ganglionId,
                                          "rule_index", String.valueOf(index),
                                          "expression_point", "confidence_evaluation").increment();
                }
                return rule.confidence();
            }
            if (val < 0.0 || val > 1.0) {
                LOG.warning("Confidence expression returned " + val + " for rule " + index
                            + " in ganglion '" + ganglionId + "', clamping to [0.0, 1.0]");
                if (meterRegistry != null) {
                    meterRegistry.counter("ras.expression.error",
                                          "ganglion_id", ganglionId,
                                          "rule_index", String.valueOf(index),
                                          "expression_point", "confidence_clamped").increment();
                }
                return Math.max(0.0, Math.min(1.0, val));
            }
            return val;
        } else {
            if (result != null) {
                LOG.warning("Confidence expression returned non-numeric " + result.getClass().getSimpleName()
                            + " for rule " + index + " in ganglion '" + ganglionId + "', using fallback");
                if (meterRegistry != null) {
                    meterRegistry.counter("ras.expression.error",
                                          "ganglion_id", ganglionId,
                                          "rule_index", String.valueOf(index),
                                          "expression_point", "confidence_evaluation").increment();
                }
            }
            return rule.confidence();
        }
    } catch (RuntimeException ex) {
        LOG.warning("Confidence expression failed for rule " + index
                    + " in ganglion '" + ganglionId + "': " + ex.getMessage());
        if (meterRegistry != null) {
            meterRegistry.counter("ras.expression.error",
                                  "ganglion_id", ganglionId,
                                  "rule_index", String.valueOf(index),
                                  "expression_point", "confidence_evaluation").increment();
        }
        return rule.confidence();
    }
}
```

In `buildResult()`, change:
```java
return new DetectionResult(ganglionId, rule.confidence(), rule.signal(), evidence);
```
to:
```java
return new DetectionResult(ganglionId, resolveConfidence(rule, index, ctx), rule.signal(), evidence);
```

- [ ] **Step 7: Run the new test to verify it passes**

Run: `mvn --batch-mode test -pl runtime -Dtest=ExpressionRulesGanglionTest#dynamicConfidenceUsedWhenExpressionReturnsValidNumber`
Expected: PASS

- [ ] **Step 8: Write and run remaining confidence tests**

Add all remaining tests to `ExpressionRulesGanglionTest`:

```java
@Test
void dynamicConfidenceClampedAboveOne() {
    var ganglion = new ExpressionRulesGanglion("g1", Set.of("test.event"), List.of(
            new ExpressionRulesGanglion.CompiledRule(
                    matching(), DetectionSignal.DETECTED, 0.5,
                    confidenceReturning(1.5), Map.of())), null);
    DetectionResult result = ganglion.detect(event(), CTX).await().indefinitely();
    assertThat(result.confidence()).isEqualTo(1.0);
}

@Test
void dynamicConfidenceClampedBelowZero() {
    var ganglion = new ExpressionRulesGanglion("g1", Set.of("test.event"), List.of(
            new ExpressionRulesGanglion.CompiledRule(
                    matching(), DetectionSignal.DETECTED, 0.5,
                    confidenceReturning(-0.3), Map.of())), null);
    DetectionResult result = ganglion.detect(event(), CTX).await().indefinitely();
    assertThat(result.confidence()).isEqualTo(0.0);
}

@Test
void dynamicConfidenceNanFallsBackToStatic() {
    var ganglion = new ExpressionRulesGanglion("g1", Set.of("test.event"), List.of(
            new ExpressionRulesGanglion.CompiledRule(
                    matching(), DetectionSignal.DETECTED, 0.5,
                    confidenceReturning(Double.NaN), Map.of())), null);
    DetectionResult result = ganglion.detect(event(), CTX).await().indefinitely();
    assertThat(result.confidence()).isEqualTo(0.5);
}

@Test
void dynamicConfidencePositiveInfinityFallsBackToStatic() {
    var ganglion = new ExpressionRulesGanglion("g1", Set.of("test.event"), List.of(
            new ExpressionRulesGanglion.CompiledRule(
                    matching(), DetectionSignal.DETECTED, 0.5,
                    confidenceReturning(Double.POSITIVE_INFINITY), Map.of())), null);
    DetectionResult result = ganglion.detect(event(), CTX).await().indefinitely();
    assertThat(result.confidence()).isEqualTo(0.5);
}

@Test
void dynamicConfidenceNegativeInfinityFallsBackToStatic() {
    var ganglion = new ExpressionRulesGanglion("g1", Set.of("test.event"), List.of(
            new ExpressionRulesGanglion.CompiledRule(
                    matching(), DetectionSignal.DETECTED, 0.5,
                    confidenceReturning(Double.NEGATIVE_INFINITY), Map.of())), null);
    DetectionResult result = ganglion.detect(event(), CTX).await().indefinitely();
    assertThat(result.confidence()).isEqualTo(0.5);
}

@Test
void dynamicConfidenceNullFallsBackToStatic() {
    var ganglion = new ExpressionRulesGanglion("g1", Set.of("test.event"), List.of(
            new ExpressionRulesGanglion.CompiledRule(
                    matching(), DetectionSignal.DETECTED, 0.5,
                    confidenceReturning(null), Map.of())), null);
    DetectionResult result = ganglion.detect(event(), CTX).await().indefinitely();
    assertThat(result.confidence()).isEqualTo(0.5);
}

@Test
void dynamicConfidenceNonNumericFallsBackToStatic() {
    var ganglion = new ExpressionRulesGanglion("g1", Set.of("test.event"), List.of(
            new ExpressionRulesGanglion.CompiledRule(
                    matching(), DetectionSignal.DETECTED, 0.5,
                    confidenceReturning("not-a-number"), Map.of())), null);
    DetectionResult result = ganglion.detect(event(), CTX).await().indefinitely();
    assertThat(result.confidence()).isEqualTo(0.5);
}

@Test
void dynamicConfidenceExceptionFallsBackToStatic() {
    var ganglion = new ExpressionRulesGanglion("g1", Set.of("test.event"), List.of(
            new ExpressionRulesGanglion.CompiledRule(
                    matching(), DetectionSignal.DETECTED, 0.5,
                    confidenceThrowing(), Map.of())), null);
    DetectionResult result = ganglion.detect(event(), CTX).await().indefinitely();
    assertThat(result.confidence()).isEqualTo(0.5);
}

@Test
void dynamicConfidenceOnOtherwiseRule() {
    var ganglion = new ExpressionRulesGanglion("g1", Set.of("test.event"), List.of(
            new ExpressionRulesGanglion.CompiledRule(
                    nonMatching(), DetectionSignal.DETECTED, 0.9, null, Map.of()),
            new ExpressionRulesGanglion.CompiledRule(
                    null, DetectionSignal.NOISE, 0.1,
                    confidenceReturning(0.3), Map.of())), null);
    DetectionResult result = ganglion.detect(event(), CTX).await().indefinitely();
    assertThat(result.signal()).isEqualTo(DetectionSignal.NOISE);
    assertThat(result.confidence()).isEqualTo(0.3);
}

@Test
void dynamicConfidenceErrorMetricIncremented() {
    var registry = new SimpleMeterRegistry();
    var ganglion = new ExpressionRulesGanglion("g1", Set.of("test.event"), List.of(
            new ExpressionRulesGanglion.CompiledRule(
                    matching(), DetectionSignal.DETECTED, 0.5,
                    confidenceThrowing(), Map.of())), registry);
    ganglion.detect(event(), CTX).await().indefinitely();
    var counter = registry.find("ras.expression.error")
                          .tag("ganglion_id", "g1")
                          .tag("rule_index", "0")
                          .tag("expression_point", "confidence_evaluation").counter();
    assertThat(counter).isNotNull();
    assertThat(counter.count()).isEqualTo(1.0);
}

@Test
void dynamicConfidenceClampedMetricIncremented() {
    var registry = new SimpleMeterRegistry();
    var ganglion = new ExpressionRulesGanglion("g1", Set.of("test.event"), List.of(
            new ExpressionRulesGanglion.CompiledRule(
                    matching(), DetectionSignal.DETECTED, 0.5,
                    confidenceReturning(1.5), Map.of())), registry);
    ganglion.detect(event(), CTX).await().indefinitely();
    var counter = registry.find("ras.expression.error")
                          .tag("ganglion_id", "g1")
                          .tag("rule_index", "0")
                          .tag("expression_point", "confidence_clamped").counter();
    assertThat(counter).isNotNull();
    assertThat(counter.count()).isEqualTo(1.0);
}

@Test
void staticConfidenceUnchangedWhenNoExpression() {
    var ganglion = new ExpressionRulesGanglion("g1", Set.of("test.event"), List.of(
            new ExpressionRulesGanglion.CompiledRule(
                    matching(), DetectionSignal.DETECTED, 0.9,
                    null, Map.of())), null);
    DetectionResult result = ganglion.detect(event(), CTX).await().indefinitely();
    assertThat(result.confidence()).isEqualTo(0.9);
}

@Test
void dynamicConfidenceIntegerCoercion() {
    var ganglion = new ExpressionRulesGanglion("g1", Set.of("test.event"), List.of(
            new ExpressionRulesGanglion.CompiledRule(
                    matching(), DetectionSignal.DETECTED, 0.5,
                    confidenceReturning(1), Map.of())), null);
    DetectionResult result = ganglion.detect(event(), CTX).await().indefinitely();
    assertThat(result.confidence()).isEqualTo(1.0);
}
```

- [ ] **Step 9: Run all tests**

Run: `mvn --batch-mode test -pl runtime -Dtest=ExpressionRulesGanglionTest`
Expected: all tests pass

- [ ] **Step 10: Commit**

```bash
git add runtime/src/main/java/io/casehub/ras/runtime/ExpressionRulesGanglion.java \
        runtime/src/test/java/io/casehub/ras/runtime/ExpressionRulesGanglionTest.java \
        runtime/src/test/java/io/casehub/ras/runtime/ExpressionRulesGanglionContractTest.java \
        runtime/src/main/java/io/casehub/ras/runtime/SituationDefinitionRegistry.java
git commit -m "feat(runtime): dynamic confidence resolution in ExpressionRulesGanglion (#52)"
```

---

### Task 3: YAML parsing + registry compilation + integration test (runtime/)

**Files:**
- Modify: `runtime/src/main/java/io/casehub/ras/runtime/YamlSituationDefinitionProvider.java` — parse `confidenceExpression`
- Modify: `runtime/src/main/java/io/casehub/ras/runtime/SituationDefinitionRegistry.java` — compile confidence expression
- Modify: `runtime/src/test/java/io/casehub/ras/runtime/YamlSituationDefinitionProviderTest.java` — parsing tests
- Modify: `runtime/src/test/java/io/casehub/ras/runtime/SituationDefinitionRegistryTest.java` — compilation tests
- Create: `runtime/src/test/resources/META-INF/ras-situations-dynamic-confidence.yaml` — integration test YAML

**Interfaces:**
- Consumes: `GanglionDescriptor.ExpressionRules.Rule` with `confidenceExpression` field (Task 1)
- Consumes: `ExpressionRulesGanglion.CompiledRule` with `confidenceExpression` field (Task 2)

- [ ] **Step 1: Write failing YAML parsing test — confidenceExpression present**

Add to `YamlSituationDefinitionProviderTest`:

```java
@Test
void expressionRulesWithConfidenceExpression() {
    var yaml = """
            ganglia:
              - ganglionId: dyn-conf
                type: expression-rules
                handledEventTypes: [test.event]
                rules:
                  - when:
                      expression: ".data.severity == \\"HIGH\\""
                      language: jq
                    signal: DETECTED
                    confidence: 0.5
                    confidenceExpression:
                      expression: ".data.score / 100"
                      language: jq
                  - otherwise:
                    signal: NOISE
                    confidence: 0.0
            """;
    var provider = new YamlSituationDefinitionProvider(
            new java.io.ByteArrayInputStream(yaml.getBytes()));
    var descriptors = provider.ganglionDescriptors();
    assertThat(descriptors).hasSize(1);
    var er = (GanglionDescriptor.ExpressionRules) descriptors.get(0);
    assertThat(er.rules().get(0).confidenceExpression()).isNotNull();
    assertThat(er.rules().get(1).confidenceExpression()).isNull();
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn --batch-mode test -pl runtime -Dtest=YamlSituationDefinitionProviderTest#expressionRulesWithConfidenceExpression`
Expected: FAIL — parser doesn't read `confidenceExpression` yet, passes null

Actually this test would pass with null since we pass null already. Adjust the test to assert `isNotNull()` specifically — it will fail because the parser doesn't read the field yet.

- [ ] **Step 3: Implement confidenceExpression parsing in YamlSituationDefinitionProvider**

In `parseExpressionRulesGanglion`, change the Rule construction (around line 219):

```java
ExpressionEvaluator confidenceExpr = parseExpressionEvaluator(ruleMap, "confidenceExpression");

rules.add(new GanglionDescriptor.ExpressionRules.Rule(
        when, signal, confidence, confidenceExpr, parseEvidenceTemplates(ruleMap)));
```

The existing `parseExpressionEvaluator(Map, String)` method already handles the `{expression, language}` map pattern and returns null when the key is absent.

- [ ] **Step 4: Run parsing test to verify it passes**

Run: `mvn --batch-mode test -pl runtime -Dtest=YamlSituationDefinitionProviderTest#expressionRulesWithConfidenceExpression`
Expected: PASS

- [ ] **Step 5: Write and run test — confidenceExpression absent remains null**

```java
@Test
void expressionRulesWithoutConfidenceExpressionRemainsNull() {
    var yaml = """
            ganglia:
              - ganglionId: static-conf
                type: expression-rules
                handledEventTypes: [test.event]
                rules:
                  - when:
                      expression: ".data.severity == \\"HIGH\\""
                      language: jq
                    signal: DETECTED
                    confidence: 0.9
            """;
    var provider = new YamlSituationDefinitionProvider(
            new java.io.ByteArrayInputStream(yaml.getBytes()));
    var descriptors = provider.ganglionDescriptors();
    var er = (GanglionDescriptor.ExpressionRules) descriptors.get(0);
    assertThat(er.rules().get(0).confidenceExpression()).isNull();
}
```

Run: `mvn --batch-mode test -pl runtime -Dtest=YamlSituationDefinitionProviderTest#expressionRulesWithoutConfidenceExpressionRemainsNull`
Expected: PASS

- [ ] **Step 6: Write and run test — confidenceExpression with invalid language**

```java
@Test
void expressionRulesConfidenceExpressionInvalidLanguage() {
    var yaml = """
            ganglia:
              - ganglionId: bad-lang
                type: expression-rules
                handledEventTypes: [test.event]
                rules:
                  - when:
                      expression: ".data.x"
                      language: jq
                    signal: DETECTED
                    confidence: 0.5
                    confidenceExpression:
                      expression: "score"
                      language: unknown
            """;
    assertThatThrownBy(() -> new YamlSituationDefinitionProvider(
            new java.io.ByteArrayInputStream(yaml.getBytes())))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unknown expression language");
}
```

Run: `mvn --batch-mode test -pl runtime -Dtest=YamlSituationDefinitionProviderTest#expressionRulesConfidenceExpressionInvalidLanguage`
Expected: PASS (already handled by `parseExpressionEntry`)

- [ ] **Step 7: Wire confidence expression compilation in SituationDefinitionRegistry**

In `constructExpressionRules()`, change:
```java
compiledRules.add(new ExpressionRulesGanglion.CompiledRule(
        compiled, rule.signal(), rule.confidence(), null, compiledEvidence));
```
to:
```java
CompiledExpression<Map, Object> compiledConfidence = null;
if (rule.confidenceExpression() != null) {
    compiledConfidence = compileExpression(
            rule.confidenceExpression(), er.ganglionId(), Map.class, Object.class);
}
compiledRules.add(new ExpressionRulesGanglion.CompiledRule(
        compiled, rule.signal(), rule.confidence(), compiledConfidence, compiledEvidence));
```

- [ ] **Step 8: Write and run registry compilation test**

Add to `SituationDefinitionRegistryTest`:

```java
@Test
void expressionRulesWithConfidenceExpressionCompiled() {
    var provider = new SimpleSituationDefinitionProvider(
            List.of(),
            List.of(new GanglionDescriptor.ExpressionRules(
                    "dyn-g", Set.of("test.event"),
                    List.of(new GanglionDescriptor.ExpressionRules.Rule(
                            null, DetectionSignal.DETECTED, 0.5,
                            new JQExpressionEvaluator(".data.score"),
                            Map.of())),
                    Map.of())));
    var registry = new SituationDefinitionRegistry(
            List.of(provider), List.of(), expressionRegistry());
    assertThat(registry.ganglion("dyn-g")).isNotNull();
}
```

Run: `mvn --batch-mode test -pl runtime -Dtest=SituationDefinitionRegistryTest#expressionRulesWithConfidenceExpressionCompiled`
Expected: PASS

- [ ] **Step 9: Create integration test YAML and test**

Create `runtime/src/test/resources/META-INF/ras-situations-dynamic-confidence.yaml`:

```yaml
ganglia:
  - ganglionId: dyn-confidence
    type: expression-rules
    handledEventTypes: [test.dynamic]
    rules:
      - when:
          expression: ".data.severity == \"HIGH\""
          language: jq
        signal: DETECTED
        confidence: 0.5
        confidenceExpression:
          expression: ".data.score / 100"
          language: jq
      - otherwise:
        signal: NOISE
        confidence: 0.0

situations:
  - situationId: dyn-confidence-sit
    eventTypes: [test.dynamic]
    chainMode:
      type: or
      ganglia: [dyn-confidence]
    triggerAction:
      type: notify-only
```

Add integration test (to an appropriate test class or a new one following existing patterns):

```java
@Test
void endToEndDynamicConfidenceExpression() {
    var provider = new YamlSituationDefinitionProvider(
            Thread.currentThread().getContextClassLoader()
                  .getResourceAsStream("META-INF/ras-situations-dynamic-confidence.yaml"));
    var registry = new SituationDefinitionRegistry(
            List.of(provider), List.of(), expressionRegistry());

    Ganglion ganglion = registry.ganglion("dyn-confidence");
    var event = CloudEventBuilder.v1()
            .withId("e1").withSource(URI.create("/t")).withType("test.dynamic")
            .withData("application/json", "{\"severity\":\"HIGH\",\"score\":85}".getBytes())
            .build();
    var ctx = SituationContext.initial("dyn-confidence-sit", "key", "t1", Instant.now());
    DetectionResult result = ganglion.detect(event, ctx).await().indefinitely();
    assertThat(result.confidence()).isEqualTo(0.85);
    assertThat(result.signal()).isEqualTo(DetectionSignal.DETECTED);
}
```

- [ ] **Step 10: Run all tests**

Run: `mvn --batch-mode test -pl api,runtime`
Expected: all tests pass

- [ ] **Step 11: Commit**

```bash
git add runtime/src/main/java/io/casehub/ras/runtime/YamlSituationDefinitionProvider.java \
        runtime/src/main/java/io/casehub/ras/runtime/SituationDefinitionRegistry.java \
        runtime/src/test/java/io/casehub/ras/runtime/YamlSituationDefinitionProviderTest.java \
        runtime/src/test/java/io/casehub/ras/runtime/SituationDefinitionRegistryTest.java \
        runtime/src/test/resources/META-INF/ras-situations-dynamic-confidence.yaml
git commit -m "feat(runtime): parse and compile confidenceExpression in YAML ganglia (#52)"
```
