package io.casehub.ras.runtime;

import io.casehub.ras.api.DetectionResult;
import io.casehub.ras.api.DetectionSignal;
import io.casehub.ras.api.GanglionState;
import io.casehub.ras.api.GanglionStateConflictException;
import io.casehub.ras.api.GanglionStateKey;
import io.casehub.ras.api.GanglionStateStore;
import io.casehub.ras.api.SituationContext;
import io.casehub.ras.api.TimestampedDetection;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import org.junit.jupiter.api.Test;

import io.smallrye.mutiny.Uni;

import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.assertj.core.data.Offset.offset;

class NaiveBayesGanglionTest {

    private static CloudEvent testEvent(String type, Instant time) {
        return CloudEventBuilder.v1()
                .withId("evt-1")
                .withSource(URI.create("/test"))
                .withType(type)
                .withTime(OffsetDateTime.ofInstant(time, ZoneOffset.UTC))
                .build();
    }

    private static SituationContext testContext() {
        return SituationContext.initial("sit-1", "key-1", "tenant-a",
                Instant.parse("2026-06-26T10:00:00Z"));
    }

    private static NaiveBayesConfig twoOutcomeConfig() {
        return new NaiveBayesConfig(
                "bayes-g",
                Set.of("test.event"),
                List.of("NORMAL", "ANOMALY"),
                new double[]{0.9, 0.1},
                Map.of("severity", new FeatureLikelihood(
                        List.of("LOW", "MEDIUM", "HIGH"),
                        new double[][]{{0.7, 0.25, 0.05}, {0.1, 0.3, 0.6}})),
                event -> Map.of("severity", "HIGH"),
                new NaiveBayesSignalMapping("ANOMALY", 0.75, 0.30, 0.05));
    }

    private static NaiveBayesGanglion twoOutcomeGanglion() {
        return new NaiveBayesGanglion(twoOutcomeConfig(), new InMemoryGanglionStateStore());
    }

    // --- Basic properties ---

    @Test
    void ganglionIdAndHandledEventTypes() {
        var ganglion = twoOutcomeGanglion();
        assertThat(ganglion.ganglionId()).isEqualTo("bayes-g");
        assertThat(ganglion.handledEventTypes()).containsExactly("test.event");
    }

    // --- Single observation ---

    @Test
    void singleObservationShiftsPosteriorTowardCorrectOutcome() {
        var ganglion = twoOutcomeGanglion();
        var event = testEvent("test.event", Instant.parse("2026-06-26T10:00:00Z"));

        DetectionResult result = ganglion.detect(event, testContext()).await().indefinitely();

        assertThat(result.ganglionId()).isEqualTo("bayes-g");
        double posterior = (double) result.evidence().get("posterior");
        assertThat(posterior).isGreaterThan(0.1);
    }

    // --- Multiple features ---

    @Test
    void multipleFeaturesContributeIndependently() {
        var ganglion = new NaiveBayesGanglion(new NaiveBayesConfig(
                "multi-f",
                Set.of("test.event"),
                List.of("NORMAL", "ANOMALY"),
                new double[]{0.9, 0.1},
                Map.of(
                        "severity", new FeatureLikelihood(
                                List.of("LOW", "HIGH"),
                                new double[][]{{0.8, 0.2}, {0.2, 0.8}}),
                        "source", new FeatureLikelihood(
                                List.of("TRUSTED", "UNKNOWN"),
                                new double[][]{{0.9, 0.1}, {0.3, 0.7}})),
                event -> Map.of("severity", "HIGH", "source", "UNKNOWN"),
                new NaiveBayesSignalMapping("ANOMALY", 0.75, 0.30)), new InMemoryGanglionStateStore());

        DetectionResult result = ganglion.detect(
                testEvent("test.event", Instant.parse("2026-06-26T10:00:00Z")),
                testContext()).await().indefinitely();

        double posterior = (double) result.evidence().get("posterior");
        assertThat(posterior).isGreaterThan(0.5);
    }

    // --- Incremental accumulation ---

    @Test
    void multipleObservationsAccumulateState() {
        var ganglion = twoOutcomeGanglion();
        var ctx = testContext();
        var event = testEvent("test.event", Instant.parse("2026-06-26T10:00:00Z"));

        DetectionResult r1 = ganglion.detect(event, ctx).await().indefinitely();
        double p1 = (double) r1.evidence().get("posterior");

        DetectionResult r2 = ganglion.detect(event, ctx).await().indefinitely();
        double p2 = (double) r2.evidence().get("posterior");

        assertThat(p2).isGreaterThan(p1);
    }

    // --- Unknown features ---

    @Test
    void unknownFeatureNameIsSilentlyIgnored() {
        var ganglion = new NaiveBayesGanglion(new NaiveBayesConfig(
                "unk-g",
                Set.of("test.event"),
                List.of("A", "B"),
                new double[]{0.5, 0.5},
                Map.of("known", new FeatureLikelihood(
                        List.of("X"), new double[][]{{0.6}, {0.4}})),
                event -> Map.of("unknown_feature", "value"),
                new NaiveBayesSignalMapping("B", 0.75, 0.30)), new InMemoryGanglionStateStore());

        DetectionResult result = ganglion.detect(
                testEvent("test.event", Instant.parse("2026-06-26T10:00:00Z")),
                testContext()).await().indefinitely();

        double posterior = (double) result.evidence().get("posterior");
        assertThat(posterior).isEqualTo(0.5);
    }

    @Test
    void unknownFeatureValueIsSilentlyIgnored() {
        var ganglion = new NaiveBayesGanglion(new NaiveBayesConfig(
                "unk-v-g",
                Set.of("test.event"),
                List.of("A", "B"),
                new double[]{0.5, 0.5},
                Map.of("feature", new FeatureLikelihood(
                        List.of("X", "Y"), new double[][]{{0.7, 0.3}, {0.4, 0.6}})),
                event -> Map.of("feature", "UNKNOWN_VALUE"),
                new NaiveBayesSignalMapping("B", 0.75, 0.30)), new InMemoryGanglionStateStore());

        DetectionResult result = ganglion.detect(
                testEvent("test.event", Instant.parse("2026-06-26T10:00:00Z")),
                testContext()).await().indefinitely();

        double posterior = (double) result.evidence().get("posterior");
        assertThat(posterior).isEqualTo(0.5);
    }

    // --- Signal mapping ---

    @Test
    void detectedSignalAboveThreshold() {
        var ganglion = new NaiveBayesGanglion(new NaiveBayesConfig(
                "sig-g",
                Set.of("test.event"),
                List.of("A", "B"),
                new double[]{0.1, 0.9},
                Map.of(),
                event -> Map.of(),
                new NaiveBayesSignalMapping("B", 0.75, 0.30)), new InMemoryGanglionStateStore());

        DetectionResult result = ganglion.detect(
                testEvent("test.event", Instant.parse("2026-06-26T10:00:00Z")),
                testContext()).await().indefinitely();

        assertThat(result.signal()).isEqualTo(DetectionSignal.DETECTED);
        assertThat(result.confidence()).isCloseTo(0.9, offset(1e-9));
    }

    @Test
    void weakSignalBetweenThresholds() {
        var ganglion = new NaiveBayesGanglion(new NaiveBayesConfig(
                "sig-g",
                Set.of("test.event"),
                List.of("A", "B"),
                new double[]{0.6, 0.4},
                Map.of(),
                event -> Map.of(),
                new NaiveBayesSignalMapping("B", 0.75, 0.30)), new InMemoryGanglionStateStore());

        DetectionResult result = ganglion.detect(
                testEvent("test.event", Instant.parse("2026-06-26T10:00:00Z")),
                testContext()).await().indefinitely();

        assertThat(result.signal()).isEqualTo(DetectionSignal.WEAK);
        assertThat(result.confidence()).isCloseTo(0.4, offset(1e-9));
    }

    @Test
    void noiseSignalBelowWeakThreshold() {
        var ganglion = new NaiveBayesGanglion(new NaiveBayesConfig(
                "sig-g",
                Set.of("test.event"),
                List.of("A", "B"),
                new double[]{0.85, 0.15},
                Map.of(),
                event -> Map.of(),
                new NaiveBayesSignalMapping("B", 0.75, 0.30)), new InMemoryGanglionStateStore());

        DetectionResult result = ganglion.detect(
                testEvent("test.event", Instant.parse("2026-06-26T10:00:00Z")),
                testContext()).await().indefinitely();

        assertThat(result.signal()).isEqualTo(DetectionSignal.NOISE);
        assertThat(result.confidence()).isEqualTo(0.0);
    }

    // --- ANTI signal ---

    @Test
    void antiSignalBelowAntiThreshold() {
        var ganglion = new NaiveBayesGanglion(new NaiveBayesConfig(
                "anti-g",
                Set.of("test.event"),
                List.of("A", "B"),
                new double[]{0.97, 0.03},
                Map.of(),
                event -> Map.of(),
                new NaiveBayesSignalMapping("B", 0.75, 0.30, 0.05)), new InMemoryGanglionStateStore());

        DetectionResult result = ganglion.detect(
                testEvent("test.event", Instant.parse("2026-06-26T10:00:00Z")),
                testContext()).await().indefinitely();

        assertThat(result.signal()).isEqualTo(DetectionSignal.ANTI);
        assertThat(result.confidence()).isCloseTo(0.97, within(0.01));
    }

    @Test
    void antiDisabledWhenAntiThresholdNull() {
        var ganglion = new NaiveBayesGanglion(new NaiveBayesConfig(
                "no-anti-g",
                Set.of("test.event"),
                List.of("A", "B"),
                new double[]{0.97, 0.03},
                Map.of(),
                event -> Map.of(),
                new NaiveBayesSignalMapping("B", 0.75, 0.30)), new InMemoryGanglionStateStore());

        DetectionResult result = ganglion.detect(
                testEvent("test.event", Instant.parse("2026-06-26T10:00:00Z")),
                testContext()).await().indefinitely();

        assertThat(result.signal()).isEqualTo(DetectionSignal.NOISE);
    }

    // --- close() ---

    @Test
    void closeRemovesStateSubsequentDetectStartsFromPriors() {
        var ganglion = twoOutcomeGanglion();
        var ctx = testContext();
        var event = testEvent("test.event", Instant.parse("2026-06-26T10:00:00Z"));

        ganglion.detect(event, ctx).await().indefinitely();
        ganglion.detect(event, ctx).await().indefinitely();
        DetectionResult beforeClose = ganglion.detect(event, ctx).await().indefinitely();
        double posteriorBeforeClose = (double) beforeClose.evidence().get("posterior");

        ganglion.close("sit-1", "key-1", "tenant-a").await().indefinitely();

        DetectionResult afterClose = ganglion.detect(event, ctx).await().indefinitely();
        double posteriorAfterClose = (double) afterClose.evidence().get("posterior");

        assertThat(posteriorAfterClose).isLessThan(posteriorBeforeClose);
    }

    // --- Numerical stability ---

    @Test
    void numericalStabilityOver100Observations() {
        var ganglion = twoOutcomeGanglion();
        var ctx = testContext();
        var event = testEvent("test.event", Instant.parse("2026-06-26T10:00:00Z"));

        DetectionResult result = null;
        for (int i = 0; i < 100; i++) {
            result = ganglion.detect(event, ctx).await().indefinitely();
        }

        assertThat(result).isNotNull();
        assertThat(result.confidence()).isBetween(0.0, 1.0);
        double posterior = (double) result.evidence().get("posterior");
        assertThat(posterior).isBetween(0.0, 1.0);
        assertThat(Double.isNaN(posterior)).isFalse();
    }

    // --- Concurrent situations ---

    @Test
    void independentStatePerSituation() {
        var ganglion = twoOutcomeGanglion();
        var event = testEvent("test.event", Instant.parse("2026-06-26T10:00:00Z"));

        var ctx1 = SituationContext.initial("sit-1", "key-1", "tenant-a",
                Instant.parse("2026-06-26T10:00:00Z"));
        var ctx2 = SituationContext.initial("sit-2", "key-2", "tenant-a",
                Instant.parse("2026-06-26T10:00:00Z"));

        ganglion.detect(event, ctx1).await().indefinitely();
        ganglion.detect(event, ctx1).await().indefinitely();
        ganglion.detect(event, ctx1).await().indefinitely();

        DetectionResult r1 = ganglion.detect(event, ctx1).await().indefinitely();
        DetectionResult r2 = ganglion.detect(event, ctx2).await().indefinitely();

        double p1 = (double) r1.evidence().get("posterior");
        double p2 = (double) r2.evidence().get("posterior");
        assertThat(p1).isGreaterThan(p2);
    }

    // --- compact() ---

    @Test
    void compactCollapsesToLastProcessedDetection() {
        var ganglion = twoOutcomeGanglion();
        var event = testEvent("test.event", Instant.parse("2026-06-26T10:00:00Z"));
        var ctx = testContext();

        DetectionResult r1 = ganglion.detect(event, ctx).await().indefinitely();
        ctx = ctx.withDetection(r1, Instant.parse("2026-06-26T10:01:00Z"));

        DetectionResult r2 = ganglion.detect(event, ctx).await().indefinitely();
        ctx = ctx.withDetection(r2, Instant.parse("2026-06-26T10:02:00Z"));

        assertThat(ctx.detections()).hasSize(2);

        SituationContext compacted = ganglion.compact(ctx).await().indefinitely();

        assertThat(compacted.detections()).hasSize(1);
        assertThat(compacted.detections().getFirst().result().ganglionId()).isEqualTo("bayes-g");
        double compactedPosterior = (double) compacted.detections().getFirst().result().evidence().get("posterior");
        double lastPosterior = (double) r2.evidence().get("posterior");
        assertThat(compactedPosterior).isEqualTo(lastPosterior);
    }

    @Test
    void compactPreservesOtherGangliaDetections() {
        var ganglion = twoOutcomeGanglion();
        var event = testEvent("test.event", Instant.parse("2026-06-26T10:00:00Z"));
        var ctx = testContext();

        var otherResult = new DetectionResult("other-g", 0.5, DetectionSignal.DETECTED, Map.of());
        ctx = ctx.withDetection(otherResult, Instant.parse("2026-06-26T10:00:30Z"));

        DetectionResult bayesResult = ganglion.detect(event, ctx).await().indefinitely();
        ctx = ctx.withDetection(bayesResult, Instant.parse("2026-06-26T10:01:00Z"));

        assertThat(ctx.detections()).hasSize(2);

        SituationContext compacted = ganglion.compact(ctx).await().indefinitely();

        assertThat(compacted.detections()).hasSize(2);
        assertThat(compacted.detections().stream()
                .map(td -> td.result().ganglionId())
                .toList())
                .containsExactlyInAnyOrder("other-g", "bayes-g");
    }

    @Test
    void compactWithNoDetectionsFromThisGanglionReturnsUnchanged() {
        var ganglion = twoOutcomeGanglion();
        var ctx = testContext();
        var otherResult = new DetectionResult("other-g", 0.5, DetectionSignal.DETECTED, Map.of());
        ctx = ctx.withDetection(otherResult, Instant.parse("2026-06-26T10:00:30Z"));

        SituationContext compacted = ganglion.compact(ctx).await().indefinitely();

        assertThat(compacted).isSameAs(ctx);
    }

    @Test
    void compactUnderOutOfOrderEventsRetainsLastProcessed() {
        var ganglion = twoOutcomeGanglion();
        var event = testEvent("test.event", Instant.parse("2026-06-26T10:00:00Z"));
        var ctx = testContext();

        DetectionResult r1 = ganglion.detect(event, ctx).await().indefinitely();
        ctx = ctx.withDetection(r1, Instant.parse("2026-06-26T10:05:00Z"));

        DetectionResult r2 = ganglion.detect(event, ctx).await().indefinitely();
        ctx = ctx.withDetection(r2, Instant.parse("2026-06-26T10:02:00Z"));

        SituationContext compacted = ganglion.compact(ctx).await().indefinitely();

        assertThat(compacted.detections()).hasSize(1);
        TimestampedDetection retained = compacted.detections().getFirst();
        assertThat(retained.eventTime()).isEqualTo(Instant.parse("2026-06-26T10:02:00Z"));
        double retainedPosterior = (double) retained.result().evidence().get("posterior");
        double r2Posterior = (double) r2.evidence().get("posterior");
        assertThat(retainedPosterior).isEqualTo(r2Posterior);
    }

    // --- Evidence ---

    @Test
    void evidenceContainsPosteriorAndFeatures() {
        var ganglion = twoOutcomeGanglion();
        var event = testEvent("test.event", Instant.parse("2026-06-26T10:00:00Z"));

        DetectionResult result = ganglion.detect(event, testContext()).await().indefinitely();

        assertThat(result.evidence()).containsKey("posterior");
        assertThat(result.evidence()).containsKey("features");
        @SuppressWarnings("unchecked")
        Map<String, String> features = (Map<String, String>) result.evidence().get("features");
        assertThat(features).containsEntry("severity", "HIGH");
    }

    @Test
    void posteriorsSurviveStoreRoundTrip() {
        var stateStore = new InMemoryGanglionStateStore();
        var ganglion1  = new NaiveBayesGanglion(twoOutcomeConfig(), stateStore);
        var ctx        = testContext();
        var event      = testEvent("test.event", Instant.parse("2026-06-26T10:00:00Z"));

        ganglion1.detect(event, ctx).await().indefinitely();
        ganglion1.detect(event, ctx).await().indefinitely();
        DetectionResult r1 = ganglion1.detect(event, ctx).await().indefinitely();
        double          p1 = (double) r1.evidence().get("posterior");

        var             ganglion2 = new NaiveBayesGanglion(twoOutcomeConfig(), stateStore);
        DetectionResult r2        = ganglion2.detect(event, ctx).await().indefinitely();
        double          p2        = (double) r2.evidence().get("posterior");

        assertThat(p2).isGreaterThan(p1);
    }

    @Test
    void detectRetriesOnConflictException() {
        var callCount = new java.util.concurrent.atomic.AtomicInteger();
        var delegate  = new InMemoryGanglionStateStore();
        GanglionStateStore conflictingStore = new GanglionStateStore() {
            public Uni<java.util.Optional<GanglionState>> load(GanglionStateKey key) {
                return delegate.load(key);
            }

            public Uni<Void> save(GanglionStateKey key, GanglionState state) {
                if (callCount.getAndIncrement() == 0) {
                    return Uni.createFrom().failure(
                            new GanglionStateConflictException("test conflict", null));
                }
                return delegate.save(key, state);
            }

            public Uni<Void> remove(GanglionStateKey key) {return delegate.remove(key);}

            public Uni<Void> removeForSituation(String situationId) {
                return delegate.removeForSituation(situationId);
            }
        };

        var ganglion = new NaiveBayesGanglion(twoOutcomeConfig(), conflictingStore);
        var ctx      = testContext();
        var event    = testEvent("test.event", Instant.parse("2026-06-26T10:00:00Z"));

        DetectionResult result = ganglion.detect(event, ctx).await().indefinitely();

        assertThat(result).isNotNull();
        assertThat(callCount.get()).isEqualTo(2);
    }
}
