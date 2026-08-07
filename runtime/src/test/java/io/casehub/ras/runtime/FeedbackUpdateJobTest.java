package io.casehub.ras.runtime;

import io.casehub.ras.api.CaseTriggerConfig;
import io.casehub.ras.api.ChainMode;
import io.casehub.ras.api.FeedbackConfig;
import io.casehub.ras.api.GanglionDescriptor;
import io.casehub.ras.api.OutcomeClassification;
import io.casehub.ras.api.OutcomeRecord;
import io.casehub.ras.api.SituationDefinition;
import io.casehub.ras.api.SituationDefinitionProvider;
import io.casehub.ras.api.SituationRegistration;
import io.casehub.ras.api.TriggerAction;
import io.casehub.ras.testing.MockGanglion;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FeedbackUpdateJobTest {

    private InMemoryOutcomeLedger ledger;
    private FeedbackState feedbackState;
    private DefaultTuningStrategy tuningStrategy;
    private FeedbackMetrics feedbackMetrics;
    private SimpleMeterRegistry meterRegistry;

    private static final CaseTriggerConfig TRIGGER_CFG =
            new CaseTriggerConfig("ns", "case", "1.0", Map.of());

    @BeforeEach
    void setUp() {
        ledger = new InMemoryOutcomeLedger();
        feedbackState = new FeedbackState();
        tuningStrategy = new DefaultTuningStrategy();
        meterRegistry = new SimpleMeterRegistry();
        feedbackMetrics = new FeedbackMetrics(meterRegistry);
    }

    private FeedbackConfig feedbackConfig(boolean tuningEnabled) {
        return new FeedbackConfig(
                Set.of("dismissed"), Set.of("escalated"),
                Duration.ofHours(6), 0.1, Duration.ofDays(90), tuningEnabled);
    }

    private void recordOutcomes(String situationId, String tenancyId, int noiseCount, int confirmedCount) {
        for (int i = 0; i < noiseCount; i++) {
            ledger.record(new OutcomeRecord(situationId, "k1", tenancyId, "dismissed",
                    OutcomeClassification.NOISE, Instant.now().minus(Duration.ofDays(1)), UUID.randomUUID()));
        }
        for (int i = 0; i < confirmedCount; i++) {
            ledger.record(new OutcomeRecord(situationId, "k1", tenancyId, "escalated",
                    OutcomeClassification.CONFIRMED, Instant.now().minus(Duration.ofDays(1)), UUID.randomUUID()));
        }
    }

    @Test
    void skipsSituationsWithoutFeedbackConfig() {
        var ganglion = new MockGanglion("g1", Set.of("test.event"),
                io.casehub.ras.testing.FixedDetectionResult.detected("g1", 0.9));
        var def = new SituationDefinition("sit-1", Set.of("test.event"),
                Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")),
                new TriggerAction.CreateCase(TRIGGER_CFG), null);
        var registry = new SituationDefinitionRegistry(
                List.of(() -> List.of(new SituationRegistration(def))), List.of(ganglion));

        recordOutcomes("sit-1", "t1", 8, 2);

        var analyzer = new FeedbackAnalyzer(ledger);
        var job = new FeedbackUpdateJob(registry, ledger, analyzer, tuningStrategy,
                feedbackState, feedbackMetrics);
        job.updateFeedback();

        assertThat(feedbackState.effectiveThreshold("sit-1", "t1")).isEmpty();
    }

    @Test
    void appliesThresholdOverrideWhenTuningEnabled() {
        var ganglion = new MockGanglion("g1", Set.of("test.event"),
                io.casehub.ras.testing.FixedDetectionResult.detected("g1", 0.9));
        var def = new SituationDefinition("sit-1", Set.of("test.event"),
                Duration.ofMinutes(5), null,
                new ChainMode.Threshold(Set.of("g1"), 0.5),
                new TriggerAction.CreateCase(TRIGGER_CFG), null,
                null, null, Map.of(), feedbackConfig(true));
        var registry = new SituationDefinitionRegistry(
                List.of(() -> List.of(new SituationRegistration(def))), List.of(ganglion));

        recordOutcomes("sit-1", "t1", 8, 2);

        var analyzer = new FeedbackAnalyzer(ledger);
        var job = new FeedbackUpdateJob(registry, ledger, analyzer, tuningStrategy,
                feedbackState, feedbackMetrics);
        job.updateFeedback();

        assertThat(feedbackState.effectiveThreshold("sit-1", "t1")).isPresent();
        assertThat(feedbackState.effectiveThreshold("sit-1", "t1").getAsDouble())
                .isGreaterThan(0.5);
    }

    @Test
    void skipsThresholdAdjustmentWhenTuningDisabled() {
        var ganglion = new MockGanglion("g1", Set.of("test.event"),
                io.casehub.ras.testing.FixedDetectionResult.detected("g1", 0.9));
        var def = new SituationDefinition("sit-1", Set.of("test.event"),
                Duration.ofMinutes(5), null,
                new ChainMode.Threshold(Set.of("g1"), 0.5),
                new TriggerAction.CreateCase(TRIGGER_CFG), null,
                null, null, Map.of(), feedbackConfig(false));
        var registry = new SituationDefinitionRegistry(
                List.of(() -> List.of(new SituationRegistration(def))), List.of(ganglion));

        recordOutcomes("sit-1", "t1", 8, 2);

        var analyzer = new FeedbackAnalyzer(ledger);
        var job = new FeedbackUpdateJob(registry, ledger, analyzer, tuningStrategy,
                feedbackState, feedbackMetrics);
        job.updateFeedback();

        assertThat(feedbackState.effectiveThreshold("sit-1", "t1")).isEmpty();
    }

    @Test
    void appliesPriorOverrideForNaiveBayesGanglia() {
        var descriptor = new GanglionDescriptor.NaiveBayes(
                "nb-g", Set.of("test.event"),
                List.of("fraud", "legitimate"), new double[]{0.1, 0.9},
                Map.of("f1", new GanglionDescriptor.NaiveBayes.Feature(
                        new io.casehub.platform.api.expression.JQExpressionEvaluator(".data.f"),
                        List.of("X", "Y"), new double[][]{{0.8, 0.2}, {0.3, 0.7}})),
                new GanglionDescriptor.NaiveBayes.SignalMapping("fraud", 0.7, 0.3, null),
                Map.of(), Map.of(),
                Map.of("escalated", "fraud", "dismissed", "legitimate"));

        SituationDefinitionProvider provider = new SituationDefinitionProvider() {
            public List<SituationRegistration> registrations() {
                var def = new SituationDefinition("sit-1", Set.of("test.event"),
                        Duration.ofMinutes(5), null,
                        new ChainMode.Threshold(Set.of("nb-g"), 0.5),
                        new TriggerAction.CreateCase(TRIGGER_CFG), null,
                        null, null, Map.of(), feedbackConfig(true));
                return List.of(new SituationRegistration(def));
            }
            public List<GanglionDescriptor> ganglionDescriptors() { return List.of(descriptor); }
        };

        var engines = new io.casehub.platform.expression.DefaultExpressionEngineRegistry();
        engines.register(new io.casehub.platform.expression.JQExpressionEngine());
        var registry = new SituationDefinitionRegistry(
                List.of(provider), List.of(), engines, new InMemoryGanglionStateStore(), null, null);

        recordOutcomes("sit-1", "t1", 3, 7);

        var analyzer = new FeedbackAnalyzer(ledger);
        var job = new FeedbackUpdateJob(registry, ledger, analyzer, tuningStrategy,
                feedbackState, feedbackMetrics);
        job.updateFeedback();

        assertThat(feedbackState.adjustedLogPriors("nb-g", "t1")).isPresent();
    }

    @Test
    void skipsPriorAdjustmentWhenNoOutcomeGroundTruth() {
        var descriptor = new GanglionDescriptor.NaiveBayes(
                "nb-g", Set.of("test.event"),
                List.of("fraud", "legitimate"), new double[]{0.1, 0.9},
                Map.of("f1", new GanglionDescriptor.NaiveBayes.Feature(
                        new io.casehub.platform.api.expression.JQExpressionEvaluator(".data.f"),
                        List.of("X", "Y"), new double[][]{{0.8, 0.2}, {0.3, 0.7}})),
                new GanglionDescriptor.NaiveBayes.SignalMapping("fraud", 0.7, 0.3, null),
                Map.of(), Map.of());

        SituationDefinitionProvider provider = new SituationDefinitionProvider() {
            public List<SituationRegistration> registrations() {
                var def = new SituationDefinition("sit-1", Set.of("test.event"),
                        Duration.ofMinutes(5), null,
                        new ChainMode.Threshold(Set.of("nb-g"), 0.5),
                        new TriggerAction.CreateCase(TRIGGER_CFG), null,
                        null, null, Map.of(), feedbackConfig(true));
                return List.of(new SituationRegistration(def));
            }
            public List<GanglionDescriptor> ganglionDescriptors() { return List.of(descriptor); }
        };

        var engines = new io.casehub.platform.expression.DefaultExpressionEngineRegistry();
        engines.register(new io.casehub.platform.expression.JQExpressionEngine());
        var registry = new SituationDefinitionRegistry(
                List.of(provider), List.of(), engines, new InMemoryGanglionStateStore(), null, null);

        recordOutcomes("sit-1", "t1", 3, 7);

        var analyzer = new FeedbackAnalyzer(ledger);
        var job = new FeedbackUpdateJob(registry, ledger, analyzer, tuningStrategy,
                feedbackState, feedbackMetrics);
        job.updateFeedback();

        assertThat(feedbackState.adjustedLogPriors("nb-g", "t1")).isEmpty();
    }

    @Test
    void tenantIsolation() {
        var ganglion = new MockGanglion("g1", Set.of("test.event"),
                io.casehub.ras.testing.FixedDetectionResult.detected("g1", 0.9));
        var def = new SituationDefinition("sit-1", Set.of("test.event"),
                Duration.ofMinutes(5), null,
                new ChainMode.Threshold(Set.of("g1"), 0.5),
                new TriggerAction.CreateCase(TRIGGER_CFG), null,
                null, null, Map.of(), feedbackConfig(true));
        var registry = new SituationDefinitionRegistry(
                List.of(() -> List.of(new SituationRegistration(def))), List.of(ganglion));

        recordOutcomes("sit-1", "t1", 8, 2);
        recordOutcomes("sit-1", "t2", 2, 8);

        var analyzer = new FeedbackAnalyzer(ledger);
        var job = new FeedbackUpdateJob(registry, ledger, analyzer, tuningStrategy,
                feedbackState, feedbackMetrics);
        job.updateFeedback();

        assertThat(feedbackState.effectiveThreshold("sit-1", "t1")).isPresent();
        assertThat(feedbackState.effectiveThreshold("sit-1", "t2")).isEmpty();
    }

    @Test
    void runsRetentionCleanup() {
        var ganglion = new MockGanglion("g1", Set.of("test.event"),
                io.casehub.ras.testing.FixedDetectionResult.detected("g1", 0.9));
        var config = new FeedbackConfig(
                Set.of("dismissed"), Set.of("escalated"),
                Duration.ofHours(6), 0.1, Duration.ofDays(1), false);
        var def = new SituationDefinition("sit-1", Set.of("test.event"),
                Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")),
                new TriggerAction.CreateCase(TRIGGER_CFG), null,
                null, null, Map.of(), config);
        var registry = new SituationDefinitionRegistry(
                List.of(() -> List.of(new SituationRegistration(def))), List.of(ganglion));

        ledger.record(new OutcomeRecord("sit-1", "k1", "t1", "dismissed",
                OutcomeClassification.NOISE, Instant.now().minus(Duration.ofDays(3)), UUID.randomUUID()));
        ledger.record(new OutcomeRecord("sit-1", "k1", "t1", "escalated",
                OutcomeClassification.CONFIRMED, Instant.now(), UUID.randomUUID()));

        var analyzer = new FeedbackAnalyzer(ledger);
        var job = new FeedbackUpdateJob(registry, ledger, analyzer, tuningStrategy,
                feedbackState, feedbackMetrics);
        job.updateFeedback();

        var stats = ledger.statistics("sit-1", "t1", Instant.EPOCH);
        assertThat(stats.totalOutcomes()).isEqualTo(1);
    }
}
