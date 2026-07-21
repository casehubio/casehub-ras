# Evidence Extraction Templates + Expression-Rule Ganglion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use executing-plans to
> implement this plan task-by-task. Each task follows TDD
> (test-driven-development) and uses ide-tooling for structural editing.
> Steps use checkbox (`- [ ]`) syntax for tracking.

**Focal issue:** #48 — Evidence extraction templates
**Issue group:** #48, #50

**Goal:** Add expression-based evidence templates as a cross-cutting
`GanglionDescriptor` feature, and a new expression-rules ganglion type
for declarative condition→signal detection in YAML.

**Architecture:** Evidence templates are a cross-cutting
`GanglionDescriptor` feature applied via an `EvidenceExtractingGanglion`
wrapper at construction time. `ExpressionRulesGanglion` is a new
stateless ganglion that evaluates boolean rules in order. Both use
existing expression infrastructure (`ExpressionEngineRegistry`,
`CloudEventExpressionContext`).

**Tech Stack:** Java 21, Quarkus CDI, Mutiny Uni, CloudEvents,
JQ/MVEL expression engines (casehub-platform-expression at test scope)

## Global Constraints

- Pre-release: breaking API changes are acceptable
- Expression compilation via `ExpressionEngineRegistry`
- IntelliJ MCP mandatory for all Java file operations
- TDD: failing test → implement → verify pass → commit
- Evidence templates extract from `CloudEvent` only (DetectionResult
  portability invariant)
- All `{expression, language}` parsing consolidated into shared
  `parseExpressionEntry()` method

**Spec:** `docs/superpowers/specs/2026-07-21-evidence-templates-expression-rules-design.md`

---

### Task 1: GanglionDescriptor API Changes (api/)

**Files:**
- Modify: `api/src/main/java/io/casehub/ras/api/GanglionDescriptor.java`
- Modify: `api/src/test/java/io/casehub/ras/api/GanglionDescriptorTest.java`

**Interfaces:**
- Produces: `GanglionDescriptor.evidenceTemplates()` default method,
  `GanglionDescriptor.NaiveBayes` with 7th field `Map<String, ExpressionEvaluator> evidenceTemplates`,
  `GanglionDescriptor.ExpressionRules` record with `List<Rule> rules` + `Map<String, ExpressionEvaluator> evidenceTemplates`,
  `GanglionDescriptor.ExpressionRules.Rule(ExpressionEvaluator when, DetectionSignal signal, double confidence)`

- [ ] **Step 1: Write failing tests for ExpressionRules**

Add to `GanglionDescriptorTest`:
```java
@Test
void expressionRulesRecordCarriesAllFields() {
    var rule = new GanglionDescriptor.ExpressionRules.Rule(
            new JQExpressionEvaluator(".data.severity == \"HIGH\""),
            DetectionSignal.DETECTED, 0.9);
    var otherwise = new GanglionDescriptor.ExpressionRules.Rule(
            null, DetectionSignal.NOISE, 0.0);

    var descriptor = new GanglionDescriptor.ExpressionRules(
            "severity-checker", Set.of("sensor.reading"),
            List.of(rule, otherwise), Map.of());

    assertThat(descriptor.ganglionId()).isEqualTo("severity-checker");
    assertThat(descriptor.handledEventTypes()).containsExactly("sensor.reading");
    assertThat(descriptor.rules()).hasSize(2);
    assertThat(descriptor.rules().get(0).when()).isNotNull();
    assertThat(descriptor.rules().get(0).signal()).isEqualTo(DetectionSignal.DETECTED);
    assertThat(descriptor.rules().get(0).confidence()).isEqualTo(0.9);
    assertThat(descriptor.rules().get(1).when()).isNull();
    assertThat(descriptor.evidenceTemplates()).isEmpty();
}

@Test
void expressionRulesWithEvidenceTemplates() {
    var descriptor = new GanglionDescriptor.ExpressionRules(
            "checker", Set.of("event.type"),
            List.of(new GanglionDescriptor.ExpressionRules.Rule(null, DetectionSignal.NOISE, 0.0)),
            Map.of("severity", new JQExpressionEvaluator(".data.severity")));

    assertThat(descriptor.evidenceTemplates()).containsKey("severity");
}

@Test
void evidenceTemplatesDefaultReturnsEmptyMap() {
    // ExpressionRules with empty evidence templates — default method returns Map.of()
    var descriptor = new GanglionDescriptor.ExpressionRules(
            "test", Set.of("t"), List.of(), Map.of());
    assertThat(descriptor.evidenceTemplates()).isEmpty();
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn --batch-mode test -pl api/ -Dtest=GanglionDescriptorTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure — `ExpressionRules` does not exist

- [ ] **Step 3: Implement GanglionDescriptor changes**

Using `ide_edit_member` on `GanglionDescriptor.java`:

1. Add `default Map<String, ExpressionEvaluator> evidenceTemplates() { return Map.of(); }` to the sealed interface
2. Add `Map<String, ExpressionEvaluator> evidenceTemplates` as 7th parameter to `NaiveBayes` record
3. Add `ExpressionRules` record with nested `Rule` record as new `permits` variant

- [ ] **Step 4: Update NaiveBayes callers — add `Map.of()` as 7th argument**

All callers (found via `ide_find_references`):
- `GanglionDescriptorTest.naiveBayesRecordCarriesAllFields` (line 24)
- `YamlSituationDefinitionProvider.parseNaiveBayesGanglion` (line 151) — will get `parseEvidenceTemplates(map)` in Task 2
- `SituationDefinitionRegistryTest` — 4 test methods constructing NaiveBayes (lines 439, 464, 489, 515)

For now, add `Map.of()` to all. Task 2 replaces the YAML provider call with actual template parsing.

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvn --batch-mode test -pl api/`
Expected: all pass including new ExpressionRules tests

- [ ] **Step 6: Commit**

```
feat(#48,#50): GanglionDescriptor — evidenceTemplates default, ExpressionRules variant
```

---

### Task 2: EvidenceExtractingGanglion + Evidence Pipeline (runtime/)

**Files:**
- Create: `runtime/src/main/java/io/casehub/ras/runtime/EvidenceExtractingGanglion.java`
- Create: `runtime/src/test/java/io/casehub/ras/runtime/EvidenceExtractingGanglionTest.java`
- Modify: `runtime/src/main/java/io/casehub/ras/runtime/YamlSituationDefinitionProvider.java`
- Modify: `runtime/src/main/java/io/casehub/ras/runtime/SituationDefinitionRegistry.java`
- Modify: `runtime/src/test/java/io/casehub/ras/runtime/YamlSituationDefinitionProviderTest.java`
- Modify: `runtime/src/test/java/io/casehub/ras/runtime/SituationDefinitionRegistryTest.java`

**Interfaces:**
- Consumes: `GanglionDescriptor.evidenceTemplates()`, `CloudEventExpressionContext.build()`,
  `DetectionResult`, `Ganglion` SPI, `CompiledExpression<Map, Object>`
- Produces: `EvidenceExtractingGanglion` wrapper class, `parseExpressionEntry()` shared method,
  `parseEvidenceTemplates()` method, registry evidence wrapping with key collision warning

- [ ] **Step 1: Write EvidenceExtractingGanglion tests**

Create `EvidenceExtractingGanglionTest.java`:
```java
// Tests using a MockGanglion delegate and mock CompiledExpressions:
// - mergesTemplateEvidenceWithDelegateEvidence
// - templateKeysOverwriteDelegateKeysOnClash
// - nullExpressionResultOmitsKey
// - perTemplateErrorIsolation (one fails, other succeeds)
// - expressionErrorMetricIncrementedOnFailure
// - delegatesGanglionId
// - delegatesHandledEventTypes
// - delegatesCompact
// - delegatesClose
```

Each test constructs an `EvidenceExtractingGanglion` wrapping a `MockGanglion`
(from testing/) with specific `CompiledExpression` lambdas, invokes `detect()`,
and asserts on the merged evidence map.

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn --batch-mode test -pl runtime/ -Dtest=EvidenceExtractingGanglionTest`
Expected: compilation failure — class does not exist

- [ ] **Step 3: Implement EvidenceExtractingGanglion**

Create `runtime/src/main/java/io/casehub/ras/runtime/EvidenceExtractingGanglion.java`:
- Implements `Ganglion`
- Constructor: `(Ganglion delegate, Map<String, CompiledExpression<Map, Object>> templates, MeterRegistry meterRegistry)`
- `detect()`: delegates to inner, `.map()` to enrich with `CloudEventExpressionContext.build(event)`
- `enrichEvidence()`: iterates templates, per-template try/catch, null-skip, metric on error
- All other methods delegate to `delegate`

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn --batch-mode test -pl runtime/ -Dtest=EvidenceExtractingGanglionTest`
Expected: all pass

- [ ] **Step 5: Refactor parseExpressionEntry as shared method**

In `YamlSituationDefinitionProvider`, extract shared method:
```java
private static ExpressionEvaluator parseExpressionEntry(Map<String, Object> exprMap, String context) {
    String expression = requireString(exprMap, "expression");
    String language = requireString(exprMap, "language");
    return switch (language) {
        case "jq" -> new JQExpressionEvaluator(expression);
        case "mvel" -> new MvelExpressionEvaluator(expression);
        default -> throw new IllegalArgumentException(
                "Unknown expression language '" + language + "' in " + context);
    };
}
```

Refactor existing callers to use it:
- `parseExpressionEvaluator()` — delegate to `parseExpressionEntry()`
- `parseNaiveBayesFeature()` — use `parseExpressionEntry()` for expression+language
- `parseDynamicCaseData()` — use `parseExpressionEntry()` for each entry

- [ ] **Step 6: Run existing YAML tests to verify refactoring is safe**

Run: `mvn --batch-mode test -pl runtime/ -Dtest=YamlSituationDefinitionProviderTest`
Expected: all existing tests pass (behavior-preserving refactoring)

- [ ] **Step 7: Add parseEvidenceTemplates and update NaiveBayes parsing**

Add to `YamlSituationDefinitionProvider`:
```java
private static Map<String, ExpressionEvaluator> parseEvidenceTemplates(Map<String, Object> map) {
    Map<String, Object> raw = (Map<String, Object>) map.get("evidenceTemplates");
    if (raw == null) { return Map.of(); }
    Map<String, ExpressionEvaluator> result = new LinkedHashMap<>();
    for (var entry : raw.entrySet()) {
        result.put(entry.getKey(), parseExpressionEntry(
                (Map<String, Object>) entry.getValue(),
                "evidenceTemplate '" + entry.getKey() + "'"));
    }
    return Map.copyOf(result);
}
```

Update `parseNaiveBayesGanglion()`: replace `Map.of()` 7th arg with `parseEvidenceTemplates(map)`.

- [ ] **Step 8: Write YAML evidence template parsing tests**

Add to `YamlSituationDefinitionProviderTest`:
```java
// - parsesEvidenceTemplatesOnNaiveBayes (YAML with evidenceTemplates → non-empty map)
// - evidenceTemplatesAbsentReturnsEmptyMap (no evidenceTemplates key → Map.of())
```

- [ ] **Step 9: Run YAML tests**

Run: `mvn --batch-mode test -pl runtime/ -Dtest=YamlSituationDefinitionProviderTest`
Expected: all pass

- [ ] **Step 10: Add registry evidence wrapping + key collision warning**

In `SituationDefinitionRegistry.constructGanglion()`:
1. Replace `instanceof` with `switch` on `GanglionDescriptor`
2. After inner ganglion construction, check `descriptor.evidenceTemplates()`
3. If non-empty: compile templates, warn on key collisions, wrap in `EvidenceExtractingGanglion`

For the `switch`, note it won't be exhaustive yet (ExpressionRules case added in Task 3).
Use `case NaiveBayes nb -> constructNaiveBayes(...)` and a `default -> throw` for now.

Add key collision warning logic:
```java
Set<String> autoKeys = switch (descriptor) {
    case GanglionDescriptor.NaiveBayes _ -> Set.of("posterior", "features");
    default -> Set.of();
};
```
(Updated in Task 3 to include ExpressionRules keys.)

- [ ] **Step 11: Write registry evidence wrapping tests**

Add to `SituationDefinitionRegistryTest`:
```java
// - evidenceTemplatesWrappedInEvidenceExtractingGanglion
//   Construct NaiveBayes descriptor with evidence templates, verify detect()
//   returns merged evidence (posterior + features + template keys)
// - evidenceTemplatesEmptyNoWrapping
//   Construct NaiveBayes descriptor without templates, verify no wrapper
```

- [ ] **Step 12: Run registry tests**

Run: `mvn --batch-mode test -pl runtime/ -Dtest=SituationDefinitionRegistryTest`
Expected: all pass

- [ ] **Step 13: Commit**

```
feat(#48): EvidenceExtractingGanglion, evidence templates pipeline
```

---

### Task 3: ExpressionRulesGanglion + Expression-Rules Pipeline (runtime/)

**Files:**
- Create: `runtime/src/main/java/io/casehub/ras/runtime/ExpressionRulesGanglion.java`
- Create: `runtime/src/test/java/io/casehub/ras/runtime/ExpressionRulesGanglionTest.java`
- Modify: `runtime/src/main/java/io/casehub/ras/runtime/YamlSituationDefinitionProvider.java`
- Modify: `runtime/src/main/java/io/casehub/ras/runtime/SituationDefinitionRegistry.java`
- Modify: `runtime/src/test/java/io/casehub/ras/runtime/YamlSituationDefinitionProviderTest.java`
- Modify: `runtime/src/test/java/io/casehub/ras/runtime/SituationDefinitionRegistryTest.java`

**Interfaces:**
- Consumes: `GanglionDescriptor.ExpressionRules` from Task 1,
  `CloudEventExpressionContext.build()`, `CompiledExpression<Map, Boolean>`,
  `parseExpressionEntry()` from Task 2
- Produces: `ExpressionRulesGanglion` class, `parseExpressionRulesGanglion()` method,
  `constructExpressionRules()` method, exhaustive switch in `constructGanglion()`

- [ ] **Step 1: Write ExpressionRulesGanglion tests**

Create `ExpressionRulesGanglionTest.java`:
```java
// Tests using CompiledExpression lambdas (no real expression engine needed):
// - firstMatchingRuleWins (two rules, first matches)
// - secondRuleMatchesWhenFirstDoesNot
// - otherwiseMatchesWhenNoRuleMatches
// - noRuleNoOtherwiseReturnsNoise
// - matchedRuleIndexInEvidence (correct index for each case)
// - matchedRuleIndexMinusOneForImplicitFallback
// - nullExpressionResultTreatedAsNoMatch
// - expressionExceptionSkipsRuleTrysNext
// - allFourSignalTypes (DETECTED, WEAK, NOISE, ANTI)
// - ganglionIdReturned
// - handledEventTypesReturned
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn --batch-mode test -pl runtime/ -Dtest=ExpressionRulesGanglionTest`
Expected: compilation failure — class does not exist

- [ ] **Step 3: Implement ExpressionRulesGanglion**

Create `runtime/src/main/java/io/casehub/ras/runtime/ExpressionRulesGanglion.java`:
- Implements `Ganglion`
- Constructor: `(String ganglionId, Set<String> handledEventTypes, List<CompiledRule> rules, MeterRegistry meterRegistry)`
- Nested `record CompiledRule(CompiledExpression<Map, Boolean> when, DetectionSignal signal, double confidence)`
- `detect()`: iterate rules, first match returns `DetectionResult` with `matchedRuleIndex`
- No `compact()` or `close()` overrides — uses defaults
- Per-rule try/catch with metric on error

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn --batch-mode test -pl runtime/ -Dtest=ExpressionRulesGanglionTest`
Expected: all pass

- [ ] **Step 5: Add YAML parsing for expression-rules**

Add `parseExpressionRulesGanglion()` to `YamlSituationDefinitionProvider`:
- Parse `ganglionId`, `handledEventTypes` (non-empty validation)
- Parse `rules` list (non-empty validation)
- For each rule: mutual exclusivity (`when` vs `otherwise`), `otherwise`-last validation
- `signal` via `DetectionSignal.valueOf(toUpperCase())` — startup error on invalid
- `confidence` via `Number.doubleValue()` — validated `0.0 ≤ x ≤ 1.0` at parse time
- `when` expression via `parseExpressionEntry()`
- Parse `evidenceTemplates` via `parseEvidenceTemplates()`
- Return `GanglionDescriptor.ExpressionRules`

Add `case "expression-rules" ->` to the switch in `parseGanglia()`.

- [ ] **Step 6: Write YAML expression-rules parsing tests**

Add to `YamlSituationDefinitionProviderTest`:
```java
// - parsesExpressionRulesGanglionFromYaml (valid rules + evidence templates)
// - expressionRulesEmptyRulesThrows
// - expressionRulesMissingRulesThrows
// - expressionRulesOtherwiseNotLastThrows
// - expressionRulesInvalidSignalThrows
// - expressionRulesConfidenceOutOfRangeThrows (1.5 and -0.1)
// - expressionRulesBothWhenAndOtherwiseThrows
// - expressionRulesNeitherWhenNorOtherwiseThrows
// - expressionRulesHandledEventTypesEmptyThrows
// - expressionRulesMissingSignalThrows
// - expressionRulesMissingConfidenceThrows
```

- [ ] **Step 7: Run YAML tests**

Run: `mvn --batch-mode test -pl runtime/ -Dtest=YamlSituationDefinitionProviderTest`
Expected: all pass

- [ ] **Step 8: Add registry constructExpressionRules + exhaustive switch**

In `SituationDefinitionRegistry`:
1. Add `constructExpressionRules()` method: compiles rule expressions with
   `Boolean.class`, builds `CompiledRule` list, constructs `ExpressionRulesGanglion`
2. Update `constructGanglion()` switch to be exhaustive:
   `case NaiveBayes nb -> ...` / `case ExpressionRules er -> ...`
3. Update key collision `autoKeys` to include ExpressionRules: `Set.of("matchedRuleIndex")`

- [ ] **Step 9: Write registry expression-rules tests**

Add to `SituationDefinitionRegistryTest`:
```java
// - expressionRulesDescriptorRegisteredAndFindable
// - expressionRulesWithEvidenceTemplatesMergesEvidence
// - expressionRulesAndNaiveBayesCoexist
// - duplicateGanglionIdAcrossExpressionRulesAndCdiThrows
```

These tests need `ExpressionEngineRegistry` with a mock or the real
`casehub-platform-expression` (already at test scope in runtime/).

- [ ] **Step 10: Run registry tests**

Run: `mvn --batch-mode test -pl runtime/ -Dtest=SituationDefinitionRegistryTest`
Expected: all pass

- [ ] **Step 11: Commit**

```
feat(#50): ExpressionRulesGanglion, expression-rules pipeline
```

---

### Task 4: Contract Tests + Integration Test + CLAUDE.md

**Files:**
- Create: `runtime/src/test/java/io/casehub/ras/runtime/ExpressionRulesGanglionContractTest.java`
- Create: `runtime/src/test/java/io/casehub/ras/runtime/EvidenceExtractingGanglionContractTest.java`
- Create: `runtime/src/test/resources/META-INF/expression-rules-integration-test.yaml`
  (or inline YAML in the test — match existing pattern)
- Modify: CLAUDE.md — update module documentation

**Interfaces:**
- Consumes: `AbstractGanglionContractTest` from api/,
  `ExpressionRulesGanglion` from Task 3,
  `EvidenceExtractingGanglion` from Task 2,
  full YAML → detection → trigger pipeline

- [ ] **Step 1: Write ExpressionRulesGanglionContractTest**

```java
class ExpressionRulesGanglionContractTest extends AbstractGanglionContractTest {

    @Override
    protected Ganglion createGanglion() {
        return new ExpressionRulesGanglion(
                "test-rules", Set.of("test.event"),
                List.of(new ExpressionRulesGanglion.CompiledRule(
                        ctx -> true, DetectionSignal.DETECTED, 0.8)),
                null);
    }

    @Override
    protected CloudEvent createTestEvent() {
        // Build a minimal CloudEvent with type "test.event"
    }
}
```

- [ ] **Step 2: Write EvidenceExtractingGanglionContractTest**

```java
class EvidenceExtractingGanglionContractTest extends AbstractGanglionContractTest {

    @Override
    protected Ganglion createGanglion() {
        Ganglion inner = new ExpressionRulesGanglion(
                "test-wrapped", Set.of("test.event"),
                List.of(new ExpressionRulesGanglion.CompiledRule(
                        ctx -> true, DetectionSignal.DETECTED, 0.8)),
                null);
        return new EvidenceExtractingGanglion(inner, Map.of(), null);
    }

    @Override
    protected CloudEvent createTestEvent() {
        // Build a minimal CloudEvent with type "test.event"
    }
}
```

- [ ] **Step 3: Run contract tests**

Run: `mvn --batch-mode test -pl runtime/ -Dtest="*ContractTest"`
Expected: all pass

- [ ] **Step 4: Write integration test**

End-to-end test using YAML with expression-rules ganglion + evidence templates +
situation → CloudEvent → rule evaluation → detection → threshold chain mode →
trigger → verify evidence in case input data.

Uses `casehub-platform-expression` (already at test scope) for real JQ
expression evaluation. Verifies the full pipeline from YAML definition
through to `DetectionResult` with both `matchedRuleIndex` and template evidence.

- [ ] **Step 5: Run integration test**

Run: `mvn --batch-mode test -pl runtime/ -Dtest=ExpressionRulesIntegrationTest`
Expected: pass

- [ ] **Step 6: Run full build**

Run: `mvn --batch-mode install`
Expected: all modules pass

- [ ] **Step 7: Update CLAUDE.md**

Update module documentation:
- `runtime/` description: add `ExpressionRulesGanglion`, `EvidenceExtractingGanglion`
- Core Types table: add `ExpressionRules` GanglionDescriptor variant
- YAML Ganglia section: document `type: expression-rules` and `evidenceTemplates`

- [ ] **Step 8: Commit**

```
feat(#48,#50): contract tests, integration test, CLAUDE.md update
```
