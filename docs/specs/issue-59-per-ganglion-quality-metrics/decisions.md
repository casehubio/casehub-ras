## D1: Per-ganglion quality scope — positive contributions only

**Choice:** Per-ganglion precision/noise rate counts only outcomes where the ganglion contributed a positive signal (DETECTED or WEAK). NOISE and ANTI contributions are excluded.
**Alternatives:**
- All contributions regardless of signal — would dilute the metric with ganglion signals that didn't claim detection
**Rationale:** The useful question is "when this ganglion said something was happening, how often was it right?" NOISE/ANTI signals are the ganglion explicitly NOT claiming detection.
**Trade-offs:** Ganglia that only ever contribute NOISE/ANTI will have no quality metrics (NaN precision). Acceptable — they have nothing to evaluate.
**Sources:** OutcomeRecorder.java, DetectionResult.java, issue #59 body
**Exploration:** quick
**Status:** captured

## D2: Storage — JSONB column on existing outcome table

**Choice:** Add a `ganglion_contributions JSONB` column to `ras_outcome_record` storing an array of `{ganglionId, confidence, signal}` objects.
**Alternatives:**
- Normalized child table `ras_outcome_ganglion` — standard SQL aggregation but breaks atomic INSERT ON CONFLICT, adds FK cascade complexity, 2-5x row volume
**Rationale:** Keeps write path atomic (single INSERT ON CONFLICT), cleanup simple (DELETE parent deletes everything), follows existing JSONB patterns in persistence-jpa/. Aggregation via `jsonb_array_elements` in batch job — not hot path.
**Trade-offs:** JSONB aggregation queries are slightly more complex than standard GROUP BY. Acceptable for a 5-minute batch job.
**Sources:** JpaOutcomeLedger.java, OutcomeRecordEntity.java, SituationEntity.java (JSONB precedent — opaque blob; this feature introduces queryable JSONB aggregation via jsonb_array_elements, which is new to this codebase but standard PostgreSQL)
**Exploration:** quick
**Status:** captured

## D3: Extraction — deduplicate by ganglionId at recording time

**Choice:** `OutcomeRecorder` collapses raw detections to one entry per ganglionId, keeping the highest signal and its confidence. A situation with 20 raw detections across 3 ganglia produces 3 contribution records.
**Alternatives:**
- Store all raw detections — more data, requires dedup at query time (more complex SQL), unbounded JSONB growth
**Rationale:** Per-ganglion quality asks "did this ganglion contribute positively to a noise/confirmed outcome?" The answer doesn't change with detection count. Bounded JSONB size. Simpler aggregation queries.
**Trade-offs:** Loses per-event granularity within a ganglion. Acceptable — event-level data is in SituationContext, not the feedback path.
**Sources:** SituationContext.java (detection accumulation), Ganglion.compact() (compaction precedent)
**Exploration:** quick
**Status:** captured

## D4: Scope — observational gauges only, no tuning action

**Choice:** Per-ganglion quality metrics are purely observational — publish gauges, no automated tuning response.
**Alternatives:**
- Feed into tuning — FeedbackTuningStrategy adjusts per-ganglion weights based on quality stats. Significantly larger scope (new API surface, new failure modes, unclear semantics for suppressing one ganglion in a multi-ganglion chain).
**Rationale:** Matches issue scope. Operators see which ganglia are noisy and can act manually. Automated per-ganglion tuning is a separate design problem.
**Trade-offs:** No automated remediation — noisy ganglia require manual intervention. Acceptable as a first step; per-ganglion tuning can be a follow-up issue if metrics reveal actionable patterns.
**Depends on:** D1 (positive contributions only)
**Sources:** Issue #59 body ("Surface as gauges"), FeedbackTuningStrategy.java
**Exploration:** quick
**Status:** captured
