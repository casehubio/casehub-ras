## D1: Delivery mechanism

**Choice:** `SituationReplayRunner` in `runtime/` module — programmatic replay API
**Alternatives:**
- `testing/` module placement — inverts the clean `testing/` → `api/` dependency direction; turns a test-doubles module into a pipeline-orchestration module
- New `replay/` module — clean separation but unnecessary indirection; `runtime/` already contains all pipeline components
- Runtime REST endpoint — heavier, introduces HTTP concerns into a detection library
**Rationale:** The replay runner orchestrates the production detection pipeline — that is pipeline logic, not a test double. Placing it in `runtime/` gives package-private access to `RasMetrics`, `flushIdleBuffers()`, and event buffer internals needed for end-of-stream drain. The runner accepts `SituationStore` and `CaseTrigger` as API interface parameters, so consumers provide their own implementations (`InMemorySituationStore`, `MockCaseTrigger`) without `runtime/` depending on `persistence-memory/` or `testing/`.
**Trade-offs:** Replay code lives alongside production code. Acceptable — the runner is a legitimate execution mode for the pipeline, analogous to a dry-run capability.
**Sources:** casehubio/casehub-ras#44 issue body, `SituationEvaluator`, `RasMetrics` (package-private), `testing/pom.xml` dependency posture
**Exploration:** quick → revised in review round 1
**Status:** revised

## D2: Situation definition input

**Choice:** Both YAML path and programmatic `SituationRegistration` list
**Alternatives:**
- YAML-only — misses programmatic variant testing use case
- Programmatic-only — doesn't validate the YAML-to-definition path
**Rationale:** YAML path as primary API validates the full production path including ganglion construction from descriptors. `SituationDefinitionRegistry.forTesting()` delegates to the full 6-arg constructor which processes `SituationDefinitionProvider.ganglionDescriptors()` in Phase 1, constructing ganglia from YAML descriptors with `InMemoryGanglionStateStore`. The `List<Ganglion>` parameter is for CDI-provided ganglia (Phase 2), not a bypass of descriptor construction. Programmatic registrations for advanced use cases (A/B testing definition variants). `SituationRegistration` bundles `correlationKeyExtractor` and `eventFilter` alongside the definition — the replay runner honours these during event routing (see D8).
**Trade-offs:** Slightly larger API surface, but both paths converge to the same internal wiring.
**Sources:** `YamlSituationDefinitionProvider`, `SituationRegistration`, `SituationDefinitionRegistry.forTesting()`, `SituationDefinitionRegistry` 6-arg constructor (lines 54–106, Phase 1 descriptor construction)
**Exploration:** quick
**Status:** captured

## D3: Result model

**Choice:** Self-contained `ReplayResult` — timeline events, trigger details, final accumulated state, and aggregate summary
**Alternatives:**
- Timeline-only (`List<SituationChangeEvent>`) — misses below-threshold evaluations; `CONTINUE_ACCUMULATING` emits no change event, so non-triggering replays produce an empty result
- New parallel type hierarchy (`ReplayEvent`, etc.) — creates types that must be kept in sync with production types; consumers learn two event models
- Delegate to external objects (consumer's `CaseTrigger`, consumer's `SituationStore`) — inaccessible when using builder defaults, since the runner creates these internally
- Summary only — answers "did it work?" but can't debug why a definition failed
**Rationale:** `ReplayResult` is the complete answer to "what happened during replay?" It must be self-contained — consumers should not need references to internal pipeline components. Contents:
1. `List<SituationChangeEvent>` — chronological timeline of state changes (TRIGGERED, RESOLVED, DISCARDED, SUPPRESSED, DISMISSED), collected via a collecting `Event<SituationChangeEvent>` implementation
2. `List<TriggerRecord>` — fired case details (caseId, config, context), collected via a collecting `CaseTrigger` decorator that wraps the consumer-provided or default trigger
3. Final accumulated `SituationContext` per situation instance — the detection history (via `List<TimestampedDetection>`) for all active situations at end-of-replay, collected via a collecting `SituationStore` decorator that tracks all `save()` calls. This answers "why didn't this trigger?" — confidence 0.74 vs 0.01 is visible in the detection history
4. Computed aggregate summary (total triggers, per-definition breakdown, per-tenancy breakdown)
The collecting decorators are transparent — they wrap whatever `CaseTrigger`/`SituationStore` the consumer provides (or the defaults), forwarding all calls and capturing results. The consumer gets a complete result regardless of whether they used defaults or provided their own implementations.
**Trade-offs:** The collecting decorators add a thin layer. Negligible cost for replay volumes.
**Sources:** `SituationChangeEvent`, `SituationChangeEvent.ChangeType`, `SituationEvaluator.executeDecision()` (CONTINUE_ACCUMULATING emits no event, line 388), `SituationContext.detections()`, `TimestampedDetection`, `SituationStore.findActive()`
**Exploration:** quick → revised in review rounds 1, 2
**Status:** revised

## D4: Event reorder buffer handling

**Choice:** Include the buffer — wire `EventReorderBuffer` the same way production does; add public `drainAllBuffers()` on `SituationEvaluator` for end-of-stream flush
**Alternatives:**
- Skip the buffer, require pre-sorted input — simpler but doesn't validate the full production path
- Use `flushIdleBuffers(Instant.MAX)` — semantically equivalent to drain-all but obscures intent
**Rationale:** The buffer drains by event-time watermark (`maxEventTime - bufferDelay`), not wall-clock. No simulated clock needed. Rapid replay events advance the watermark naturally. After all events are submitted, `drainAllBuffers()` flushes remaining buffered events — this is a legitimate lifecycle method useful for both replay (end-of-stream) and production (graceful shutdown). The buffer is NOT a no-op for in-order events: the last `bufferDelay` worth of events remain buffered until explicitly drained. This correctly reproduces production timing behavior. `Instant.now()` in `evaluate()` only sets `lastArrivalTime` for idle buffer detection — the drain watermark and event ordering are event-time-based and fully deterministic regardless of wall-clock.
**Trade-offs:** Adds a public method to `SituationEvaluator`. Justified — graceful buffer drain is a missing lifecycle concern, not a test-only concern.
**Sources:** `EventReorderBuffer.submit()`, `EventReorderBuffer.drainAll()`, `EventBufferFlushJob`, `SituationEvaluator.evaluate():131`, `SituationEvaluator.flushIdleBuffers()`
**Exploration:** quick → revised in review round 1
**Status:** revised

## D5: Feedback loop in replay

**Choice:** Independent opt-in for suppression, outcome ledger, and threshold adjustment — all excluded by default
**Alternatives:**
- Single "feedback on/off" toggle — conflates three architecturally distinct concerns
- Exclude entirely — can't test feedback-adjusted detection behavior
- Include always — pollutes the default "clean room" detection validation
**Rationale:** The `SituationEvaluator` constructor already separates suppression (`SuppressionStrategy`), outcome tracking (`OutcomeLedger`), and threshold adjustment (`FeedbackState`) as independent nullable parameters. The replay runner mirrors this separation. Default: all null (clean-room detection). Suppression and threshold adjustment serve opposite replay goals — suppression hides events (usually unwanted in replay validation), while threshold adjustment validates tuned detection sensitivity (often wanted). The replay builder exposes `.withSuppressionStrategy()`, `.withOutcomeLedger()`, and `.withFeedbackState()` independently.
**Trade-offs:** Three optional parameters instead of one. Justified — the evaluator already has this separation and consumers need the granularity.
**Sources:** `SituationEvaluator` 10-arg constructor (line 78), `SuppressionStrategy`, `OutcomeLedger`, `FeedbackState`, `SituationEvaluator.processEvent()` null-checks (lines 147, 188)
**Exploration:** quick → revised in review round 1
**Status:** revised

## D6: API shape

**Choice:** Builder pattern — runner owns pipeline construction, consumer configures inputs and pluggable collaborators
**Alternatives:**
- Record config + static factory — simpler but less discoverable for optional parameters
- Consumer wires pipeline components directly (evaluator, registry, stores) — maximum flexibility but pushes pipeline construction knowledge to consumers; requires package-private access to `RasMetrics`
**Rationale:** The runner owns pipeline construction — consumers configure inputs and options, the runner internally wires `SituationEvaluator`, `SituationDefinitionRegistry`, `RasMetrics`, `DefaultRasTriggerPolicy`, and the buffer drain strategy. Consumers shouldn't need to know the wiring between these components. Existing tests wire the pipeline in ~5 lines but require package-private access and knowledge of component relationships. `ReplayResult` is self-contained (see D3) — consumers do not need references to internal components to understand what happened. Required builder parameters: definitions and events. Optional: `SituationStore` (default: `InMemorySituationStore`), `CaseTrigger` (default: no-op), `SuppressionStrategy`, `OutcomeLedger`, `FeedbackState`, `ReplayErrorHandling` (default: STRICT, see D11). Builder advantages: required/optional distinction, IDE discoverability.
**Trade-offs:** More code than a record config. The builder's value is encapsulating pipeline construction, not the pattern itself.
**Sources:** `SituationEvaluatorTest.buildEvaluator()`, casehub-platform builder patterns
**Exploration:** quick → revised in review rounds 1, 2
**Status:** revised

## D7: Replay entry point

**Choice:** Use `SituationEvaluator.evaluate()` directly as the replay entry point
**Alternatives:**
- Extract core detect-evaluate-decide pipeline into a replayable function used by both evaluator and replay runner — resolves buffer/lock/CDI concerns but creates divergence risk
- Reimplement detection pipeline in the runner — duplicates logic, guaranteed to drift
**Rationale:** The entire value of replay is running the SAME code path as production. `evaluate()` is the production entry point — it handles buffering, detection, policy evaluation, conflict retry, and change event firing. The concerns about reusing it are solvable without extraction: `Instant.now()` only affects idle buffer detection (not replay-relevant — replay uses `drainAllBuffers()`); synchronized locks are uncontended in single-threaded replay (near-zero cost); CDI `Event<SituationChangeEvent>` is stubbed with a collecting implementation (same pattern as `TestChangeEvent` in existing tests). Extracting a "replayable function" introduces the very divergence that replay is designed to prevent — if a bug exists in `evaluate()` but not the extracted function, replay won't catch it.
**Sources:** `SituationEvaluator.evaluate()`, `SituationEvaluatorTest` (1569 lines using same entry point), `TestChangeEvent`
**Exploration:** surfaced in review round 1
**Status:** captured

## D8: Event routing

**Choice:** Replay runner implements routing internally using `SituationDefinitionRegistry` API
**Alternatives:**
- Extract routing from `RasEngine` into shared utility — adds abstraction for ~15 lines of core routing logic
- Require consumers to pre-route events (pass event/definition/correlationKey/tenancyId tuples) — pushes complexity to consumers
- Call `RasEngine.onCloudEvent()` directly — CDI observer method, coupled to production metrics and error handling
**Rationale:** `RasEngine.onCloudEvent()` routing logic is: find registrations by event type (`registry.findByEventType()`), apply event filter (`reg.eventFilter().accepts()`), extract correlation key (`reg.correlationKeyExtractor().extract()`), extract tenancy from CloudEvent extension. The core routing is ~15 lines plus production error handling. The replay runner implements this routing with error handling governed by the configured `ReplayErrorHandling` strategy (see D11). `SituationRegistration` bundles `eventFilter` and `correlationKeyExtractor` alongside the definition — the runner honours both.
**Sources:** `RasEngine.onCloudEvent()` (lines 29–77), `SituationDefinitionRegistry.findByEventType()`, `SituationRegistration`, `CorrelationKeyExtractor`, `EventFilter`
**Exploration:** surfaced in review round 1 → revised in review round 2
**Status:** revised

## D9: Tenancy handling

**Choice:** Extract tenancy from CloudEvent `tenancyid` extension — same as production; require it in replay event data
**Alternatives:**
- Default tenancy parameter for single-tenant replay — convenience but masks a production contract
- Ignore tenancy — breaks the evaluator contract (`tenancyId` is a required parameter)
**Rationale:** `SituationEvaluator.evaluate()` requires `tenancyId` as a non-null parameter. In production, `RasEngine.extractTenancyId()` reads the CloudEvent `tenancyid` extension. The replay runner uses the same extraction. Events without `tenancyid` are handled according to the configured `ReplayErrorHandling` strategy (see D11): STRICT throws, LENIENT skips with a warning. For test fixtures, consumers include the `tenancyid` extension in CloudEventBuilder — a one-line addition.
**Sources:** `RasEngine.extractTenancyId()` (lines 79–82), `SituationEvaluator.evaluate()` signature
**Exploration:** surfaced in review round 1 → revised in review round 2
**Status:** revised

## D10: Metrics collection during replay

**Choice:** Use `SimpleMeterRegistry` internally; optionally expose selected metrics via `ReplayResult`
**Alternatives:**
- No-op registry — loses visibility into buffer/retry/filter behavior during replay
- Expose `MeterRegistry` directly — low-level, requires Micrometer API knowledge
**Rationale:** `SituationEvaluator` requires non-null `RasMetrics`. The replay runner creates `RasMetrics` with `SimpleMeterRegistry` — same pattern as existing tests (`SituationEvaluatorTest`). Replay metrics (events buffered, events filtered, conflict retries, evaluation times) are useful for understanding pipeline behavior. `ReplayResult` can expose selected metrics as part of the aggregate summary without coupling consumers to Micrometer.
**Sources:** `RasMetrics`, `SituationEvaluatorTest` (uses `SimpleMeterRegistry`), `RasMetrics.setMeterRegistry()`
**Exploration:** surfaced in review round 1
**Status:** captured

## D11: Error handling strategy

**Choice:** Configurable via builder — STRICT (default) or LENIENT
**Alternatives:**
- Always fail-fast — clean for test fixtures but unusable with messy production event exports
- Always skip-with-warning — tolerant of noise but masks data quality issues in curated test fixtures
- No explicit strategy (ad-hoc per routing step) — leads to inconsistent behavior across routing concerns (D8 fail-fast vs D9 skip-with-warning)
**Rationale:** Replay serves two distinct error contexts: curated test fixtures (unit tests, CI validation) where bad data should fail immediately, and raw production event logs (operational analysis, incident investigation) where historical exports may contain noise (missing tenancy extensions, malformed events). STRICT mode throws on any routing error: missing `tenancyid`, `EventFilter` exception, `CorrelationKeyExtractor` failure. LENIENT mode skips problematic events, records them in `ReplayResult.skippedEvents()` with the skip reason, and continues. Events with no matching situation definition are NOT errors in either mode — they are silently skipped, same as production (`RasEngine.onCloudEvent()` lines 40–43). This is normal routing behavior: replay event streams routinely contain event types unrelated to the definitions under test. Unmatched event counts are available via replay metrics (D10) for consumers who want audit visibility. Default is STRICT — curated test data should be clean, and early failure surfaces data quality issues. Consumers replaying raw production logs explicitly opt into LENIENT via `.withErrorHandling(ReplayErrorHandling.LENIENT)`. This unifies the error handling across D8 (event routing) and D9 (tenancy extraction) under a single configurable strategy.
**Trade-offs:** One more builder parameter. Justified — the two error contexts have genuinely different requirements.
**Sources:** `RasEngine.onCloudEvent()` (production: silent skip for unmatched types, lines 40–43), issue #44 ("deterministic" requirement favours STRICT default)
**Exploration:** surfaced in review round 2 → revised in review round 3
**Status:** revised
