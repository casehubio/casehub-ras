# ExpressionEvaluator Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use executing-plans to
> implement this plan task-by-task. Each task follows TDD
> (test-driven-development) and uses ide-tooling for structural editing.
> Steps use checkbox (`- [ ]`) syntax for tracking.

**Focal issue:** #46 — ExpressionEvaluator integration — pluggable expressions in YAML situation definitions
**Issue group:** #46

**Goal:** Wire the platform's ExpressionEvaluator API into the RAS YAML situation
definition path, enabling expression-based correlation key extraction, event filtering,
and dynamic case data without Java code.

**Architecture:** Expression descriptors (`ExpressionEvaluator` instances) live on
`SituationDefinition`. `SituationDefinitionRegistry` compiles them at registration
time via `ExpressionEngineRegistry`, storing compiled results on `SituationRegistration`.
`RasEngine` evaluates filters before routing; `DefaultCaseTrigger` resolves dynamic data
at trigger time. Context builders bridge CloudEvent/SituationContext to expression engine
context maps.

**Tech Stack:** Java 21, Quarkus CDI, casehub-platform-api (ExpressionEvaluator,
ExpressionEngineRegistry, CompiledExpression), casehub-platform-expression (test scope),
Jackson ObjectMapper, SnakeYAML, Micrometer.

**Spec:** `docs/superpowers/specs/2026-07-17-expression-evaluator-integration-design.md`

## Global Constraints

- Pre-release: breaking changes are free. Fix the design, never protect callers.
- RAS `runtime/` does NOT add a compile dependency on `casehub-platform-expression`.
  Expression engines are opt-in (deployer classpath). RAS tests use it at test scope.
- `NoOpExpressionEngineRegistry` (`@DefaultBean` in `casehub-platform`) is always
  satisfied — `Instance.isUnsatisfied()` is never true. Use `registry.resolve(type).isEmpty()`
  for fail-fast.
- MVEL3 gotchas: no single-quoted strings (GE-20260715-01a695), no `.contains()` on
  strings (GE-20260714-550161).
- All expression evaluation errors are non-fatal with degraded behavior + metric.
- IntelliJ MCP is mandatory for all code navigation and editing.

---

### Task 1: StringExpressionEvaluator in platform-api (cross-repo)

**Repo:** `casehub-platform` (not casehub-ras)

**Files:**
- Create: `platform-api/src/main/java/io/casehub/platform/api/expression/StringExpressionEvaluator.java`
- Modify: `platform-api/src/main/java/io/casehub/platform/api/expression/JQExpressionEvaluator.java`
- Modify: `platform-api/src/main/java/io/casehub/platform/api/expression/MvelExpressionEvaluator.java`
- Test: `platform-api/src/test/java/io/casehub/platform/api/expression/StringExpressionEvaluatorTest.java`

**Interfaces:**
- Produces: `StringExpressionEvaluator extends ExpressionEvaluator` with `String expression()`.
  `JQExpressionEvaluator` and `MvelExpressionEvaluator` implement it. Used by Task 4's
  `compileExpression()` via `instanceof StringExpressionEvaluator`.

- [ ] **Step 1: Write the failing test**

```java
package io.casehub.platform.api.expression;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class StringExpressionEvaluatorTest {

    @Test
    void jqExpressionEvaluatorIsStringExpressionEvaluator() {
        ExpressionEvaluator eval = new JQExpressionEvaluator(".data.orderId");
        assertThat(eval).isInstanceOf(StringExpressionEvaluator.class);
        assertThat(((StringExpressionEvaluator) eval).expression()).isEqualTo(".data.orderId");
    }

    @Test
    void mvelExpressionEvaluatorIsStringExpressionEvaluator() {
        ExpressionEvaluator eval = new MvelExpressionEvaluator("data.severity >= 3");
        assertThat(eval).isInstanceOf(StringExpressionEvaluator.class);
        assertThat(((StringExpressionEvaluator) eval).expression()).isEqualTo("data.severity >= 3");
    }

    @Test
    void lambdaExpressionIsNotStringExpressionEvaluator() {
        ExpressionEvaluator eval = new LambdaExpression<>(x -> x);
        assertThat(eval).isNotInstanceOf(StringExpressionEvaluator.class);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl platform-api test -Dtest=StringExpressionEvaluatorTest -f /Users/mdproctor/claude/casehub/platform/pom.xml --batch-mode`
Expected: FAIL — `StringExpressionEvaluator` does not exist

- [ ] **Step 3: Create StringExpressionEvaluator interface**

Use `ide_create_file`:

```java
package io.casehub.platform.api.expression;

public interface StringExpressionEvaluator extends ExpressionEvaluator {
    String expression();
}
```

- [ ] **Step 4: Update JQExpressionEvaluator implements clause**

Use `ide_edit_member` on `JQExpressionEvaluator`:

```java
public record JQExpressionEvaluator(String expression) implements StringExpressionEvaluator {

    public JQExpressionEvaluator {
        Objects.requireNonNull(expression, "expression");
    }

    @Override
    public String type() { return "jq"; }
}
```

- [ ] **Step 5: Update MvelExpressionEvaluator implements clause**

Use `ide_edit_member` on `MvelExpressionEvaluator`:

```java
public record MvelExpressionEvaluator(String expression) implements StringExpressionEvaluator {

    public MvelExpressionEvaluator {
        Objects.requireNonNull(expression, "expression");
    }

    @Override
    public String type() { return "mvel"; }
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `mvn -pl platform-api test -Dtest=StringExpressionEvaluatorTest -f /Users/mdproctor/claude/casehub/platform/pom.xml --batch-mode`
Expected: PASS

- [ ] **Step 7: Run full platform-api tests**

Run: `mvn -pl platform-api test -f /Users/mdproctor/claude/casehub/platform/pom.xml --batch-mode`
Expected: PASS — no regressions

- [ ] **Step 8: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/platform add platform-api/src/main/java/io/casehub/platform/api/expression/StringExpressionEvaluator.java platform-api/src/main/java/io/casehub/platform/api/expression/JQExpressionEvaluator.java platform-api/src/main/java/io/casehub/platform/api/expression/MvelExpressionEvaluator.java platform-api/src/test/java/io/casehub/platform/api/expression/StringExpressionEvaluatorTest.java
git -C /Users/mdproctor/claude/casehub/platform commit -m "feat(casehub-ras#46): StringExpressionEvaluator — sub-interface for string-based expression evaluators"
```

- [ ] **Step 9: Install platform-api locally**

Run: `mvn -pl platform-api install -DskipTests -f /Users/mdproctor/claude/casehub/platform/pom.xml --batch-mode`
Expected: BUILD SUCCESS — makes the new interface available to RAS modules

---

### Task 2: API type changes — SituationDefinition, EventFilter, SituationRegistration

**Files:**
- Modify: `api/src/main/java/io/casehub/ras/api/SituationDefinition.java`
- Create: `api/src/main/java/io/casehub/ras/api/EventFilter.java`
- Modify: `api/src/main/java/io/casehub/ras/api/SituationRegistration.java`
- Test: `api/src/test/java/io/casehub/ras/api/SituationDefinitionTest.java` (add tests)
- Test: `api/src/test/java/io/casehub/ras/api/SituationRegistrationTest.java` (add tests)

**Interfaces:**
- Consumes: `ExpressionEvaluator` from `casehub-platform-api`, `CompiledExpression` from `casehub-platform-api`
- Produces:
  - `SituationDefinition` — 10-arg canonical constructor + 7-arg convenience constructor
  - `EventFilter` — `boolean accepts(CloudEvent event)`
  - `SituationRegistration` — 4-arg canonical constructor + existing 1-arg and 2-arg constructors

- [ ] **Step 1: Write failing tests for SituationDefinition new fields**

Add to `SituationDefinitionTest.java`:

```java
@Test
void expressionFieldsDefaultToNull() {
    var def = new SituationDefinition("sit-1", Set.of("e"),
            Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")),
            new TriggerAction.CreateCase(new CaseTriggerConfig("ns", "c", "1", Map.of())),
            null);
    assertThat(def.correlationKeyExpression()).isNull();
    assertThat(def.eventFilter()).isNull();
    assertThat(def.dynamicCaseData()).isEmpty();
}

@Test
void fullConstructorWithExpressions() {
    var corrExpr = new JQExpressionEvaluator(".data.orderId");
    var filterExpr = new MvelExpressionEvaluator("data.severity >= 3");
    var dynamicData = Map.<String, ExpressionEvaluator>of(
            "orderId", new JQExpressionEvaluator(".correlationKey"));
    var def = new SituationDefinition("sit-1", Set.of("e"),
            Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")),
            new TriggerAction.CreateCase(new CaseTriggerConfig("ns", "c", "1", Map.of())),
            null, corrExpr, filterExpr, dynamicData);
    assertThat(def.correlationKeyExpression()).isEqualTo(corrExpr);
    assertThat(def.eventFilter()).isEqualTo(filterExpr);
    assertThat(def.dynamicCaseData()).containsKey("orderId");
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl api test -Dtest=SituationDefinitionTest --batch-mode`
Expected: FAIL — no 10-arg constructor, no `correlationKeyExpression()` accessor

- [ ] **Step 3: Update SituationDefinition record**

Use `ide_edit_member` to replace the `SituationDefinition` class declaration:

```java
public record SituationDefinition(
        String situationId,
        Set<String> eventTypes,
        Duration correlationWindow,
        Duration eventBufferDelay,
        ChainMode chainMode,
        TriggerAction triggerAction,
        TriggerMode triggerMode,
        ExpressionEvaluator correlationKeyExpression,
        ExpressionEvaluator eventFilter,
        Map<String, ExpressionEvaluator> dynamicCaseData
) {
    public SituationDefinition {
        Objects.requireNonNull(situationId, "situationId");
        Objects.requireNonNull(chainMode, "chainMode");
        Objects.requireNonNull(triggerAction, "triggerAction");
        if (eventTypes == null || eventTypes.isEmpty()) {
            throw new IllegalArgumentException("eventTypes must not be empty");
        }
        eventTypes = Set.copyOf(eventTypes);
        if (correlationWindow != null
                && (correlationWindow.isZero() || correlationWindow.isNegative())) {
            throw new IllegalArgumentException(
                    "correlationWindow must be positive when set, got: " + correlationWindow);
        }
        if (eventBufferDelay != null
                && (eventBufferDelay.isZero() || eventBufferDelay.isNegative())) {
            throw new IllegalArgumentException(
                    "eventBufferDelay must be positive when set, got: " + eventBufferDelay);
        }
        triggerMode = triggerMode != null ? triggerMode : new TriggerMode.FireOnce();
        dynamicCaseData = dynamicCaseData != null ? Map.copyOf(dynamicCaseData) : Map.of();
    }

    public SituationDefinition(String situationId, Set<String> eventTypes,
            Duration correlationWindow, Duration eventBufferDelay,
            ChainMode chainMode, TriggerAction triggerAction, TriggerMode triggerMode) {
        this(situationId, eventTypes, correlationWindow, eventBufferDelay,
                chainMode, triggerAction, triggerMode, null, null, Map.of());
    }
}
```

Add imports: `io.casehub.platform.api.expression.ExpressionEvaluator`, `java.util.Map`.

- [ ] **Step 4: Create EventFilter interface**

Use `ide_create_file`:

```java
package io.casehub.ras.api;

import io.cloudevents.CloudEvent;

@FunctionalInterface
public interface EventFilter {
    boolean accepts(CloudEvent event);
}
```

- [ ] **Step 5: Write failing test for SituationRegistration new fields**

Add to `SituationRegistrationTest.java`:

```java
@Test
void fourArgConstructorSetsFilterAndDynamicData() {
    EventFilter filter = event -> true;
    Map<String, CompiledExpression<Map, Object>> dynamicData = Map.of();
    var reg = new SituationRegistration(DEF, DefaultCorrelationKeyExtractor.INSTANCE,
            filter, dynamicData);
    assertThat(reg.eventFilter()).isSameAs(filter);
    assertThat(reg.compiledDynamicData()).isSameAs(dynamicData);
}

@Test
void twoArgConstructorDefaultsFilterAndDynamicDataToNull() {
    var reg = new SituationRegistration(DEF, DefaultCorrelationKeyExtractor.INSTANCE);
    assertThat(reg.eventFilter()).isNull();
    assertThat(reg.compiledDynamicData()).isNull();
}

@Test
void singleArgConstructorDefaultsAll() {
    var reg = new SituationRegistration(DEF);
    assertThat(reg.correlationKeyExtractor()).isSameAs(DefaultCorrelationKeyExtractor.INSTANCE);
    assertThat(reg.eventFilter()).isNull();
    assertThat(reg.compiledDynamicData()).isNull();
}
```

- [ ] **Step 6: Update SituationRegistration record**

Use `ide_edit_member`:

```java
public record SituationRegistration(
        SituationDefinition definition,
        CorrelationKeyExtractor correlationKeyExtractor,
        EventFilter eventFilter,
        Map<String, CompiledExpression<Map, Object>> compiledDynamicData
) {
    public SituationRegistration {
        Objects.requireNonNull(definition, "definition");
        if (correlationKeyExtractor == null) {
            correlationKeyExtractor = DefaultCorrelationKeyExtractor.INSTANCE;
        }
    }

    public SituationRegistration(SituationDefinition definition,
            CorrelationKeyExtractor correlationKeyExtractor) {
        this(definition, correlationKeyExtractor, null, null);
    }

    public SituationRegistration(SituationDefinition definition) {
        this(definition, null, null, null);
    }
}
```

Add imports: `io.casehub.platform.api.expression.CompiledExpression`, `java.util.Map`.

- [ ] **Step 7: Run api/ tests**

Run: `mvn -pl api test --batch-mode`
Expected: PASS — all existing tests use the 7-arg convenience constructor,
new tests verify expression fields

- [ ] **Step 8: Verify no compile errors across modules**

Run: `mvn compile --batch-mode`
Expected: PASS — convenience constructors keep existing callsites working

- [ ] **Step 9: Commit**

```bash
git add api/src/main/java/io/casehub/ras/api/SituationDefinition.java api/src/main/java/io/casehub/ras/api/EventFilter.java api/src/main/java/io/casehub/ras/api/SituationRegistration.java api/src/test/java/io/casehub/ras/api/SituationDefinitionTest.java api/src/test/java/io/casehub/ras/api/SituationRegistrationTest.java
git commit -m "feat(casehub-ras#46): expression fields on SituationDefinition, EventFilter interface, SituationRegistration extension"
```

---

### Task 3: Expression context builders

**Files:**
- Create: `runtime/src/main/java/io/casehub/ras/runtime/CloudEventExpressionContext.java`
- Create: `runtime/src/main/java/io/casehub/ras/runtime/SituationContextExpressionContext.java`
- Test: `runtime/src/test/java/io/casehub/ras/runtime/CloudEventExpressionContextTest.java`
- Test: `runtime/src/test/java/io/casehub/ras/runtime/SituationContextExpressionContextTest.java`

**Interfaces:**
- Consumes: `CloudEvent` from `io.cloudevents`, `SituationContext` from `io.casehub.ras.api`
- Produces:
  - `CloudEventExpressionContext.build(CloudEvent) → Map<String, Object>` — keys: type, source, subject, id, time, tenancyid, data
  - `SituationContextExpressionContext.build(SituationContext) → Map<String, Object>` — keys: situationId, correlationKey, tenancyId, firstSignal, lastSignal, lastTriggered, triggerCount, detections

- [ ] **Step 1: Write failing tests for CloudEventExpressionContext**

```java
package io.casehub.ras.runtime;

import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class CloudEventExpressionContextTest {

    @Test
    void buildsCompleteContext() {
        CloudEvent event = CloudEventBuilder.v1()
                .withId("evt-1")
                .withSource(URI.create("/test"))
                .withType("order.created")
                .withSubject("order-123")
                .withTime(OffsetDateTime.of(2026, 7, 18, 10, 0, 0, 0, ZoneOffset.UTC))
                .withExtension("tenancyid", "tenant-A")
                .withData("application/json", "{\"orderId\":\"X\",\"severity\":3}".getBytes())
                .build();

        Map<String, Object> ctx = CloudEventExpressionContext.build(event);

        assertThat(ctx.get("type")).isEqualTo("order.created");
        assertThat(ctx.get("source")).isEqualTo("/test");
        assertThat(ctx.get("subject")).isEqualTo("order-123");
        assertThat(ctx.get("id")).isEqualTo("evt-1");
        assertThat(ctx.get("tenancyid")).isEqualTo("tenant-A");
        assertThat(ctx.get("data")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) ctx.get("data");
        assertThat(data.get("orderId")).isEqualTo("X");
        assertThat(data.get("severity")).isEqualTo(3);
    }

    @Test
    void nullSubjectAndTimeIncludedAsNull() {
        CloudEvent event = CloudEventBuilder.v1()
                .withId("evt-1")
                .withSource(URI.create("/test"))
                .withType("test.event")
                .build();

        Map<String, Object> ctx = CloudEventExpressionContext.build(event);

        assertThat(ctx).containsKey("subject");
        assertThat(ctx.get("subject")).isNull();
        assertThat(ctx).containsKey("time");
        assertThat(ctx.get("time")).isNull();
    }

    @Test
    void nonJsonDataProducesEmptyMap() {
        CloudEvent event = CloudEventBuilder.v1()
                .withId("evt-1")
                .withSource(URI.create("/test"))
                .withType("test.event")
                .withData("text/plain", "hello".getBytes())
                .build();

        Map<String, Object> ctx = CloudEventExpressionContext.build(event);

        assertThat(ctx.get("data")).isEqualTo(Map.of());
    }

    @Test
    void noDataProducesEmptyMap() {
        CloudEvent event = CloudEventBuilder.v1()
                .withId("evt-1")
                .withSource(URI.create("/test"))
                .withType("test.event")
                .build();

        Map<String, Object> ctx = CloudEventExpressionContext.build(event);

        assertThat(ctx.get("data")).isEqualTo(Map.of());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl runtime test -Dtest=CloudEventExpressionContextTest --batch-mode`
Expected: FAIL — class does not exist

- [ ] **Step 3: Implement CloudEventExpressionContext**

Use `ide_create_file`:

```java
package io.casehub.ras.runtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cloudevents.CloudEvent;

import java.util.LinkedHashMap;
import java.util.Map;

final class CloudEventExpressionContext {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private CloudEventExpressionContext() {}

    static Map<String, Object> build(CloudEvent event) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("type", event.getType());
        ctx.put("source", event.getSource() != null ? event.getSource().toString() : null);
        ctx.put("subject", event.getSubject());
        ctx.put("id", event.getId());
        ctx.put("time", event.getTime());
        ctx.put("tenancyid", event.getExtension("tenancyid"));
        ctx.put("data", parseJsonData(event));
        return ctx;
    }

    private static Map<String, Object> parseJsonData(CloudEvent event) {
        if (event.getData() == null) {
            return Map.of();
        }
        String contentType = event.getDataContentType();
        if (contentType == null || !isJsonContentType(contentType)) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(event.getData().toBytes(), MAP_TYPE);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static boolean isJsonContentType(String contentType) {
        return contentType.equals("application/json")
                || (contentType.startsWith("application/") && contentType.endsWith("+json"));
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -pl runtime test -Dtest=CloudEventExpressionContextTest --batch-mode`
Expected: PASS

- [ ] **Step 5: Write failing tests for SituationContextExpressionContext**

```java
package io.casehub.ras.runtime;

import io.casehub.ras.api.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import static org.assertj.core.api.Assertions.*;

class SituationContextExpressionContextTest {

    @Test
    void buildsCompleteContext() {
        var detection = new DetectionResult("g1", 0.9, DetectionSignal.DETECTED, Map.of("key", "val"));
        var ctx = new SituationContext("sit-1", "corr-1", "tenant-A",
                Instant.parse("2026-07-18T10:00:00Z"),
                Instant.parse("2026-07-18T10:05:00Z"),
                List.of(new TimestampedDetection(detection, Instant.parse("2026-07-18T10:05:00Z"))),
                OptionalLong.empty(), Instant.parse("2026-07-18T10:04:00Z"), 1);

        Map<String, Object> result = SituationContextExpressionContext.build(ctx);

        assertThat(result.get("situationId")).isEqualTo("sit-1");
        assertThat(result.get("correlationKey")).isEqualTo("corr-1");
        assertThat(result.get("tenancyId")).isEqualTo("tenant-A");
        assertThat(result.get("triggerCount")).isEqualTo(1);
        assertThat(result.get("lastTriggered")).isEqualTo(Instant.parse("2026-07-18T10:04:00Z"));
        assertThat(result.get("detections")).isInstanceOf(List.class);
    }

    @Test
    void nullLastTriggeredIncluded() {
        var ctx = new SituationContext("sit-1", "corr-1", "tenant-A",
                Instant.now(), Instant.now(), List.of(), OptionalLong.empty(), null, 0);

        Map<String, Object> result = SituationContextExpressionContext.build(ctx);

        assertThat(result).containsKey("lastTriggered");
        assertThat(result.get("lastTriggered")).isNull();
    }
}
```

- [ ] **Step 6: Implement SituationContextExpressionContext**

Use `ide_create_file`:

```java
package io.casehub.ras.runtime;

import io.casehub.ras.api.SituationContext;

import java.util.LinkedHashMap;
import java.util.Map;

final class SituationContextExpressionContext {

    private SituationContextExpressionContext() {}

    static Map<String, Object> build(SituationContext context) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("situationId", context.situationId());
        ctx.put("correlationKey", context.correlationKey());
        ctx.put("tenancyId", context.tenancyId());
        ctx.put("firstSignal", context.firstSignal());
        ctx.put("lastSignal", context.lastSignal());
        ctx.put("lastTriggered", context.lastTriggered());
        ctx.put("triggerCount", context.triggerCount());
        ctx.put("detections", context.detections());
        return ctx;
    }
}
```

- [ ] **Step 7: Run both context tests**

Run: `mvn -pl runtime test -Dtest=CloudEventExpressionContextTest,SituationContextExpressionContextTest --batch-mode`
Expected: PASS

- [ ] **Step 8: Commit**

```bash
git add runtime/src/main/java/io/casehub/ras/runtime/CloudEventExpressionContext.java runtime/src/main/java/io/casehub/ras/runtime/SituationContextExpressionContext.java runtime/src/test/java/io/casehub/ras/runtime/CloudEventExpressionContextTest.java runtime/src/test/java/io/casehub/ras/runtime/SituationContextExpressionContextTest.java
git commit -m "feat(casehub-ras#46): CloudEvent and SituationContext expression context builders"
```

---

### Task 4: SituationDefinitionRegistry — expression compilation

**Files:**
- Modify: `runtime/src/main/java/io/casehub/ras/runtime/SituationDefinitionRegistry.java`
- Test: `runtime/src/test/java/io/casehub/ras/runtime/SituationDefinitionRegistryTest.java` (add tests)

**Interfaces:**
- Consumes: `ExpressionEngineRegistry` (CDI), `StringExpressionEvaluator`, `CompiledExpression`,
  `LambdaExpression`, `CloudEventExpressionContext` (Task 3), `EventFilter` (Task 2),
  `CorrelationKeyExtractor` (api/)
- Produces:
  - `compileRegistration(SituationRegistration) → SituationRegistration` — compiles expressions from definition, returns new registration with compiled extractor/filter/dynamicData
  - `getCompiledDynamicData(String situationId) → Map<String, CompiledExpression<Map, Object>>` — O(1) lookup via `bySituationId` snapshot index

- [ ] **Step 1: Write failing test — compilation replaces correlation key extractor**

Add to `SituationDefinitionRegistryTest`:

```java
@Test
void register_compiles_correlationKeyExpression() {
    var g1 = ganglion("g1", "io.test.event");
    var mockRegistry = new TestExpressionEngineRegistry();
    var registry = new SituationDefinitionRegistry(
            List.of(), List.of(g1), mockRegistry);

    var def = new SituationDefinition("sit-A", Set.of("io.test.event"),
            Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")),
            new TriggerAction.CreateCase(new CaseTriggerConfig("ns", "case", "1.0", Map.of())),
            null, new JQExpressionEvaluator(".subject"), null, Map.of());
    registry.register(new SituationRegistration(def));

    var regs = registry.findByEventType("io.test.event");
    assertThat(regs).hasSize(1);
    assertThat(regs.get(0).correlationKeyExtractor())
            .isNotSameAs(DefaultCorrelationKeyExtractor.INSTANCE);
}
```

This requires a `TestExpressionEngineRegistry` — a simple test double in the test class that returns mock compiled expressions. Also requires a 3-arg constructor on `SituationDefinitionRegistry`.

- [ ] **Step 2: Write failing test — compilation sets event filter**

```java
@Test
void register_compiles_eventFilter() {
    var g1 = ganglion("g1", "io.test.event");
    var mockRegistry = new TestExpressionEngineRegistry();
    var registry = new SituationDefinitionRegistry(
            List.of(), List.of(g1), mockRegistry);

    var def = new SituationDefinition("sit-A", Set.of("io.test.event"),
            Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")),
            new TriggerAction.CreateCase(new CaseTriggerConfig("ns", "case", "1.0", Map.of())),
            null, null,
            new MvelExpressionEvaluator("data.severity >= 3"), Map.of());
    registry.register(new SituationRegistration(def));

    var regs = registry.findByEventType("io.test.event");
    assertThat(regs).hasSize(1);
    assertThat(regs.get(0).eventFilter()).isNotNull();
}
```

- [ ] **Step 3: Write failing test — fail-fast on missing engine**

```java
@Test
void register_failsFast_whenExpressionEngineNotFound() {
    var g1 = ganglion("g1", "io.test.event");
    var emptyRegistry = new TestExpressionEngineRegistry(false);
    var registry = new SituationDefinitionRegistry(
            List.of(), List.of(g1), emptyRegistry);

    var def = new SituationDefinition("sit-A", Set.of("io.test.event"),
            Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")),
            new TriggerAction.CreateCase(new CaseTriggerConfig("ns", "case", "1.0", Map.of())),
            null, new JQExpressionEvaluator(".subject"), null, Map.of());

    assertThatIllegalStateException()
            .isThrownBy(() -> registry.register(new SituationRegistration(def)))
            .withMessageContaining("sit-A")
            .withMessageContaining("jq");
}
```

- [ ] **Step 4: Write failing test — LambdaExpression passed through without compilation**

```java
@Test
void register_lambdaExpression_passedThroughWithoutCompilation() {
    var g1 = ganglion("g1", "io.test.event");
    var mockRegistry = new TestExpressionEngineRegistry();
    var registry = new SituationDefinitionRegistry(
            List.of(), List.of(g1), mockRegistry);

    LambdaExpression<Map, String> lambda = new LambdaExpression<>(
            ctx -> (String) ((Map) ctx).get("subject"));
    var def = new SituationDefinition("sit-A", Set.of("io.test.event"),
            Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")),
            new TriggerAction.CreateCase(new CaseTriggerConfig("ns", "case", "1.0", Map.of())),
            null, lambda, null, Map.of());
    registry.register(new SituationRegistration(def));

    var regs = registry.findByEventType("io.test.event");
    assertThat(regs).hasSize(1);
    assertThat(regs.get(0).correlationKeyExtractor())
            .isNotSameAs(DefaultCorrelationKeyExtractor.INSTANCE);
    assertThat(mockRegistry.compileCount).isZero();
}
```

- [ ] **Step 5: Write failing test — bySituationId index and getCompiledDynamicData**

```java
@Test
void getCompiledDynamicData_returns_compiled_expressions() {
    var g1 = ganglion("g1", "io.test.event");
    var mockRegistry = new TestExpressionEngineRegistry();
    var registry = new SituationDefinitionRegistry(
            List.of(), List.of(g1), mockRegistry);

    var dynamicData = Map.<String, ExpressionEvaluator>of(
            "orderId", new JQExpressionEvaluator(".correlationKey"));
    var def = new SituationDefinition("sit-A", Set.of("io.test.event"),
            Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")),
            new TriggerAction.CreateCase(new CaseTriggerConfig("ns", "case", "1.0", Map.of())),
            null, null, null, dynamicData);
    registry.register(new SituationRegistration(def));

    var compiled = registry.getCompiledDynamicData("sit-A");
    assertThat(compiled).isNotNull().containsKey("orderId");
}

@Test
void getCompiledDynamicData_returns_null_when_no_expressions() {
    var g1 = ganglion("g1", "io.test.event");
    var registry = new SituationDefinitionRegistry(List.of(), List.of(g1));

    var def = definition("sit-A", Set.of("io.test.event"), new ChainMode.Or(Set.of("g1")));
    registry.register(new SituationRegistration(def));

    assertThat(registry.getCompiledDynamicData("sit-A")).isNull();
}
```

- [ ] **Step 6: Run tests to verify they fail**

Run: `mvn -pl runtime test -Dtest=SituationDefinitionRegistryTest --batch-mode`
Expected: FAIL — no 3-arg constructor, no compilation logic, no `getCompiledDynamicData()`

- [ ] **Step 7: Add TestExpressionEngineRegistry inner class to test file**

```java
private static class TestExpressionEngineRegistry implements ExpressionEngineRegistry {
    int compileCount = 0;
    private final boolean resolveSucceeds;

    TestExpressionEngineRegistry() { this(true); }
    TestExpressionEngineRegistry(boolean resolveSucceeds) { this.resolveSucceeds = resolveSucceeds; }

    @Override
    public void register(ExpressionEngine engine) {}

    @Override
    public Optional<ExpressionEngine> resolve(String type) {
        return resolveSucceeds ? Optional.of(new StubEngine()) : Optional.empty();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <C, R> CompiledExpression<C, R> compile(
            String type, String expression, Class<C> contextType, Class<R> resultType) {
        compileCount++;
        return (CompiledExpression<C, R>) new StubCompiledExpression(expression);
    }

    @Override
    public <C, R> CompiledExpression<C, R> compile(
            String type, String expression, Class<C> contextType, Class<R> resultType,
            Map<String, Object> variables) {
        return compile(type, expression, contextType, resultType);
    }

    @Override
    public void validate(String type, String expression) {}

    private record StubCompiledExpression(String expression) implements CompiledExpression<Map, Object> {
        @Override public String type() { return "stub"; }
        @Override public Object eval(Map context) { return context.get("subject"); }
    }

    private static class StubEngine implements ExpressionEngine {
        @Override public String type() { return "stub"; }
        @Override public <C, R> CompiledExpression<C, R> compile(String e, Class<C> c, Class<R> r) { return null; }
        @Override public <C, R> CompiledExpression<C, R> compile(String e, Class<C> c, Class<R> r, Map<String, Object> v) { return null; }
        @Override public void validate(String e) {}
    }
}
```

- [ ] **Step 8: Implement SituationDefinitionRegistry changes**

Changes to `SituationDefinitionRegistry`:

1. Add `ExpressionEngineRegistry` field and 3-arg test constructor
2. Update CDI constructor to inject `ExpressionEngineRegistry`
3. Add `compileExpression()` private method (spec §Compilation Flow)
4. Add `compileRegistration()` private method — wraps compilation for all three expression types
5. Call `compileRegistration()` in startup loop and `register()`
6. Add `bySituationId` index to `RegistrySnapshot`
7. Add `getCompiledDynamicData(String situationId)` public method

Use `ide_edit_member` / `ide_insert_member` / `ide_replace_member` for structural changes.

Key implementation details:
- `compileExpression()`: check `instanceof CompiledExpression` first (LambdaExpression), then `instanceof StringExpressionEvaluator`, then throw
- `compileRegistration()`: if definition has expressions, create a new `SituationRegistration` with compiled extractor/filter/dynamicData
- `RegistrySnapshot` becomes: `record RegistrySnapshot(Map<String, List<SituationRegistration>> byEventType, Map<String, SituationRegistration> bySituationId, Set<String> situationIds, Duration maxCorrelationWindow)`
- `getCompiledDynamicData()`: `snapshot.bySituationId().get(situationId)?.compiledDynamicData()`
- Correlation key wrapper: `event -> { var ctx = CloudEventExpressionContext.build(event); String result = (String) compiled.eval(ctx); return result != null ? result : "_singleton"; }`
- Event filter wrapper: `event -> { var ctx = CloudEventExpressionContext.build(event); Boolean result = (Boolean) compiled.eval(ctx); return result != null && result; }`

- [ ] **Step 9: Run tests to verify they pass**

Run: `mvn -pl runtime test -Dtest=SituationDefinitionRegistryTest --batch-mode`
Expected: PASS

- [ ] **Step 10: Run full runtime tests**

Run: `mvn -pl runtime test --batch-mode`
Expected: PASS — existing tests unchanged (use 7-arg constructor, no expressions)

- [ ] **Step 11: Commit**

```bash
git add runtime/src/main/java/io/casehub/ras/runtime/SituationDefinitionRegistry.java runtime/src/test/java/io/casehub/ras/runtime/SituationDefinitionRegistryTest.java
git commit -m "feat(casehub-ras#46): registry-based expression compilation — compiles expressions at registration, stores on SituationRegistration"
```

---

### Task 5: YAML parsing — correlationKey, eventFilter, dynamicCaseData

**Files:**
- Modify: `runtime/src/main/java/io/casehub/ras/runtime/YamlSituationDefinitionProvider.java`
- Test: `runtime/src/test/java/io/casehub/ras/runtime/YamlSituationDefinitionProviderTest.java` (add tests)

**Interfaces:**
- Consumes: `JQExpressionEvaluator`, `MvelExpressionEvaluator` from `casehub-platform-api`
- Produces: `SituationDefinition` with expression descriptors set from YAML fields.
  The registry (Task 4) handles compilation — the provider just creates descriptors.

- [ ] **Step 1: Write failing test — parses correlationKey expression**

```java
@Test
void parsesCorrelationKeyExpression() {
    var regs = provider("""
            situations:
              - situationId: sit1
                eventTypes: [e1]
                correlationKey:
                  expression: ".data.orderId"
                  language: jq
                chainMode:
                  type: or
                  ganglia: [g1]
                triggerAction:
                  type: create-case
                  caseNamespace: ns
                  caseName: c
                  caseVersion: "1"
            """).registrations();

    assertThat(regs).hasSize(1);
    var def = regs.get(0).definition();
    assertThat(def.correlationKeyExpression()).isNotNull();
    assertThat(def.correlationKeyExpression()).isInstanceOf(JQExpressionEvaluator.class);
    assertThat(((JQExpressionEvaluator) def.correlationKeyExpression()).expression())
            .isEqualTo(".data.orderId");
}
```

- [ ] **Step 2: Write failing test — parses eventFilter expression**

```java
@Test
void parsesEventFilterExpression() {
    var regs = provider("""
            situations:
              - situationId: sit1
                eventTypes: [e1]
                eventFilter:
                  expression: "data.severity >= 3"
                  language: mvel
                chainMode:
                  type: or
                  ganglia: [g1]
                triggerAction:
                  type: create-case
                  caseNamespace: ns
                  caseName: c
                  caseVersion: "1"
            """).registrations();

    var def = regs.get(0).definition();
    assertThat(def.eventFilter()).isNotNull();
    assertThat(def.eventFilter()).isInstanceOf(MvelExpressionEvaluator.class);
}
```

- [ ] **Step 3: Write failing test — parses dynamicCaseData**

```java
@Test
void parsesDynamicCaseData() {
    var regs = provider("""
            situations:
              - situationId: sit1
                eventTypes: [e1]
                dynamicCaseData:
                  orderId:
                    expression: ".correlationKey"
                    language: jq
                  severity:
                    expression: ".detections[-1].result.evidence.severity"
                    language: jq
                chainMode:
                  type: or
                  ganglia: [g1]
                triggerAction:
                  type: create-case
                  caseNamespace: ns
                  caseName: c
                  caseVersion: "1"
            """).registrations();

    var def = regs.get(0).definition();
    assertThat(def.dynamicCaseData()).hasSize(2);
    assertThat(def.dynamicCaseData()).containsKey("orderId");
    assertThat(def.dynamicCaseData()).containsKey("severity");
}
```

- [ ] **Step 4: Write failing test — unknown language rejects**

```java
@Test
void unknownExpressionLanguageThrows() {
    assertThatIllegalArgumentException().isThrownBy(() -> provider("""
            situations:
              - situationId: sit1
                eventTypes: [e1]
                correlationKey:
                  expression: ".data.orderId"
                  language: groovy
                chainMode:
                  type: or
                  ganglia: [g1]
                triggerAction:
                  type: create-case
                  caseNamespace: ns
                  caseName: c
                  caseVersion: "1"
            """).registrations())
            .withMessageContaining("groovy");
}
```

- [ ] **Step 5: Write failing test — absent expression fields default to null/empty**

```java
@Test
void absentExpressionFieldsDefaultToNull() {
    var regs = provider("""
            situations:
              - situationId: sit1
                eventTypes: [e1]
                chainMode:
                  type: or
                  ganglia: [g1]
                triggerAction:
                  type: create-case
                  caseNamespace: ns
                  caseName: c
                  caseVersion: "1"
            """).registrations();

    var def = regs.get(0).definition();
    assertThat(def.correlationKeyExpression()).isNull();
    assertThat(def.eventFilter()).isNull();
    assertThat(def.dynamicCaseData()).isEmpty();
}
```

- [ ] **Step 6: Run tests to verify they fail**

Run: `mvn -pl runtime test -Dtest=YamlSituationDefinitionProviderTest --batch-mode`
Expected: FAIL — new methods/fields not parsed

- [ ] **Step 7: Implement YAML parsing changes**

Add to `YamlSituationDefinitionProvider.parseSituation()`:

1. Parse `correlationKey` map → `parseExpressionEvaluator(map, "correlationKey")` returning `ExpressionEvaluator` or null
2. Parse `eventFilter` map → same pattern
3. Parse `dynamicCaseData` map → iterate entries, parse each as expression evaluator
4. Pass all three to the 10-arg `SituationDefinition` constructor

Add `parseExpressionEvaluator(Map, String)` private method:
```java
private static ExpressionEvaluator parseExpressionEvaluator(Map<String, Object> map, String key) {
    @SuppressWarnings("unchecked")
    Map<String, Object> exprMap = (Map<String, Object>) map.get(key);
    if (exprMap == null) return null;
    String expression = requireString(exprMap, "expression");
    String language = requireString(exprMap, "language");
    return switch (language) {
        case "jq" -> new JQExpressionEvaluator(expression);
        case "mvel" -> new MvelExpressionEvaluator(expression);
        default -> throw new IllegalArgumentException(
                "Unknown expression language '" + language + "'. Expected 'jq' or 'mvel'");
    };
}
```

Add `parseDynamicCaseData(Map)` private method:
```java
@SuppressWarnings("unchecked")
private static Map<String, ExpressionEvaluator> parseDynamicCaseData(Map<String, Object> map) {
    Map<String, Object> raw = (Map<String, Object>) map.get("dynamicCaseData");
    if (raw == null) return Map.of();
    Map<String, ExpressionEvaluator> result = new LinkedHashMap<>();
    for (var entry : raw.entrySet()) {
        Map<String, Object> exprMap = (Map<String, Object>) entry.getValue();
        String expression = requireString(exprMap, "expression");
        String language = requireString(exprMap, "language");
        ExpressionEvaluator evaluator = switch (language) {
            case "jq" -> new JQExpressionEvaluator(expression);
            case "mvel" -> new MvelExpressionEvaluator(expression);
            default -> throw new IllegalArgumentException(
                    "Unknown expression language '" + language + "' for dynamicCaseData key '" + entry.getKey() + "'");
        };
        result.put(entry.getKey(), evaluator);
    }
    return result;
}
```

Update the `SituationDefinition` constructor call in `parseSituation()` to use the 10-arg constructor.

- [ ] **Step 8: Run tests to verify they pass**

Run: `mvn -pl runtime test -Dtest=YamlSituationDefinitionProviderTest --batch-mode`
Expected: PASS

- [ ] **Step 9: Commit**

```bash
git add runtime/src/main/java/io/casehub/ras/runtime/YamlSituationDefinitionProvider.java runtime/src/test/java/io/casehub/ras/runtime/YamlSituationDefinitionProviderTest.java
git commit -m "feat(casehub-ras#46): YAML parsing for correlationKey, eventFilter, dynamicCaseData expressions"
```

---

### Task 6: RasEngine — event filter integration with error handling

**Files:**
- Modify: `runtime/src/main/java/io/casehub/ras/runtime/RasEngine.java`
- Modify: `runtime/src/main/java/io/casehub/ras/runtime/RasMetrics.java`
- Test: `runtime/src/test/java/io/casehub/ras/runtime/RasEngineTest.java` (add tests)

**Interfaces:**
- Consumes: `EventFilter.accepts(CloudEvent)` (Task 2), `CloudEventExpressionContext.build()` (Task 3),
  `SituationRegistration.eventFilter()` (Task 2)
- Produces: events filtered before evaluation; `ras.events.filtered` metric; filter errors
  treated as pass-through with `ras.expression.error` metric

- [ ] **Step 1: Write failing test — filtered events skip evaluation**

Add to `RasEngineTest`:

```java
@Test
void filteredEventSkipsEvaluation() {
    var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
            FixedDetectionResult.detected("g1", 0.9));
    var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
            Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")),
            new TriggerAction.CreateCase(TRIGGER), null);
    EventFilter rejectAll = event -> false;
    var reg = new SituationRegistration(def, DefaultCorrelationKeyExtractor.INSTANCE,
            rejectAll, null);
    var registry = new SituationDefinitionRegistry(
            List.of(() -> List.of(reg)), List.of(ganglion));
    var store = new InMemorySituationStore();
    var caseTrigger = new MockCaseTrigger();
    var metrics = new RasMetrics(registry);
    metrics.setMeterRegistry(meterRegistry);
    metrics.init();
    var evaluator = new SituationEvaluator(store, new DefaultRasTriggerPolicy(), caseTrigger,
            registry, 3, null, metrics);
    var engine = new RasEngine(registry, evaluator, metrics);

    engine.onCloudEvent(event("temp.reading", "t1"));

    assertThat(caseTrigger.firedCount()).isZero();
    assertThat(meterRegistry.find("ras.events.filtered").counter()).isNotNull();
}
```

- [ ] **Step 2: Write failing test — null filter means no filtering**

```java
@Test
void nullFilterAllowsEvent() {
    var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
            FixedDetectionResult.detected("g1", 0.9));
    var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
            Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")),
            new TriggerAction.CreateCase(TRIGGER), null);
    var reg = new SituationRegistration(def);
    var registry = new SituationDefinitionRegistry(
            List.of(() -> List.of(reg)), List.of(ganglion));
    var store = new InMemorySituationStore();
    var caseTrigger = new MockCaseTrigger();
    var metrics = new RasMetrics(registry);
    metrics.setMeterRegistry(meterRegistry);
    metrics.init();
    var evaluator = new SituationEvaluator(store, new DefaultRasTriggerPolicy(), caseTrigger,
            registry, 3, null, metrics);
    var engine = new RasEngine(registry, evaluator, metrics);

    engine.onCloudEvent(event("temp.reading", "t1"));

    assertThat(caseTrigger.firedCount()).isEqualTo(1);
}
```

- [ ] **Step 3: Write failing test — filter error treated as pass-through**

```java
@Test
void filterExceptionTreatedAsPassThrough() {
    var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
            FixedDetectionResult.detected("g1", 0.9));
    var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
            Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")),
            new TriggerAction.CreateCase(TRIGGER), null);
    EventFilter brokenFilter = event -> { throw new RuntimeException("broken"); };
    var reg = new SituationRegistration(def, DefaultCorrelationKeyExtractor.INSTANCE,
            brokenFilter, null);
    var registry = new SituationDefinitionRegistry(
            List.of(() -> List.of(reg)), List.of(ganglion));
    var store = new InMemorySituationStore();
    var caseTrigger = new MockCaseTrigger();
    var metrics = new RasMetrics(registry);
    metrics.setMeterRegistry(meterRegistry);
    metrics.init();
    var evaluator = new SituationEvaluator(store, new DefaultRasTriggerPolicy(), caseTrigger,
            registry, 3, null, metrics);
    var engine = new RasEngine(registry, evaluator, metrics);

    engine.onCloudEvent(event("temp.reading", "t1"));

    assertThat(caseTrigger.firedCount()).isEqualTo(1);
    assertThat(meterRegistry.find("ras.expression.error").counter()).isNotNull();
}
```

- [ ] **Step 4: Run tests to verify they fail**

Run: `mvn -pl runtime test -Dtest=RasEngineTest --batch-mode`
Expected: FAIL — no filter check in onCloudEvent

- [ ] **Step 5: Add metrics methods to RasMetrics**

Add `eventFiltered(String situationId, String tenancyId)` and
`expressionError(String situationId, String expressionPoint)` methods.

- [ ] **Step 6: Implement filter check in RasEngine.onCloudEvent()**

In the `for (SituationRegistration reg : registrations)` loop, add before
`correlationKeyExtractor().extract()`:

```java
if (reg.eventFilter() != null) {
    try {
        if (!reg.eventFilter().accepts(event)) {
            metrics.eventFiltered(reg.definition().situationId(), tenancyId);
            continue;
        }
    } catch (RuntimeException ex) {
        LOG.warning("Event filter error for situation '" + reg.definition().situationId()
                    + "', treating as pass-through: " + ex.getMessage());
        metrics.expressionError(reg.definition().situationId(), "event_filter");
    }
}
```

Also add correlation key expression error handling — wrap
`reg.correlationKeyExtractor().extract(event)` in try-catch, falling back to
`DefaultCorrelationKeyExtractor.INSTANCE.extract(event)` on error:

```java
String correlationKey;
try {
    correlationKey = reg.correlationKeyExtractor().extract(event);
} catch (RuntimeException ex) {
    LOG.warning("Correlation key expression error for situation '"
                + reg.definition().situationId() + "', using default: " + ex.getMessage());
    metrics.expressionError(reg.definition().situationId(), "correlation_key");
    correlationKey = DefaultCorrelationKeyExtractor.INSTANCE.extract(event);
}
```

- [ ] **Step 7: Run tests to verify they pass**

Run: `mvn -pl runtime test -Dtest=RasEngineTest --batch-mode`
Expected: PASS

- [ ] **Step 8: Run full runtime tests**

Run: `mvn -pl runtime test --batch-mode`
Expected: PASS

- [ ] **Step 9: Commit**

```bash
git add runtime/src/main/java/io/casehub/ras/runtime/RasEngine.java runtime/src/main/java/io/casehub/ras/runtime/RasMetrics.java runtime/src/test/java/io/casehub/ras/runtime/RasEngineTest.java
git commit -m "feat(casehub-ras#46): event filter integration in RasEngine with error handling and metrics"
```

---

### Task 7: DefaultCaseTrigger — dynamic case data with error handling

**Files:**
- Modify: `runtime/src/main/java/io/casehub/ras/runtime/DefaultCaseTrigger.java`
- Test: `runtime/src/test/java/io/casehub/ras/runtime/DefaultCaseTriggerTest.java` (add tests)

**Interfaces:**
- Consumes: `SituationDefinitionRegistry.getCompiledDynamicData(String)` (Task 4),
  `SituationContextExpressionContext.build()` (Task 3), `CompiledExpression.eval()` (platform-api)
- Produces: case input data enriched with dynamically evaluated expression results.
  Merge order: static baseCaseData → dynamic expressions → correlation metadata → CaseInputContributors.

- [ ] **Step 1: Write failing test — dynamic data merged into case input**

Add to `DefaultCaseTriggerTest`:

```java
@Test
void dynamicCaseDataMergedIntoCaseInput() {
    // Build a registry with compiled dynamic data
    var g1 = new MockGanglion("g1", Set.of("e"), FixedDetectionResult.detected("g1", 0.9));
    var mockExprRegistry = new TestExpressionEngineRegistry();
    var dynamicData = Map.<String, ExpressionEvaluator>of(
            "customField", new JQExpressionEvaluator(".correlationKey"));
    var def = new SituationDefinition("sit-1", Set.of("e"),
            Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")),
            new TriggerAction.CreateCase(new CaseTriggerConfig("ns", "case", "1.0", Map.of())),
            null, null, null, dynamicData);
    var registry = new SituationDefinitionRegistry(
            List.of(() -> List.of(new SituationRegistration(def))),
            List.of(g1), mockExprRegistry);

    var caseHub = mockCaseHub("ns", "case", "1.0");
    var trigger = new DefaultCaseTrigger(List.of(caseHub), List.of(), registry);

    var context = new SituationContext("sit-1", "order-123", "tenant-A",
            Instant.now(), Instant.now(), List.of(), OptionalLong.empty(), null, 0);

    trigger.fire(new CaseTriggerConfig("ns", "case", "1.0", Map.of()), context)
            .await().indefinitely();

    Map<String, Object> inputData = caseHub.lastInputData();
    assertThat(inputData).containsKey("customField");
}
```

- [ ] **Step 2: Write failing test — dynamic expression error skips key**

```java
@Test
void dynamicExpressionErrorSkipsKeyButCreatesCase() {
    var g1 = new MockGanglion("g1", Set.of("e"), FixedDetectionResult.detected("g1", 0.9));
    var def = new SituationDefinition("sit-1", Set.of("e"),
            Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")),
            new TriggerAction.CreateCase(new CaseTriggerConfig("ns", "case", "1.0",
                    Map.of("static", "value"))),
            null);

    CompiledExpression<Map, Object> throwingExpr = new CompiledExpression<>() {
        @Override public String type() { return "test"; }
        @Override public Object eval(Map context) { throw new RuntimeException("broken"); }
    };
    var reg = new SituationRegistration(def, DefaultCorrelationKeyExtractor.INSTANCE,
            null, Map.of("brokenKey", throwingExpr));
    var registry = new SituationDefinitionRegistry(
            List.of(() -> List.of(reg)), List.of(g1));

    var caseHub = mockCaseHub("ns", "case", "1.0");
    var trigger = new DefaultCaseTrigger(List.of(caseHub), List.of(), registry);

    var context = new SituationContext("sit-1", "corr-1", "tenant-A",
            Instant.now(), Instant.now(), List.of(), OptionalLong.empty(), null, 0);
    trigger.fire(new CaseTriggerConfig("ns", "case", "1.0",
            Map.of("static", "value")), context).await().indefinitely();

    assertThat(caseHub.lastInputData()).containsKey("static");
    assertThat(caseHub.lastInputData()).doesNotContainKey("brokenKey");
}
```

- [ ] **Step 3: Write failing test — merge order correctness**

```java
@Test
void mergeOrderStaticThenDynamicThenMetadata() {
    // Static baseCaseData has key "foo"
    // Dynamic expression also produces key "foo"
    // Assert dynamic overrides static
    // Also assert "situationId" key (correlation metadata) cannot be overridden by dynamic
}
```

- [ ] **Step 4: Run tests to verify they fail**

Run: `mvn -pl runtime test -Dtest=DefaultCaseTriggerTest --batch-mode`
Expected: FAIL — no registry parameter, no dynamic data resolution

- [ ] **Step 5: Implement DefaultCaseTrigger changes**

1. Add `SituationDefinitionRegistry` constructor parameter
2. Update CDI constructor to inject it
3. Update test constructor to accept it (nullable for backwards compat)
4. In `buildInputData()`, after static baseCaseData, resolve dynamic expressions:

```java
if (registry != null) {
    var compiled = registry.getCompiledDynamicData(context.situationId());
    if (compiled != null) {
        Map<String, Object> exprCtx = SituationContextExpressionContext.build(context);
        for (var entry : compiled.entrySet()) {
            try {
                data.put(entry.getKey(), entry.getValue().eval(exprCtx));
            } catch (RuntimeException ex) {
                LOG.warning("Dynamic case data expression error for key '"
                            + entry.getKey() + "' in situation '"
                            + context.situationId() + "': " + ex.getMessage());
            }
        }
    }
}
```

Then add correlation metadata (after dynamic, so it cannot be overridden).

- [ ] **Step 6: Run tests to verify they pass**

Run: `mvn -pl runtime test -Dtest=DefaultCaseTriggerTest --batch-mode`
Expected: PASS

- [ ] **Step 7: Run full test suite**

Run: `mvn test --batch-mode`
Expected: PASS

- [ ] **Step 8: Commit**

```bash
git add runtime/src/main/java/io/casehub/ras/runtime/DefaultCaseTrigger.java runtime/src/test/java/io/casehub/ras/runtime/DefaultCaseTriggerTest.java
git commit -m "feat(casehub-ras#46): dynamic case data resolution in DefaultCaseTrigger with error handling"
```

---

### Task 8: Integration test — end-to-end with real expression engine

**Files:**
- Modify: `runtime/pom.xml` (add `casehub-platform-expression` test dependency)
- Create: `runtime/src/test/java/io/casehub/ras/runtime/ExpressionIntegrationTest.java`

**Interfaces:**
- Consumes: all previous tasks. Real `DefaultExpressionEngineRegistry` + JQ/MVEL engines
  on test classpath.
- Produces: verification that YAML → parse → compile → filter → correlate → trigger with
  dynamic data works end-to-end with real expression evaluation.

- [ ] **Step 1: Add casehub-platform-expression test dependency**

Add to `runtime/pom.xml`:

```xml
<dependency>
    <groupId>io.casehub</groupId>
    <artifactId>casehub-platform-expression</artifactId>
    <scope>test</scope>
</dependency>
```

- [ ] **Step 2: Write integration test**

```java
package io.casehub.ras.runtime;

import io.casehub.ras.api.*;
import io.casehub.ras.persistence.memory.InMemorySituationStore;
import io.casehub.ras.testing.MockCaseTrigger;
import io.casehub.ras.testing.MockGanglion;
import io.casehub.ras.testing.FixedDetectionResult;
import io.casehub.platform.expression.DefaultExpressionEngineRegistry;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

class ExpressionIntegrationTest {

    private static final CaseTriggerConfig TRIGGER =
            new CaseTriggerConfig("ns", "case", "1.0", Map.of("static", "value"));

    @Test
    void endToEnd_yamlExpressions_filterCorrelateAndDynamicData() {
        // 1. Parse YAML with all three expression features
        String yaml = """
                situations:
                  - situationId: sla-breach
                    eventTypes: [order.status]
                    correlationWindow: PT30M
                    correlationKey:
                      expression: ".data.orderId"
                      language: jq
                    eventFilter:
                      expression: "data.severity >= 3"
                      language: mvel
                    dynamicCaseData:
                      extractedOrder:
                        expression: ".correlationKey"
                        language: jq
                    chainMode:
                      type: or
                      ganglia: [sla-detector]
                    triggerAction:
                      type: create-case
                      caseNamespace: ns
                      caseName: case
                      caseVersion: "1.0"
                      baseCaseData:
                        static: value
                """;
        var provider = new YamlSituationDefinitionProvider(
                new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));

        // 2. Build registry with real expression engine
        var ganglion = new MockGanglion("sla-detector", Set.of("order.status"),
                FixedDetectionResult.detected("sla-detector", 0.95));
        var exprRegistry = new DefaultExpressionEngineRegistry();
        var registry = new SituationDefinitionRegistry(
                List.of(provider), List.of(ganglion), exprRegistry);

        // 3. Build engine
        var store = new InMemorySituationStore();
        var caseTrigger = new MockCaseTrigger();
        var metrics = new RasMetrics(registry);
        metrics.setMeterRegistry(new SimpleMeterRegistry());
        metrics.init();
        var evaluator = new SituationEvaluator(store, new DefaultRasTriggerPolicy(),
                caseTrigger, registry, 3, null, metrics);
        var engine = new RasEngine(registry, evaluator, metrics);

        // 4. Send event that passes filter (severity=5)
        CloudEvent passingEvent = CloudEventBuilder.v1()
                .withId("evt-1").withSource(URI.create("/test")).withType("order.status")
                .withSubject("order-ABC")
                .withTime(OffsetDateTime.now(ZoneOffset.UTC))
                .withExtension("tenancyid", "tenant-A")
                .withData("application/json",
                        "{\"orderId\":\"ORD-999\",\"severity\":5}".getBytes())
                .build();
        engine.onCloudEvent(passingEvent);

        // 5. Verify: case triggered with expression-derived correlation key
        assertThat(caseTrigger.firedCount()).isEqualTo(1);

        // 6. Send event that fails filter (severity=1)
        CloudEvent filteredEvent = CloudEventBuilder.v1()
                .withId("evt-2").withSource(URI.create("/test")).withType("order.status")
                .withSubject("order-DEF")
                .withTime(OffsetDateTime.now(ZoneOffset.UTC))
                .withExtension("tenancyid", "tenant-A")
                .withData("application/json",
                        "{\"orderId\":\"ORD-000\",\"severity\":1}".getBytes())
                .build();
        engine.onCloudEvent(filteredEvent);

        // 7. Verify: second event filtered — case count still 1
        assertThat(caseTrigger.firedCount()).isEqualTo(1);
    }
}
```

- [ ] **Step 3: Run integration test**

Run: `mvn -pl runtime test -Dtest=ExpressionIntegrationTest --batch-mode`
Expected: PASS

- [ ] **Step 4: Run full build**

Run: `mvn install --batch-mode`
Expected: PASS — all modules green

- [ ] **Step 5: Commit**

```bash
git add runtime/pom.xml runtime/src/test/java/io/casehub/ras/runtime/ExpressionIntegrationTest.java
git commit -m "feat(casehub-ras#46): end-to-end integration test with real expression engines"
```

---

### Task 9: CLAUDE.md and spec updates

**Files:**
- Modify: `CLAUDE.md`
- Modify: `docs/superpowers/specs/2026-07-17-expression-evaluator-integration-design.md`

**Interfaces:**
- Consumes: all implemented behavior
- Produces: updated documentation reflecting the new expression integration

- [ ] **Step 1: Update CLAUDE.md**

Add expression-related entries to Core SPIs, Core Types, YAML Situation Definitions,
and Module Structure sections. Key additions:
- `EventFilter` in Core SPIs
- `dynamicCaseData` on SituationDefinition in Core Types
- Expression fields in YAML Situation Definitions section
- `casehub-platform-expression` test dependency in runtime/ module description

- [ ] **Step 2: Update spec status**

Change `**Status:** Draft` to `**Status:** Implemented`

- [ ] **Step 3: Commit**

```bash
git add CLAUDE.md docs/superpowers/specs/2026-07-17-expression-evaluator-integration-design.md
git commit -m "docs(casehub-ras#46): update CLAUDE.md and spec for ExpressionEvaluator integration"
```
