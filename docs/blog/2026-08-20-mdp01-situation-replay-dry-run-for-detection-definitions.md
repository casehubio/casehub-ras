---
layout: post
title: "Situation Replay — Dry-Run for Detection Definitions"
date: 2026-08-20
entry_type: note
subtype: diary
projects: [casehub-ras]
tags: [ras, replay, testing, detection]
series: issue-44-situation-replay
---

The RAS has always had a gap: you couldn't answer "would this definition have caught last week's incident?" without deploying and waiting. Situation definition authoring was trial-and-error in production. Threshold and Sequence chain modes are particularly unforgiving — subtle configuration errors either flood with false positives or silently miss real events.

`SituationReplayRunner` closes that gap. Feed it historical CloudEvents and a situation definition, get back exactly what would have triggered — same code path as production, zero side effects.

```java
var result = SituationReplayRunner.builder()
    .withYaml("META-INF/ras-situations.yaml")
    .withGanglia(List.of(myGanglion))
    .withEvents(historicalCloudEvents)
    .build()
    .run();

result.didTrigger("fraud-detection");
```

The interesting design question was where to put it. The obvious answer was `testing/` — it's a test utility, right? The decision review caught the flaw: the replay runner orchestrates the production detection pipeline. It needs package-private access to `RasMetrics`, `flushIdleBuffers()`, and event buffer internals. It's not a test double; it's a dry-run execution mode. It lives in `runtime/`.

The pipeline turned out to be more replay-friendly than expected. `SituationEvaluator` already uses event time from `CloudEvent.getTime()` for all time-critical decisions — correlation window expiry, detection ordering, buffer watermarks. The two `Instant.now()` calls in the detection path are non-issues: one sets `lastArrivalTime` for buffer idle detection (irrelevant during rapid replay), the other is a fallback for events without timestamps. No clock abstraction needed.

The `EventReorderBuffer` works naturally too. Its drain is driven by an event-time watermark (`maxEventTime - bufferDelay`), not wall-clock time. During replay, events advance the watermark as they arrive. A new `drainAllBuffers()` method on `SituationEvaluator` handles end-of-stream flush — useful for both replay and production graceful shutdown.

The result model is self-contained: `ReplayResult` carries a timeline of `SituationChangeEvent`s, trigger details, final accumulated state per situation instance, and aggregate summaries. Collecting decorators wrap the `CaseTrigger`, `SituationStore`, and CDI `Event` transparently — consumers get a complete picture regardless of whether they provided custom implementations or used the defaults. The distinction between "this triggered" and "this accumulated to confidence 0.74 but didn't cross the 0.8 threshold" is visible directly in the detection history.

Error handling splits into two modes: STRICT throws on routing errors (missing tenancy extensions, filter failures), LENIENT skips and records. The split maps to two genuinely different use cases — curated test fixtures where bad data should fail fast vs raw production event logs that inevitably contain noise.

The feedback loop (suppression, threshold adjustment, outcome tracking) is excluded by default — clean-room detection for definition validation. Each concern is independently opt-in via the builder, mirroring the same separation already in the `SituationEvaluator` constructor. You can test whether your threshold-tuned definition still fires correctly without also enabling suppression.
