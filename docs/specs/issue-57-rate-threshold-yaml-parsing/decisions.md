## D1: Eliminate IoT parser duplication

**Choice:** IoT delegates to `YamlSituationDefinitionProvider` directly — deletes its inner `YamlParser` class entirely, migrates YAML files to canonical schema
**Alternatives:**
- Extract `SituationYamlParser` utility class — adds unnecessary indirection; IoT still maintains its own calling code
- Make parsing methods public on `YamlSituationDefinitionProvider` — pollutes the provider's API surface with implementation details
**Rationale:** IoT shouldn't have its own parser. The duplication is the design flaw. `YamlSituationDefinitionProvider` can be instantiated directly with a classpath resource path — IoT calls `new YamlSituationDefinitionProvider("META-INF/ras-iot-situations.yaml")` and gets `registrations()` back. IoT YAML migrates `triggerConfig` → `triggerAction`, `requiredGanglia` → `ganglia`. IoT gains streak, rate, templates, expressions, ganglia descriptors, and feedback config support automatically.
**Trade-offs:** IoT YAML files need schema migration (pre-release, zero cost). IoT depends on `casehub-ras` runtime (likely already does for RasEngine).
**Sources:** `YamlSituationDefinitionProvider` constructor, IoT `JpaRuntimeSituationDefinitionProvider.YamlParser`, issue #55 body
**Exploration:** quick
**Status:** captured

## D2: FeedbackState naming — keep threshold terminology

**Choice:** Keep `effectiveThreshold()` / `applyThresholdOverride()` unchanged
**Alternatives:**
- Rename to `effectiveParameter` / `applyParameterOverride` — more generic but loses semantic clarity
**Rationale:** `minRate` IS a threshold — a minimum rate threshold. The word "threshold" is accurate for both `Threshold.minConfidence` and `Rate.minRate`. Both are [0,1] tunables with identical drift semantics. Renaming adds churn without adding clarity.
**Trade-offs:** None — the existing names are correct.
**Sources:** `FeedbackState.effectiveThreshold()`, `FeedbackState.applyThresholdOverride()`, `ChainMode.Rate.minRate()`
**Exploration:** quick
**Status:** captured
