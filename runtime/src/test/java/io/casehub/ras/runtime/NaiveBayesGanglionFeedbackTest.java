package io.casehub.ras.runtime;

import io.casehub.ras.api.DetectionResult;
import io.casehub.ras.api.DetectionSignal;
import io.casehub.ras.api.SituationContext;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class NaiveBayesGanglionFeedbackTest {

    private static CloudEvent testEvent() {
        return CloudEventBuilder.v1()
                .withId("evt-1")
                .withSource(URI.create("/test"))
                .withType("test.event")
                .withTime(OffsetDateTime.ofInstant(Instant.parse("2026-08-06T10:00:00Z"), ZoneOffset.UTC))
                .build();
    }

    private static SituationContext context(String tenancyId) {
        return SituationContext.initial("sit-1", "key-1", tenancyId,
                Instant.parse("2026-08-06T10:00:00Z"));
    }

    private static NaiveBayesConfig config() {
        return new NaiveBayesConfig(
                "bayes-g", Set.of("test.event"),
                List.of("NORMAL", "ANOMALY"),
                new double[]{0.9, 0.1},
                Map.of("severity", new FeatureLikelihood(
                        List.of("LOW", "HIGH"),
                        new double[][]{{0.8, 0.2}, {0.2, 0.8}})),
                event -> Map.of("severity", "HIGH"),
                new NaiveBayesSignalMapping("ANOMALY", 0.6, 0.3, null),
                Map.of());
    }

    @Test
    void newInstanceUsesFeedbackAdjustedPriors() {
        var feedbackState = new FeedbackState();
        feedbackState.applyPriorOverride("bayes-g", "tenant-a",
                new double[]{0.5, 0.5});

        var ganglion = new NaiveBayesGanglion(config(),
                new InMemoryGanglionStateStore(), null, feedbackState);

        DetectionResult withFeedback = ganglion.detect(testEvent(), context("tenant-a"));

        var ganglionNoFeedback = new NaiveBayesGanglion(config(),
                new InMemoryGanglionStateStore(), null, null);

        DetectionResult withoutFeedback = ganglionNoFeedback.detect(testEvent(), context("tenant-a"));

        assertThat(withFeedback.confidence()).isNotEqualTo(withoutFeedback.confidence());
        assertThat(withFeedback.confidence()).isGreaterThan(withoutFeedback.confidence());
    }

    @Test
    void noFeedbackOverrideFallsBackToConfigPriors() {
        var feedbackState = new FeedbackState();

        var ganglion = new NaiveBayesGanglion(config(),
                new InMemoryGanglionStateStore(), null, feedbackState);
        var ganglionNull = new NaiveBayesGanglion(config(),
                new InMemoryGanglionStateStore(), null, null);

        DetectionResult withEmptyFeedback = ganglion.detect(testEvent(), context("tenant-a"));
        DetectionResult withNullFeedback = ganglionNull.detect(testEvent(), context("tenant-a"));

        assertThat(withEmptyFeedback.confidence()).isEqualTo(withNullFeedback.confidence());
        assertThat(withEmptyFeedback.signal()).isEqualTo(withNullFeedback.signal());
    }

    @Test
    void feedbackPriorsAreTenantScoped() {
        var feedbackState = new FeedbackState();
        feedbackState.applyPriorOverride("bayes-g", "tenant-a",
                new double[]{0.5, 0.5});

        var storeA = new InMemoryGanglionStateStore();
        var storeB = new InMemoryGanglionStateStore();

        var ganglionA = new NaiveBayesGanglion(config(), storeA, null, feedbackState);
        var ganglionB = new NaiveBayesGanglion(config(), storeB, null, feedbackState);

        DetectionResult tenantA = ganglionA.detect(testEvent(), context("tenant-a"));
        DetectionResult tenantB = ganglionB.detect(testEvent(), context("tenant-b"));

        assertThat(tenantA.confidence()).isNotEqualTo(tenantB.confidence());
    }

    @Test
    void existingStateNotOverriddenByFeedback() {
        var feedbackState = new FeedbackState();
        feedbackState.applyPriorOverride("bayes-g", "tenant-a",
                new double[]{0.5, 0.5});

        var storeA = new InMemoryGanglionStateStore();
        var storeB = new InMemoryGanglionStateStore();

        new NaiveBayesGanglion(config(), storeA, null, null)
                .detect(testEvent(), context("tenant-a"));
        new NaiveBayesGanglion(config(), storeB, null, null)
                .detect(testEvent(), context("tenant-a"));

        DetectionResult withFeedback = new NaiveBayesGanglion(config(), storeA, null, feedbackState)
                .detect(testEvent(), context("tenant-a"));
        DetectionResult withoutFeedback = new NaiveBayesGanglion(config(), storeB, null, null)
                .detect(testEvent(), context("tenant-a"));

        assertThat(withFeedback.confidence()).isEqualTo(withoutFeedback.confidence());
    }
}
