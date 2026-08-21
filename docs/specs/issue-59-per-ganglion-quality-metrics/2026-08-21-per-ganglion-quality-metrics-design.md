# Per-Ganglion Detection Quality Metrics Design

**Issue:** casehubio/casehub-ras#59
**Date:** 2026-08-21
**Status:** Draft

## Problem

The feedback loop (#40) computes detection quality metrics (precision, noise rate)
per `(situationId, tenancyId)`. A situation with multiple ganglia (e.g., threshold
mode summing across a Bayes classifier and a rule engine) triggers as a unit — the
outcome reflects the combined detection, not any individual ganglion.

Operators cannot determine which ganglion is driving noise. A situation with 80%
noise rate might have one excellent ganglion and one terrible one — but the current
metrics cannot distinguish.

## Approach

Capture per-ganglion detection contributions when recording outcomes, compute
per-ganglion precision/noise rate, publish as Micrometer gauges. Purely
observational — no automated tuning response.

The detection breakdown already flows through the system: `SituationContext.detections()`
carries `List<TimestampedDetection>` with per-ganglion `DetectionResult`. `DefaultCaseTrigger`
puts this into the case file. `CaseOutcomeEvent.caseFileSnapshot()` carries it back
at outcome time. The recording path (`OutcomeRecorder`) currently ignores this data.

## Key Design Decisions

- **Positive contributions only (D1)** — per-ganglion precision counts only outcomes
  where the ganglion contributed DETECTED or WEAK signal. NOISE (inactive) and ANTI
  (counter-signal) are excluded from quality metrics. ANTI contributions are stored
  in the JSONB when they are the ganglion's highest signal — ganglia that also
  produced WEAK or DETECTED lose their ANTI record through deduplication (D3).
  The filtering to positive signals happens at query time, not storage time.
  Per-ganglion recall is deferred to #58 (requires false-negative signals that RAS
  cannot compute from its own outcome data).
- **JSONB column on existing table (D2)** — `ganglion_contributions JSONB` on
  `ras_outcome_record`. Keeps write path atomic (single INSERT ON CONFLICT), cleanup
  simple (DELETE parent deletes everything). Aggregation via `jsonb_array_elements` in
  the 5-minute batch job. This is a new SQL pattern in this codebase (existing JSONB
  usage in `SituationEntity` is opaque blob read/write) but standard PostgreSQL.
- **Deduplicate at recording time (D3)** — `OutcomeRecorder` collapses raw detections
  to one entry per ganglionId, keeping the highest signal, breaking ties by highest
  confidence. Bounded JSONB size, simpler aggregation queries.
- **Observational gauges only (D4)** — publish `ras.feedback.ganglion.precision` and
  `ras.feedback.ganglion.noise_rate`. No automated tuning action. Per-ganglion tuning
  is a separate design problem deferred to a follow-up issue.

## New Types (api/)

### GanglionContribution

Compact per-ganglion summary for outcome attribution:

```java
record GanglionContribution(
    String ganglionId,
    double confidence,
    DetectionSignal signal
) {
    public GanglionContribution {
        Objects.requireNonNull(ganglionId, "ganglionId");
        Objects.requireNonNull(signal, "signal");
        if (Double.isNaN(confidence) || confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be 0.0-1.0, got: " + confidence);
        }
    }
}
```

One entry per unique ganglionId per outcome. Stored as JSONB array in
`ras_outcome_record.ganglion_contributions`.

### GanglionOutcomeStatistics

Per-ganglion aggregate within a retention window:

```java
record GanglionOutcomeStatistics(
    String ganglionId,
    long totalOutcomes,
    long noiseCount,
    long confirmedCount,
    long neutralCount
) {
    public double precision() {
        long decisive = confirmedCount + noiseCount;
        return decisive == 0 ? Double.NaN : (double) confirmedCount / decisive;
    }

    public double noiseRate() {
        return totalOutcomes == 0 ? Double.NaN : (double) noiseCount / totalOutcomes;
    }
}
```

Same computation formulas as `OutcomeStatistics`. Only counts outcomes where the
ganglion's contribution signal was DETECTED or WEAK.

## Changes to Existing Types

| Type | Change |
|------|--------|
| `OutcomeRecord` | New `List<GanglionContribution> ganglionContributions` field. Existing 7-arg constructor preserved (defaults to `List.of()`). New 8-arg constructor. Null-safe: compact constructor defaults null to `List.of()`. |
| `OutcomeLedger` | New `Map<String, GanglionOutcomeStatistics> ganglionStatistics(String situationId, String tenancyId, Instant since)` method. Returns map keyed by ganglionId. Only counts positive-signal contributions. |
| `OutcomeRecordEntity` | New `ganglion_contributions` JSONB column. Nullable. |
| `OutcomeRecorder` | Extracts detection breakdown from `caseFileSnapshot()`, deduplicates by ganglionId, passes to `OutcomeRecord`. |
| `FeedbackMetrics` | New `recordGanglionStatistics()` method publishing per-ganglion gauges. |
| `FeedbackUpdateJob` | Calls `ledger.ganglionStatistics()` per tenant, publishes to `FeedbackMetrics`. |

| `FeedbackAnalyzer` | New `Map<String, GanglionOutcomeStatistics> ganglionAnalyze(String situationId, String tenancyId, FeedbackConfig config)` method. Routes per-ganglion stats through the analysis layer for consistency with situation-level path. |

Note: `OutcomeStatistics`, `FeedbackState`, `DefaultRasTriggerPolicy`,
`SituationEvaluator`, `DefaultSuppressionStrategy`, and `DefaultTuningStrategy` are NOT
modified. Per-ganglion metrics are observational only — they do not feed into any tuning
or suppression path.

## Recording Path — OutcomeRecorder

`OutcomeRecorder.onOutcome()` extracts the `"detections"` list from `caseFileSnapshot()`
and deduplicates by ganglionId:

```java
@SuppressWarnings("unchecked")
List<GanglionContribution> extractContributions(Map<String, Object> snapshot) {
    Object detectionsObj = snapshot.get("detections");
    if (detectionsObj == null) return List.of();

    List<Map<String, Object>> rawDetections;
    try {
        rawDetections = (List<Map<String, Object>>) detectionsObj;
    } catch (ClassCastException ex) {
        LOG.fine("Cannot parse detections from case file snapshot: " + ex.getMessage());
        return List.of();
    }

    Map<String, GanglionContribution> best = new LinkedHashMap<>();
    for (Map<String, Object> det : rawDetections) {
        try {
            Map<String, Object> result = (Map<String, Object>) det.get("result");
            if (result == null) continue;

            String ganglionId = (String) result.get("ganglionId");
            if (ganglionId == null) continue;
            DetectionSignal signal = DetectionSignal.valueOf((String) result.get("signal"));
            double confidence = ((Number) result.get("confidence")).doubleValue();

            best.merge(ganglionId,
                new GanglionContribution(ganglionId, confidence, signal),
                (existing, incoming) -> {
                    int cmp = incoming.signal().compareTo(existing.signal());
                    if (cmp == 0) cmp = Double.compare(incoming.confidence(), existing.confidence());
                    return cmp > 0 ? incoming : existing;
                });
        } catch (RuntimeException ex) {
            LOG.fine("Skipping malformed detection entry: " + ex.getMessage());
        }
    }
    return List.copyOf(best.values());
}
```

Key properties:
- The case file snapshot carries detections as deserialized maps (Jackson serde through
  the engine), not typed `TimestampedDetection` objects. The extraction handles the
  `TimestampedDetection → DetectionResult` nesting.
- **Graceful degradation:** if `"detections"` is absent, malformed, or any individual
  entry fails to parse, returns empty/partial list. The outcome record is still written
  with null/empty ganglion contributions. Per-detection try-catch isolates individual
  parse failures.
- `DetectionSignal` comparison uses enum ordinal (NOISE < ANTI < WEAK < DETECTED) —
  `merge()` keeps the higher signal per ganglionId, breaking ties by highest confidence.

## Persistence

### Flyway V8

```sql
ALTER TABLE ras_outcome_record
  ADD COLUMN ganglion_contributions JSONB;
```

Nullable, no default. Existing rows have NULL (no attribution data, recorded before
this feature). No index needed — column is only queried in a batch job that filters on
`(situation_id, tenancy_id, closed_at)` via the existing composite index.

### JpaOutcomeLedger

**`record()`** — updated INSERT adds the JSONB column:

```sql
INSERT INTO ras_outcome_record
(situation_id, correlation_key, tenancy_id, outcome_label, classification,
 closed_at, case_id, ganglion_contributions)
VALUES (:sid, :ck, :tid, :label, :cls, :closedAt, :caseId, CAST(:gc AS jsonb))
ON CONFLICT (case_id) DO NOTHING
```

The `ganglion_contributions` parameter is a JSON string serialized from
`List<GanglionContribution>` via Jackson. NULL when contributions are empty.

**`ganglionStatistics()`** — new method:

```sql
SELECT elem->>'ganglionId' AS ganglion_id,
       o.classification, COUNT(*)
FROM ras_outcome_record o,
     jsonb_array_elements(o.ganglion_contributions) elem
WHERE o.situation_id = :sid AND o.tenancy_id = :tid
  AND o.closed_at >= :since
  AND elem->>'signal' IN ('DETECTED', 'WEAK')
GROUP BY elem->>'ganglionId', o.classification
```

Filters to positive signals in the SQL. Rows with NULL `ganglion_contributions` are
automatically excluded by `jsonb_array_elements` (returns no rows for NULL input).
Results mapped to `Map<String, GanglionOutcomeStatistics>`.

### InMemoryOutcomeLedger

Implements `ganglionStatistics()` with in-memory stream filtering/grouping over stored
`OutcomeRecord` objects. Same positive-signal filter logic. Groups by ganglionId, counts
by classification.

## Metrics

### New Gauges

| Metric | Type | Tags | When |
|--------|------|------|------|
| `ras.feedback.ganglion.precision` | Gauge | `ganglion_id`, `situation_id`, `tenancy_id` | Per-ganglion confirmed / (confirmed + noise) |
| `ras.feedback.ganglion.noise_rate` | Gauge | `ganglion_id`, `situation_id`, `tenancy_id` | Per-ganglion noise / total |

Updated by `FeedbackUpdateJob` every 5 minutes (same cycle as situation-level metrics).
NaN values (insufficient data) suppress gauge registration — same pattern as existing
`ras.feedback.precision`.

### FeedbackMetrics — Gauge Holder Pattern

The existing `FeedbackMetrics.recordStatistics()` has a pre-existing gauge anti-pattern:
`meterRegistry.gauge(name, tags, boxedDouble, v -> v)` creates a weak reference to a
local `Double` that gets GC'd, causing gauges to permanently report NaN. This issue (#60
to be filed) affects `ras.feedback.precision`, `ras.feedback.noise_rate`, and
`ras.feedback.outcomes_total`.

This spec fixes the pattern for both existing situation-level and new per-ganglion gauges
using a holder map:

```java
private final ConcurrentHashMap<String, AtomicReference<Double>> gaugeHolders =
    new ConcurrentHashMap<>();

private void setGauge(String name, Tags tags, double value) {
    if (meterRegistry == null || Double.isNaN(value)) return;
    String key = name + tags.stream()
        .map(t -> t.getKey() + "=" + t.getValue())
        .collect(Collectors.joining(","));
    AtomicReference<Double> holder = gaugeHolders.computeIfAbsent(key, k -> {
        AtomicReference<Double> ref = new AtomicReference<>(value);
        meterRegistry.gauge(name, tags, ref, AtomicReference::get);
        return ref;
    });
    holder.set(value);
}
```

Register once, update holder on subsequent calls. The `AtomicReference` has a strong
reference from the `ConcurrentHashMap`, preventing GC. Both existing `recordStatistics()`
and new `recordGanglionStatistics()` use this helper.

New method:

```java
public void recordGanglionStatistics(String ganglionId, String situationId,
                                      String tenancyId, GanglionOutcomeStatistics stats) {
    Tags tags = Tags.of("ganglion_id", ganglionId, "situation_id", situationId,
                         "tenancy_id", tenancyId);
    setGauge("ras.feedback.ganglion.precision", tags, stats.precision());
    setGauge("ras.feedback.ganglion.noise_rate", tags, stats.noiseRate());
}
```

### FeedbackAnalyzer

New method, consistent with existing `analyze()` pattern:

```java
Map<String, GanglionOutcomeStatistics> ganglionAnalyze(
        String situationId, String tenancyId, FeedbackConfig config) {
    return ledger.ganglionStatistics(
        situationId, tenancyId,
        Instant.now().minus(config.retentionPeriod()));
}
```

Routes per-ganglion stats through the analysis layer — same retention window
computation as `analyze()`. Keeps the pattern consistent: `FeedbackUpdateJob` never
calls `OutcomeLedger` directly.

### FeedbackUpdateJob

Addition to `processTenant()`, after existing situation-level stats:

```java
Map<String, GanglionOutcomeStatistics> ganglionStats =
    analyzer.ganglionAnalyze(situationId, tenancyId, config);
for (var entry : ganglionStats.entrySet()) {
    feedbackMetrics.recordGanglionStatistics(
        entry.getKey(), situationId, tenancyId, entry.getValue());
}
```

Same batch job, same 5-minute cycle, same tenant iteration. One JSONB aggregation query
per `(situationId, tenancyId)` pair — same cardinality as the existing `statistics()` call.

### Relationship to Situation-Level Metrics

Per-ganglion metrics are independent observations, not decomposable components of
situation-level precision. A situation's precision reflects the combined chain mode
evaluation (threshold sum, sequence, etc.), which may be non-linear. Operators should
treat them as complementary dimensions: situation-level answers "is this situation
accurate?", per-ganglion answers "which ganglion is driving noise?"

## Module Placement

| Component | Module | Notes |
|-----------|--------|-------|
| `GanglionContribution` | `api/` | New record |
| `GanglionOutcomeStatistics` | `api/` | New record |
| `OutcomeRecord` (change) | `api/` | New field + constructor |
| `OutcomeLedger` (change) | `api/` | New method |
| `AbstractOutcomeLedgerContractTest` (change) | `api/` test-jar | New test cases |
| `OutcomeRecorder` (change) | `runtime/` | Extraction + dedup logic |
| `FeedbackAnalyzer` (change) | `runtime/` | New `ganglionAnalyze()` method |
| `FeedbackMetrics` (change) | `runtime/` | New gauge method + gauge holder fix for existing gauges |
| `FeedbackUpdateJob` (change) | `runtime/` | Per-ganglion stats via analyzer |
| `InMemoryOutcomeLedger` (change) | `runtime/` | New method impl |
| `OutcomeRecordEntity` (change) | `persistence-jpa/` | New JSONB column |
| `JpaOutcomeLedger` (change) | `persistence-jpa/` | Updated INSERT + new method |
| Flyway V8 | `persistence-jpa/` | ALTER TABLE |

## Testing

### AbstractOutcomeLedgerContractTest (api/ test-jar)

- `ganglionStatistics_empty` — no outcomes, returns empty map
- `ganglionStatistics_onlyPositiveSignals` — NOISE/ANTI contributions excluded from counts
- `ganglionStatistics_multipleGanglia` — correct per-ganglion breakdown
- `ganglionStatistics_multiTenant` — tenant isolation
- `ganglionStatistics_windowFiltering` — respects `since` cutoff
- `ganglionStatistics_nullContributions` — records with null JSONB gracefully skipped

### OutcomeRecorderTest (runtime/)

- `extractContributions_fromSnapshot` — round-trip through deserialized map structure
- `extractContributions_deduplicatesByGanglionId` — keeps highest signal
- `extractContributions_missingDetections` — returns empty list, outcome still recorded
- `extractContributions_malformedDetections` — graceful degradation

### FeedbackUpdateJobTest (runtime/)

- `processTenant_publishesGanglionGauges` — per-ganglion metrics published from `SimpleMeterRegistry`

### FeedbackAnalyzerTest (runtime/)

- `ganglionAnalyze_appliesRetentionWindow` — delegates to ledger with correct window

### FeedbackMetricsTest (runtime/)

- `recordGanglionStatistics_registersGauges` — correct metric names and tags
- `gaugeHolder_updatesOnSubsequentCalls` — gauge value reflects latest stats, not first
- `gaugeHolder_survivesGC` — holder pattern prevents weak reference collection

Existing tests updated to pass the new `ganglionContributions` field where needed
(backwards compat via 7-arg constructor defaulting to `List.of()`).

## Future Work

- **Counter-claim accuracy metrics:** ANTI contributions are stored in the JSONB and
  can be queried with `signal = 'ANTI'`. A future `ras.feedback.ganglion.counter_claim_accuracy`
  metric would answer "when this ganglion said NO, was it right?" Useful for detecting
  ganglia that suppress real detections. No schema change needed.
- **Per-ganglion tuning:** If metrics reveal actionable patterns, `FeedbackTuningStrategy`
  could receive per-ganglion stats and adjust weights. Separate design problem — chain
  mode interaction makes per-ganglion weight adjustment non-trivial.

## References

- `api/src/main/java/io/casehub/ras/api/OutcomeRecord.java` — current record, no ganglion data
- `api/src/main/java/io/casehub/ras/api/DetectionResult.java` — per-ganglion detection output
- `api/src/main/java/io/casehub/ras/api/SituationContext.java` — detection accumulation
- `runtime/src/main/java/io/casehub/ras/runtime/OutcomeRecorder.java` — current recording path
- `runtime/src/main/java/io/casehub/ras/runtime/DefaultCaseTrigger.java:110` — detections put into case file
- `runtime/src/main/java/io/casehub/ras/runtime/FeedbackMetrics.java` — current situation-level metrics
- `runtime/src/main/java/io/casehub/ras/runtime/FeedbackUpdateJob.java` — batch metric computation
- `persistence-jpa/src/main/java/io/casehub/ras/persistence/jpa/JpaOutcomeLedger.java` — JPA ledger
- `persistence-jpa/src/main/java/io/casehub/ras/persistence/jpa/SituationEntity.java` — JSONB column precedent
- `docs/specs/issue-40-ras-feedback-loop/2026-08-06-ras-feedback-loop-design.md` — feedback loop design
- `docs/specs/2026-07-12-ras-runtime-metrics-design.md` — RAS metrics design, naming conventions
