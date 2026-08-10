# Epic 3: JavaSwitchGanglion & NaiveBayesGanglion — Design Spec

**Date:** 2026-06-26 (revised 2026-06-26, third pass)
**Status:** Approved
**Issue:** casehubio/casehub-ras#3
**Depends on:** #1 (Core RAS API — done), #2 (RAS Runtime — done)

---

## 1. Scope

Implement two built-in `Ganglion` implementations — zero external dependencies, pure Java:

- **JavaSwitchGanglion** — abstract base class in `api/`. Developers subclass and write
  synchronous detection logic using Java pattern matching, switch expressions, or predicate
  chains. The fastest path. Intended as the first ganglion developers implement for a new domain.

- **NaiveBayesGanglion** — concrete class in `runtime/`. Configured with prior probabilities,
  per-feature likelihood tables, and a feature extraction function. Incrementally accumulates
  posteriors across `detect()` calls using Naive Bayes (conditional independence assumption).
  Implements `compact()` to collapse running posteriors into a single detection.

**Also in scope:**
- Add `Double.isNaN(confidence)` check to `DetectionResult` validation — pre-existing gap
  in the API that affects all ganglia producing NaN. Not specific to this epic but discovered
  during design.
- Refactor `GanglionContractTest` to abstract base class — enables contract verification
  across all Ganglion implementations.

**Not in scope:** Full Bayesian network extraction from `drools-beliefs`. CloudEvent payload
extraction utilities. Persistent BayesState storage. YAML-based NaiveBayesConfig. Temporal
decay on posteriors.

---

## 2. JavaSwitchGanglion

### 2.1 Module and package

**Module:** `api/` — `io.casehub.ras.api`

This is an abstract extension point for consumers. Placing it in `api/` means consumers
depend only on `casehub-ras-api`, not on the full runtime. Same rationale as `AbstractList`
in `java.util` — a convenience adapter for the core SPI.

No new dependencies — uses only types already in `api/`: `Ganglion`, `DetectionResult`,
`DetectionSignal`, `SituationContext`, `CloudEvent`, `Uni`.

### 2.2 Design decisions

**No type parameter.** The class is not parameterized on a payload type `<T>`. Reasons:

- Multiple event types with different payloads → single `<T>` forces `Object` and casts
- Headers-only detection → `Void` as `T` is ugly
- Context-dependent detection → `T` is unused
- Payload deserialization is application-specific (JSON, protobuf, raw bytes) — the base
  class cannot own it without introducing format assumptions and dependencies

The "typed predicate logic" described in the issue refers to what the developer writes inside
`evaluate()` — pattern matching on typed domain objects obtained through their own extraction.

**No payload extraction.** The developer handles deserialization in `evaluate()`. This is one
line of application-specific code. Trying to generalize it (Jackson dependency, `Class<T>`
parameter, custom deserializer function) introduces more problems than it solves.

**Synchronous contract.** `detect()` is `final` — it calls `evaluate()` and wraps the result
in `Uni`. JavaSwitchGanglion is for deterministic, in-process detection. Developers needing
async I/O should implement `Ganglion` directly.

**Stateless.** `compact()` and `close()` inherit the Ganglion defaults (no-op). No internal
state to manage.

### 2.3 Class definition

```java
public abstract class JavaSwitchGanglion implements Ganglion {

    private final String ganglionId;
    private final Set<String> handledEventTypes;

    protected JavaSwitchGanglion(String ganglionId, Set<String> handledEventTypes) {
        this.ganglionId = Objects.requireNonNull(ganglionId, "ganglionId");
        if (handledEventTypes == null || handledEventTypes.isEmpty()) {
            throw new IllegalArgumentException("handledEventTypes must not be empty");
        }
        this.handledEventTypes = Set.copyOf(handledEventTypes);
    }

    @Override public final String ganglionId() { return ganglionId; }
    @Override public final Set<String> handledEventTypes() { return handledEventTypes; }

    protected abstract DetectionResult evaluate(CloudEvent event, SituationContext context);

    @Override
    public final Uni<DetectionResult> detect(CloudEvent event, SituationContext context) {
        return Uni.createFrom().item(evaluate(event, context));
    }

    protected DetectionResult detected(double confidence, Map<String, Object> evidence) {
        return new DetectionResult(ganglionId, confidence, DetectionSignal.DETECTED, evidence);
    }

    protected DetectionResult detected(double confidence) {
        return detected(confidence, Map.of());
    }

    protected DetectionResult weak(double confidence, Map<String, Object> evidence) {
        return new DetectionResult(ganglionId, confidence, DetectionSignal.WEAK, evidence);
    }

    protected DetectionResult weak(double confidence) {
        return weak(confidence, Map.of());
    }

    protected DetectionResult noise() {
        return new DetectionResult(ganglionId, 0.0, DetectionSignal.NOISE, Map.of());
    }

    protected DetectionResult anti(double confidence, Map<String, Object> evidence) {
        return new DetectionResult(ganglionId, confidence, DetectionSignal.ANTI, evidence);
    }

    protected DetectionResult anti(double confidence) {
        return anti(confidence, Map.of());
    }
}
```

### 2.4 Consumer usage

```java
class TempGanglion extends JavaSwitchGanglion {
    TempGanglion() { super("temp-g", Set.of("iot.temp")); }

    @Override
    protected DetectionResult evaluate(CloudEvent event, SituationContext ctx) {
        var r = Json.fromCloudEvent(event, TemperatureReading.class);
        return switch (r) {
            case TemperatureReading t when t.celsius() > 80 ->
                detected(0.85, Map.of("celsius", t.celsius()));
            case TemperatureReading t when t.celsius() > 60 ->
                weak(0.40, Map.of("celsius", t.celsius()));
            default -> noise();
        };
    }
}
```

Wired via CDI `@Produces` (same pattern as DroolsGanglion):

```java
@Produces @ApplicationScoped @Startup
Ganglion tempGanglion() { return new TempGanglion(); }
```

### 2.5 Relationship to EPHEMERAL DroolsGanglion

Both JavaSwitchGanglion and EPHEMERAL DroolsGanglion serve stateless detection. They coexist:

| Need | JavaSwitchGanglion | EPHEMERAL DroolsGanglion |
|------|-------------------|--------------------------|
| Simple threshold/predicate | Preferred — zero deps, fastest | Overkill |
| Multi-field pattern match | Java 21 patterns handle natively | Works but heavy |
| Complex accumulate patterns | Verbose in Java | DRL shines |
| Large rule sets (50+) | Unwieldy | DRL composition |
| Zero-dep constraint | Fits | Fails — needs Drools |

JavaSwitchGanglion is the preferred path for stateless detection. EPHEMERAL DroolsGanglion is
reserved for cases where DRL's declarative power is genuinely needed.

---

## 3. NaiveBayesGanglion

### 3.1 Module and package

**Module:** `runtime/` — `io.casehub.ras.runtime`

Concrete implementation with internal state management (ConcurrentHashMap for per-situation
posteriors). This is implementation behavior, not SPI — belongs in runtime, not api.

No external dependencies — pure Java math.

### 3.2 Design decisions

**Naive Bayes, not full Bayesian.** The RAS detection use case is analogous to spam
classification: multiple independent signals accumulate evidence for/against a situation.
Conditional independence between features holds for most detection patterns. The existing
ChainMode composition (AND, OR, Threshold, Sequence, Count) already handles cross-signal
correlation at the situation level — within a single ganglion, signals are independently
informative.

A full Bayesian network (junction tree inference, causal DAG modelling) exists in
`drools-beliefs` and can be extracted to a standalone module in a future epic if causal
modelling within a single ganglion is needed.

**Concrete class, configured.** The extensibility mechanism is configuration (prior/likelihood
tables, feature extractor), not subclassing. The Bayesian math is fixed — the developer
provides the probabilistic model as data. Same pattern as DroolsGanglion (configured with
DRL rules, not subclassed).

**Log-space arithmetic.** Posteriors are stored and updated as log-probabilities. Multiplying
many small probabilities (0.01 * 0.05 * ...) underflows to zero in linear space. In log
space: log(0.01) + log(0.05) + ... stays numerically stable. Log-sum-exp normalization
converts back to probabilities when needed.

**Internal ConcurrentHashMap for state.** The per-situation state is a `double[]` of
log-posteriors — trivially small and trivially serializable. No SPI needed. If disk backing
is required later, the state serializes to JSON/binary without any interface changes.

**Feature extraction is part of config.** Unlike `DroolsObjectExtractor` (loosely coupled
CDI beans), the feature extractor is tightly coupled to the likelihood tables — changing
features means changing tables. It belongs in the config, not as a separate SPI.

**compact() is a first-class operation.** NaiveBayes produces running posteriors — each
`detect()` result subsumes all prior observations. The detection list in `SituationContext`
grows linearly, but the ganglion's internal state is fixed-size O(outcomes). `compact()`
collapses all detections from this ganglion to a single detection reflecting the current
posterior. This is architecturally correct and necessary for correct interaction with
Threshold ChainMode (§3.7).

**Threading contract.** `detect()` mutates the per-situation `double[]` in-place without
internal synchronization. Same-key safety relies on `SituationEvaluator`'s striped lock
(consistent with `DroolsGanglion` — `KieSession` is also not thread-safe per-key). The
runtime serializes all ganglion operations per `(situationId, correlationKey, tenancyId)`.
Cross-situation concurrency is safe — different keys use different `double[]` arrays.

### 3.3 Config types

```java
public record NaiveBayesConfig(
        String ganglionId,
        Set<String> handledEventTypes,
        List<String> outcomes,
        double[] priors,
        Map<String, FeatureLikelihood> features,
        BayesFeatureExtractor featureExtractor,
        BayesSignalMapping signalMapping
) {
    public NaiveBayesConfig {
        Objects.requireNonNull(ganglionId, "ganglionId");
        if (handledEventTypes == null || handledEventTypes.isEmpty()) {
            throw new IllegalArgumentException("handledEventTypes must not be empty");
        }
        handledEventTypes = Set.copyOf(handledEventTypes);
        if (outcomes == null || outcomes.size() < 2) {
            throw new IllegalArgumentException("outcomes must have at least 2 entries");
        }
        outcomes = List.copyOf(outcomes);
        Objects.requireNonNull(priors, "priors");
        if (priors.length != outcomes.size()) {
            throw new IllegalArgumentException(
                    "priors length (" + priors.length
                    + ") must match outcomes size (" + outcomes.size() + ")");
        }
        priors = Arrays.copyOf(priors, priors.length);
        for (int i = 0; i < priors.length; i++) {
            if (Double.isNaN(priors[i]) || priors[i] <= 0.0) {
                throw new IllegalArgumentException(
                        "priors[" + i + "] must be > 0.0 and not NaN, got: " + priors[i]
                        + " — zero priors make outcomes permanently impossible");
            }
        }
        double sum = Arrays.stream(priors).sum();
        if (Math.abs(sum - 1.0) > 1e-6) {
            throw new IllegalArgumentException("priors must sum to 1.0, got: " + sum);
        }
        features = Map.copyOf(features);
        for (var entry : features.entrySet()) {
            if (entry.getValue().likelihoods().length != outcomes.size()) {
                throw new IllegalArgumentException(
                        "Feature '" + entry.getKey() + "' has "
                        + entry.getValue().likelihoods().length
                        + " likelihood rows but there are " + outcomes.size() + " outcomes");
            }
        }
        Objects.requireNonNull(featureExtractor, "featureExtractor");
        Objects.requireNonNull(signalMapping, "signalMapping");
        int targetIndex = outcomes.indexOf(signalMapping.targetOutcome());
        if (targetIndex < 0) {
            throw new IllegalArgumentException(
                    "targetOutcome '" + signalMapping.targetOutcome() + "' not in outcomes");
        }
    }
}
```

```java
public record FeatureLikelihood(
        List<String> values,
        double[][] likelihoods   // [outcome_index][value_index] = P(value | outcome)
) {
    public FeatureLikelihood {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("values must not be empty");
        }
        values = List.copyOf(values);
        Objects.requireNonNull(likelihoods, "likelihoods");
        for (int i = 0; i < likelihoods.length; i++) {
            if (likelihoods[i].length != values.size()) {
                throw new IllegalArgumentException(
                        "likelihoods row " + i + " length (" + likelihoods[i].length
                        + ") must match values size (" + values.size() + ")");
            }
            for (int j = 0; j < likelihoods[i].length; j++) {
                if (Double.isNaN(likelihoods[i][j]) || likelihoods[i][j] <= 0.0) {
                    throw new IllegalArgumentException(
                            "likelihoods[" + i + "][" + j + "] must be > 0.0 and not NaN, got: "
                            + likelihoods[i][j]
                            + " — apply Laplace smoothing to avoid zero probabilities");
                }
            }
        }
        likelihoods = Arrays.stream(likelihoods)
                .map(row -> Arrays.copyOf(row, row.length))
                .toArray(double[][]::new);
    }
}
```

```java
@FunctionalInterface
public interface BayesFeatureExtractor {
    Map<String, String> extract(CloudEvent event);
}
```

```java
public record BayesSignalMapping(
        String targetOutcome,
        double detectedThreshold,
        double weakThreshold,
        Double antiThreshold
) {
    public BayesSignalMapping {
        Objects.requireNonNull(targetOutcome, "targetOutcome");
        if (Double.isNaN(detectedThreshold) || detectedThreshold <= weakThreshold) {
            throw new IllegalArgumentException(
                    "detectedThreshold (" + detectedThreshold
                    + ") must be > weakThreshold (" + weakThreshold + ") and not NaN");
        }
        if (Double.isNaN(weakThreshold) || weakThreshold <= 0) {
            throw new IllegalArgumentException(
                    "weakThreshold must be > 0 and not NaN, got: " + weakThreshold);
        }
        if (detectedThreshold > 1.0) {
            throw new IllegalArgumentException(
                    "detectedThreshold must be <= 1.0, got: " + detectedThreshold);
        }
        if (weakThreshold > 1.0) {
            throw new IllegalArgumentException(
                    "weakThreshold must be <= 1.0, got: " + weakThreshold);
        }
        if (antiThreshold != null) {
            if (Double.isNaN(antiThreshold) || antiThreshold <= 0) {
                throw new IllegalArgumentException(
                        "antiThreshold must be > 0 and not NaN when set, got: " + antiThreshold);
            }
            if (antiThreshold >= weakThreshold) {
                throw new IllegalArgumentException(
                        "antiThreshold (" + antiThreshold
                        + ") must be < weakThreshold (" + weakThreshold + ")");
            }
        }
    }

    public BayesSignalMapping(String targetOutcome, double detectedThreshold,
                              double weakThreshold) {
        this(targetOutcome, detectedThreshold, weakThreshold, null);
    }
}
```

### 3.4 Core class

```java
public class NaiveBayesGanglion implements Ganglion {

    private record StateKey(String situationId, String correlationKey, String tenancyId) {}

    private final NaiveBayesConfig config;
    private final double[] logPriors;
    private final int targetIndex;
    private final ConcurrentHashMap<StateKey, double[]> states;

    public NaiveBayesGanglion(NaiveBayesConfig config) {
        this.config = config;
        this.logPriors = Arrays.stream(config.priors()).map(Math::log).toArray();
        this.targetIndex = config.outcomes().indexOf(config.signalMapping().targetOutcome());
        this.states = new ConcurrentHashMap<>();
    }

    @Override
    public String ganglionId() { return config.ganglionId(); }

    @Override
    public Set<String> handledEventTypes() { return config.handledEventTypes(); }

    @Override
    public Uni<DetectionResult> detect(CloudEvent event, SituationContext context) {
        var key = new StateKey(context.situationId(), context.correlationKey(), context.tenancyId());
        double[] logPosteriors = states.computeIfAbsent(key,
                k -> Arrays.copyOf(logPriors, logPriors.length));

        Map<String, String> observed = config.featureExtractor().extract(event);
        for (var entry : observed.entrySet()) {
            FeatureLikelihood fl = config.features().get(entry.getKey());
            if (fl == null) continue;
            int valueIndex = fl.values().indexOf(entry.getValue());
            if (valueIndex < 0) continue;
            for (int i = 0; i < logPosteriors.length; i++) {
                logPosteriors[i] += Math.log(fl.likelihoods()[i][valueIndex]);
            }
        }

        double[] posteriors = normalizeLogPosteriors(logPosteriors);
        double targetPosterior = posteriors[targetIndex];

        DetectionSignal signal;
        double confidence;
        BayesSignalMapping mapping = config.signalMapping();

        if (targetPosterior >= mapping.detectedThreshold()) {
            signal = DetectionSignal.DETECTED;
            confidence = targetPosterior;
        } else if (targetPosterior >= mapping.weakThreshold()) {
            signal = DetectionSignal.WEAK;
            confidence = targetPosterior;
        } else if (mapping.antiThreshold() != null
                   && targetPosterior <= mapping.antiThreshold()) {
            signal = DetectionSignal.ANTI;
            confidence = 1.0 - targetPosterior;
        } else {
            signal = DetectionSignal.NOISE;
            confidence = 0.0;
        }

        var evidence = Map.<String, Object>of(
                "posterior", targetPosterior, "features", Map.copyOf(observed));
        return Uni.createFrom().item(
                new DetectionResult(config.ganglionId(), confidence, signal, evidence));
    }

    @Override
    public Uni<SituationContext> compact(SituationContext context) {
        // The detection list is in processing order (SituationContext.withDetection appends).
        // Under out-of-order event delivery (@ObservesAsync provides no ordering guarantee),
        // the last detection from this ganglion in the list always has the most complete
        // posterior — it was computed last and incorporates all prior events. Selecting by
        // eventTime would pick the wrong detection when events arrive out of temporal order.
        TimestampedDetection latest = null;
        List<TimestampedDetection> kept = new ArrayList<>();
        for (TimestampedDetection td : context.detections()) {
            if (td.result().ganglionId().equals(config.ganglionId())) {
                latest = td;
            } else {
                kept.add(td);
            }
        }
        if (latest == null) {
            return Uni.createFrom().item(context);
        }
        kept.add(latest);
        return Uni.createFrom().item(new SituationContext(
                context.situationId(), context.correlationKey(), context.tenancyId(),
                context.firstSignal(), context.lastSignal(), kept));
    }

    @Override
    public Uni<Void> close(String situationId, String correlationKey, String tenancyId) {
        states.remove(new StateKey(situationId, correlationKey, tenancyId));
        return Uni.createFrom().voidItem();
    }

    private static double[] normalizeLogPosteriors(double[] logP) {
        double max = logP[0];
        for (int i = 1; i < logP.length; i++) {
            if (logP[i] > max) max = logP[i];
        }
        double[] exp = new double[logP.length];
        double sum = 0;
        for (int i = 0; i < logP.length; i++) {
            exp[i] = Math.exp(logP[i] - max);
            sum += exp[i];
        }
        for (int i = 0; i < exp.length; i++) {
            exp[i] /= sum;
        }
        return exp;
    }
}
```

### 3.5 Consumer wiring

```java
@Produces @ApplicationScoped @Startup
Ganglion amlBayesGanglion() {
    return new NaiveBayesGanglion(new NaiveBayesConfig(
            "aml-bayes",
            Set.of("transaction.completed"),
            List.of("LEGITIMATE", "SUSPICIOUS"),
            new double[]{0.99, 0.01},
            Map.of(
                "amount_range", new FeatureLikelihood(
                    List.of("LOW", "MEDIUM", "HIGH"),
                    new double[][]{{0.7, 0.25, 0.05}, {0.1, 0.3, 0.6}}),
                "country_risk", new FeatureLikelihood(
                    List.of("LOW", "MEDIUM", "HIGH"),
                    new double[][]{{0.8, 0.15, 0.05}, {0.2, 0.3, 0.5}})
            ),
            event -> extractTransactionFeatures(event),
            new BayesSignalMapping("SUSPICIOUS", 0.75, 0.30, 0.05)
    ));
}
```

### 3.6 Lifecycle

- **State per situation:** `double[]` of log-posteriors, keyed by `(situationId, correlationKey, tenancyId)`
- **compact():** Collapses all detections from this ganglion to a single detection: the latest
  posterior. Other ganglia's detections are preserved. Called by `SituationEvaluator` after
  `CONTINUE_ACCUMULATING` for persistent situations (`correlationWindow == null`). This is the
  first ganglion to implement `compact()` as a real mechanism, demonstrating its design purpose.
- **close():** Removes state for terminated situations. Called by the runtime when a situation
  terminates (CREATE_CASE or DISCARD).

### 3.7 Interaction with Threshold ChainMode

`DefaultRasTriggerPolicy.evaluateThreshold()` sums all qualifying confidence values from
`SituationContext.detections`. NaiveBayes produces running posteriors — each result subsumes
all prior observations. Without compaction, five observations producing posteriors
[0.2, 0.3, 0.4, 0.55, 0.72] sum to ~1.97 when the actual current state is 0.72.

**For persistent situations:** `compact()` resolves this. After each `CONTINUE_ACCUMULATING`,
the evaluator calls `compact()`, which collapses to a single detection with the current
posterior. Threshold sees one value: 0.72.

**For windowed situations:** `compact()` is not called (windowed situations skip compaction).
The summing problem remains within the window duration. Consumers should prefer `Or` or
`Count` chain modes with NaiveBayes in windowed situations, or set `minConfidence` to account
for running posterior accumulation.

**ANTI interaction:** When `antiThreshold` is configured and the posterior drops below it,
NaiveBayes emits ANTI with confidence `1.0 - targetPosterior`. In a Threshold chain,
Threshold subtracts ANTI confidence from the sum. This allows NaiveBayes to express
counter-evidence: strong evidence against the target outcome actively reduces the
Threshold sum.

---

## 4. DetectionResult NaN guard

Pre-existing gap: `DetectionResult` validates `confidence < 0.0 || confidence > 1.0` but
`NaN` comparisons are all false in IEEE 754 — `NaN` passes silently. Any ganglion producing
`NaN` (via division errors, log underflow, etc.) creates a `DetectionResult` with undefined
behaviour that propagates into Threshold sums, signal comparisons, and evidence.

**Fix:** Add `Double.isNaN(confidence)` to the existing validation:

```java
if (Double.isNaN(confidence) || confidence < 0.0 || confidence > 1.0) {
    throw new IllegalArgumentException("confidence must be 0.0–1.0, got: " + confidence);
}
```

This is a defensive correction to the existing API, not specific to this epic. It protects
against any ganglion implementation producing NaN.

**Principle: validate at the input boundary.** NaiveBayesGanglion also validates all numeric
config values for NaN at construction time (priors, likelihoods, thresholds). Config errors
are caught once at startup, not per-event at detect()-time. The `DetectionResult` NaN guard
is the output boundary — catches bugs in any ganglion's math, not just configuration errors.

---

## 5. GanglionContractTest refactoring

The existing `GanglionContractTest` is concrete with private factory methods — not designed
for subclassing. Refactor to an abstract base class that any `Ganglion` implementation can
extend to verify SPI contract compliance.

### 5.1 Abstract contract test

```java
public abstract class AbstractGanglionContractTest {

    protected abstract Ganglion createGanglion();

    protected abstract CloudEvent createTestEvent();

    @Test
    void ganglionIdIsNonNull() {
        assertThat(createGanglion().ganglionId()).isNotNull();
    }

    @Test
    void handledEventTypesIsNonEmpty() {
        assertThat(createGanglion().handledEventTypes()).isNotEmpty();
    }

    @Test
    void detectReturnsCompletingUni() {
        Ganglion ganglion = createGanglion();
        var ctx = SituationContext.initial("sit-1", "key-1", "tenant-a",
                Instant.parse("2026-06-20T10:00:00Z"));
        DetectionResult result = ganglion.detect(createTestEvent(), ctx)
                .await().indefinitely();
        assertThat(result).isNotNull();
        assertThat(result.ganglionId()).isEqualTo(ganglion.ganglionId());
    }

    @Test
    void compactReturnsNonNullContext() {
        var ctx = SituationContext.initial("sit-1", "key-1", "tenant-a",
                Instant.parse("2026-06-20T10:00:00Z"));
        SituationContext compacted = createGanglion().compact(ctx).await().indefinitely();
        assertThat(compacted).isNotNull();
    }

    @Test
    void closeCompletes() {
        Void result = createGanglion().close("sit-1", "key-1", "tenant-a")
                .await().indefinitely();
        assertThat(result).isNull();
    }
}
```

### 5.2 Cross-module visibility via test-jar

`AbstractGanglionContractTest` lives in `api/src/test/`. Maven test classes are not visible
across modules by default. To enable `runtime/src/test/` and `ras-drools/src/test/` to extend
it, `api/` publishes a test-jar:

**api/pom.xml** — add maven-jar-plugin with test-jar goal:
```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-jar-plugin</artifactId>
    <executions>
        <execution>
            <goals><goal>test-jar</goal></goals>
        </execution>
    </executions>
</plugin>
```

**Consuming modules** (runtime/pom.xml, ras-drools/pom.xml) — add test-jar dependency:
```xml
<dependency>
    <groupId>io.casehub</groupId>
    <artifactId>casehub-ras-api</artifactId>
    <type>test-jar</type>
    <scope>test</scope>
</dependency>
```

This is the standard Maven pattern for shared test infrastructure. No cycles — testing/
module option was rejected because it would create api → testing → api.

### 5.3 Implementation subclasses

Each ganglion implementation provides a concrete subclass: `JavaSwitchGanglionContractTest`
(in `api/src/test/`), `NaiveBayesGanglionContractTest` (in `runtime/src/test/`),
`DroolsGanglionContractTest` (in `ras-drools/src/test/`). Each supplies `createGanglion()`
and `createTestEvent()` for its implementation.

The existing `GanglionContractTest` is replaced by the abstract base plus a concrete
subclass that uses the anonymous `Ganglion` implementation (testing the interface defaults).

---

## 6. Public Types Summary

### api/ module (1 new type + 1 refactored test)

| Type | Kind | Purpose |
|------|------|---------|
| `JavaSwitchGanglion` | abstract class | Synchronous detection base class — subclass and override `evaluate()` |
| `AbstractGanglionContractTest` | abstract test class | SPI contract verification base — replaces `GanglionContractTest` |

### runtime/ module (5 new types)

| Type | Kind | Purpose |
|------|------|---------|
| `NaiveBayesGanglion` | class | Concrete Naive Bayes ganglion — configured, stateful, implements `compact()` |
| `NaiveBayesConfig` | record | Immutable configuration — priors, likelihoods, feature extractor, signal mapping |
| `FeatureLikelihood` | record | Per-feature likelihood table — values and conditional probabilities (> 0.0) |
| `BayesFeatureExtractor` | functional interface | CloudEvent → observed feature-value pairs |
| `BayesSignalMapping` | record | Posterior → DetectionSignal threshold mapping with optional ANTI |

---

## 7. Testing

### 7.1 JavaSwitchGanglion tests (api/src/test/)

- `JavaSwitchGanglionContractTest` — extends `AbstractGanglionContractTest`, verifies SPI contract
- Subclass with trivial detection logic — verify `evaluate()` result propagates through `detect()`
- `ganglionId()` and `handledEventTypes()` return constructor values
- Result helpers: `detected()`, `weak()`, `noise()`, `anti()` — correct signal, confidence, ganglionId in each
- `detected(confidence, evidence)` — evidence map propagated
- Null ganglionId rejected
- Null/empty handledEventTypes rejected
- `evaluate()` returning null — propagates (developer bug, not swallowed)

### 7.2 NaiveBayesGanglion tests (runtime/src/test/)

- `NaiveBayesGanglionContractTest` — extends `AbstractGanglionContractTest`, verifies SPI contract
- Single feature, single observation — posterior shifts toward correct outcome
- Multiple features — posteriors combine correctly (independent log-space addition)
- Multiple observations on same situation — state accumulates incrementally across `detect()` calls
- Unknown feature name in extraction — silently ignored, no update
- Unknown feature value in extraction — silently ignored, no update
- Missing features — only observed features update posteriors
- `close()` removes state — subsequent `detect()` starts from priors
- Signal mapping thresholds — correct DETECTED/WEAK/NOISE at boundary values
- ANTI signal — posterior below antiThreshold emits ANTI with confidence `1.0 - posterior`
- ANTI disabled — null antiThreshold, low posterior maps to NOISE not ANTI
- Numerical stability — 100+ observations don't underflow (log-space arithmetic)
- Concurrent situations — independent state per (situationId, correlationKey, tenancyId)
- compact() — collapses this ganglion's detections to last in processing order, preserves other ganglia's
- compact() under out-of-order events — retains last-processed detection (most complete posterior),
  not the one with the latest eventTime
- compact() with no detections from this ganglion — returns context unchanged
- Config validation — priors must sum to ~1.0, individual priors > 0.0, likelihoods row count must
  match outcome count, likelihood values must be > 0.0, at least 2 outcomes,
  detectedThreshold > weakThreshold > 0, both thresholds ≤ 1.0, targetOutcome must exist in outcomes,
  antiThreshold < weakThreshold when set
- NaN config rejection — NaN in priors, likelihoods, detectedThreshold, weakThreshold, antiThreshold
  all rejected at config construction time (not deferred to detect()-time)

### 7.3 DetectionResult NaN test (api/src/test/)

- `DetectionResult` with `NaN` confidence — throws `IllegalArgumentException`

---

## 8. YAML Situation Definitions

No changes. `YamlSituationDefinitionProvider` already supports all five `ChainMode` variants.
JavaSwitchGanglion and NaiveBayesGanglion participate in situations through ChainMode like any
other ganglion. The ganglia are wired via CDI `@Produces`; YAML declares situations and which
ganglion IDs participate.

---

## 9. Deferred Items

| Item | Reason |
|------|--------|
| CloudEvent payload extraction utilities | Application-specific — developer handles deserialization |
| Persistent BayesState storage | State is trivially serializable (`double[]`); ConcurrentHashMap sufficient for now |
| Full Bayesian network ganglion | drools-beliefs extraction is a separate, larger epic |
| YAML-based NaiveBayesConfig | Config is Java-only for now via `@Produces` |
| Temporal decay on NaiveBayes posteriors | Future config option — discount old evidence over time |
| Windowed-situation compact | compact() only runs for persistent situations; windowed NaiveBayes+Threshold needs consumer awareness |

---

## 10. CLAUDE.md Updates

Update the Core SPIs section to add `JavaSwitchGanglion`. Update the Module Structure table
to note both new types. Update the Key Rules section to note that JavaSwitchGanglion lives
in api/ (abstract extension point) while NaiveBayesGanglion lives in runtime/ (concrete
implementation). Update `DetectionResult` entry in Core Types to note NaN validation.
