# Per-Decision-Path Evidence Templates Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> subagent-driven-development (recommended) or executing-plans to
> implement this plan task-by-task. Each task follows TDD
> (test-driven-development) and uses ide-tooling for structural
> editing. Steps use checkbox (`- [ ]`) syntax for tracking.

**Focal issue:** #51 — Support per-rule evidence templates in expression-rules ganglion
**Issue group:** #51, #53

**Goal:** Add per-decision-path evidence templates to both ExpressionRules (per-rule) and
NaiveBayes (per-outcome) ganglion descriptors, with evaluation inside each ganglion and
YAML/registry support.

**Architecture:** Each descriptor variant's structural decision unit (rule / outcome) carries
evidence templates. The ganglion evaluates the matched path's templates, merging into
DetectionResult before the ganglion-level EvidenceExtractingGanglion wrapper adds its
cross-cutting templates. Three-layer merge: automatic evidence → per-decision-path → ganglion-level.

**Tech Stack:** Java 21, Quarkus, Mutiny, JQ/MVEL expressions via casehub-platform-expression,
Micrometer metrics, SnakeYAML

## Global Constraints

- Pre-release — all changes are breaking, no convenience constructors
- Evidence templates extract from CloudEvent only (DetectionResult portability invariant)
- Expression compilation via ExpressionEngineRegistry (casehub-platform-expression at test scope)
- Null expression result → key omitted from evidence (not error)
- Per-template error isolation — one failure doesn't block others
- IntelliJ MCP mandatory for all .java edits — use `ide_edit_member`, `ide_replace_member`,
  `ide_insert_member` for code changes, `ide_diagnostics` after each edit

---

### Task 1: API Type Changes + Fix All Breaking Call Sites

Add new fields to `GanglionDescriptor.ExpressionRules.Rule` and `GanglionDescriptor.NaiveBayes`.
Fix all callers with `Map.of()` so the build stays green. No behavior change.

**Files:**
- Modify: `api/src/main/java/io/casehub/ras/api/GanglionDescriptor.java`
- Modify: `api/src/test/java/io/casehub/ras/api/GanglionDescriptorTest.java`
- Modify: `runtime/src/main/java/io/casehub/ras/runtime/YamlSituationDefinitionProvider.java:218`
- Modify: `runtime/src/main/java/io/casehub/ras/runtime/YamlSituationDefinitionProvider.java:153-156`
- Modify: `runtime/src/test/java/io/casehub/ras/runtime/SituationDefinitionRegistryTest.java` (4 call sites)

**Interfaces:**
- Produces: `Rule(ExpressionEvaluator when, DetectionSignal signal, double confidence, Map<String, ExpressionEvaluator> evidenceTemplates)` — 4-arg record
- Produces: `NaiveBayes(..., Map<String, Map<String, ExpressionEvaluator>> outcomeEvidenceTemplates)` — 8-arg record

- [ ] **Step 1: Write failing test for Rule with evidenceTemplates**

In `GanglionDescriptorTest`, add test that constructs a Rule with per-rule evidence:

```java
@Test
void ruleWithEvidenceTemplates() {
    var rule = new GanglionDescriptor.ExpressionRules.Rule(
            new JQExpressionEvaluator(".data.severity == \"HIGH\""),
            DetectionSignal.DETECTED, 0.9,
            Map.of("reason", new JQExpressionEvaluator(".data.reason")));
    assertThat(rule.evidenceTemplates()).containsKey("reason");
}
```

- [ ] **Step 2: Write failing test for NaiveBayes with outcomeEvidenceTemplates**

```java
@Test
void naiveBayesWithOutcomeEvidenceTemplates() {
    var feature = new GanglionDescriptor.NaiveBayes.Feature(
            new JQExpressionEvaluator(".data.severity"),
            List.of("LOW", "HIGH"),
            new double[][]{{0.8, 0.2}, {0.3, 0.7}});
    var mapping = new GanglionDescriptor.NaiveBayes.SignalMapping(
            "ANOMALY", 0.75, 0.30, null);
    var descriptor = new GanglionDescriptor.NaiveBayes(
            "bayes-1", Set.of("sensor.reading"),
            List.of("NORMAL", "ANOMALY"), new double[]{0.9, 0.1},
            Map.of("severity", feature), mapping, Map.of(),
            Map.of("ANOMALY", Map.of("type", new JQExpressionEvaluator(".data.anomalyType"))));
    assertThat(descriptor.outcomeEvidenceTemplates()).containsKey("ANOMALY");
    assertThat(descriptor.outcomeEvidenceTemplates().get("ANOMALY")).containsKey("type");
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `mvn --batch-mode test -pl api/ -Dtest=GanglionDescriptorTest`
Expected: FAIL — Rule constructor has 3 args, NaiveBayes has 7 args

- [ ] **Step 4: Update Rule record to add evidenceTemplates field**

In `GanglionDescriptor.java`, update the `Rule` record:

```java
public record Rule(
        ExpressionEvaluator when,
        DetectionSignal signal,
        double confidence,
        Map<String, ExpressionEvaluator> evidenceTemplates
) {}
```

- [ ] **Step 5: Update NaiveBayes record to add outcomeEvidenceTemplates field**

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

- [ ] **Step 6: Fix existing GanglionDescriptorTest call sites**

All existing Rule constructions gain `Map.of()` as 4th arg.
All existing NaiveBayes constructions gain `Map.of()` as 8th arg.

In `expressionRulesRecordCarriesAllFields`:
```java
var rule = new GanglionDescriptor.ExpressionRules.Rule(
        new JQExpressionEvaluator(".data.severity == \"HIGH\""),
        DetectionSignal.DETECTED, 0.9, Map.of());
var otherwise = new GanglionDescriptor.ExpressionRules.Rule(
        null, DetectionSignal.NOISE, 0.0, Map.of());
```

In `expressionRulesWithEvidenceTemplates`:
```java
List.of(new GanglionDescriptor.ExpressionRules.Rule(null, DetectionSignal.NOISE, 0.0, Map.of())),
```

In `naiveBayesRecordCarriesAllFields` and `naiveBayesWithEvidenceTemplates`: add `Map.of()` as 8th arg.

- [ ] **Step 7: Fix YamlSituationDefinitionProvider call sites**

Line 218 — Rule construction in `parseExpressionRulesGanglion`:
```java
rules.add(new GanglionDescriptor.ExpressionRules.Rule(when, signal, confidence, parseEvidenceTemplates(ruleMap)));
```

Lines 153-156 — NaiveBayes construction in `parseNaiveBayesGanglion`:
```java
return new GanglionDescriptor.NaiveBayes(
        ganglionId, new LinkedHashSet<>(eventTypes), outcomes, priors,
        features, parseSignalMapping(sigMap),
        parseEvidenceTemplates(map), Map.of());
```

(The `Map.of()` for outcomeEvidenceTemplates is a stub — replaced in Task 4.)

- [ ] **Step 8: Fix SituationDefinitionRegistryTest call sites**

All 4 Rule construction sites in the registry test gain `Map.of()` as 4th arg.
NaiveBayes construction at line 632-639 gains `Map.of()` as 8th arg.

- [ ] **Step 9: Run full build to verify green**

Run: `mvn --batch-mode test`
Expected: ALL PASS — no behavior change, just field additions with defaults

- [ ] **Step 10: Verify with ide_diagnostics**

Run `ide_diagnostics` on `GanglionDescriptor.java`, `GanglionDescriptorTest.java`,
`YamlSituationDefinitionProvider.java`, `SituationDefinitionRegistryTest.java`.

- [ ] **Step 11: Commit**

```bash
git add api/src/main/java/io/casehub/ras/api/GanglionDescriptor.java \
        api/src/test/java/io/casehub/ras/api/GanglionDescriptorTest.java \
        runtime/src/main/java/io/casehub/ras/runtime/YamlSituationDefinitionProvider.java \
        runtime/src/test/java/io/casehub/ras/runtime/SituationDefinitionRegistryTest.java
git commit -m "feat(#51,#53): add evidenceTemplates to Rule, outcomeEvidenceTemplates to NaiveBayes descriptor"
```

---

### Task 2: ExpressionRulesGanglion — Per-Rule Evidence

Add `evidenceTemplates` to `CompiledRule`. Refactor `detect()` to delegate matched-rule
result building to a new `buildResult()` method that evaluates per-rule evidence templates.
Fix all CompiledRule call sites.

**Files:**
- Modify: `runtime/src/main/java/io/casehub/ras/runtime/ExpressionRulesGanglion.java`
- Modify: `runtime/src/test/java/io/casehub/ras/runtime/ExpressionRulesGanglionTest.java`
- Modify: `runtime/src/test/java/io/casehub/ras/runtime/ExpressionRulesGanglionContractTest.java:22`
- Modify: `runtime/src/main/java/io/casehub/ras/runtime/SituationDefinitionRegistry.java:338`

**Interfaces:**
- Consumes: `CloudEventExpressionContext.build(event)` — returns `Map<String, Object>` (already used)
- Produces: `CompiledRule(CompiledExpression<Map, Boolean> when, DetectionSignal signal, double confidence, Map<String, CompiledExpression<Map, Object>> evidenceTemplates)` — 4-arg record

- [ ] **Step 1: Write failing test — per-rule evidence evaluated for matched rule**

```java
@Test
void perRuleEvidenceEvaluatedForMatchedRule() {
    CompiledExpression<Map, Object> template = new CompiledExpression<>() {
        @Override public String type() { return "test"; }
        @Override public Object eval(Map context) { return "extracted-value"; }
    };
    var ganglion = new ExpressionRulesGanglion("g1", Set.of("test.event"), List.of(
            new ExpressionRulesGanglion.CompiledRule(matching(), DetectionSignal.DETECTED, 0.9,
                    Map.of("custom_key", template))), null);
    DetectionResult result = ganglion.detect(event(), CTX).await().indefinitely();
    assertThat(result.evidence()).containsEntry("matchedRuleIndex", 0);
    assertThat(result.evidence()).containsEntry("custom_key", "extracted-value");
}
```

- [ ] **Step 2: Write failing test — null template result omits key**

```java
@Test
void perRuleEvidenceNullResultOmitsKey() {
    CompiledExpression<Map, Object> template = new CompiledExpression<>() {
        @Override public String type() { return "test"; }
        @Override public Object eval(Map context) { return null; }
    };
    var ganglion = new ExpressionRulesGanglion("g1", Set.of("test.event"), List.of(
            new ExpressionRulesGanglion.CompiledRule(matching(), DetectionSignal.DETECTED, 0.9,
                    Map.of("absent_key", template))), null);
    DetectionResult result = ganglion.detect(event(), CTX).await().indefinitely();
    assertThat(result.evidence()).doesNotContainKey("absent_key");
}
```

- [ ] **Step 3: Write failing test — per-template error isolation**

```java
@Test
void perRuleEvidenceTemplateErrorIsolation() {
    CompiledExpression<Map, Object> good = new CompiledExpression<>() {
        @Override public String type() { return "test"; }
        @Override public Object eval(Map context) { return "ok"; }
    };
    CompiledExpression<Map, Object> bad = new CompiledExpression<>() {
        @Override public String type() { return "test"; }
        @Override public Object eval(Map context) { throw new RuntimeException("boom"); }
    };
    var ganglion = new ExpressionRulesGanglion("g1", Set.of("test.event"), List.of(
            new ExpressionRulesGanglion.CompiledRule(matching(), DetectionSignal.DETECTED, 0.9,
                    Map.of("bad_key", bad, "good_key", good))), null);
    DetectionResult result = ganglion.detect(event(), CTX).await().indefinitely();
    assertThat(result.evidence()).containsEntry("good_key", "ok");
    assertThat(result.evidence()).doesNotContainKey("bad_key");
}
```

- [ ] **Step 4: Write failing test — error metric for per-rule evidence**

```java
@Test
void perRuleEvidenceErrorMetric() {
    CompiledExpression<Map, Object> bad = new CompiledExpression<>() {
        @Override public String type() { return "test"; }
        @Override public Object eval(Map context) { throw new RuntimeException("boom"); }
    };
    var registry = new SimpleMeterRegistry();
    var ganglion = new ExpressionRulesGanglion("g1", Set.of("test.event"), List.of(
            new ExpressionRulesGanglion.CompiledRule(matching(), DetectionSignal.DETECTED, 0.9,
                    Map.of("bad_key", bad))), registry);
    ganglion.detect(event(), CTX).await().indefinitely();
    var counter = registry.find("ras.expression.error")
            .tag("ganglion_id", "g1")
            .tag("evidence_key", "bad_key")
            .tag("rule_index", "0")
            .tag("expression_point", "rule_evidence_extraction").counter();
    assertThat(counter).isNotNull();
    assertThat(counter.count()).isEqualTo(1.0);
}
```

- [ ] **Step 5: Write failing test — otherwise rule with evidence templates**

```java
@Test
void otherwiseRuleWithEvidenceTemplates() {
    CompiledExpression<Map, Object> template = new CompiledExpression<>() {
        @Override public String type() { return "test"; }
        @Override public Object eval(Map context) { return "fallback-value"; }
    };
    var ganglion = new ExpressionRulesGanglion("g1", Set.of("test.event"), List.of(
            new ExpressionRulesGanglion.CompiledRule(nonMatching(), DetectionSignal.DETECTED, 0.9, Map.of()),
            new ExpressionRulesGanglion.CompiledRule(null, DetectionSignal.NOISE, 0.0,
                    Map.of("fallback", template))), null);
    DetectionResult result = ganglion.detect(event(), CTX).await().indefinitely();
    assertThat(result.signal()).isEqualTo(DetectionSignal.NOISE);
    assertThat(result.evidence()).containsEntry("matchedRuleIndex", 1);
    assertThat(result.evidence()).containsEntry("fallback", "fallback-value");
}
```

- [ ] **Step 6: Run tests to verify they fail**

Run: `mvn --batch-mode test -pl runtime/ -Dtest=ExpressionRulesGanglionTest`
Expected: FAIL — CompiledRule constructor has 3 args

- [ ] **Step 7: Update CompiledRule record**

```java
record CompiledRule(
        CompiledExpression<Map, Boolean> when,
        DetectionSignal signal,
        double confidence,
        Map<String, CompiledExpression<Map, Object>> evidenceTemplates
) {}
```

- [ ] **Step 8: Fix all existing CompiledRule call sites with Map.of()**

All 17 existing `new ExpressionRulesGanglion.CompiledRule(...)` calls in
`ExpressionRulesGanglionTest` and `ExpressionRulesGanglionContractTest` gain `Map.of()` as 4th arg.

The `SituationDefinitionRegistry.constructExpressionRules()` call at line 338 also needs update — but
defer to Task 5 (that method is rewritten there). For now, add `Map.of()`:
```java
compiledRules.add(new ExpressionRulesGanglion.CompiledRule(
        compiled, rule.signal(), rule.confidence(), Map.of()));
```

- [ ] **Step 9: Add buildResult() method and update detect()**

Add private `buildResult()` method. Refactor `detect()` to delegate matched-rule result
building. See spec §3 for the exact code.

- [ ] **Step 10: Run tests to verify they pass**

Run: `mvn --batch-mode test -pl runtime/ -Dtest=ExpressionRulesGanglionTest`
Expected: ALL PASS

- [ ] **Step 11: Run full build**

Run: `mvn --batch-mode test`
Expected: ALL PASS

- [ ] **Step 12: Commit**

```bash
git add runtime/src/main/java/io/casehub/ras/runtime/ExpressionRulesGanglion.java \
        runtime/src/test/java/io/casehub/ras/runtime/ExpressionRulesGanglionTest.java \
        runtime/src/test/java/io/casehub/ras/runtime/ExpressionRulesGanglionContractTest.java \
        runtime/src/main/java/io/casehub/ras/runtime/SituationDefinitionRegistry.java
git commit -m "feat(#51): per-rule evidence templates in ExpressionRulesGanglion"
```

---

### Task 3: NaiveBayesConfig + NaiveBayesGanglion — Per-Outcome Evidence

Add `outcomeEvidenceTemplates` to `NaiveBayesConfig` (breaking — 8-arg).
Add `MeterRegistry` to `NaiveBayesGanglion` (breaking — 3-arg constructor replaces 2-arg).
Add `winningOutcome` to automatic evidence. Evaluate per-outcome evidence templates.

**Files:**
- Modify: `runtime/src/main/java/io/casehub/ras/runtime/NaiveBayesConfig.java`
- Modify: `runtime/src/main/java/io/casehub/ras/runtime/NaiveBayesGanglion.java`
- Modify: `runtime/src/test/java/io/casehub/ras/runtime/NaiveBayesConfigTest.java`
- Modify: `runtime/src/test/java/io/casehub/ras/runtime/NaiveBayesGanglionTest.java`
- Modify: `runtime/src/test/java/io/casehub/ras/runtime/NaiveBayesGanglionContractTest.java`
- Modify: `runtime/src/main/java/io/casehub/ras/runtime/SituationDefinitionRegistry.java:309-315`

**Interfaces:**
- Consumes: `CloudEventExpressionContext.build(event)` — returns `Map<String, Object>`
- Produces: `NaiveBayesConfig(..., Map<String, Map<String, CompiledExpression<Map, Object>>> outcomeEvidenceTemplates)` — 8-arg record
- Produces: `NaiveBayesGanglion(NaiveBayesConfig, GanglionStateStore, MeterRegistry)` — 3-arg constructor

- [ ] **Step 1: Write failing test — NaiveBayesConfig rejects unknown outcome key**

```java
@Test
void unknownOutcomeKeyInOutcomeEvidenceTemplatesIsRejected() {
    assertThatIllegalArgumentException()
            .isThrownBy(() -> new NaiveBayesConfig(
                    "g", Set.of("e"), List.of("A", "B"),
                    new double[]{0.5, 0.5}, Map.of(), NOOP_EXTRACTOR, VALID_MAPPING,
                    Map.of("C", Map.of())))
            .withMessageContaining("C")
            .withMessageContaining("not in outcomes");
}
```

- [ ] **Step 2: Write failing test — winningOutcome in evidence**

```java
@Test
void winningOutcomeInEvidence() {
    var ganglion = new NaiveBayesGanglion(twoOutcomeConfig(),
            new InMemoryGanglionStateStore(), null);
    var event = testEvent("test.event", Instant.parse("2026-06-26T10:00:00Z"));
    DetectionResult result = ganglion.detect(event, testContext()).await().indefinitely();
    assertThat(result.evidence()).containsKey("winningOutcome");
    String winner = (String) result.evidence().get("winningOutcome");
    assertThat(winner).isIn("NORMAL", "ANOMALY");
}
```

- [ ] **Step 3: Write failing test — per-outcome evidence evaluated for winning outcome**

```java
@Test
void perOutcomeEvidenceEvaluatedForWinningOutcome() {
    CompiledExpression<Map, Object> template = new CompiledExpression<>() {
        @Override public String type() { return "test"; }
        @Override public Object eval(Map context) { return "extracted"; }
    };
    // High prior on B → B wins → per-outcome evidence for B evaluated
    var config = new NaiveBayesConfig("g", Set.of("test.event"),
            List.of("A", "B"), new double[]{0.1, 0.9},
            Map.of(), event -> Map.of(),
            new NaiveBayesSignalMapping("B", 0.75, 0.30),
            Map.of("B", Map.of("custom", template)));
    var ganglion = new NaiveBayesGanglion(config, new InMemoryGanglionStateStore(), null);
    var event = testEvent("test.event", Instant.parse("2026-06-26T10:00:00Z"));
    DetectionResult result = ganglion.detect(event, testContext()).await().indefinitely();
    assertThat(result.evidence()).containsEntry("custom", "extracted");
    assertThat(result.evidence().get("winningOutcome")).isEqualTo("B");
}
```

- [ ] **Step 4: Write failing test — non-winning outcome's templates not evaluated**

```java
@Test
void nonWinningOutcomeTemplatesNotEvaluated() {
    var shouldNotBeCalled = new java.util.concurrent.atomic.AtomicBoolean(false);
    CompiledExpression<Map, Object> trap = new CompiledExpression<>() {
        @Override public String type() { return "test"; }
        @Override public Object eval(Map context) { shouldNotBeCalled.set(true); return "trap"; }
    };
    // High prior on B → B wins → A's templates NOT evaluated
    var config = new NaiveBayesConfig("g", Set.of("test.event"),
            List.of("A", "B"), new double[]{0.1, 0.9},
            Map.of(), event -> Map.of(),
            new NaiveBayesSignalMapping("B", 0.75, 0.30),
            Map.of("A", Map.of("trap_key", trap)));
    var ganglion = new NaiveBayesGanglion(config, new InMemoryGanglionStateStore(), null);
    ganglion.detect(testEvent("test.event", Instant.parse("2026-06-26T10:00:00Z")),
            testContext()).await().indefinitely();
    assertThat(shouldNotBeCalled.get()).isFalse();
}
```

- [ ] **Step 5: Write failing test — per-outcome evidence error metric**

```java
@Test
void perOutcomeEvidenceErrorMetric() {
    CompiledExpression<Map, Object> bad = new CompiledExpression<>() {
        @Override public String type() { return "test"; }
        @Override public Object eval(Map context) { throw new RuntimeException("boom"); }
    };
    var registry = new SimpleMeterRegistry();
    var config = new NaiveBayesConfig("g", Set.of("test.event"),
            List.of("A", "B"), new double[]{0.1, 0.9},
            Map.of(), event -> Map.of(),
            new NaiveBayesSignalMapping("B", 0.75, 0.30),
            Map.of("B", Map.of("bad_key", bad)));
    var ganglion = new NaiveBayesGanglion(config, new InMemoryGanglionStateStore(), registry);
    ganglion.detect(testEvent("test.event", Instant.parse("2026-06-26T10:00:00Z")),
            testContext()).await().indefinitely();
    var counter = registry.find("ras.expression.error")
            .tag("ganglion_id", "g")
            .tag("evidence_key", "bad_key")
            .tag("outcome", "B")
            .tag("expression_point", "outcome_evidence_extraction").counter();
    assertThat(counter).isNotNull();
    assertThat(counter.count()).isEqualTo(1.0);
}
```

- [ ] **Step 6: Run tests to verify they fail**

Run: `mvn --batch-mode test -pl runtime/ -Dtest="NaiveBayesConfigTest,NaiveBayesGanglionTest"`
Expected: FAIL — constructors have wrong arity

- [ ] **Step 7: Update NaiveBayesConfig — add outcomeEvidenceTemplates field**

Add 8th field `Map<String, Map<String, CompiledExpression<Map, Object>>> outcomeEvidenceTemplates`.
Add validation in compact constructor: keys must be in `outcomes`.
See spec §5 for exact code.

- [ ] **Step 8: Fix all NaiveBayesConfig call sites**

All existing 7-arg `NaiveBayesConfig(...)` calls gain `Map.of()` as 8th arg.
This covers: `NaiveBayesConfigTest` (~13 call sites), `NaiveBayesGanglionTest` (~10 call sites),
`NaiveBayesGanglionContractTest` (1 call site), `SituationDefinitionRegistry.constructNaiveBayes()` (1 call site).

- [ ] **Step 9: Update NaiveBayesGanglion — 3-arg constructor with MeterRegistry**

Replace 2-arg `(config, stateStore)` with 3-arg `(config, stateStore, meterRegistry)`.
Add `private final MeterRegistry meterRegistry` field.
Add LOG field.
See spec §4 for exact code.

- [ ] **Step 10: Fix all NaiveBayesGanglion constructor call sites**

All existing `new NaiveBayesGanglion(config, stateStore)` calls become
`new NaiveBayesGanglion(config, stateStore, null)`.
This covers: `NaiveBayesGanglionTest` (~8 call sites), `NaiveBayesGanglionContractTest` (1),
`SituationDefinitionRegistry.constructNaiveBayes()` (1 — pass `meterRegistry` not `null`).

- [ ] **Step 11: Implement winningOutcome + per-outcome evidence evaluation in detect()**

Rewrite the evidence construction block in `detect()`. After computing `posteriors`:
1. Find winnerIndex (highest posterior)
2. Build evidence as LinkedHashMap with posterior, features, winningOutcome
3. If outcomeTemplates exist for winning outcome, build CloudEventExpressionContext and evaluate
See spec §4 for exact code.

- [ ] **Step 12: Run tests to verify they pass**

Run: `mvn --batch-mode test -pl runtime/ -Dtest="NaiveBayesConfigTest,NaiveBayesGanglionTest"`
Expected: ALL PASS

- [ ] **Step 13: Run full build**

Run: `mvn --batch-mode test`
Expected: ALL PASS

- [ ] **Step 14: Commit**

```bash
git add runtime/src/main/java/io/casehub/ras/runtime/NaiveBayesConfig.java \
        runtime/src/main/java/io/casehub/ras/runtime/NaiveBayesGanglion.java \
        runtime/src/test/java/io/casehub/ras/runtime/NaiveBayesConfigTest.java \
        runtime/src/test/java/io/casehub/ras/runtime/NaiveBayesGanglionTest.java \
        runtime/src/test/java/io/casehub/ras/runtime/NaiveBayesGanglionContractTest.java \
        runtime/src/main/java/io/casehub/ras/runtime/SituationDefinitionRegistry.java
git commit -m "feat(#53): per-outcome evidence templates + winningOutcome in NaiveBayesGanglion"
```

---

### Task 4: YAML Parsing — Per-Rule and Per-Outcome Evidence

Parse `evidenceTemplates` within individual expression-rules, and `outcomeEvidenceTemplates`
on naive-bayes ganglia. Validation: outcome keys must match declared outcomes.

**Files:**
- Modify: `runtime/src/main/java/io/casehub/ras/runtime/YamlSituationDefinitionProvider.java`
- Modify: `runtime/src/test/java/io/casehub/ras/runtime/YamlSituationDefinitionProviderTest.java`

**Interfaces:**
- Consumes: `parseExpressionEntry(Map, String)` — existing shared parser
- Consumes: `parseEvidenceTemplates(Map)` — existing method
- Produces: `parseOutcomeEvidenceTemplates(Map, List<String>, String)` — new method

- [ ] **Step 1: Write failing test — per-rule evidenceTemplates parsed**

```java
@Test
void parsesPerRuleEvidenceTemplates() {
    var provider = provider("""
            ganglia:
              - ganglionId: rule-evid
                type: expression-rules
                handledEventTypes: [test.event]
                rules:
                  - when:
                      expression: ".data.x == \\"HIGH\\""
                      language: jq
                    signal: DETECTED
                    confidence: 0.9
                    evidenceTemplates:
                      reason:
                        expression: ".data.reason"
                        language: jq
                  - otherwise:
                    signal: NOISE
                    confidence: 0.0
            """);
    var er = (io.casehub.ras.api.GanglionDescriptor.ExpressionRules) provider.ganglionDescriptors().getFirst();
    assertThat(er.rules().get(0).evidenceTemplates()).containsKey("reason");
    assertThat(er.rules().get(1).evidenceTemplates()).isEmpty();
}
```

- [ ] **Step 2: Write failing test — per-rule evidenceTemplates absent defaults to empty**

```java
@Test
void perRuleEvidenceTemplatesAbsentDefaultsToEmpty() {
    var provider = provider("""
            ganglia:
              - ganglionId: no-evid
                type: expression-rules
                handledEventTypes: [test.event]
                rules:
                  - when:
                      expression: ".data.x"
                      language: jq
                    signal: DETECTED
                    confidence: 0.9
            """);
    var er = (io.casehub.ras.api.GanglionDescriptor.ExpressionRules) provider.ganglionDescriptors().getFirst();
    assertThat(er.rules().get(0).evidenceTemplates()).isEmpty();
}
```

- [ ] **Step 3: Write failing test — outcomeEvidenceTemplates parsed**

```java
@Test
void parsesOutcomeEvidenceTemplates() {
    var provider = provider("""
            ganglia:
              - ganglionId: outcome-evid
                type: naive-bayes
                handledEventTypes: [test.event]
                outcomes: [A, B]
                priors: [0.5, 0.5]
                features:
                  f1:
                    expression: ".data.f"
                    language: jq
                    values: [X]
                    likelihoods:
                      - [1]
                      - [1]
                signalMapping:
                  targetOutcome: B
                  detectedThreshold: 0.75
                  weakThreshold: 0.30
                outcomeEvidenceTemplates:
                  A:
                    reason:
                      expression: ".data.reason"
                      language: jq
                  B:
                    detail:
                      expression: ".data.detail"
                      language: jq
            """);
    var nb = (io.casehub.ras.api.GanglionDescriptor.NaiveBayes) provider.ganglionDescriptors().getFirst();
    assertThat(nb.outcomeEvidenceTemplates()).hasSize(2);
    assertThat(nb.outcomeEvidenceTemplates().get("A")).containsKey("reason");
    assertThat(nb.outcomeEvidenceTemplates().get("B")).containsKey("detail");
}
```

- [ ] **Step 4: Write failing test — unknown outcome key rejected**

```java
@Test
void outcomeEvidenceTemplatesUnknownOutcomeThrows() {
    assertThatIllegalArgumentException().isThrownBy(() -> provider("""
            ganglia:
              - ganglionId: bad
                type: naive-bayes
                handledEventTypes: [test.event]
                outcomes: [A, B]
                priors: [0.5, 0.5]
                features:
                  f1:
                    expression: ".data.f"
                    language: jq
                    values: [X]
                    likelihoods:
                      - [1]
                      - [1]
                signalMapping:
                  targetOutcome: B
                  detectedThreshold: 0.75
                  weakThreshold: 0.30
                outcomeEvidenceTemplates:
                  UNKNOWN:
                    reason:
                      expression: ".data.x"
                      language: jq
            """))
            .withMessageContaining("UNKNOWN")
            .withMessageContaining("not in outcomes");
}
```

- [ ] **Step 5: Run tests to verify they fail**

Run: `mvn --batch-mode test -pl runtime/ -Dtest=YamlSituationDefinitionProviderTest`
Expected: FAIL — per-rule evidence not parsed, outcomeEvidenceTemplates not parsed

- [ ] **Step 6: Implement parseOutcomeEvidenceTemplates()**

Add new method. See spec §6 for exact code.

- [ ] **Step 7: Wire into parseNaiveBayesGanglion()**

Replace the `Map.of()` stub from Task 1 with `parseOutcomeEvidenceTemplates(map, outcomes, ganglionId)`.

- [ ] **Step 8: Run tests to verify they pass**

Run: `mvn --batch-mode test -pl runtime/ -Dtest=YamlSituationDefinitionProviderTest`
Expected: ALL PASS

- [ ] **Step 9: Run full build**

Run: `mvn --batch-mode test`
Expected: ALL PASS

- [ ] **Step 10: Commit**

```bash
git add runtime/src/main/java/io/casehub/ras/runtime/YamlSituationDefinitionProvider.java \
        runtime/src/test/java/io/casehub/ras/runtime/YamlSituationDefinitionProviderTest.java
git commit -m "feat(#51,#53): YAML parsing for per-rule and per-outcome evidence templates"
```

---

### Task 5: Registry Integration — Compile Per-Decision-Path Evidence

Update `SituationDefinitionRegistry.constructExpressionRules()` to compile per-rule evidence
templates. Update `constructNaiveBayes()` to compile per-outcome evidence templates and pass
to NaiveBayesConfig. Update collision warnings.

**Files:**
- Modify: `runtime/src/main/java/io/casehub/ras/runtime/SituationDefinitionRegistry.java`
- Modify: `runtime/src/test/java/io/casehub/ras/runtime/SituationDefinitionRegistryTest.java`

**Interfaces:**
- Consumes: `compileExpression(ExpressionEvaluator, String, Class, Class)` — existing method
- Produces: Compiled per-rule and per-outcome templates wired into ganglion construction

- [ ] **Step 1: Write failing test — per-rule evidence compiled and functional**

```java
@Test
void expressionRulesWithPerRuleEvidenceCompiled() {
    var descriptor = new io.casehub.ras.api.GanglionDescriptor.ExpressionRules(
            "per-rule-evid", Set.of("test.event"),
            List.of(new io.casehub.ras.api.GanglionDescriptor.ExpressionRules.Rule(
                    new io.casehub.platform.api.expression.JQExpressionEvaluator("true"),
                    io.casehub.ras.api.DetectionSignal.DETECTED, 0.9,
                    Map.of("extracted", new io.casehub.platform.api.expression.JQExpressionEvaluator(".type")))),
            Map.of());

    io.casehub.ras.api.SituationDefinitionProvider provider = new io.casehub.ras.api.SituationDefinitionProvider() {
        public List<io.casehub.ras.api.SituationRegistration> registrations()    {return List.of();}
        public List<io.casehub.ras.api.GanglionDescriptor> ganglionDescriptors() {return List.of(descriptor);}
    };

    var registry = new SituationDefinitionRegistry(
            List.of(provider), List.of(),
            new StubExpressionEngineRegistry(), new InMemoryGanglionStateStore(), null);

    assertThat(registry.ganglion("per-rule-evid")).isNotNull();
}
```

- [ ] **Step 2: Write failing test — per-outcome evidence compiled and NaiveBayes functional**

```java
@Test
void naiveBayesWithPerOutcomeEvidenceCompiled() {
    var descriptor = new io.casehub.ras.api.GanglionDescriptor.NaiveBayes(
            "per-outcome-evid", Set.of("test.event"),
            List.of("A", "B"), new double[]{0.5, 0.5},
            Map.of("f1", new io.casehub.ras.api.GanglionDescriptor.NaiveBayes.Feature(
                    new io.casehub.platform.api.expression.JQExpressionEvaluator(".data.f"),
                    List.of("X"), new double[][]{{0.6}, {0.4}})),
            new io.casehub.ras.api.GanglionDescriptor.NaiveBayes.SignalMapping("B", 0.75, 0.30, null),
            Map.of(),
            Map.of("A", Map.of("detail", new io.casehub.platform.api.expression.JQExpressionEvaluator(".data.detail"))));

    io.casehub.ras.api.SituationDefinitionProvider provider = new io.casehub.ras.api.SituationDefinitionProvider() {
        public List<io.casehub.ras.api.SituationRegistration> registrations()    {return List.of();}
        public List<io.casehub.ras.api.GanglionDescriptor> ganglionDescriptors() {return List.of(descriptor);}
    };

    var registry = new SituationDefinitionRegistry(
            List.of(provider), List.of(),
            new StubExpressionEngineRegistry(), new InMemoryGanglionStateStore(), null);

    assertThat(registry.ganglion("per-outcome-evid")).isNotNull();
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `mvn --batch-mode test -pl runtime/ -Dtest=SituationDefinitionRegistryTest`
Expected: FAIL — per-rule/per-outcome templates not compiled, Map.of() stubs from earlier tasks

- [ ] **Step 4: Update constructExpressionRules() to compile per-rule evidence**

See spec §7 for exact code. Replace `Map.of()` stub with per-rule compilation loop.

- [ ] **Step 5: Update constructNaiveBayes() to compile per-outcome evidence + pass to config**

See spec §7 for exact code. Compile outcomeEvidenceTemplates, pass to NaiveBayesConfig constructor.
Pass `meterRegistry` to NaiveBayesGanglion constructor.

- [ ] **Step 6: Update collision warnings in constructGanglion()**

Update NaiveBayes auto key set from `Set.of("posterior", "features")` to
`Set.of("posterior", "features", "winningOutcome")`.

- [ ] **Step 7: Add per-rule collision warning in constructExpressionRules()**

```java
if (compiledEvidence.containsKey("matchedRuleIndex")) {
    LOG.warning("Per-rule evidence template key 'matchedRuleIndex' in rule " + i
                + " of ganglion '" + er.ganglionId()
                + "' shadows automatic evidence key — template will overwrite");
}
```

- [ ] **Step 8: Add per-outcome collision warning in constructNaiveBayes()**

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

- [ ] **Step 9: Run tests to verify they pass**

Run: `mvn --batch-mode test -pl runtime/ -Dtest=SituationDefinitionRegistryTest`
Expected: ALL PASS

- [ ] **Step 10: Run full build**

Run: `mvn --batch-mode test`
Expected: ALL PASS

- [ ] **Step 11: Commit**

```bash
git add runtime/src/main/java/io/casehub/ras/runtime/SituationDefinitionRegistry.java \
        runtime/src/test/java/io/casehub/ras/runtime/SituationDefinitionRegistryTest.java
git commit -m "feat(#51,#53): registry compilation for per-rule and per-outcome evidence templates"
```

---

### Task 6: Integration Tests + CLAUDE.md Update

End-to-end YAML tests with casehub-platform-expression on the test classpath.
Update CLAUDE.md to reflect new types and fields.

**Files:**
- Create: `runtime/src/test/resources/META-INF/ras-situations-e2e-per-rule-evidence.yaml`
- Create: `runtime/src/test/resources/META-INF/ras-situations-e2e-per-outcome-evidence.yaml`
- Modify: `runtime/src/test/java/io/casehub/ras/runtime/YamlSituationDefinitionProviderTest.java`
- Modify: `CLAUDE.md`

**Interfaces:**
- Consumes: All prior tasks — full pipeline from YAML → parsing → registry → ganglion

- [ ] **Step 1: Create per-rule evidence E2E YAML fixture**

File: `runtime/src/test/resources/META-INF/ras-situations-e2e-per-rule-evidence.yaml`

```yaml
ganglia:
  - ganglionId: e2e-rules
    type: expression-rules
    handledEventTypes: [test.e2e]
    rules:
      - when:
          expression: ".data.severity == \"HIGH\""
          language: jq
        signal: DETECTED
        confidence: 0.9
        evidenceTemplates:
          reason:
            expression: ".data.reason"
            language: jq
      - otherwise:
        signal: NOISE
        confidence: 0.0
    evidenceTemplates:
      severity:
        expression: ".data.severity"
        language: jq

situations:
  - situationId: e2e-per-rule
    eventTypes: [test.e2e]
    chainMode:
      type: or
      ganglia: [e2e-rules]
    triggerAction:
      type: notify-only
```

- [ ] **Step 2: Create per-outcome evidence E2E YAML fixture**

File: `runtime/src/test/resources/META-INF/ras-situations-e2e-per-outcome-evidence.yaml`

```yaml
ganglia:
  - ganglionId: e2e-bayes-outcome
    type: naive-bayes
    handledEventTypes: [test.e2e]
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
    signalMapping:
      targetOutcome: ANOMALY
      detectedThreshold: 0.75
      weakThreshold: 0.30
    outcomeEvidenceTemplates:
      ANOMALY:
        anomaly_type:
          expression: ".data.anomalyType"
          language: jq
    evidenceTemplates:
      sensor_id:
        expression: ".data.sensorId"
        language: jq

situations:
  - situationId: e2e-per-outcome
    eventTypes: [test.e2e]
    chainMode:
      type: or
      ganglia: [e2e-bayes-outcome]
    triggerAction:
      type: notify-only
```

- [ ] **Step 3: Write E2E test — per-rule evidence flows through pipeline**

```java
@Test
void endToEndPerRuleEvidenceTemplates() {
    var provider = new YamlSituationDefinitionProvider(
            Thread.currentThread().getContextClassLoader()
                  .getResourceAsStream("META-INF/ras-situations-e2e-per-rule-evidence.yaml"));

    var engines = new io.casehub.platform.expression.DefaultExpressionEngineRegistry();
    engines.register(new io.casehub.platform.expression.JQExpressionEngine());
    var registry = new SituationDefinitionRegistry(
            java.util.List.of(provider), java.util.List.of(), engines, new InMemoryGanglionStateStore(), null);

    var event = io.cloudevents.core.builder.CloudEventBuilder.v1()
            .withId("e2e-1").withSource(java.net.URI.create("/test")).withType("test.e2e")
            .withSubject("device-1")
            .withData("application/json", "{\"severity\":\"HIGH\",\"reason\":\"temperature spike\"}".getBytes())
            .build();

    var ganglion = registry.ganglion("e2e-rules");
    var ctx = io.casehub.ras.api.SituationContext.initial("e2e-per-rule", "device-1", "tenant-1",
            java.time.Instant.parse("2026-07-22T10:00:00Z"));
    var result = ganglion.detect(event, ctx).await().indefinitely();

    assertThat(result.evidence()).containsEntry("matchedRuleIndex", 0);
    assertThat(result.evidence()).containsEntry("reason", "temperature spike");
    assertThat(result.evidence()).containsEntry("severity", "HIGH");
}
```

- [ ] **Step 4: Write E2E test — per-outcome evidence flows through pipeline**

```java
@Test
void endToEndPerOutcomeEvidenceTemplates() {
    var provider = new YamlSituationDefinitionProvider(
            Thread.currentThread().getContextClassLoader()
                  .getResourceAsStream("META-INF/ras-situations-e2e-per-outcome-evidence.yaml"));

    var engines = new io.casehub.platform.expression.DefaultExpressionEngineRegistry();
    engines.register(new io.casehub.platform.expression.JQExpressionEngine());
    var registry = new SituationDefinitionRegistry(
            java.util.List.of(provider), java.util.List.of(), engines, new InMemoryGanglionStateStore(), null);

    var event = io.cloudevents.core.builder.CloudEventBuilder.v1()
            .withId("e2e-1").withSource(java.net.URI.create("/test")).withType("test.e2e")
            .withSubject("sensor-1")
            .withData("application/json",
                    "{\"severity\":\"HIGH\",\"anomalyType\":\"overheating\",\"sensorId\":\"S42\"}".getBytes())
            .build();

    var ganglion = registry.ganglion("e2e-bayes-outcome");
    var ctx = io.casehub.ras.api.SituationContext.initial("e2e-per-outcome", "sensor-1", "tenant-1",
            java.time.Instant.parse("2026-07-22T10:00:00Z"));
    var result = ganglion.detect(event, ctx).await().indefinitely();

    assertThat(result.evidence()).containsKey("posterior");
    assertThat(result.evidence()).containsKey("winningOutcome");
    assertThat(result.evidence()).containsEntry("sensor_id", "S42");
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvn --batch-mode test -pl runtime/ -Dtest=YamlSituationDefinitionProviderTest`
Expected: ALL PASS

- [ ] **Step 6: Run full build**

Run: `mvn --batch-mode test`
Expected: ALL PASS

- [ ] **Step 7: Update CLAUDE.md**

Update the following sections:
- `GanglionDescriptor` description — mention `outcomeEvidenceTemplates` on NaiveBayes, per-rule `evidenceTemplates` on Rule
- `ExpressionRulesGanglion` — note per-rule evidence evaluation
- `NaiveBayesGanglion` — note winningOutcome auto evidence, per-outcome evidence evaluation
- `NaiveBayesConfig` type — note new `outcomeEvidenceTemplates` field
- Core Types table — `DetectionResult` evidence keys updated for NaiveBayes (add `winningOutcome`)
- YAML Ganglia section — document per-rule and per-outcome evidence YAML syntax

- [ ] **Step 8: Commit**

```bash
git add runtime/src/test/resources/META-INF/ras-situations-e2e-per-rule-evidence.yaml \
        runtime/src/test/resources/META-INF/ras-situations-e2e-per-outcome-evidence.yaml \
        runtime/src/test/java/io/casehub/ras/runtime/YamlSituationDefinitionProviderTest.java \
        CLAUDE.md
git commit -m "feat(#51,#53): E2E tests for per-decision-path evidence + CLAUDE.md update"
```
