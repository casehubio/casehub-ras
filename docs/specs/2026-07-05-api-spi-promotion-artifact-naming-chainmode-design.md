# API Module SPI Promotion, Artifact Naming, and ChainMode Extensions

**Date:** 2026-07-05
**Issues:** #27 (SPI promotion), #23 (subsumed by #27), #24 (artifact naming), #25 (ChainMode.Streak), #26 (ChainMode.Rate)
**Branch:** issue-27-api-spi-and-chainmode

## Problem

Three related architectural concerns in casehub-ras:

1. **SPI types misplaced in runtime** — `CorrelationKeyExtractor`, `SituationDefinitionProvider`, `SituationRegistration`, and `DefaultCorrelationKeyExtractor` live in `io.casehub.ras.runtime`. Domain adapter modules (e.g. `casehub-desiredstate/ras-adapter`) must depend on `casehub-ras` (runtime) instead of just `casehub-ras-api` to implement these SPIs.

2. **Artifact IDs don't match folder names** — `persistence-jpa/` produces `casehub-ras-jpa`; `persistence-memory/` produces `casehub-ras-memory`. The IoT webapp already references the folder-derived name `casehub-ras-persistence-jpa` (currently broken). Inconsistent within the repo and with the `{repo-prefix}-{folder}` convention.

3. **Missing ChainMode variants** — `casehub-desiredstate#59` (constraint/evaluation model) needs Streak (consecutive detection with ANTI reset) and Rate (ratio-based detection with sliding window). Neither is expressible with existing ChainMode variants. Count counts totals, not consecutive; Threshold sums confidence, not ratios.

## Design

### 1. SPI Promotion

Move 4 types from `io.casehub.ras.runtime` to `io.casehub.ras.api`:

| Type | Kind | Dependencies |
|------|------|-------------|
| `CorrelationKeyExtractor` | `@FunctionalInterface` | `CloudEvent` (platform-api, already in api) |
| `DefaultCorrelationKeyExtractor` | `final class` | `CloudEvent` |
| `SituationDefinitionProvider` | `interface` | `SituationRegistration` |
| `SituationRegistration` | `record` | `SituationDefinition`, `CorrelationKeyExtractor`, `DefaultCorrelationKeyExtractor` |

**Package:** `io.casehub.ras.api` (flat, alongside existing SPIs — Ganglion, RasTriggerPolicy, SituationStore, CaseTrigger, SituationSource). No subpackages.

**Scope note:** Issue #27 specifies three types; this spec promotes four, adding `DefaultCorrelationKeyExtractor`. The addition is required because `SituationRegistration`'s compact constructor references `DefaultCorrelationKeyExtractor.INSTANCE` — the two must move together.

**Rationale:** All 4 types have zero dependencies beyond what `casehub-ras-api` already provides. `DefaultCorrelationKeyExtractor` defines the API contract for default correlation key extraction (`CloudEvent.getSubject()` or `"_singleton"` when null) — this is an API concern, not an implementation detail.

**Impact within casehub-ras:**
- `runtime/` — delete 4 source files, update imports in `SituationDefinitionRegistry`, `YamlSituationDefinitionProvider`, `RasEngine`, test files
- No new dependencies in `api/pom.xml`
- `CLAUDE.md` — update api module description to include promoted types; runtime description loses four types

**Cross-repo impact (deferred):**
- `casehub-desiredstate/ras-adapter` — can drop `casehub-ras` (runtime) compile dep, keep only `casehub-ras-api`. 3 import changes in 2 files. File as follow-up issue on casehub-desiredstate.

### 2. Artifact Naming

| Module folder | Old artifactId | New artifactId |
|---------------|---------------|----------------|
| `persistence-jpa/` | `casehub-ras-jpa` | `casehub-ras-persistence-jpa` |
| `persistence-memory/` | `casehub-ras-memory` | `casehub-ras-persistence-memory` |

**Files changed:**
- `persistence-jpa/pom.xml` — `<artifactId>`
- `persistence-memory/pom.xml` — `<artifactId>`
- `pom.xml` (parent) — 2 entries in `<dependencyManagement>`
- `runtime/pom.xml` — test dep reference
- `CLAUDE.md` — module table

**Cross-repo impact (deferred):**
- `casehub-iot` — currently references `casehub-ras-persistence-jpa` (broken, this rename fixes it). No issue needed — it already uses the new name.
- Any other consumer referencing the old artifact IDs — file follow-up issues as discovered.

### 3. ChainMode.Streak

New variant on the `ChainMode` sealed interface.

```java
record Streak(String ganglionId, int requiredCount) implements ChainMode {
    public Streak {
        Objects.requireNonNull(ganglionId, "ganglionId");
        if (requiredCount < 1) {
            throw new IllegalArgumentException(
                    "requiredCount must be >= 1, got: " + requiredCount);
        }
    }
}
```

**Semantics:** Count consecutive DETECTED/WEAK signals from the named ganglion. ANTI from the same ganglion resets the streak to 0. NOISE is ignored (does not reset or increment). Trigger when streak reaches `requiredCount`.

**Why not overload Count:** Count = "this happened N times total" (cumulative). Streak = "this happened N times in a row without recovery" (consecutive). Distinct failure modes — a fraud alert that fired 3 times ever vs an infrastructure component that failed 3 times consecutively without recovery.

**`referencedGanglia()`:** `case Streak s -> Set.of(s.ganglionId());`

**Why single ganglion:** Streak tracks a single ganglion because consecutive semantics require a single signal source — interleaved signals from multiple ganglia have no natural consecutive interpretation. Rate uses multiple ganglia because ratio-based evaluation aggregates across sources (same as Threshold and Or).

**`DefaultRasTriggerPolicy.evaluateStreak()`:**
1. Filter detections to `ganglionId`
2. Sort filtered detections by `eventTime` (ascending)
3. Iterate sorted list:
   - WEAK or DETECTED → increment counter
   - ANTI → reset counter to 0
   - NOISE → skip
4. Return `counter >= requiredCount`

**YAML:**
```yaml
chainMode:
  type: streak
  ganglionId: fault-ganglion
  requiredCount: 3
```

**Files changed:**
- `api/.../ChainMode.java` — add `Streak` record variant
- `runtime/.../DefaultRasTriggerPolicy.java` — add `evaluateStreak()`, update `evaluate()` switch
- `runtime/.../YamlSituationDefinitionProvider.java` — add `"streak"` case in `parseChainMode()`
- `CLAUDE.md` — add STREAK to chain modes list in Routing Model section

### 4. ChainMode.Rate

New variant on the `ChainMode` sealed interface.

```java
record Rate(Set<String> ganglia, double minRate, int windowSize) implements ChainMode {
    public Rate {
        if (ganglia == null || ganglia.isEmpty()) {
            throw new IllegalArgumentException("ganglia must not be empty");
        }
        ganglia = Set.copyOf(ganglia);
        if (minRate <= 0.0 || minRate > 1.0) {
            throw new IllegalArgumentException(
                    "minRate must be in (0.0, 1.0], got: " + minRate);
        }
        if (windowSize < 1) {
            throw new IllegalArgumentException(
                    "windowSize must be >= 1, got: " + windowSize);
        }
    }
}
```

**Semantics:** Compute the ratio of qualifying signals (WEAK + DETECTED) to total scoreable signals (ANTI + WEAK + DETECTED) across the named ganglia, considering only the last `windowSize` scoreable signals. NOISE is excluded entirely — invisible to the window and the ratio. Trigger when `ratio >= minRate`.

**Window fullness:** If fewer than `windowSize` scoreable signals have accumulated, return `false`. A rate threshold of "60% of the last 10" must not trigger on 2/3 early signals — the window must be full for the rate to be meaningful.

**`referencedGanglia()`:** `case Rate r -> r.ganglia();`

**`DefaultRasTriggerPolicy.evaluateRate()`:**
1. Filter detections to named ganglia
2. Filter out NOISE (keep ANTI, WEAK, DETECTED)
3. Sort filtered detections by `eventTime` (ascending)
4. Take the last `windowSize` signals from the sorted list
5. If fewer than `windowSize` available → return `false`
6. Count qualifying (WEAK + DETECTED)
7. Return `(double) qualifying / windowSize >= minRate`

**YAML:**
```yaml
chainMode:
  type: rate
  ganglia: [fault-ganglion]
  minRate: 0.6
  windowSize: 10
```

**Files changed:**
- `api/.../ChainMode.java` — add `Rate` record variant
- `runtime/.../DefaultRasTriggerPolicy.java` — add `evaluateRate()`, update `evaluate()` switch
- `runtime/.../YamlSituationDefinitionProvider.java` — add `"rate"` case in `parseChainMode()`
- `CLAUDE.md` — add RATE to chain modes list in Routing Model section

### Design Constraint: Compaction and Detection-Count Chain Modes

For persistent situations (`correlationWindow = null`), `SituationEvaluator` calls `Ganglion.compact()` after every `CONTINUE_ACCUMULATING` decision. Ganglia that compact aggressively (e.g. `NaiveBayesGanglion`, which collapses all detections from its ganglionId into one) reduce the stored detection list. On the next event, the policy evaluates against this compacted list.

This affects any chain mode that depends on detection count or temporal ordering:

- **Streak:** After compaction, at most 2 detections from the ganglion exist (compacted + new). `requiredCount > 2` becomes unreachable. Compaction can also remove intermediate ANTI signals, causing missed streak resets.
- **Rate:** After compaction, fewer than `windowSize` scoreable signals may remain, causing the fullness check to always return `false`.
- **Count** (pre-existing): Same mechanism as Streak — `requiredCount > 2` on a compacting ganglion is unreachable on persistent situations.
- **Sequence** (pre-existing): Compaction shifts a ganglion's detection to a later timestamp (keeps latest, drops earlier), which can reorder it past subsequent ganglia in the sorted list and break the ordered match.

Modes that depend on existence (And, Or) or aggregate values (Threshold) are unaffected — Threshold is the intended pairing for `NaiveBayesGanglion`, where compaction preserves the running posterior as designed.

**Design constraint:** Ganglia referenced by Streak, Rate, Count, or Sequence on persistent situations should use no-op compaction — `JavaSwitchGanglion` provides this by default. Windowed situations are unaffected (compaction is skipped).

## Issue #23

Closed as duplicate of #27. #23 proposed moving `SituationDefinitionProvider` alone; #27 is the broader scope covering all 3 consumer-facing types plus `DefaultCorrelationKeyExtractor`.

## Deferred — GitHub Issues to File

| Concern | Target repo | Description |
|---------|------------|-------------|
| Drop runtime dep from ras-adapter | casehub-desiredstate | After SPI promotion, `ras-adapter/pom.xml` can remove `casehub-ras` (runtime) compile dep — only `casehub-ras-api` needed. Primary deliverable: remove `casehub-ras` dependency from `ras-adapter/pom.xml`. 3 import changes in 2 files (`DesiredStateCorrelationKeyExtractor`, `DesiredStateSituationDefinitionProvider`). |
| Platform-wide artifact naming audit | casehub-parent | `casehub-ledger` has same inconsistency: `persistence-memory/` → `casehub-ledger-memory`. Audit all repos for folder/artifact mismatches. |

## Breaking Changes

All breaking changes are internal to casehub-ras or affect pre-release consumers:

- **ChainMode sealed interface** — 2 new variants. Breaks exhaustive switches in `DefaultRasTriggerPolicy.evaluate()` (line 16), `ChainMode.referencedGanglia()` (line 10), and `YamlSituationDefinitionProvider.parseChainMode()` (string-based switch, line 111) — all within this repo. No external sealed-type switches found (verified: searched for `case ChainMode`, `instanceof ChainMode`, and ChainMode variant names in pattern-match position across all source directories; `casehub-desiredstate/ras-adapter` constructs ChainMode values but never switches on them).
- **Artifact renames** — downstream `<dependency>` entries referencing old artifact IDs will fail to resolve. IoT already uses the new name. Other consumers updated via follow-up issues.
- **Package moves** — `io.casehub.ras.runtime.{CorrelationKeyExtractor,SituationDefinitionProvider,SituationRegistration,DefaultCorrelationKeyExtractor}` → `io.casehub.ras.api.*`. Import changes in desiredstate via follow-up issue.
