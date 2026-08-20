# Rate Threshold Drift + Shared YAML Parsing

**Issues:** casehubio/casehub-ras#57, casehubio/casehub-ras#55
**Date:** 2026-08-20

## Problem

### #57 — Rate chain mode has no feedback-driven threshold drift

The feedback loop (#40) implements threshold drift for `ChainMode.Threshold` — `FeedbackUpdateJob` adjusts `minConfidence` based on outcome statistics, and `SituationEvaluator` applies the override at evaluation time. `ChainMode.Rate` has a structurally analogous tunable (`minRate`) but is not wired into the same mechanism. `DriftDirection.UNDER_SENSITIVE` and noise-rate-driven threshold shifts apply equally to Rate's `minRate`.

### #55 — IoT duplicated the YAML situation parser

`JpaRuntimeSituationDefinitionProvider` in casehub-iot contains a 150-line inner `YamlParser` class that duplicates core parsing logic from `YamlSituationDefinitionProvider`. The copy has diverged:
- Uses `triggerConfig` (flat `CaseTriggerConfig`) instead of `triggerAction` (sealed `TriggerAction`)
- Supports legacy `requiredGanglia` field name for And chain mode
- Missing `streak` and `rate` chain mode types
- No template, expression, ganglion descriptor, or feedback config support

## Solution

### #57 — Extend threshold drift to Rate

Two code changes in `runtime/`:

**1. `SituationEvaluator.processEvent()`** — Add `ChainMode.Rate` case alongside existing `ChainMode.Threshold` (line ~178):

```java
if (feedbackState != null
        && definition.chainMode() instanceof ChainMode.Threshold threshold) {
    OptionalDouble adjusted = feedbackState.effectiveThreshold(situationId, tenancyId);
    if (adjusted.isPresent()) {
        effectiveDef = definition.withChainMode(
                new ChainMode.Threshold(threshold.ganglia(), adjusted.getAsDouble()));
    }
} else if (feedbackState != null
        && definition.chainMode() instanceof ChainMode.Rate rate) {
    OptionalDouble adjusted = feedbackState.effectiveThreshold(situationId, tenancyId);
    if (adjusted.isPresent()) {
        effectiveDef = definition.withChainMode(
                new ChainMode.Rate(rate.ganglia(), adjusted.getAsDouble(), rate.windowSize()));
    }
}
```

**2. `FeedbackUpdateJob.processTenant()`** — Add `ChainMode.Rate` case alongside existing `ChainMode.Threshold` (line ~88):

```java
if (definition.chainMode() instanceof ChainMode.Threshold threshold) {
    double currentThreshold = feedbackState.effectiveThreshold(situationId, tenancyId)
            .orElse(threshold.minConfidence());
    // ... existing threshold adjustment
} else if (definition.chainMode() instanceof ChainMode.Rate rate) {
    double currentRate = feedbackState.effectiveThreshold(situationId, tenancyId)
            .orElse(rate.minRate());
    OptionalDouble adjusted = tuningStrategy.adjustThreshold(stats, currentRate, config);
    if (adjusted.isPresent()) {
        feedbackState.applyThresholdOverride(situationId, tenancyId, adjusted.getAsDouble());
        feedbackMetrics.thresholdAdjusted(situationId, tenancyId, adjusted.getAsDouble());
    }
}
```

**No changes needed to:**
- `FeedbackState` — `effectiveThreshold()` / `applyThresholdOverride()` are generic (keyed by situationId+tenancyId, store/return a `double`)
- `FeedbackTuningStrategy` — `adjustThreshold()` takes a `double currentThreshold` and returns `OptionalDouble` — semantics are identical for both tunables
- `DefaultTuningStrategy` — noise-rate-driven threshold shift applies equally to confidence thresholds and rate thresholds

### #55 — IoT delegates to `YamlSituationDefinitionProvider`

IoT's `JpaRuntimeSituationDefinitionProvider` stops maintaining its own parser. Changes:

**In casehub-iot:**

1. **Delete** the inner `YamlParser` class from `JpaRuntimeSituationDefinitionProvider`
2. **Replace** `parseYaml(InputStream)` with delegation to `YamlSituationDefinitionProvider`:

```java
private List<SituationRegistration> parseYaml(InputStream yaml) {
    return new YamlSituationDefinitionProvider(yaml).registrations();
}
```

`YamlSituationDefinitionProvider` already has an `InputStream` constructor (line 67-71) that parses and returns registrations.

3. **Migrate IoT YAML files** to canonical schema:
   - `triggerConfig:` → `triggerAction:` with `type: create-case` wrapper
   - `requiredGanglia:` → `ganglia:` (And chain mode)
   - Any missing chain modes (streak, rate) are now available

**Dependency verification:** IoT's `webapp` module must depend on `casehub-ras` (runtime). Check `webapp/pom.xml` — if it already has `casehub-ras` as a dependency (likely, since it runs `RasEngine`), no POM changes needed.

## Test Strategy

### #57 tests

1. **Rate threshold override applied in evaluator** — create a Rate situation, set a `FeedbackState` threshold override, verify the evaluator uses the adjusted `minRate`
2. **FeedbackUpdateJob adjusts Rate threshold** — provide outcome statistics with high noise rate, verify `feedbackState.effectiveThreshold()` is updated for a Rate situation
3. **Rate and Threshold on same registry don't interfere** — two situations (one Threshold, one Rate) with different overrides, verify isolation

### #55 tests

1. **IoT YAML loads via canonical parser** — verify IoT's classpath YAML files parse correctly through `YamlSituationDefinitionProvider`
2. **IoT gains streak and rate chain modes** — add streak/rate definitions to IoT test YAML, verify they parse
3. **JPA overlay still works** — verify database definitions still override classpath definitions after parser swap

## References

- `SituationEvaluator.processEvent()` lines 178-186 — existing Threshold override
- `FeedbackUpdateJob.processTenant()` lines 88-96 — existing Threshold adjustment
- `FeedbackState` — generic threshold override storage
- `DefaultRasTriggerPolicy.evaluateRate()` lines 128-147 — Rate evaluation using `minRate`
- `ChainMode.Rate` record — `ganglia`, `minRate`, `windowSize`
- `YamlSituationDefinitionProvider(InputStream)` constructor — line 67
- IoT `JpaRuntimeSituationDefinitionProvider` — full source read
- casehubio/casehub-ras#57, casehubio/casehub-ras#55
- casehubio/casehub-ras#40 — feedback loop (parent feature)
