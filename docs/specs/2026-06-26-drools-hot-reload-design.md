# DroolsGanglion Hot Rule Reload — Design Spec

**Issue:** casehubio/casehub-ras#9
**Date:** 2026-06-26
**Depends on:** #4 (DroolsGanglion — done)
**Prior spec:** `2026-06-21-epic4-drools-ganglion-design.md` §6, §7, §10

## Problem

`DroolsGanglion` builds a `final KieBase` at construction time from `DroolsGanglionConfig`
rule sources (classpath and programmatic). Changing rules requires restarting the application.

## Design

### 1. Decompose config — eliminate stale state

`DroolsGanglion` currently stores `private final DroolsGanglionConfig config` (Epic 4 §6.1).
After a `reload()` call, the config's rule lists would be stale while the rest (ganglionId,
sessionMode, etc.) remain correct.

Fix: the constructor extracts individual fields and does not store the config object. The
config record remains the construction parameter (Epic 4 §10 consumer wiring pattern unchanged).

```java
private final String ganglionId;
private final Set<String> handledEventTypes;
private final SessionMode sessionMode;
private final ClockMode clockMode;
private final ResultCollectionStrategy resultCollectionStrategy;
private final DroolsSessionStore sessionStore;
private final List<DroolsObjectExtractor> extractors;
private volatile KieBase kieBase;
private volatile long reloadGeneration = 0;
private ReleaseId currentReleaseId;  // accessed only in synchronized reload() and constructor
private final ConcurrentHashMap<SessionGenKey, Long> sessionGenerations = new ConcurrentHashMap<>();

private record SessionGenKey(String situationId, String correlationKey, String tenancyId) {}
```

`SessionGenKey` is a record (not string concatenation) — matching the
`InMemoryDroolsSessionStore.SessionKey` pattern. Records provide correct
`equals()`/`hashCode()` with zero collision risk.

### 2. Volatile KieBase with consistent snapshot flow

`detect()` captures both volatile fields at method entry and passes the KieBase through
the entire call chain — no re-reads.

**Read order matters.** `reloadGeneration` is the "flag" and `kieBase` is the "data." The
standard acquire-release pattern requires: writer writes data then flag; reader reads flag
then data. This ensures a happens-before chain from the writer's data write to the reader's
data read (JLS §17.4.5).

```java
public Uni<DetectionResult> detect(CloudEvent event, SituationContext context) {
    long currentGen = this.reloadGeneration;  // volatile read: FLAG first
    KieBase currentBase = this.kieBase;       // volatile read: DATA second
    // ...
    session = createSession(currentBase);
    // ...
}

private KieSession createSession(KieBase base) {
    KieSessionConfiguration ksc = KieServices.Factory.get().newKieSessionConfiguration();
    if (clockMode == ClockMode.PSEUDO) {
        ksc.setOption(ClockTypeOption.PSEUDO);
    }
    return base.newKieSession(ksc, null);
}
```

**Why this order:** if `detect()` reads `reloadGeneration=N` (written by reload), then
by the happens-before chain:

    write(kieBase)  hb  write(reloadGeneration)    [program order in reload]
                    hb  read(reloadGeneration)     [volatile sync, same variable]
                    hb  read(kieBase)              [program order in detect]

By transitivity: `write(kieBase)` hb `read(kieBase)` — detect is guaranteed to see the
KieBase that was written before generation N. Reading in the reverse order (kieBase first)
breaks this chain — the JMM permits reading old kieBase and new reloadGeneration, leaving
a session permanently stuck on old rules.

### 3. Lazy invalidation via generation counter

Pure drain leaves persistent situations (`correlationWindow = null`) with long-lived sessions
that never converge to new rules — the session uses old rules indefinitely until an external
trigger. For buggy rules, this means the situation accumulates events under incorrect logic
with no self-correcting mechanism.

Instead of drain: lazy invalidation. `DroolsGanglion` tracks a generation counter. On
`detect()`, after retrieving a long-lived session from the store, if the session's recorded
generation is less than current, it is disposed and a new session is created from the current
KieBase — within SituationEvaluator's existing per-key synchronized context (Epic 4 §7).

```java
// In detect(), for LONG_LIVED mode:
var genKey = new SessionGenKey(situationId, correlationKey, tenancyId);
KieSession session = sessionStore.get(ganglionId, situationId, correlationKey, tenancyId)
        .orElse(null);
Long storedGen = sessionGenerations.get(genKey);

if (session != null && (storedGen == null || storedGen < currentGen)) {
    sessionStore.remove(ganglionId, situationId, correlationKey, tenancyId);
    session = null;
}

if (session == null) {
    session = createSession(currentBase);
    isNewSession = true;
}

// After session store put:
sessionGenerations.put(genKey, currentGen);
```

**Trade-off:** disposing a long-lived session loses accumulated CEP temporal state (sliding
windows, event history). But that state was computed under old (potentially buggy) rules.
The consumer calls `reload()` explicitly, accepting this trade-off.

**close() cleanup:**
```java
public Uni<Void> close(String situationId, String correlationKey, String tenancyId) {
    sessionStore.remove(ganglionId, situationId, correlationKey, tenancyId);
    sessionGenerations.remove(new SessionGenKey(situationId, correlationKey, tenancyId));
    return Uni.createFrom().voidItem();
}
```

**Pre-existing double dispose in error handler.** The existing `detect()` error path
(lines 82-89) calls `session.dispose()` unconditionally, then `sessionStore.remove()` for
non-new sessions — double dispose for the non-new case. Fix: branch on `isNewSession`. New
sessions: explicit `dispose()` (not in store). Non-new sessions: delegate to
`sessionStore.remove()` (store owns disposal per Epic 4 §5).

**No removeAll() on DroolsSessionStore SPI.** A bulk-remove operation races with in-flight
`detect()` calls — the per-key serialization in SituationEvaluator doesn't cover cross-key
operations from outside the ganglion call chain. `InMemoryDroolsSessionStore` retains a
concrete `removeAll(String ganglionId)` for test cleanup only.

### 4. Synchronized reload()

`reload()` is synchronized to serialize KieBase swap + generation increment across
concurrent administrative calls. Without this, concurrent reloads can lose generation
increments (both read gen=0, both write gen=1), leaving sessions created between the
reloads permanently on stale rules.

```java
public synchronized void reload(List<String> classpathRules, List<String> programmaticRules) {
    if (classpathRules.isEmpty() && programmaticRules.isEmpty()) {
        throw new IllegalArgumentException("At least one rule source required");
    }
    KieBase newBase = buildKieBase(classpathRules, programmaticRules);
    this.kieBase = newBase;       // volatile write: DATA
    this.reloadGeneration++;      // volatile write: FLAG (safe under synchronized — no lost updates)
}
```

`detect()` does NOT acquire the lock — no performance impact on the event processing path.
The one-call delay (detect captures before reload completes) is acceptable: the session is
invalidated on the next detect call.

With reload() synchronized, `currentReleaseId` drops volatile — it's only accessed inside
the synchronized method and the constructor (pre-publication). `kieBase` and
`reloadGeneration` remain volatile — they're read by unsynchronized `detect()` calls.

### 5. Unique release ID per compilation with correct error ordering

`KieRepository` is a JVM singleton (Drools 10.1.0). The current `buildKieBase()` uses the
default release ID, so repeated compilations overwrite each other in the repository.

Fix: generate a unique release ID per invocation. Create the KieBase BEFORE updating
tracking — if `newKieBase()` throws, `currentReleaseId` is unchanged and the old module
stays in the repository. The failed build's module leaks (one entry, bounded, harmless).

```java
private KieBase buildKieBase(List<String> classpathRules, List<String> programmaticRules) {
    KieServices ks = KieServices.Factory.get();
    KieFileSystem kfs = ks.newKieFileSystem();
    ReleaseId rid = ks.newReleaseId("io.casehub.ras.drools", ganglionId,
            String.valueOf(System.nanoTime()));
    kfs.generateAndWritePomXML(rid);
    for (String path : classpathRules) {
        kfs.write(ks.getResources().newClassPathResource(path));
    }
    for (int i = 0; i < programmaticRules.size(); i++) {
        kfs.write("src/main/resources/programmatic-" + i + ".drl", programmaticRules.get(i));
    }
    KieBuilder kb = ks.newKieBuilder(kfs).buildAll(ExecutableModelProject.class);
    Results results = kb.getResults();
    if (results.hasMessages(Message.Level.ERROR)) {
        throw new IllegalStateException(
                "DRL compilation failed for ganglion '" + ganglionId
                + "': " + results.getMessages());
    }
    var kbc = ks.newKieBaseConfiguration();
    kbc.setOption(EventProcessingOption.STREAM);
    KieBase result = ks.newKieContainer(rid).newKieBase(kbc);

    // Update tracking AFTER successful KieBase creation
    ReleaseId oldRid = this.currentReleaseId;
    this.currentReleaseId = rid;
    if (oldRid != null) {
        ks.getRepository().removeKieModule(oldRid);
    }
    return result;
}
```

### 6. Thread safety summary

- `volatile KieBase` + `volatile long reloadGeneration` — read in detect(), written in
  synchronized reload().
- detect() reads flag (reloadGeneration) FIRST, then data (kieBase) — acquire-release
  pattern ensures JMM-correct visibility across all architectures.
- reload() writes data (kieBase) FIRST, then flag (reloadGeneration) — matching release order.
- `createSession(KieBase base)` receives the captured reference — no re-read.
- `SituationEvaluator` serializes `detect()` per situation key (Epic 4 §7).
- Generation check happens within that serialized context — no race with concurrent detect.
- `synchronized reload()` serializes administrative calls — no lost generation increments.
- `currentReleaseId` is not volatile — accessed only under synchronized or pre-publication.
- If compilation fails, old KieBase and generation remain — no partial state.
- Concurrent reload + detect: detect captures before reload completes → one-call delay,
  then session is invalidated on next call.

## Out of scope

- Rule source delivery mechanism (filesystem watcher, API endpoint, config reload) — how
  rules arrive is a deployment concern. Consumer `@Produces` method (Epic 4 §10) holds the
  `DroolsGanglion` reference and calls `reload()` when it has new rules.
- Reloadable interface on the Ganglion SPI — hot reload is Drools-specific (compiled rule
  base). JavaSwitchGanglion (compiled Java), NaiveBayesGanglion (config-driven posteriors),
  and LlmGanglion (external model) have no equivalent concept.
- KieSession fact migration — KieSessions are bound to their KieBase's rete network.
  Migration is architecturally impossible. Lazy invalidation (dispose + recreate) is the
  correct mechanism.

## Test plan

1. `reload()` swaps KieBase — ephemeral-mode detection uses new rules immediately.
2. Compilation failure leaves old KieBase and generation intact — detections continue working.
3. `reload()` with empty rule lists throws `IllegalArgumentException`.
4. Lazy invalidation: long-lived session created before reload is disposed on next `detect()`
   call. New session uses new rules. Verified by changing a rule's confidence output.
5. Full lifecycle: detect with old rules → reload → next detect for same situation key uses
   new rules → verify new rules produced the result.
6. Generation counter: session created at generation 0 is invalidated after reload increments
   to generation 1. Session created at generation 1 survives until next reload.
7. close() cleans up generation entry — no stale entries accumulate in sessionGenerations.
8. `InMemoryDroolsSessionStore.removeAll()` disposes all sessions for a ganglion (test
   utility verification — not SPI).
