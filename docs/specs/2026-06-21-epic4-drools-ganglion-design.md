# Epic 4: DroolsGanglion — Design Spec

**Date:** 2026-06-21 (revised 2026-06-21, fourth pass)
**Status:** Approved
**Issue:** casehubio/casehub-ras#4
**Depends on:** #1 (Core RAS API — done), #2 (RAS Runtime — open, not blocking module build)

---

## 1. Scope

Implement `DroolsGanglion` in the `ras-drools/` module — an optional `Ganglion` implementation
using classic Drools CEP (kie-api) for temporal pattern detection. Sliding time windows, event
correlation, temporal operators (`after`, `before`, `during`, `coincides`), accumulate functions.

**Also in scope:** Add `close(String situationId, String tenancyId)` default method to the
`Ganglion` SPI in `casehub-ras-api`. This is an API change to Epic 1's output, required to
close a lifecycle gap that affects all stateful ganglion implementations (§6.8).

**Not in scope:** Persistent session serialization (#7), configurable result collection strategy (#8),
hot rule reload (#9), Quarkus extension integration, Rule Units API.

---

## 2. Module Structure and Dependencies

`ras-drools/` is an optional Jandex library module per the optional-module-pattern protocol.
Adding it to the classpath activates Drools CEP detection; omitting it has zero impact.

**Package:** `io.casehub.ras.drools`

**Dependencies:**
- `casehub-ras-api` (compile) — `Ganglion`, `DetectionResult`, `SituationContext`, `CloudEvent`
- `org.drools:drools-model-codegen:10.1.0` (compile) — `ExecutableModelProject`, DRL compilation
  via executable model. Transitively provides `drools-model-compiler`, `drools-compiler`,
  `drools-core`, `drools-kiesession`, `kie-api`.
- `org.drools:drools-wiring-static:10.1.0` (runtime) — static classloading, no dynamic bytecode
- `io.quarkus:quarkus-arc` (compile) — CDI annotations
- `io.smallrye.reactive:mutiny` (provided) — `Uni<T>` in SPI signatures

No dependency on `casehub-ras` (runtime), `casehub-engine-api`, or any transport library.

**No Rule Units. No `drools-quarkus` extension.** Classic kie-api only — `KieBase`/`KieSession` with
`EventProcessingOption.STREAM` for CEP.

**Public types (7):**

| Type | Kind | Purpose |
|------|------|---------|
| `DroolsGanglion` | class | Implements `Ganglion`, orchestrates detect flow |
| `DroolsGanglionConfig` | record | Immutable configuration |
| `SessionMode` | enum | `LONG_LIVED`, `EPHEMERAL` |
| `ClockMode` | enum | `PSEUDO`, `REALTIME` |
| `DroolsObjectExtractor` | interface | SPI: CloudEvent → domain object extraction |
| `DroolsSessionStore` | interface | SPI: KieSession lifecycle management |
| `InMemoryDroolsSessionStore` | class | `@DefaultBean` in-memory implementation |

**Package-private:**
- `ResultCollectorChannel` — Drools `Channel` impl that collects emitted `DetectionResult`s

---

## 3. DroolsGanglionConfig

```java
public record DroolsGanglionConfig(
    String ganglionId,
    Set<String> handledEventTypes,
    SessionMode sessionMode,
    ClockMode clockMode,
    List<String> classpathRules,
    List<String> programmaticRules
) {
    public DroolsGanglionConfig {
        Objects.requireNonNull(ganglionId);
        if (handledEventTypes == null || handledEventTypes.isEmpty()) {
            throw new IllegalArgumentException("handledEventTypes must not be empty");
        }
        handledEventTypes = Set.copyOf(handledEventTypes);
        if (classpathRules == null) classpathRules = List.of();
        if (programmaticRules == null) programmaticRules = List.of();
        classpathRules = List.copyOf(classpathRules);
        programmaticRules = List.copyOf(programmaticRules);
        if (classpathRules.isEmpty() && programmaticRules.isEmpty()) {
            throw new IllegalArgumentException("At least one rule source required");
        }
    }
}
```

```java
public enum SessionMode { LONG_LIVED, EPHEMERAL }
public enum ClockMode { PSEUDO, REALTIME }
```

---

## 4. DroolsObjectExtractor SPI

```java
public interface DroolsObjectExtractor {

    Set<String> handledEventTypes();

    List<Object> extract(CloudEvent event);
}
```

**Contract:**
- `handledEventTypes()` — CloudEvent types this extractor can decompose. Matched to ganglion
  instances at startup by event type overlap.
- `extract(CloudEvent)` — Converts a CloudEvent into zero or more domain objects for insertion
  into the KieSession. Returns empty list if no extractable domain data. The raw CloudEvent is
  always inserted separately by the ganglion regardless of what extractors return.

**Matching rules:**
- At startup, the ganglion finds all `DroolsObjectExtractor` CDI beans whose
  `handledEventTypes()` overlaps with the ganglion's own `handledEventTypes`.
- Multiple extractors can handle the same event type — all are called, all results inserted.
- No extractor for an event type is valid — the CloudEvent is still inserted raw.
  Extractors are optional enrichment, not required.

Extractor implementations live in the consumer's module, not in `ras-drools`.

---

## 5. DroolsSessionStore SPI

```java
public interface DroolsSessionStore {

    Optional<KieSession> get(String ganglionId, String situationId, String tenancyId);

    void put(String ganglionId, String situationId, String tenancyId, KieSession session);

    void remove(String ganglionId, String situationId, String tenancyId);
}
```

**Contract:**
- `get()` — Retrieve a live KieSession for a ganglion's situation instance. Returns empty if
  none exists.
- `put()` — Store a KieSession. Upsert semantics.
- `remove()` — Dispose the `KieSession` and remove it from the store. The caller must not use
  the session after calling `remove()`. The store owns disposal — it holds the session reference,
  so it is responsible for calling `KieSession.dispose()` before eviction. No-op if no session
  exists for the key.

**Keyed by `(ganglionId, situationId, tenancyId)`.** The `ganglionId` is required because
multiple `DroolsGanglion` instances may share a single `@ApplicationScoped` store (CDI
singleton) while processing the same situation. Under `ChainMode.And`, `Sequence`, `Threshold`,
or `Or`, the engine dispatches to multiple ganglia for the same `(situationId, tenancyId)`. Without
`ganglionId` in the key, one ganglion's session overwrites another's — data corruption.

**InMemoryDroolsSessionStore** — `@DefaultBean`, `@ApplicationScoped`,
`ConcurrentHashMap<SessionKey, KieSession>`-backed where `SessionKey` is a composite of
`(ganglionId, situationId, tenancyId)`. Any consumer `@ApplicationScoped` implementation
displaces it.

Only used in `LONG_LIVED` mode. In `EPHEMERAL` mode the ganglion creates and disposes the
session within a single `detect()` call — the store is never consulted.

**Limitation:** This SPI stores live `KieSession` objects. It is inherently an in-memory-only
contract. `KieSession` cannot be reliably serialized in Drools 10, so a persistent implementation
would require a fundamentally different approach — storing the inserted facts and replaying them
into a fresh session on retrieval. If #7 is pursued, the SPI will need to change to a
fact-replay model. The deferred item description reflects this.

---

## 6. DroolsGanglion

### 6.1 Construction

```java
public class DroolsGanglion implements Ganglion {

    public static final String RESULT_CHANNEL = "results";

    private final DroolsGanglionConfig config;
    private final KieBase kieBase;
    private final DroolsSessionStore sessionStore;
    private final List<DroolsObjectExtractor> extractors;

    public DroolsGanglion(DroolsGanglionConfig config,
                          DroolsSessionStore sessionStore,
                          List<DroolsObjectExtractor> extractors) {
        this.config = config;
        this.sessionStore = sessionStore;
        this.extractors = List.copyOf(extractors);
        this.kieBase = buildKieBase(config);
    }
}
```

Not CDI-managed itself. Consumers produce CDI beans from it via `@Produces` methods — one
instance or many, one global or many scoped, consumer decides the topology.

### 6.2 KieBase construction

```java
private KieBase buildKieBase(DroolsGanglionConfig config) {
    KieServices ks = KieServices.Factory.get();
    KieFileSystem kfs = ks.newKieFileSystem();
    for (String path : config.classpathRules()) {
        kfs.write(ks.getResources().newClassPathResource(path));
    }
    for (int i = 0; i < config.programmaticRules().size(); i++) {
        kfs.write("src/main/resources/programmatic-" + i + ".drl",
                   config.programmaticRules().get(i));
    }
    KieBuilder kb = ks.newKieBuilder(kfs)
            .buildAll(ExecutableModelProject.class);
    Results results = kb.getResults();
    if (results.hasMessages(Message.Level.ERROR)) {
        throw new IllegalStateException(
            "DRL compilation failed for ganglion '" + config.ganglionId()
            + "': " + results.getMessages());
    }
    KieModule module = kb.getKieModule();
    KieBaseConfiguration kbc = ks.newKieBaseConfiguration();
    kbc.setOption(EventProcessingOption.STREAM);
    return ks.newKieContainer(module.getReleaseId()).newKieBase(kbc);
}
```

`EventProcessingOption.STREAM` is set unconditionally — this is a CEP ganglion.
`ExecutableModelProject.class` uses the Drools 10 executable model compilation path (the
deprecated DRL-to-bytecode path is avoided).

### 6.3 Session creation

```java
private KieSession createSession() {
    KieSessionConfiguration ksc = KieServices.Factory.get()
            .newKieSessionConfiguration();
    if (config.clockMode() == ClockMode.PSEUDO) {
        ksc.setOption(ClockTypeOption.PSEUDO);
    }
    return kieBase.newKieSession(ksc, null);
}
```

### 6.4 detect() flow

1. **Get or create session** — `LONG_LIVED`: `sessionStore.get(ganglionId, situationId, tenancyId)`,
   create new if absent. `EPHEMERAL`: create fresh session.
2. **Register channel** — Register a `RESULT_CHANNEL` channel (`ResultCollectorChannel`) on the
   session.
3. **Advance clock** (pseudo mode) — Compute delta from current clock time to
   `CloudEvent.getTime()`. If the delta is negative, throw `IllegalStateException` — the caller
   violated the monotonic ordering precondition (§8.1). Advance `SessionPseudoClock` by the delta.
4. **Insert CloudEvent** — Always inserted as a fact. Retain the `FactHandle`.
5. **Extract and insert domain objects** — Call each matching extractor's `extract(event)`,
   insert all returned objects.
6. **Fire rules** — `session.fireAllRules()`.
7. **Retract CloudEvent** — `session.delete(cloudEventFactHandle)`. The CloudEvent is per-call
   metadata — it was available for rule evaluation (e.g. `CloudEvent(type == "...")`) but has no
   purpose after firing. Cross-call temporal correlation uses extracted domain objects, which have
   proper `@role(event)` / `@timestamp` / `@expires` declarations. Retraction prevents unbounded
   accumulation of CloudEvent facts in LONG_LIVED sessions.
8. **Collect result** — Read `DetectionResult` from the channel. If no rule fired, return a NOISE
   result with zero confidence.
9. **Unregister channel** — Remove `RESULT_CHANNEL` channel from the session.
10. **Session lifecycle** — `LONG_LIVED`: `sessionStore.put(ganglionId, ...)`. `EPHEMERAL`:
    `session.dispose()`.
11. **Return** — `Uni.createFrom().item(result)`.

**Error handling (LONG_LIVED mode):** If any step from 3 through 8 throws an exception, the
session is in an inconsistent state (partially inserted facts, partially evaluated rules). Drools
has no transactional rollback. The ganglion must: unregister the channel, explicitly dispose the
session, then call `sessionStore.remove(ganglionId, situationId, tenancyId)`. Explicit `dispose()`
handles the case where the session was newly created in step 1 and never stored — `remove()` would
be a no-op for it. For sessions already in the store, `remove()` handles disposal internally
(`dispose()` is idempotent). The next `detect()` call creates a fresh session — accumulated event
history is lost, but correctness is preserved. The exception is propagated to the caller, not
silently swallowed.

**Error handling (EPHEMERAL mode):** Dispose the session on any exception. No store interaction
needed. Propagate the exception.

**The `SituationContext` parameter is used only for `situationId` and `tenancyId` (session store
lookup). The detection data (`detections`, `firstSignal`, `lastSignal`) is not inserted into the
KieSession. The ganglion maintains its own state via the KieSession's working memory, independent
of the engine's accumulated detections.**

### 6.5 Channel and result collection

```java
class ResultCollectorChannel implements Channel {
    private DetectionResult result;

    public void send(Object object) {
        if (object instanceof DetectionResult dr) {
            result = dr;
        }
    }

    DetectionResult getResult() { return result; }
}
```

Channel is registered before firing, result collected after, channel unregistered before session
storage. Last-write-wins if multiple rules fire. Future: #8 tracks configurable collection
strategies.

### 6.6 compact()

No override. The default no-op from the `Ganglion` SPI is correct for both session modes.

For `LONG_LIVED` sessions, Drools STREAM mode handles event expiration automatically via
`@expires` annotations on declared event types in DRL. When an event is outside all active
windows and older than its `@expires` duration, Drools garbage-collects it. DRL rules in
`LONG_LIVED` mode should declare `@expires` on event types to control memory growth.

For `EPHEMERAL` sessions, no events persist between calls.

### 6.7 Ganglion SPI methods

```java
public String ganglionId() { return config.ganglionId(); }
public Set<String> handledEventTypes() { return config.handledEventTypes(); }
```

### 6.8 close() — Ganglion SPI addition

The `Ganglion` SPI (in `casehub-ras-api`) gains a new default method:

```java
default Uni<Void> close(String situationId, String tenancyId) {
    return Uni.createFrom().voidItem();
}
```

Returns `Uni<Void>` per spi-reactive-blocking-io protocol — future implementations may perform
I/O during cleanup (persistent session store removal, remote LLM context release). Stateless
ganglia inherit the default which returns an immediately-resolved `Uni`.

**Why this change is needed:** When the engine terminates a situation (CREATE_CASE or DISCARD),
it calls `SituationStore.remove(situationId, tenancyId)`. But no corresponding lifecycle method
exists on `Ganglion` to notify the ganglion. For stateful ganglia like `DroolsGanglion`, this
means the `DroolsSessionStore` holds orphaned `KieSession` objects indefinitely — an unbounded
memory leak.

This is not DroolsGanglion-specific. Any stateful ganglion (Bayesian accumulator, LLM with
cached context, future CEP variants) has the same problem. The gap is in the SPI, not in any
single implementation.

**DroolsGanglion override:**

```java
@Override
public Uni<Void> close(String situationId, String tenancyId) {
    sessionStore.remove(config.ganglionId(), situationId, tenancyId);
    return Uni.createFrom().voidItem();
}
```

`sessionStore.remove()` disposes the `KieSession` and removes it from the store (§5).

**Engine contract:** The engine (Epic 2) must call `ganglion.close(situationId, tenancyId)` for
every ganglion that participated in a situation when that situation terminates. This call happens
after `SituationStore.remove()` — the situation is already gone, the ganglion is just cleaning up
its private state. `close()` must be the final call for a given `(situationId, tenancyId)` — no
`detect()` or `compact()` may follow.

**Impact on existing code:**
- `Ganglion` interface: one new default method — backward compatible
- `MockGanglion`: no change needed (inherits default no-op)
- `GanglionContractTest`: add a test verifying `close()` compiles without override
- CLAUDE.md: update Core SPIs section to include `close()`

---

## 7. Concurrency Contract

**`KieSession` is not thread-safe.** Concurrent operations on the same session would corrupt the
Rete network's internal state.

**Contract: the runtime serializes all ganglion operations per situation key.** The `RasEngine`
(Epic 2) must guarantee that only one ganglion operation — `detect()`, `compact()`, or `close()`
— is in flight per `(situationId, tenancyId)` at any time for a given ganglion instance. This is
a precondition on the caller, not a responsibility of `DroolsGanglion` or `DroolsSessionStore`.

**`close()` is terminal.** Once the runtime calls `close(situationId, tenancyId)`, no subsequent
`detect()` or `compact()` may be called for that key. The session has been disposed — any further
operation would fail.

This is the architecturally correct placement — the engine already manages the situation lifecycle
(find/create `SituationContext`, dispatch to ganglia, evaluate chain modes). Per-key serialization
belongs at the dispatch point, not inside a detection unit or a storage SPI. Putting locking
inside the ganglion or the store would duplicate a concern the runtime already owns.

**Cross-situation concurrency is safe.** Different `(situationId, tenancyId)` tuples use different
`KieSession` instances. The ganglion and session store are safe for concurrent calls to different
situations.

---

## 8. Clock and Event Ordering

### 8.1 Pseudo clock mode (default)

`SessionPseudoClock.advanceTime(long amount, TimeUnit unit)` takes a positive delta — the clock
cannot go backwards. Out-of-order events (event at T=5 arriving after event at T=10) would
produce incorrect temporal evaluation: the T=5 event would enter working memory with the session
clock at T=10, causing temporal operators and sliding windows to evaluate against the wrong time
context.

**Precondition: pseudo clock mode requires events to be dispatched in non-decreasing timestamp
order.** The runtime (Epic 2) is responsible for ordering events by `CloudEvent.getTime()` before
dispatching to the ganglion. The runtime already has the event timestamp — it uses it for
`SituationContext.withDetection()`. Sorting at the dispatch boundary is a runtime concern, not
a ganglion concern.

**Defensive check:** The ganglion verifies the precondition on each `detect()` call. If the
computed clock delta is negative (`eventTime < clock.getCurrentTime()`), the ganglion throws
`IllegalStateException` with the ganglion ID, event time, and current clock time. This surfaces
runtime ordering bugs immediately instead of silently producing incorrect temporal evaluations.

### 8.2 Realtime clock mode

Events are timestamped at insertion using wall-clock time. Out-of-order delivery is handled
naturally — the clock is monotonic regardless of event arrival order. Temporal operators evaluate
against insertion time, not event time.

Trade-off: realtime mode makes testing non-deterministic. Use pseudo clock for tests, realtime
for deployments where event ordering cannot be guaranteed and wall-clock semantics are acceptable.

---

## 9. Session Modes

### 9.1 LONG_LIVED

A `KieSession` persists across multiple `detect()` calls for the same situation instance. Events
accumulate in working memory. Temporal operators (`after`, `before`, `during`, `coincides`) and
sliding windows (`over window:time(...)`) evaluate across the full event history in the session.

Session is stored via `DroolsSessionStore` after each `detect()` call.

**DRL rules in LONG_LIVED mode should declare `@expires` on event types** to control memory
growth. Drools automatically garbage-collects events that are outside all active windows and
older than their `@expires` duration.

### 9.2 EPHEMERAL

A fresh `KieSession` is created and disposed per `detect()` call. The session contains only the
current CloudEvent and its extracted domain objects. The `DroolsSessionStore` is never consulted.

**EPHEMERAL mode is for non-temporal rules only.** Temporal operators and sliding windows require
event history across multiple insertions — they cannot match with a single event. Use EPHEMERAL
for complex stateless pattern matching: multi-field conditions, accumulate over data fields
within a single event's payload.

Use `LONG_LIVED` mode for any rule that requires temporal correlation across events.

---

## 10. Consumer Wiring Pattern

Consumers register `DroolsGanglion` as a CDI `Ganglion` bean via `@Produces`:

```java
@ApplicationScoped
public class MyGanglionProducer {

    @Inject
    DroolsSessionStore sessionStore;

    @Inject
    Instance<DroolsObjectExtractor> extractors;

    @Startup
    @Produces
    @ApplicationScoped
    Ganglion temperatureGanglion() {
        var config = new DroolsGanglionConfig(
                "temperature-cep",
                Set.of("temperature.reading"),
                SessionMode.LONG_LIVED,
                ClockMode.PSEUDO,
                List.of("META-INF/drools/temperature-rules.drl"),
                List.of());

        var matched = extractors.stream()
                .filter(e -> e.handledEventTypes().stream()
                        .anyMatch(config.handledEventTypes()::contains))
                .toList();

        return new DroolsGanglion(config, sessionStore, matched);
    }
}
```

**`@Startup` on the `@Produces` method** ensures the producer method is invoked eagerly at
application startup. In Quarkus, `@Startup` on a bean class only creates the class instance and
calls `@PostConstruct` — it does NOT invoke `@Produces` methods. Placing `@Startup` on the
producer method itself generates a synthetic `StartupEvent` observer for the produced bean,
ensuring `DroolsGanglion` construction (and KieBase/DRL compilation) happens at deployment time.
DRL compilation errors surface immediately, not on first use.

Multiple ganglia: multiple `@Produces` methods, each with `@Startup`. The engine sees independent
`Ganglion` beans. The producer lives in the consumer's module, not in `ras-drools`.

---

## 11. Example DRL Rules

### 11.1 LONG_LIVED: Temporal CEP rule

```drl
package io.casehub.ras.examples;

import io.cloudevents.CloudEvent;
import io.casehub.ras.api.DetectionResult;
import io.casehub.ras.api.DetectionSignal;
import java.util.Map;

declare TemperatureReading
    @role(event)
    @timestamp(readingTime)
    @expires(30m)
    sensorId : String
    celsius : double
    readingTime : long
end

rule "Temperature spike detected"
when
    $t1 : TemperatureReading(celsius > 80.0) over window:time(10m)
    $t2 : TemperatureReading(
        sensorId == $t1.sensorId,
        celsius > $t1.celsius + 20.0,
        this after[0s, 10m] $t1)
then
    channels["results"].send(new DetectionResult(
        "temperature-cep",
        0.85,
        DetectionSignal.DETECTED,
        Map.of("sensorId", $t1.getSensorId(),
               "initialTemp", $t1.getCelsius(),
               "spikeTemp", $t2.getCelsius())));
end
```

- `@expires(30m)` controls memory growth — Drools GC's events after 30 minutes
- Temporal operators (`after`, `over window:time`) work because `EventProcessingOption.STREAM`
  is set and the pseudo clock advances to event time
- Results go to `channels["results"]` — ganglion collects via `ResultCollectorChannel`
- Empty channel → ganglion returns NOISE with zero confidence

### 11.2 EPHEMERAL: Non-temporal pattern matching

```drl
package io.casehub.ras.examples;

import io.cloudevents.CloudEvent;
import io.casehub.ras.api.DetectionResult;
import io.casehub.ras.api.DetectionSignal;
import java.util.Map;

rule "High-value transaction from flagged source"
when
    $ce : CloudEvent(type == "transaction.completed")
    $tx : TransactionRecord(
        amount > 10000.0,
        sourceCountry in ("XX", "YY", "ZZ"),
        recipientType == "SHELL_COMPANY")
then
    channels["results"].send(new DetectionResult(
        "aml-pattern-match",
        0.70,
        DetectionSignal.DETECTED,
        Map.of("amount", $tx.getAmount(),
               "source", $tx.getSourceCountry(),
               "recipient", $tx.getRecipientId())));
end
```

- No `@role(event)`, no `@timestamp`, no temporal operators — stateless rule
- `TransactionRecord` inserted by a `DroolsObjectExtractor`
- `CloudEvent` also available for metadata checks
- Works in EPHEMERAL mode — no cross-event history needed

---

## 12. Deferred Items

| Item | Issue | Reason |
|------|-------|--------|
| Persistent DroolsSessionStore | #7 | Current SPI stores live `KieSession` objects — inherently in-memory-only. A persistent impl requires a fact-replay model (store inserted facts, replay into fresh session on retrieval). The SPI will need to change. `KieSession` cannot be reliably serialized in Drools 10. |
| Configurable result collection strategy | #8 | Last-write-wins sufficient for first iteration |
| Hot rule reload without restart | #9 | KieBase rebuild + session migration — complex |
| Rule Units / drools-quarkus extension | — | Rejected: too heavy, not needed |

---

## 13. Protocol Compliance

| Protocol | Status |
|----------|--------|
| optional-module-pattern | ✅ Jandex library, classpath activation |
| module-tier-structure | ✅ Depends on Tier 1 api/, no JPA, no engine-api |
| cdi-classpath-presence-requires-module-separation | ✅ @DefaultBean in ras-drools, consumer impl in separate module |
| engine-api-scope-rule | ✅ No casehub-engine-api dependency |
| library-jars-require-jandex | ✅ jandex-maven-plugin in build |
| spi-reactive-blocking-io | ✅ close() returns Uni\<Void\> — consistent with detect(), compact() |
