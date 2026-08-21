---
layout: post
title: "Which ganglion is lying to you"
date: 2026-08-21
entry_type: note
subtype: diary
projects: [casehub-ras]
tags: [feedback-loop, metrics, ganglion, micrometer, jsonb, outcome-attribution]
series: issue-59-per-ganglion-quality-metrics
---

The feedback loop (#40) gave RAS a memory — outcomes flow back and adjust detection thresholds and priors. But the metrics it publishes are situation-level. A situation with an 80% noise rate tells you the situation is noisy. It doesn't tell you which of its three ganglia is responsible.

Issue #59 fills that gap: per-ganglion precision and noise rate, published as Micrometer gauges.

The interesting problem isn't the metrics — it's attribution. When a case closes with an outcome, the feedback system needs to know which ganglia contributed what to the original detection. That data already exists in `SituationContext.detections()` — a list of `TimestampedDetection` records, each carrying the ganglion's ID, confidence, and signal. `DefaultCaseTrigger.buildInputData()` puts the full list into the case file at trigger time. The case engine serialises it, stores it, and when the case closes, `CaseOutcomeEvent.caseFileSnapshot()` carries it back.

The recording path just wasn't using it. `OutcomeRecorder.onOutcome()` extracted `situationId` and `correlationKey` from the snapshot and ignored `detections`. The fix: extract the detection list, deduplicate by ganglion ID (keeping the highest signal, breaking ties by confidence), and store the result as a JSONB array on the existing `ras_outcome_record` table.

JSONB over a normalised child table was a deliberate choice. The outcome record uses `INSERT ... ON CONFLICT (case_id) DO NOTHING` for idempotent writes — adding a child table would break the atomicity. JSONB keeps the write path as a single atomic INSERT, and the query (a `jsonb_array_elements` aggregation in the batch job) runs every five minutes, not on the hot path.

The deduplication at recording time has an edge worth noting. A ganglion might contribute `DETECTED(0.8)` on one event and `NOISE` on a later event within the same situation window. We keep only the peak — the highest signal. This means a ganglion that self-corrects (detected, then backed off) still appears as a positive contributor. For quality metrics this is the right trade-off: the ganglion *did* contribute a positive signal to a situation that triggered, and the outcome tells us whether that was warranted. But it's a lossy compression. The raw event-level data lives in `SituationContext` for anything that needs full trajectory.

The spec review caught a real bug in the existing `FeedbackMetrics` that would have affected the new gauges too. The pattern `meterRegistry.gauge(name, tags, boxedDouble, v -> v)` creates a `WeakReference` to a local `Double`. After GC, the gauge permanently reports `NaN` and can never recover — subsequent `gauge()` calls return the existing (dead) gauge and silently discard the new value. The fix is a holder pattern: a `ConcurrentHashMap<String, AtomicReference<Double>>` that keeps a strong reference and updates the value in place. This applies to the existing situation-level gauges too — they had the same silent-death bug since #40.

The `QualityMetrics` interface was a late addition, suggested by the plan review. Both `OutcomeStatistics` and `GanglionOutcomeStatistics` had identical `precision()` and `noiseRate()` methods. Since Java records auto-generate component accessors that satisfy interface contracts, both records just add `implements QualityMetrics` and inherit the default computation methods. Clean dedup, no boilerplate.

One scope decision worth recording: per-ganglion metrics are purely observational. The gauges tell you which ganglion is noisy. They don't automatically adjust anything — no per-ganglion weight reduction, no automatic suppression. That's a different design problem (how do you attenuate one ganglion in a threshold-sum chain without breaking the combined signal?) and it stays deferred. Operators see the data, operators act on it.

ANTI contributions are stored in the JSONB when they're a ganglion's highest signal, but excluded from the precision calculation by the query filter. This means future counter-claim accuracy metrics ("when this ganglion said *no*, was it right?") can be added with a query change and no schema migration.
