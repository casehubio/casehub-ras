package io.casehub.ras.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class NaiveBayesConfigTest {

    private static final NaiveBayesFeatureExtractor NOOP_EXTRACTOR = event -> Map.of();
    private static final NaiveBayesSignalMapping    VALID_MAPPING  =
            new NaiveBayesSignalMapping("B", 0.75, 0.30);

    private static FeatureLikelihood twoOutcomeTwoValues() {
        return new FeatureLikelihood(List.of("X", "Y"), new double[][]{{0.7, 0.3}, {0.4, 0.6}});
    }

    private NaiveBayesConfig validConfig() {
        return new NaiveBayesConfig(
                "bayes-g",
                Set.of("test.event"),
                List.of("A", "B"),
                new double[]{0.6, 0.4},
                Map.of("feature1", twoOutcomeTwoValues()),
                NOOP_EXTRACTOR,
                VALID_MAPPING,
                Map.of());
    }

    @Test
    void validConfigIsCreated() {
        var config = validConfig();
        assertThat(config.ganglionId()).isEqualTo("bayes-g");
        assertThat(config.handledEventTypes()).containsExactly("test.event");
        assertThat(config.outcomes()).containsExactly("A", "B");
        assertThat(config.priors()).containsExactly(0.6, 0.4);
    }

    @Test
    void nullGanglionIdIsRejected() {
        assertThatNullPointerException()
                .isThrownBy(() -> new NaiveBayesConfig(
                        null, Set.of("e"), List.of("A", "B"),
                        new double[]{0.5, 0.5}, Map.of(), NOOP_EXTRACTOR, VALID_MAPPING, Map.of()));
    }

    @Test
    void emptyHandledEventTypesIsRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new NaiveBayesConfig(
                        "g", Set.of(), List.of("A", "B"),
                        new double[]{0.5, 0.5}, Map.of(), NOOP_EXTRACTOR, VALID_MAPPING, Map.of()));
    }

    @Test
    void singleOutcomeIsRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new NaiveBayesConfig(
                        "g", Set.of("e"), List.of("A"),
                        new double[]{1.0}, Map.of(), NOOP_EXTRACTOR, VALID_MAPPING, Map.of()));
    }

    @Test
    void priorsLengthMustMatchOutcomesSize() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new NaiveBayesConfig(
                        "g", Set.of("e"), List.of("A", "B"),
                        new double[]{0.5, 0.3, 0.2}, Map.of(), NOOP_EXTRACTOR, VALID_MAPPING, Map.of()))
                .withMessageContaining("priors length");
    }

    @Test
    void priorsMustSumToOne() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new NaiveBayesConfig(
                        "g", Set.of("e"), List.of("A", "B"),
                        new double[]{0.3, 0.3}, Map.of(), NOOP_EXTRACTOR, VALID_MAPPING, Map.of()))
                .withMessageContaining("sum to 1.0");
    }

    @Test
    void zeroPriorIsRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new NaiveBayesConfig(
                        "g", Set.of("e"), List.of("A", "B"),
                        new double[]{1.0, 0.0}, Map.of(), NOOP_EXTRACTOR, VALID_MAPPING, Map.of()))
                .withMessageContaining("priors[1]")
                .withMessageContaining("> 0.0");
    }

    @Test
    void nanPriorIsRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new NaiveBayesConfig(
                        "g", Set.of("e"), List.of("A", "B"),
                        new double[]{Double.NaN, 0.5}, Map.of(), NOOP_EXTRACTOR, VALID_MAPPING, Map.of()))
                .withMessageContaining("NaN");
    }

    @Test
    void featureLikelihoodRowCountMustMatchOutcomes() {
        var wrongRowCount = new FeatureLikelihood(List.of("X", "Y"),
                new double[][]{{0.5, 0.5}, {0.3, 0.7}, {0.2, 0.8}});
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new NaiveBayesConfig(
                        "g", Set.of("e"), List.of("A", "B"),
                        new double[]{0.5, 0.5}, Map.of("f", wrongRowCount),
                        NOOP_EXTRACTOR, VALID_MAPPING, Map.of()))
                .withMessageContaining("Feature 'f'")
                .withMessageContaining("3 likelihood rows")
                .withMessageContaining("2 outcomes");
    }

    @Test
    void nullFeatureExtractorIsRejected() {
        assertThatNullPointerException()
                .isThrownBy(() -> new NaiveBayesConfig(
                        "g", Set.of("e"), List.of("A", "B"),
                        new double[]{0.5, 0.5}, Map.of(), null, VALID_MAPPING, Map.of()));
    }

    @Test
    void nullSignalMappingIsRejected() {
        assertThatNullPointerException()
                .isThrownBy(() -> new NaiveBayesConfig(
                        "g", Set.of("e"), List.of("A", "B"),
                        new double[]{0.5, 0.5}, Map.of(), NOOP_EXTRACTOR, null, Map.of()));
    }

    @Test
    void targetOutcomeMustExistInOutcomes() {
        var mismatched = new NaiveBayesSignalMapping("MISSING", 0.75, 0.30);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new NaiveBayesConfig(
                        "g", Set.of("e"), List.of("A", "B"),
                        new double[]{0.5, 0.5}, Map.of(), NOOP_EXTRACTOR, mismatched, Map.of()))
                .withMessageContaining("MISSING")
                .withMessageContaining("not in outcomes");
    }

    @Test
    void priorsAreDefensivelyCopied() {
        double[] priors = {0.6, 0.4};
        var config = new NaiveBayesConfig("g", Set.of("e"), List.of("A", "B"),
                priors, Map.of(), NOOP_EXTRACTOR, VALID_MAPPING, Map.of());
        priors[0] = 999.0;
        assertThat(config.priors()[0]).isEqualTo(0.6);
    }

    @Test
    void unknownOutcomeKeyInOutcomeEvidenceTemplatesIsRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new NaiveBayesConfig(
                        "g", Set.of("e"), List.of("A", "B"),
                        new double[]{0.5, 0.5}, Map.of(), NOOP_EXTRACTOR, VALID_MAPPING,
                        Map.of("C", Map.of())))
                .withMessageContaining("C")
                .withMessageContaining("not in outcomes");
    }
}
