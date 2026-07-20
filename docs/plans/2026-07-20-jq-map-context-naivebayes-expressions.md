# JQ Map Context Cleanup + NaiveBayes Expression Feature Extraction — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> subagent-driven-development (recommended) or executing-plans to
> implement this plan task-by-task. Each task follows TDD
> (test-driven-development) and uses ide-tooling for structural
> editing. Steps use checkbox (`- [ ]`) syntax for tracking.

**Focal issue:** #49 — JQ engine native Map context support
**Issue group:** #49, #47

**Goal:** Remove the JqMapAdapter workaround from RAS (the platform now handles Map context natively), then add YAML-declared NaiveBayes ganglia with expression-based feature extraction.

**Architecture:** The platform's `JQExpressionEngine` now supports `Map` context via `MapAdaptedJQExpression`. RAS removes its local adapter and adds a thin `JqResultUnwrapper` for scalar result types. A new `GanglionDescriptor` sealed interface in `api/` enables any `SituationDefinitionProvider` to declare ganglia. The YAML provider parses a `ganglia:` section, and the registry constructs `NaiveBayesGanglion` instances with `ExpressionFeatureExtractor` during its three-phase startup.

**Tech Stack:** Java 21, Quarkus, Mutiny, jackson-jq, SnakeYAML, Micrometer, AssertJ

## Global Constraints

- `api/` has zero runtime dependencies beyond `casehub-platform-api` and `cloudevents-core`
- `runtime/` depends on `api/` — never the reverse
- `casehub-platform-expression` is test-scope only in `runtime/` — deployers add it to classpath
- All expression compilation goes through `ExpressionEngineRegistry` — no custom compilation
- `NaiveBayesConfig`, `NaiveBayesFeatureExtractor`, `FeatureLikelihood`, `NaiveBayesSignalMapping` are unchanged
- Jackson annotations for sealed types use `@JsonTypeInfo(property="type")` — match existing `ChainMode`/`TriggerAction` pattern
- IntelliJ MCP (`mcp__intellij-index__*`) for all code navigation and editing

---

### Task 1: GanglionDescriptor sealed interface in api/

**Files:**
- Create: `api/src/main/java/io/casehub/ras/api/GanglionDescriptor.java`
- Modify: `api/src/main/java/io/casehub/ras/api/SituationDefinitionProvider.java`
- Test: `api/src/test/java/io/casehub/ras/api/GanglionDescriptorTest.java`
- Test: `api/src/test/java/io/casehub/ras/api/SituationDefinitionProviderTest.java`

**Interfaces:**
- Consumes: `ExpressionEvaluator` from `casehub-platform-api`
- Produces:
  - `GanglionDescriptor` sealed interface with `String ganglionId()`, `Set<String> handledEventTypes()`
  - `GanglionDescriptor.NaiveBayes` record with fields: `ganglionId`, `handledEventTypes`, `outcomes` (List\<String\>), `priors` (double[]), `features` (Map\<String, Feature\>), `signalMapping` (SignalMapping)
  - `GanglionDescriptor.NaiveBayes.Feature` record with fields: `expression` (ExpressionEvaluator), `values` (List\<String\>), `likelihoods` (double[][])
  - `GanglionDescriptor.NaiveBayes.SignalMapping` record with fields: `targetOutcome` (String), `detectedThreshold` (double), `weakThreshold` (double), `antiThreshold` (Double, nullable)
  - `SituationDefinitionProvider.ganglionDescriptors()` default method returning `List.of()`

- [ ] **Step 1: Write test for GanglionDescriptor.NaiveBayes construction**

```java
package io.casehub.ras.api;

import io.casehub.platform.api.expression.JQExpressionEvaluator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class GanglionDescriptorTest {

    @Test
    void naiveBayesRecordCarriesAllFields() {
        var feature = new GanglionDescriptor.NaiveBayes.Feature(
                new JQExpressionEvaluator(".data.severity"),
                List.of("LOW", "HIGH"),
                new double[][]{{0.8, 0.2}, {0.3, 0.7}});

        var mapping = new GanglionDescriptor.NaiveBayes.SignalMapping(
                "ANOMALY", 0.75, 0.30, 0.05);

        var descriptor = new GanglionDescriptor.NaiveBayes(
                "bayes-1",
                Set.of("sensor.reading"),
                List.of("NORMAL", "ANOMALY"),
                new double[]{0.9, 0.1},
                Map.of("severity", feature),
                mapping);

        assertThat(descriptor.ganglionId()).isEqualTo("bayes-1");
        assertThat(descriptor.handledEventTypes()).containsExactly("sensor.reading");
        assertThat(descriptor.outcomes()).containsExactly("NORMAL", "ANOMALY");
        assertThat(descriptor.priors()).containsExactly(0.9, 0.1);
        assertThat(descriptor.features()).containsKey("severity");
        assertThat(descriptor.signalMapping().targetOutcome()).isEqualTo("ANOMALY");
        assertThat(descriptor.signalMapping().antiThreshold()).isEqualTo(0.05);
    }

    @Test
    void signalMappingWithNullAntiThreshold() {
        var mapping = new GanglionDescriptor.NaiveBayes.SignalMapping(
                "ANOMALY", 0.75, 0.30, null);

        assertThat(mapping.antiThreshold()).isNull();
    }

    @Test
    void sealedInterfacePermitsOnlyNaiveBayes() {
        assertThat(GanglionDescriptor.class.isSealed()).isTrue();
        assertThat(GanglionDescriptor.class.getPermittedSubclasses())
                .hasSize(1)
                .allSatisfy(c -> assertThat(c.getSimpleName()).isEqualTo("NaiveBayes"));
    }
}
```

- [ ] **Step 2: Write test for SituationDefinitionProvider default method**

```java
package io.casehub.ras.api;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SituationDefinitionProviderTest {

    @Test
    void ganglionDescriptorsDefaultReturnsEmptyList() {
        SituationDefinitionProvider provider = List::of;
        assertThat(provider.ganglionDescriptors()).isEmpty();
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `mvn --batch-mode test -pl api -Dtest="GanglionDescriptorTest,SituationDefinitionProviderTest" -DfailIfNoTests=false`
Expected: compilation failure — `GanglionDescriptor` does not exist

- [ ] **Step 4: Create GanglionDescriptor.java**

Use `ide_create_file` to create `api/src/main/java/io/casehub/ras/api/GanglionDescriptor.java`:

```java
package io.casehub.ras.api;

import io.casehub.platform.api.expression.ExpressionEvaluator;

import java.util.List;
import java.util.Map;
import java.util.Set;

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

- [ ] **Step 5: Add default method to SituationDefinitionProvider**

Use `ide_edit_member` on `SituationDefinitionProvider` to replace the interface declaration, adding the default method:

```java
public interface SituationDefinitionProvider {
    List<SituationRegistration> registrations();

    default List<GanglionDescriptor> ganglionDescriptors() { return List.of(); }
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `mvn --batch-mode test -pl api -Dtest="GanglionDescriptorTest,SituationDefinitionProviderTest"`
Expected: PASS

- [ ] **Step 7: Run full api/ module tests**

Run: `mvn --batch-mode test -pl api`
Expected: all tests pass (existing tests unaffected — default method returns empty list)

- [ ] **Step 8: Commit**

```bash
git add api/src/main/java/io/casehub/ras/api/GanglionDescriptor.java \
        api/src/main/java/io/casehub/ras/api/SituationDefinitionProvider.java \
        api/src/test/java/io/casehub/ras/api/GanglionDescriptorTest.java \
        api/src/test/java/io/casehub/ras/api/SituationDefinitionProviderTest.java
git commit -m "feat(casehub-ras#47): GanglionDescriptor sealed interface + SituationDefinitionProvider.ganglionDescriptors()"
```

---

### Task 2: JqResultUnwrapper + JqMapAdapter removal

**Files:**
- Create: `runtime/src/main/java/io/casehub/ras/runtime/JqResultUnwrapper.java`
- Modify: `runtime/src/main/java/io/casehub/ras/runtime/SituationDefinitionRegistry.java` (delete JqMapAdapter, modify compileExpression)
- Test: `runtime/src/test/java/io/casehub/ras/runtime/JqResultUnwrapperTest.java`

**Interfaces:**
- Consumes: `CompiledExpression<Map, ?>` from platform expression engine
- Produces: `JqResultUnwrapper<R>` implementing `CompiledExpression<Map, R>` — wraps a JQ expression, unwraps `List<JsonNode>` first element to target scalar type

- [ ] **Step 1: Write JqResultUnwrapper tests**

```java
package io.casehub.ras.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.casehub.platform.api.expression.CompiledExpression;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JqResultUnwrapperTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <R> JqResultUnwrapper<R> unwrapper(
            CompiledExpression<Map, ?> delegate, Class<R> resultType) {
        return new JqResultUnwrapper<>((CompiledExpression) delegate, resultType, MAPPER);
    }

    @Test
    void unwrapsStringFromSingleElementList() {
        CompiledExpression<Map, List<JsonNode>> delegate =
                ctx -> List.of(new TextNode("hello"));

        var wrapper = unwrapper(delegate, String.class);
        assertThat(wrapper.eval(Map.of())).isEqualTo("hello");
    }

    @Test
    void unwrapsNullNodeToNull() {
        CompiledExpression<Map, List<JsonNode>> delegate =
                ctx -> List.of(NullNode.getInstance());

        var wrapper = unwrapper(delegate, String.class);
        assertThat(wrapper.eval(Map.of())).isNull();
    }

    @Test
    void emptyListReturnsNull() {
        CompiledExpression<Map, List<JsonNode>> delegate =
                ctx -> List.of();

        var wrapper = unwrapper(delegate, String.class);
        assertThat(wrapper.eval(Map.of())).isNull();
    }

    @Test
    void unwrapsObjectViaConvertValue() {
        CompiledExpression<Map, List<JsonNode>> delegate =
                ctx -> List.of(MAPPER.valueToTree(Map.of("key", "val")));

        var wrapper = unwrapper(delegate, Object.class);
        Object result = wrapper.eval(Map.of());
        assertThat(result).isInstanceOf(Map.class);
    }

    @Test
    void typeReturnsJq() {
        CompiledExpression<Map, List<JsonNode>> delegate = ctx -> List.of();
        var wrapper = unwrapper(delegate, String.class);
        assertThat(wrapper.type()).isEqualTo("jq");
    }

    @Test
    void booleanResultPassesThroughWithoutUnwrapping() {
        CompiledExpression<Map, Boolean> delegate = ctx -> true;

        @SuppressWarnings({"unchecked", "rawtypes"})
        var wrapper = new JqResultUnwrapper<>((CompiledExpression) delegate, Boolean.class, MAPPER);
        assertThat(wrapper.eval(Map.of())).isEqualTo(true);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn --batch-mode test -pl runtime -Dtest="JqResultUnwrapperTest" -DfailIfNoTests=false`
Expected: compilation failure — `JqResultUnwrapper` does not exist

- [ ] **Step 3: Create JqResultUnwrapper.java**

Use `ide_create_file` to create `runtime/src/main/java/io/casehub/ras/runtime/JqResultUnwrapper.java`:

```java
package io.casehub.ras.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.platform.api.expression.CompiledExpression;

import java.util.List;
import java.util.Map;

@SuppressWarnings({"unchecked", "rawtypes"})
final class JqResultUnwrapper<R> implements CompiledExpression<Map, R> {

    private final CompiledExpression<Map, ?> delegate;
    private final Class<R>                  resultType;
    private final ObjectMapper              mapper;

    JqResultUnwrapper(CompiledExpression<Map, ?> delegate, Class<R> resultType, ObjectMapper mapper) {
        this.delegate   = delegate;
        this.resultType = resultType;
        this.mapper     = mapper;
    }

    @Override
    public String type() { return "jq"; }

    @Override
    public R eval(Map context) {
        Object result = delegate.eval(context);
        if (result instanceof Boolean) {
            return (R) result;
        }
        if (result instanceof List<?> list) {
            if (list.isEmpty()) { return null; }
            JsonNode first = (JsonNode) list.getFirst();
            if (first.isNull()) { return null; }
            if (resultType == String.class) {
                return (R) first.asText();
            }
            return mapper.convertValue(first, resultType);
        }
        return (R) result;
    }
}
```

- [ ] **Step 4: Run JqResultUnwrapper tests**

Run: `mvn --batch-mode test -pl runtime -Dtest="JqResultUnwrapperTest"`
Expected: PASS

- [ ] **Step 5: Delete JqMapAdapter and modify compileExpression()**

In `SituationDefinitionRegistry.java`:

1. Use `ide_refactor_safe_delete` on the `JqMapAdapter` inner class (line 223)
2. Use `ide_replace_member` on `compileExpression` to replace the method body. The new implementation removes the JQ special-case branch and adds JqResultUnwrapper for non-Boolean, non-List JQ results:

```java
    @SuppressWarnings("unchecked")
    private <C, R> CompiledExpression<C, R> compileExpression(
            ExpressionEvaluator evaluator, String situationId,
            Class<C> contextType, Class<R> resultType) {
        if (evaluator instanceof CompiledExpression<?, ?> compiled) {
            return (CompiledExpression<C, R>) compiled;
        }
        if (evaluator instanceof StringExpressionEvaluator stringEval) {
            if (expressionRegistry == null || expressionRegistry.resolve(stringEval.type()).isEmpty()) {
                throw new IllegalStateException(
                        "Situation '" + situationId + "' uses expression type '"
                        + stringEval.type() + "' but no ExpressionEngine is registered for it"
                        + " — add casehub-platform-expression to the classpath");
            }
            CompiledExpression<C, R> compiled = expressionRegistry.compile(
                    stringEval.type(), stringEval.expression(), contextType, resultType);
            if ("jq".equals(stringEval.type())
                && resultType != Boolean.class
                && resultType != List.class) {
                return (CompiledExpression<C, R>) new JqResultUnwrapper<>(
                        (CompiledExpression<Map, ?>) compiled, resultType,
                        new com.fasterxml.jackson.databind.ObjectMapper());
            }
            return compiled;
        }
        throw new IllegalStateException(
                "Unknown ExpressionEvaluator type: " + evaluator.getClass().getName());
    }
```

Also add the import for `JqResultUnwrapper` — it's in the same package so no import needed. Remove the now-unused `com.fasterxml.jackson.databind.JsonNode` import if `JqMapAdapter` was the only user.

- [ ] **Step 6: Run existing registry and expression tests**

Run: `mvn --batch-mode test -pl runtime -Dtest="SituationDefinitionRegistryTest,JqResultUnwrapperTest"`
Expected: PASS (existing expression tests exercise the same compilation paths)

- [ ] **Step 7: Run full runtime/ test suite**

Run: `mvn --batch-mode test -pl runtime`
Expected: all tests pass

- [ ] **Step 8: Commit**

```bash
git add runtime/src/main/java/io/casehub/ras/runtime/JqResultUnwrapper.java \
        runtime/src/main/java/io/casehub/ras/runtime/SituationDefinitionRegistry.java \
        runtime/src/test/java/io/casehub/ras/runtime/JqResultUnwrapperTest.java
git commit -m "feat(casehub-ras#49): JqResultUnwrapper + delete JqMapAdapter — platform handles Map context natively"
```

---

### Task 3: ExpressionFeatureExtractor

**Files:**
- Create: `runtime/src/main/java/io/casehub/ras/runtime/ExpressionFeatureExtractor.java`
- Test: `runtime/src/test/java/io/casehub/ras/runtime/ExpressionFeatureExtractorTest.java`

**Interfaces:**
- Consumes: `NaiveBayesFeatureExtractor` (api/), `CompiledExpression<Map, String>` (platform), `CloudEventExpressionContext` (runtime/)
- Produces: `ExpressionFeatureExtractor` implementing `NaiveBayesFeatureExtractor` — wraps per-feature compiled expressions, evaluates each against `CloudEventExpressionContext`, skips nulls, catches errors per-feature with metrics

- [ ] **Step 1: Write ExpressionFeatureExtractor tests**

```java
package io.casehub.ras.runtime;

import io.casehub.platform.api.expression.CompiledExpression;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExpressionFeatureExtractorTest {

    private static CloudEvent testEvent() {
        return CloudEventBuilder.v1()
                .withId("evt-1")
                .withSource(URI.create("/test"))
                .withType("sensor.reading")
                .withSubject("sensor-42")
                .build();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Map<String, CompiledExpression<Map, String>> expressions(
            Map<String, CompiledExpression<?, String>> raw) {
        Map<String, CompiledExpression<Map, String>> result = new LinkedHashMap<>();
        for (var e : raw.entrySet()) {
            result.put(e.getKey(), (CompiledExpression<Map, String>) (CompiledExpression) e.getValue());
        }
        return result;
    }

    @Test
    void extractsMultipleFeaturesFromEvent() {
        var exprs = expressions(Map.of(
                "type", (CompiledExpression<Map<String, Object>, String>) ctx -> (String) ctx.get("type"),
                "subject", (CompiledExpression<Map<String, Object>, String>) ctx -> (String) ctx.get("subject")));

        var extractor = new ExpressionFeatureExtractor("g1", exprs, null);

        Map<String, String> result = extractor.extract(testEvent());
        assertThat(result).containsEntry("type", "sensor.reading");
        assertThat(result).containsEntry("subject", "sensor-42");
    }

    @Test
    void nullResultOmitsFeature() {
        var exprs = expressions(Map.of(
                "missing", (CompiledExpression<Map<String, Object>, String>) ctx -> null));

        var extractor = new ExpressionFeatureExtractor("g1", exprs, null);

        Map<String, String> result = extractor.extract(testEvent());
        assertThat(result).isEmpty();
    }

    @Test
    void expressionErrorSkipsFeatureAndIncrementsMetric() {
        MeterRegistry registry = new SimpleMeterRegistry();

        var exprs = expressions(Map.of(
                "failing", (CompiledExpression<Map<String, Object>, String>) ctx -> {
                    throw new RuntimeException("boom");
                },
                "ok", (CompiledExpression<Map<String, Object>, String>) ctx -> "value"));

        var extractor = new ExpressionFeatureExtractor("g1", exprs, registry);

        Map<String, String> result = extractor.extract(testEvent());
        assertThat(result).containsEntry("ok", "value");
        assertThat(result).doesNotContainKey("failing");

        double errorCount = registry.counter("ras.expression.error",
                "ganglion_id", "g1",
                "feature_name", "failing",
                "expression_point", "feature_extraction").count();
        assertThat(errorCount).isEqualTo(1.0);
    }

    @Test
    void noMetricIncrementedWhenRegistryNull() {
        var exprs = expressions(Map.of(
                "failing", (CompiledExpression<Map<String, Object>, String>) ctx -> {
                    throw new RuntimeException("boom");
                }));

        var extractor = new ExpressionFeatureExtractor("g1", exprs, null);

        Map<String, String> result = extractor.extract(testEvent());
        assertThat(result).isEmpty();
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn --batch-mode test -pl runtime -Dtest="ExpressionFeatureExtractorTest" -DfailIfNoTests=false`
Expected: compilation failure — `ExpressionFeatureExtractor` does not exist

- [ ] **Step 3: Create ExpressionFeatureExtractor.java**

Use `ide_create_file` to create `runtime/src/main/java/io/casehub/ras/runtime/ExpressionFeatureExtractor.java`:

```java
package io.casehub.ras.runtime;

import io.casehub.platform.api.expression.CompiledExpression;
import io.cloudevents.CloudEvent;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

@SuppressWarnings({"unchecked", "rawtypes"})
final class ExpressionFeatureExtractor implements NaiveBayesFeatureExtractor {

    private static final Logger LOG = Logger.getLogger(ExpressionFeatureExtractor.class.getName());

    private final String                                        ganglionId;
    private final Map<String, CompiledExpression<Map, String>>  featureExpressions;
    private final MeterRegistry                                 meterRegistry;

    ExpressionFeatureExtractor(String ganglionId,
                               Map<String, CompiledExpression<Map, String>> featureExpressions,
                               MeterRegistry meterRegistry) {
        this.ganglionId         = ganglionId;
        this.featureExpressions = Map.copyOf(featureExpressions);
        this.meterRegistry      = meterRegistry;
    }

    @Override
    public Map<String, String> extract(CloudEvent event) {
        Map<String, Object> ctx    = CloudEventExpressionContext.build(event);
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

- [ ] **Step 4: Run tests**

Run: `mvn --batch-mode test -pl runtime -Dtest="ExpressionFeatureExtractorTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add runtime/src/main/java/io/casehub/ras/runtime/ExpressionFeatureExtractor.java \
        runtime/src/test/java/io/casehub/ras/runtime/ExpressionFeatureExtractorTest.java
git commit -m "feat(casehub-ras#47): ExpressionFeatureExtractor — per-feature expressions with error isolation and metrics"
```

---

### Task 4: YAML ganglia parsing in YamlSituationDefinitionProvider

**Files:**
- Modify: `runtime/src/main/java/io/casehub/ras/runtime/YamlSituationDefinitionProvider.java`
- Modify: `runtime/src/test/java/io/casehub/ras/runtime/YamlSituationDefinitionProviderTest.java`
- Create: `runtime/src/test/resources/META-INF/ras-situations-ganglia.yaml` (test fixture)

**Interfaces:**
- Consumes: `GanglionDescriptor.NaiveBayes`, `GanglionDescriptor.NaiveBayes.Feature`, `GanglionDescriptor.NaiveBayes.SignalMapping` from api/
- Produces: `YamlSituationDefinitionProvider.ganglionDescriptors()` returning `List<GanglionDescriptor>` parsed from the `ganglia:` YAML section

- [ ] **Step 1: Create test YAML fixture**

Write file `runtime/src/test/resources/META-INF/ras-situations-ganglia.yaml`:

```yaml
ganglia:
  - ganglionId: yaml-bayes
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
    signalMapping:
      targetOutcome: ANOMALY
      detectedThreshold: 0.75
      weakThreshold: 0.30
      antiThreshold: 0.05

situations:
  - situationId: yaml-sit
    eventTypes: [sensor.reading]
    correlationWindow: PT1H
    chainMode:
      type: or
      ganglia: [yaml-bayes]
    triggerAction:
      type: create-case
      caseNamespace: test
      caseName: test-case
      caseVersion: "1"
```

- [ ] **Step 2: Write YAML ganglia parsing tests**

Add to `YamlSituationDefinitionProviderTest.java`:

```java
@Test
void parsesNaiveBayesGanglionFromYaml() {
    var provider = new YamlSituationDefinitionProvider(
            loadYaml("META-INF/ras-situations-ganglia.yaml"));

    List<GanglionDescriptor> descriptors = provider.ganglionDescriptors();
    assertThat(descriptors).hasSize(1);

    var bayes = (GanglionDescriptor.NaiveBayes) descriptors.getFirst();
    assertThat(bayes.ganglionId()).isEqualTo("yaml-bayes");
    assertThat(bayes.handledEventTypes()).containsExactly("sensor.reading");
    assertThat(bayes.outcomes()).containsExactly("NORMAL", "ANOMALY");
    assertThat(bayes.priors()).containsExactly(0.9, 0.1);
    assertThat(bayes.features()).containsKey("severity");

    var feature = bayes.features().get("severity");
    assertThat(feature.expression()).isInstanceOf(JQExpressionEvaluator.class);
    assertThat(feature.values()).containsExactly("LOW", "MEDIUM", "HIGH");
    assertThat(feature.likelihoods()).hasNumberOfRows(2);
    assertThat(feature.likelihoods()[0]).containsExactly(0.7, 0.25, 0.05);

    assertThat(bayes.signalMapping().targetOutcome()).isEqualTo("ANOMALY");
    assertThat(bayes.signalMapping().detectedThreshold()).isEqualTo(0.75);
    assertThat(bayes.signalMapping().antiThreshold()).isEqualTo(0.05);
}

@Test
void parsesIntegerLikelihoodsAsDoubles() {
    String yaml = """
            ganglia:
              - ganglionId: int-test
                type: naive-bayes
                handledEventTypes: [test.event]
                outcomes: [A, B]
                priors: [1, 0]
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
            """;
    var provider = new YamlSituationDefinitionProvider(
            new java.io.ByteArrayInputStream(yaml.getBytes()));

    var bayes = (GanglionDescriptor.NaiveBayes) provider.ganglionDescriptors().getFirst();
    assertThat(bayes.priors()[0]).isEqualTo(1.0);
    assertThat(bayes.features().get("f1").likelihoods()[0][0]).isEqualTo(1.0);
}

@Test
void unknownGanglionTypeThrows() {
    String yaml = """
            ganglia:
              - ganglionId: bad
                type: unknown-type
                handledEventTypes: [test.event]
            """;
    assertThatIllegalArgumentException().isThrownBy(() ->
            new YamlSituationDefinitionProvider(
                    new java.io.ByteArrayInputStream(yaml.getBytes())))
            .withMessageContaining("Unknown ganglion type 'unknown-type'");
}

@Test
void missingGanglionIdThrows() {
    String yaml = """
            ganglia:
              - type: naive-bayes
                handledEventTypes: [test.event]
                outcomes: [A, B]
                priors: [0.5, 0.5]
                features: {}
                signalMapping:
                  targetOutcome: B
                  detectedThreshold: 0.75
                  weakThreshold: 0.30
            """;
    assertThatIllegalArgumentException().isThrownBy(() ->
            new YamlSituationDefinitionProvider(
                    new java.io.ByteArrayInputStream(yaml.getBytes())))
            .withMessageContaining("ganglionId");
}

@Test
void noGangliaSectionReturnsEmptyDescriptors() {
    String yaml = """
            situations:
              - situationId: sit-1
                eventTypes: [test.event]
                chainMode:
                  type: or
                  ganglia: [g1]
                triggerAction:
                  type: notify-only
            """;
    var provider = new YamlSituationDefinitionProvider(
            new java.io.ByteArrayInputStream(yaml.getBytes()));

    assertThat(provider.ganglionDescriptors()).isEmpty();
}
```

Ensure test imports include `GanglionDescriptor`, `JQExpressionEvaluator`, and `assertThatIllegalArgumentException`.

- [ ] **Step 3: Run tests to verify they fail**

Run: `mvn --batch-mode test -pl runtime -Dtest="YamlSituationDefinitionProviderTest#parsesNaiveBayesGanglionFromYaml+parsesIntegerLikelihoodsAsDoubles+unknownGanglionTypeThrows+missingGanglionIdThrows+noGangliaSectionReturnsEmptyDescriptors"`
Expected: FAIL — `ganglionDescriptors()` returns empty list (default method)

- [ ] **Step 4: Implement ganglia parsing in YamlSituationDefinitionProvider**

Add these fields and methods to `YamlSituationDefinitionProvider`:

1. Add field `private final List<GanglionDescriptor> ganglionDescriptors;`
2. Override `ganglionDescriptors()` to return the field
3. In `parse()`, extract `ganglia` from root map before `situations`, parse via `parseGanglia()`
4. Add `parseGanglia()`, `parseNaiveBayesGanglion()`, `parseNaiveBayesFeature()`, `parseSignalMapping()` methods
5. Use `Number.doubleValue()` for all numeric coercion

Implementation for `parseGanglia()`:

```java
@SuppressWarnings("unchecked")
private static List<GanglionDescriptor> parseGanglia(Map<String, Object> root) {
    List<Map<String, Object>> ganglia = (List<Map<String, Object>>) root.get("ganglia");
    if (ganglia == null) { return List.of(); }
    List<GanglionDescriptor> result = new ArrayList<>(ganglia.size());
    for (Map<String, Object> g : ganglia) {
        String type = requireString(g, "type");
        result.add(switch (type) {
            case "naive-bayes" -> parseNaiveBayesGanglion(g);
            default -> throw new IllegalArgumentException(
                    "Unknown ganglion type '" + type + "' for ganglion '"
                    + g.getOrDefault("ganglionId", "<missing>") + "'");
        });
    }
    return List.copyOf(result);
}
```

Implementation for `parseNaiveBayesGanglion()`:

```java
@SuppressWarnings("unchecked")
private static GanglionDescriptor.NaiveBayes parseNaiveBayesGanglion(Map<String, Object> map) {
    String ganglionId = requireString(map, "ganglionId");
    List<String> eventTypes = (List<String>) map.get("handledEventTypes");
    if (eventTypes == null || eventTypes.isEmpty()) {
        throw new IllegalArgumentException(
                "handledEventTypes must not be empty for ganglion '" + ganglionId + "'");
    }
    List<String> outcomes = (List<String>) map.get("outcomes");
    if (outcomes == null || outcomes.size() < 2) {
        throw new IllegalArgumentException(
                "outcomes must have at least 2 entries for ganglion '" + ganglionId + "'");
    }
    List<Number> priorsList = (List<Number>) map.get("priors");
    if (priorsList == null) {
        throw new IllegalArgumentException("Missing priors for ganglion '" + ganglionId + "'");
    }
    double[] priors = priorsList.stream().mapToDouble(Number::doubleValue).toArray();

    Map<String, Object> featuresMap = (Map<String, Object>) map.get("features");
    if (featuresMap == null) { featuresMap = Map.of(); }
    Map<String, GanglionDescriptor.NaiveBayes.Feature> features = new LinkedHashMap<>();
    for (var entry : featuresMap.entrySet()) {
        features.put(entry.getKey(),
                parseNaiveBayesFeature((Map<String, Object>) entry.getValue(), ganglionId, entry.getKey()));
    }

    Map<String, Object> sigMap = (Map<String, Object>) map.get("signalMapping");
    if (sigMap == null) {
        throw new IllegalArgumentException("Missing signalMapping for ganglion '" + ganglionId + "'");
    }

    return new GanglionDescriptor.NaiveBayes(
            ganglionId, new LinkedHashSet<>(eventTypes), outcomes, priors,
            features, parseSignalMapping(sigMap));
}
```

Implementation for `parseNaiveBayesFeature()`:

```java
@SuppressWarnings("unchecked")
private static GanglionDescriptor.NaiveBayes.Feature parseNaiveBayesFeature(
        Map<String, Object> map, String ganglionId, String featureName) {
    ExpressionEvaluator expression = parseExpressionEvaluator(map, "expression", "language");
    if (expression == null) {
        throw new IllegalArgumentException(
                "Feature '" + featureName + "' in ganglion '" + ganglionId
                + "' must have expression and language");
    }
    List<String> values = (List<String>) map.get("values");
    if (values == null || values.isEmpty()) {
        throw new IllegalArgumentException(
                "Feature '" + featureName + "' in ganglion '" + ganglionId
                + "' must have non-empty values");
    }
    List<List<Number>> likelihoodsList = (List<List<Number>>) map.get("likelihoods");
    if (likelihoodsList == null) {
        throw new IllegalArgumentException(
                "Feature '" + featureName + "' in ganglion '" + ganglionId
                + "' must have likelihoods");
    }
    double[][] likelihoods = likelihoodsList.stream()
            .map(row -> row.stream().mapToDouble(Number::doubleValue).toArray())
            .toArray(double[][]::new);

    return new GanglionDescriptor.NaiveBayes.Feature(expression, values, likelihoods);
}
```

Note: `parseExpressionEvaluator` needs a variant that reads `expression` and `language` as direct keys (not nested map). Add:

```java
private static ExpressionEvaluator parseExpressionEvaluator(
        Map<String, Object> map, String exprKey, String langKey) {
    Object exprValue = map.get(exprKey);
    Object langValue = map.get(langKey);
    if (exprValue == null || langValue == null) { return null; }
    String expression = exprValue.toString();
    String language = langValue.toString();
    return switch (language) {
        case "jq" -> new JQExpressionEvaluator(expression);
        case "mvel" -> new MvelExpressionEvaluator(expression);
        default -> throw new IllegalArgumentException(
                "Unknown expression language '" + language + "'. Expected 'jq' or 'mvel'");
    };
}
```

Implementation for `parseSignalMapping()`:

```java
private static GanglionDescriptor.NaiveBayes.SignalMapping parseSignalMapping(Map<String, Object> map) {
    String targetOutcome = requireString(map, "targetOutcome");
    double detected = ((Number) map.get("detectedThreshold")).doubleValue();
    double weak = ((Number) map.get("weakThreshold")).doubleValue();
    Double anti = map.containsKey("antiThreshold")
            ? ((Number) map.get("antiThreshold")).doubleValue()
            : null;
    return new GanglionDescriptor.NaiveBayes.SignalMapping(targetOutcome, detected, weak, anti);
}
```

- [ ] **Step 5: Run tests**

Run: `mvn --batch-mode test -pl runtime -Dtest="YamlSituationDefinitionProviderTest"`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add runtime/src/main/java/io/casehub/ras/runtime/YamlSituationDefinitionProvider.java \
        runtime/src/test/java/io/casehub/ras/runtime/YamlSituationDefinitionProviderTest.java \
        runtime/src/test/resources/META-INF/ras-situations-ganglia.yaml
git commit -m "feat(casehub-ras#47): YAML ganglia parsing — type: naive-bayes with per-feature expression extraction"
```

---

### Task 5: Registry three-phase constructor + descriptor ganglion construction

**Files:**
- Modify: `runtime/src/main/java/io/casehub/ras/runtime/SituationDefinitionRegistry.java`
- Modify: `runtime/src/test/java/io/casehub/ras/runtime/SituationDefinitionRegistryTest.java`

**Interfaces:**
- Consumes: `GanglionDescriptor.NaiveBayes` from api/, `ExpressionFeatureExtractor` from Task 3, `NaiveBayesGanglion`/`NaiveBayesConfig`/`FeatureLikelihood`/`NaiveBayesSignalMapping` from runtime/, `GanglionStateStore` from api/, `ExpressionEngineRegistry` from platform
- Produces: Three-phase constructor: descriptor ganglia → CDI ganglia → situation registrations. `constructGanglion()` method that compiles feature expressions and builds `NaiveBayesGanglion`.

- [ ] **Step 1: Write registry descriptor ganglion tests**

Add to `SituationDefinitionRegistryTest.java`:

```java
@Test
void descriptorGanglionRegisteredAndFindable() {
    var descriptor = new GanglionDescriptor.NaiveBayes(
            "yaml-g", Set.of("test.event"),
            List.of("NORMAL", "ANOMALY"), new double[]{0.9, 0.1},
            Map.of("f1", new GanglionDescriptor.NaiveBayes.Feature(
                    new JQExpressionEvaluator(".data.f"),
                    List.of("X", "Y"),
                    new double[][]{{0.8, 0.2}, {0.3, 0.7}})),
            new GanglionDescriptor.NaiveBayes.SignalMapping("ANOMALY", 0.75, 0.30, null));

    SituationDefinitionProvider provider = new SituationDefinitionProvider() {
        public List<SituationRegistration> registrations() { return List.of(); }
        public List<GanglionDescriptor> ganglionDescriptors() { return List.of(descriptor); }
    };

    var registry = new SituationDefinitionRegistry(
            List.of(provider), List.of(),
            new StubExpressionEngineRegistry(), new InMemoryGanglionStateStore(), null);

    assertThat(registry.ganglion("yaml-g")).isNotNull();
    assertThat(registry.ganglion("yaml-g")).isInstanceOf(NaiveBayesGanglion.class);
}

@Test
void duplicateGanglionIdBetweenDescriptorAndCdiThrows() {
    var descriptor = new GanglionDescriptor.NaiveBayes(
            "dup-g", Set.of("test.event"),
            List.of("A", "B"), new double[]{0.5, 0.5},
            Map.of("f1", new GanglionDescriptor.NaiveBayes.Feature(
                    new JQExpressionEvaluator(".data.f"),
                    List.of("X"), new double[][]{{0.6}, {0.4}})),
            new GanglionDescriptor.NaiveBayes.SignalMapping("B", 0.75, 0.30, null));

    SituationDefinitionProvider provider = new SituationDefinitionProvider() {
        public List<SituationRegistration> registrations() { return List.of(); }
        public List<GanglionDescriptor> ganglionDescriptors() { return List.of(descriptor); }
    };

    var cdiGanglion = ganglion("dup-g", "test.event");

    assertThatIllegalStateException().isThrownBy(() ->
            new SituationDefinitionRegistry(
                    List.of(provider), List.of(cdiGanglion),
                    new StubExpressionEngineRegistry(), new InMemoryGanglionStateStore(), null))
            .withMessageContaining("Duplicate ganglionId 'dup-g'")
            .withMessageContaining("YAML descriptor")
            .withMessageContaining("CDI");
}

@Test
void yamlSituationReferencingDescriptorGanglionValidates() {
    var descriptor = new GanglionDescriptor.NaiveBayes(
            "desc-g", Set.of("test.event"),
            List.of("A", "B"), new double[]{0.5, 0.5},
            Map.of("f1", new GanglionDescriptor.NaiveBayes.Feature(
                    new JQExpressionEvaluator(".data.f"),
                    List.of("X"), new double[][]{{0.6}, {0.4}})),
            new GanglionDescriptor.NaiveBayes.SignalMapping("B", 0.75, 0.30, null));

    var def = definition("sit-1", Set.of("test.event"), new ChainMode.Or(Set.of("desc-g")));

    SituationDefinitionProvider provider = new SituationDefinitionProvider() {
        public List<SituationRegistration> registrations() {
            return List.of(new SituationRegistration(def));
        }
        public List<GanglionDescriptor> ganglionDescriptors() { return List.of(descriptor); }
    };

    assertThatNoException().isThrownBy(() ->
            new SituationDefinitionRegistry(
                    List.of(provider), List.of(),
                    new StubExpressionEngineRegistry(), new InMemoryGanglionStateStore(), null));
}

@Test
void invalidDescriptorWrapsErrorWithGanglionContext() {
    var descriptor = new GanglionDescriptor.NaiveBayes(
            "bad-g", Set.of("test.event"),
            List.of("A", "B"), new double[]{0.7, 0.7},  // doesn't sum to 1
            Map.of(), new GanglionDescriptor.NaiveBayes.SignalMapping("B", 0.75, 0.30, null));

    SituationDefinitionProvider provider = new SituationDefinitionProvider() {
        public List<SituationRegistration> registrations() { return List.of(); }
        public List<GanglionDescriptor> ganglionDescriptors() { return List.of(descriptor); }
    };

    assertThatIllegalStateException().isThrownBy(() ->
            new SituationDefinitionRegistry(
                    List.of(provider), List.of(),
                    new StubExpressionEngineRegistry(), new InMemoryGanglionStateStore(), null))
            .withMessageContaining("bad-g")
            .withMessageContaining("priors must sum to 1.0");
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn --batch-mode test -pl runtime -Dtest="SituationDefinitionRegistryTest#descriptorGanglionRegisteredAndFindable+duplicateGanglionIdBetweenDescriptorAndCdiThrows+yamlSituationReferencingDescriptorGanglionValidates+invalidDescriptorWrapsErrorWithGanglionContext" -DfailIfNoTests=false`
Expected: compilation failure — constructor doesn't accept `GanglionStateStore` and `MeterRegistry` yet

- [ ] **Step 3: Implement three-phase constructor and constructGanglion()**

Modify `SituationDefinitionRegistry`:

1. Add field `private final GanglionStateStore stateStore;`
2. Add field `private final MeterRegistry meterRegistry;` (nullable)
3. Add new constructor with five parameters (providers, cdiGanglia, expressionRegistry, stateStore, meterRegistry)
4. Implement three-phase logic as specified in the design spec §5
5. Add `constructGanglion(GanglionDescriptor, GanglionStateStore, MeterRegistry)` method:

```java
@SuppressWarnings("unchecked")
private Ganglion constructGanglion(GanglionDescriptor descriptor,
                                   GanglionStateStore stateStore,
                                   MeterRegistry meterRegistry) {
    if (descriptor instanceof GanglionDescriptor.NaiveBayes nb) {
        Map<String, CompiledExpression<Map, String>> compiledFeatures = new LinkedHashMap<>();
        for (var entry : nb.features().entrySet()) {
            var feature = entry.getValue();
            CompiledExpression<Map, String> compiled = compileExpression(
                    feature.expression(), nb.ganglionId(), Map.class, String.class);
            compiledFeatures.put(entry.getKey(), compiled);
        }

        var featureExtractor = new ExpressionFeatureExtractor(
                nb.ganglionId(), compiledFeatures, meterRegistry);

        Map<String, FeatureLikelihood> features = new LinkedHashMap<>();
        for (var entry : nb.features().entrySet()) {
            features.put(entry.getKey(), new FeatureLikelihood(
                    entry.getValue().values(), entry.getValue().likelihoods()));
        }

        var signalMapping = new NaiveBayesSignalMapping(
                nb.signalMapping().targetOutcome(),
                nb.signalMapping().detectedThreshold(),
                nb.signalMapping().weakThreshold(),
                nb.signalMapping().antiThreshold());

        var config = new NaiveBayesConfig(
                nb.ganglionId(), nb.handledEventTypes(),
                nb.outcomes(), nb.priors(),
                features, featureExtractor, signalMapping);

        return new NaiveBayesGanglion(config, stateStore);
    }
    throw new IllegalStateException("Unsupported GanglionDescriptor type: "
            + descriptor.getClass().getName());
}
```

6. Update the CDI constructor to delegate to the new five-param constructor:

```java
@Inject
public SituationDefinitionRegistry(Instance<SituationDefinitionProvider> providers,
                                   Instance<Ganglion> ganglia,
                                   ExpressionEngineRegistry expressionRegistry,
                                   Instance<GanglionStateStore> stateStore,
                                   Instance<MeterRegistry> meterRegistryInstance) {
    this(toList(providers), toList(ganglia), expressionRegistry,
         stateStore.isResolvable() ? stateStore.get() : new InMemoryGanglionStateStore(),
         meterRegistryInstance != null && meterRegistryInstance.isResolvable()
                 ? meterRegistryInstance.get() : null);
}
```

7. Keep the existing two-arg test constructor `(providers, ganglia)` working — have it delegate with null stateStore/meterRegistry (for backward compat with existing tests that don't use descriptors).

- [ ] **Step 4: Run new tests**

Run: `mvn --batch-mode test -pl runtime -Dtest="SituationDefinitionRegistryTest#descriptorGanglionRegisteredAndFindable+duplicateGanglionIdBetweenDescriptorAndCdiThrows+yamlSituationReferencingDescriptorGanglionValidates+invalidDescriptorWrapsErrorWithGanglionContext"`
Expected: PASS

- [ ] **Step 5: Run all existing registry tests**

Run: `mvn --batch-mode test -pl runtime -Dtest="SituationDefinitionRegistryTest"`
Expected: PASS (existing tests use old constructors which still work)

- [ ] **Step 6: Run full runtime/ test suite**

Run: `mvn --batch-mode test -pl runtime`
Expected: all tests pass

- [ ] **Step 7: Commit**

```bash
git add runtime/src/main/java/io/casehub/ras/runtime/SituationDefinitionRegistry.java \
        runtime/src/test/java/io/casehub/ras/runtime/SituationDefinitionRegistryTest.java
git commit -m "feat(casehub-ras#47): three-phase registry constructor — descriptor ganglia, CDI ganglia, situation registrations"
```

---

### Task 6: End-to-end integration test

**Files:**
- Create: `runtime/src/test/resources/META-INF/ras-situations-e2e-naivebayes.yaml`
- Modify: `runtime/src/test/java/io/casehub/ras/runtime/YamlSituationDefinitionProviderTest.java` (or create a new integration test class)

**Interfaces:**
- Consumes: all prior tasks — the full pipeline from YAML → descriptor → registry → ganglion → detection → trigger

- [ ] **Step 1: Create e2e YAML fixture**

Write file `runtime/src/test/resources/META-INF/ras-situations-e2e-naivebayes.yaml`:

```yaml
ganglia:
  - ganglionId: e2e-bayes
    type: naive-bayes
    handledEventTypes: [test.e2e]
    outcomes: [NORMAL, ANOMALY]
    priors: [0.5, 0.5]
    features:
      severity:
        expression: ".data.severity"
        language: jq
        values: [LOW, HIGH]
        likelihoods:
          - [0.9, 0.1]
          - [0.1, 0.9]
    signalMapping:
      targetOutcome: ANOMALY
      detectedThreshold: 0.70
      weakThreshold: 0.30

situations:
  - situationId: e2e-situation
    eventTypes: [test.e2e]
    correlationWindow: PT1H
    chainMode:
      type: or
      ganglia: [e2e-bayes]
    triggerAction:
      type: notify-only
```

- [ ] **Step 2: Write end-to-end integration test**

```java
@Test
void endToEndYamlNaiveBayesGanglionDetectsAndTriggers() {
    // Parse YAML
    var provider = new YamlSituationDefinitionProvider(
            loadYaml("META-INF/ras-situations-e2e-naivebayes.yaml"));

    assertThat(provider.ganglionDescriptors()).hasSize(1);
    assertThat(provider.registrations()).hasSize(1);

    // Build registry with expression engine
    var jqEngine = new io.casehub.platform.expression.JQExpressionEngine();
    var engines = new io.casehub.platform.expression.DefaultExpressionEngineRegistry(
            List.of(jqEngine));
    var stateStore = new InMemoryGanglionStateStore();

    var registry = new SituationDefinitionRegistry(
            List.of(provider), List.of(), engines, stateStore, null);

    // Verify ganglion constructed
    assertThat(registry.ganglion("e2e-bayes")).isInstanceOf(NaiveBayesGanglion.class);

    // Verify situation routes
    assertThat(registry.findByEventType("test.e2e")).hasSize(1);
    assertThat(registry.findByEventType("test.e2e").getFirst()
            .definition().situationId()).isEqualTo("e2e-situation");

    // Send CloudEvent with HIGH severity — should shift toward ANOMALY
    var event = CloudEventBuilder.v1()
            .withId("e2e-1")
            .withSource(URI.create("/test"))
            .withType("test.e2e")
            .withSubject("device-1")
            .withData("application/json", "{\"severity\":\"HIGH\"}".getBytes())
            .build();

    var ganglion = (NaiveBayesGanglion) registry.ganglion("e2e-bayes");
    var ctx = SituationContext.initial("e2e-situation", "device-1", "tenant-1",
            Instant.parse("2026-07-20T10:00:00Z"));

    DetectionResult result = ganglion.detect(event, ctx).await().indefinitely();

    assertThat(result.ganglionId()).isEqualTo("e2e-bayes");
    double posterior = (double) result.evidence().get("posterior");
    assertThat(posterior).isGreaterThan(0.5);
    assertThat(result.signal().isAtLeast(DetectionSignal.WEAK)).isTrue();

    @SuppressWarnings("unchecked")
    Map<String, String> features = (Map<String, String>) result.evidence().get("features");
    assertThat(features).containsEntry("severity", "HIGH");
}
```

Add necessary imports: `CloudEventBuilder`, `URI`, `Instant`, `DetectionResult`, `DetectionSignal`, `SituationContext`, the platform expression classes.

- [ ] **Step 3: Run e2e test**

Run: `mvn --batch-mode test -pl runtime -Dtest="YamlSituationDefinitionProviderTest#endToEndYamlNaiveBayesGanglionDetectsAndTriggers"`
Expected: PASS

- [ ] **Step 4: Run full project build**

Run: `mvn --batch-mode install`
Expected: BUILD SUCCESS — all modules pass

- [ ] **Step 5: Commit**

```bash
git add runtime/src/test/resources/META-INF/ras-situations-e2e-naivebayes.yaml \
        runtime/src/test/java/io/casehub/ras/runtime/YamlSituationDefinitionProviderTest.java
git commit -m "test(casehub-ras#47): end-to-end YAML NaiveBayes ganglion — parse, register, detect via JQ expressions"
```

---

### Task 7: CLAUDE.md + issue #49 cleanup

**Files:**
- Modify: `CLAUDE.md`

**Interfaces:** none — documentation only

- [ ] **Step 1: Update CLAUDE.md**

Add `GanglionDescriptor` and `ExpressionFeatureExtractor` to the relevant module tables. Update the `YamlSituationDefinitionProvider` description to mention ganglia parsing. Update `SituationDefinitionRegistry` description to mention three-phase constructor, descriptor ganglion construction, and `GanglionStateStore`/`MeterRegistry` injection.

Key additions:
- `GanglionDescriptor` in api/ Core Types table
- `ExpressionFeatureExtractor` in runtime/ module description
- `JqResultUnwrapper` in runtime/ module description
- `ganglia:` section documented in YAML Situation Definitions section
- `SituationDefinitionProvider.ganglionDescriptors()` in Core SPIs

- [ ] **Step 2: Commit**

```bash
git add CLAUDE.md
git commit -m "docs(casehub-ras#47): CLAUDE.md — GanglionDescriptor, YAML ganglia, ExpressionFeatureExtractor, three-phase registry"
```
