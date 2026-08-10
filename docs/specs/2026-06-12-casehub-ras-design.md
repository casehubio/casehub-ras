# casehub-ras — Design Spec

**Date:** 2026-06-12 (revised 2026-06-18)
**Status:** Pre-implementation — spec for brainstorm and issue creation
**Repo:** casehubio/casehub-ras
**Supersedes:** Original SensoryEvent design — replaced by CloudEvent per parent P0 layering decisions (2026-06-13)

---

## 1. The Problem

CaseHub cases are currently created imperatively: something calls `startCase()` with explicit intent. This is correct for workflow-driven scenarios but leaves a gap: **who monitors the environment and decides a case is warranted?**

In regulated domains, cases are not always explicitly requested:
- A pattern of IoT sensor readings indicates equipment failure
- A sequence of financial transactions matches a suspicious pattern
- A clinical patient's metrics cross a multi-factor risk threshold
- Agent communication patterns signal a governance escalation
- A deployment's behaviour over time deviates from expected norms

These situations require **reactive, declarative case creation** — the platform watches, detects, and acts. `casehub-ras` is that layer.

---

## 2. Naming and Conceptual Model

**RAS — Reticular Activating System** maps precisely to the function:
- The biological RAS monitors all incoming sensory streams
- It filters for what is significant enough to elevate to conscious awareness
- It coordinates multiple input channels into a unified awareness signal

**Ganglion** — the pluggable detection unit inside the RAS:
- A biological ganglion pre-processes sensory signals before they reach the brain
- Multiple ganglia detect different patterns using different strategies
- Their outputs are accumulated and correlated by the RAS

Architecture: multiple `Ganglion` implementations → one `RasEngine` → `case created`.

---

## 3. Two-Tier Architecture

**Platform tier (NOT in casehub-ras):** stream ingestion and data normalisation.

The raw data sources — Kafka topics, AMQP queues, REST webhooks, database change streams, IoT state events, Qhorus messages — are infrastructure concerns. They live in casehub-platform (new submodules) or casehub-connectors. Quarkus handles the endpoint wiring; Apache Camel handles data transformation and routing when mapping is needed.

These produce `CloudEvent` CDI events (`Event<CloudEvent>.fireAsync()`) that the RAS observes. The RAS has zero knowledge of Kafka, webhooks, or Camel. `CloudEvent` comes from `io.cloudevents:cloudevents-core` via `casehub-platform-api`.

**RAS tier (casehub-ras):** detection intelligence.

The RAS observes `CloudEvent` CDI events (`@ObservesAsync CloudEvent`), routes them to registered `Ganglion` implementations, accumulates `DetectionResult` outputs, correlates composite events, and triggers case creation when the situation threshold is crossed.

```
External sources
  → casehub-platform stream submodules (Quarkus + Camel)
    → CloudEvent CDI fireAsync()
      → casehub-ras RasEngine (@ObservesAsync CloudEvent)
        → Ganglion implementations (parallel detection)
          → DetectionResult accumulation
            → CompositeEventCorrelator
              → SituationThreshold evaluation
                → startCase() via casehub-engine
```

---

## 4. Platform Stream Infrastructure (separate design — referenced here)

New submodules in `casehub-platform` (or `casehub-connectors`):

| Module | Transport | Notes |
|--------|-----------|-------|
| `platform-streams-kafka` | Apache Kafka | Quarkus Kafka consumer; configurable topics |
| `platform-streams-amqp` | AMQP 1.0 | Quarkus AMQP consumer |
| `platform-streams-webhook` | HTTP push | Quarkus REST endpoint; receives webhook payloads |
| `platform-streams-poll` | Any HTTP API | Quarkus `@Scheduled` + REST client; polling adapter |
| `platform-streams-camel` | Any Camel connector | Apache Camel route → `CloudEvent`; 300+ connectors available |

Each module:
- Activates by classpath presence (Jandex library pattern)
- Applies any needed data mapping (Camel routes for transformation)
- Fires `Event<CloudEvent>` via CDI `fireAsync()`
- Zero dependency on casehub-ras

`CloudEvent` comes from `io.cloudevents:cloudevents-core`, provided transitively via `casehub-platform-api`. Platform stream modules have no dependency on `casehub-ras-api` — the event type is a platform-tier concern, not an integration-tier concern. See parent P0 layering decisions (Decision 1).

---

## 5. Input Type — CloudEvent (from casehub-platform-api)

The RAS does not define its own input event type. It observes `io.cloudevents.CloudEvent` from the
CDI bus — the same type produced by all platform stream modules, IoT adapters, Qhorus adapters,
and connector adapters. `casehub-ras-api` depends on `casehub-platform-api` (which provides
`cloudevents-core` transitively).

| CloudEvent field | RAS usage |
|---|---|
| `type` | Logical event type — used for ganglion routing via `handledEventTypes()` |
| `source` | Logical producer URI |
| `subject` | Entity the event concerns |
| `id` | Unique event ID |
| `time` | Event timestamp |
| `data` | Typed payload — opaque to RasEngine, interpreted by ganglia |
| `tenancyid` (extension) | Tenant isolation — `event.getExtension("tenancyid")` |

## 6. Core Types (casehub-ras-api)

```java
// What a Ganglion produces when it processes a CloudEvent
record DetectionResult(
    String ganglionId,
    String situationId,     // groups related detections (composite events)
    double confidence,      // 0.0 – 1.0
    DetectionSignal signal, // DETECTED / WEAK / NOISE / ANTI
    Map<String, Object> evidence  // what the ganglion observed
) {}

// Accumulated state for an evolving situation
record SituationContext(
    String situationId,
    String tenancyId,
    Instant firstSignal,
    Instant lastSignal,
    List<DetectionResult> detections,
    Map<String, Object> accumulatedEvidence
) {}
```

---

## 7. Ganglion SPI (casehub-ras-api)

```java
interface Ganglion {
    // Unique identifier — used to route events and filter results
    String ganglionId();

    // CloudEvent types this ganglion handles — RAS routes only matching events
    Set<String> handledEventTypes();

    // Core detection — called by RasEngine for each matching CloudEvent
    Uni<DetectionResult> detect(CloudEvent event, SituationContext context);

    // Optional: ganglion can request correlation window size
    default Duration correlationWindow() { return Duration.ofMinutes(5); }
}
```

**Built-in implementations:**

| Implementation | Strategy | Notes |
|---|---|---|
| `JavaSwitchGanglion` | Java pattern switch / predicate chain | Zero deps, fast, deterministic |
| `DroolsCepGanglion` | Drools Complex Event Processing | Sliding windows, event correlation, temporal patterns |
| `BayesianGanglion` | Bayesian network | Weighted multi-signal accumulation |
| `LlmGanglion` | LLM via `casehub-platform-agent-api` | Narrative/ambiguous signals; slow path only |

---

## 8. Composite Event Chains

A single `CloudEvent` rarely warrants a case alone. The `CompositeEventCorrelator` accumulates `DetectionResult` objects sharing the same `situationId` within a configurable time window.

**Chaining modes:**
- **AND** — all named ganglia must fire before case is created
- **OR** — any single ganglion firing crosses threshold
- **THRESHOLD** — minimum confidence sum across all detections
- **SEQUENCE** — detections must arrive in declared order within window
- **COUNT** — same ganglion must fire N times within window

Declared in a `SituationDefinition` (YAML or fluent Java DSL):

```yaml
situation:
  id: equipment-failure-risk
  streamTypes: [iot.temperature, iot.vibration, iot.pressure]
  correlationWindow: PT10M
  chain:
    mode: AND
    ganglia: [temp-spike-ganglion, vibration-anomaly-ganglion]
  threshold:
    minConfidence: 0.75
  onTrigger:
    caseType: equipment-maintenance
    caseData:
      priority: HIGH
```

---

## 9. RasTriggerPolicy SPI (casehub-ras-api)

Controls when a case is created from a `SituationContext`:

```java
interface RasTriggerPolicy {
    TriggerDecision evaluate(SituationContext context, SituationDefinition definition);
    // Returns: CREATE_CASE / CONTINUE_ACCUMULATING / DISCARD
}
```

`DefaultRasTriggerPolicy` implements confidence threshold + chain mode evaluation. Custom policies can implement rate limiting, deduplication, or domain-specific gate logic.

---

## 10. Module Structure

```
casehub-ras/
  pom.xml                      ← casehub-ras-parent
  api/                         ← casehub-ras-api (depends on casehub-platform-api for CloudEvent)
    DetectionResult, SituationContext, SituationDefinition
    Ganglion SPI, RasTriggerPolicy SPI
  runtime/                     ← casehub-ras (Quarkus extension)
    RasEngine — observes CloudEvent CDI events, orchestrates ganglia
    CompositeEventCorrelator — accumulates and correlates detection results
    SituationStore — tracks open situations (in-memory default; JPA optional)
    CaseTriggerService — calls casehub-engine startCase() when threshold crossed
  ras-drools/                  ← casehub-ras-drools (Optional module, Jandex library)
    DroolsGanglion — KieSession-based CEP detection
  ras-llm/                     ← casehub-ras-llm (Optional module)
    LlmGanglion — casehub-platform-agent-api based narrative detection
  testing/                     ← casehub-ras-testing (Test scope only)
    MockGanglion, FixedDetectionResult, InMemorySituationStore
```

---

## 11. Tier Placement and Dependencies

```
Foundation:
  casehub-platform-api
    → provides io.cloudevents.CloudEvent transitively (cloudevents-core)
  casehub-platform stream submodules (Kafka, AMQP, webhook, Camel)
    → fires CloudEvent CDI events

Integration:
  casehub-ras-api
    → depends on casehub-platform-api (for CloudEvent type)
  casehub-ras
    → observes @ObservesAsync CloudEvent
    → routes to Ganglion implementations
    → triggers cases via casehub-engine-api
```

casehub-ras sits at the Integration tier — alongside claudony, casehub-openclaw, casehub-workers.

Dependency direction: `casehub-ras-api` → `casehub-platform-api` (correct: integration depends on foundation). Platform stream modules have no dependency on `casehub-ras-api`.

---

## 12. Use Cases (Reference Scenarios)

**Equipment failure (IoT):** Temperature spike + vibration anomaly within 10 minutes → create maintenance case with CRITICAL priority.

**AML composite signal:** Three transactions > threshold from same entity + PEP flag detected → create AML investigation case.

**Clinical deterioration:** Patient vitals cross CTCAE grade threshold twice in 24h + attending note contains flagged terms (LlmGanglion) → create escalation case.

**Agent mesh anomaly:** Qhorus commitment failure rate exceeds baseline for 5 minutes (Bayesian) → create platform health case.

**Compliance drift:** Log retention policy last verified > 30 days AND encryption control changed → create compliance review case.

**Service lifecycle management:** Service deployed → open `ServiceLifecycleCase` (long-lived, WAITING). RAS monitors health streams. Ganglion detects: service down → open `IncidentCase` (child); upgrade threshold crossed → open `UpgradeCase` (child); decommission signal → open `DecommissionCase` (child, closes parent with SUCCESS). The parent case blackboard holds desired state + current state + health status and is otherwise idle.

---

## 13. Service Lifecycle Management Pattern

The RAS is the natural monitor for long-lived service lifecycle cases. This section describes the first-class integration pattern.

### The Pattern

A **service lifecycle case** opens when a service is deployed and closes only on intentional decommission (SUCCESS) or unrecoverable failure after N remediation attempts. The case holds the desired state and current state on its blackboard. The RAS instance is created alongside the case and monitors health/state streams continuously.

```
ServiceLifecycleCase (long-lived, WAITING)
  ├── RAS instance (monitoring streams attached to this case)
  │     ├── HealthCheckGanglion    (CDI bean — fast, deterministic)
  │     ├── MetricsTrendGanglion   (case — stateful, 24h Bayesian/CEP window)
  │     └── UpgradeSignalGanglion  (LLM ganglion — release notes, changelog analysis)
  ├── IncidentCase       (child — opens on health failure; closes when resolved)
  ├── UpgradeCase        (child — opens when upgrade threshold crossed)
  └── DecommissionCase   (child — closes parent with SUCCESS on completion)
```

The parent case is **sparse by design** — it sits in WAITING with no active steps. All activity is in the RAS and child cases. Log compaction on the parent is trivial (almost nothing to compact). The child cases are short-lived and close cleanly. This resolves the apparent tension between "cases are bounded" and "service management is long-lived": the service lifetime IS the bound; decommission IS the terminal success condition.

### Ganglion as Cases (Optional Pattern)

For stateful, long-running detection — tracking metric trends over 24h, accumulating evidence across many events — a ganglion can itself be implemented as a **case** rather than a CDI bean. The Drools KieSession or Bayesian state lives on the ganglion case's blackboard, survives restarts, and is managed by casehub-engine.

| Ganglion type | Implementation | When to use |
|---|---|---|
| Simple predicate | CDI bean | Threshold check, pattern match — no state needed |
| Time-windowed CEP | Case (with Drools blackboard) | Sliding windows, event correlation over hours |
| Bayesian accumulation | Case (with probability state) | Weighted multi-signal accumulation over time |
| LLM narrative | CDI bean (async) | Slow path; stateless per invocation |

The `Ganglion` SPI covers both: CDI beans registered by classpath, cases registered by case type. The RAS engine dispatches to both transparently.

### RAS Instance as a Desired-State Node

The RAS instance attached to a service lifecycle case can itself be declared as a `DesiredNode` in `casehub-desiredstate`. This means:

- The desired state of the system includes "a running RAS monitoring this service"
- If the RAS instance crashes, `casehub-desiredstate` provisions a replacement
- The RAS and the service it monitors are co-managed via the same desired-state declaration

This is the full circle: casehub-desiredstate manages topologies; casehub-ras detects conditions within those topologies; casehub-engine orchestrates responses. Each layer is independently useful; together they form a self-healing, self-aware system.

---

## 13. Open Design Questions

1. **SituationStore persistence** — in-memory for prototype; JPA for durable correlation across restarts. What is the retention policy for expired situations?
2. ~~**SensoryEvent routing**~~ → **Resolved:** RasEngine routes by `handledEventTypes()` matching `CloudEvent.getType()`.
3. **Back-pressure** — high-volume streams could overwhelm ganglia. What is the buffering/dropping policy?
4. **Situation deduplication** — if two events trigger the same case type within a short window, should the second be suppressed?
5. ~~**Platform stream module placement**~~ → **Resolved:** submodules of casehub-platform (Decision 2, parent P0 layering decisions).
6. **Drools CEP integration** — stateful KieSession per situation or shared session? Stateful per-situation is safer but more expensive.
7. **LlmGanglion latency** — LLM inference is slow. Should LlmGanglion always run async on a slow path with a timeout, while fast ganglia make immediate decisions?
8. **Multi-tenancy** — each tenant has independent SituationContext. How is tenant isolation enforced in the SituationStore and RasEngine? Tenancy extracted via `event.getExtension("tenancyid")`.
