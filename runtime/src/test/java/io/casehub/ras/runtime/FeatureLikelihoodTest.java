package io.casehub.ras.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class FeatureLikelihoodTest {

    @Test
    void validFeatureLikelihoodIsCreated() {
        var fl = new FeatureLikelihood(
                List.of("LOW", "HIGH"),
                new double[][]{{0.8, 0.2}, {0.3, 0.7}});

        assertThat(fl.values()).containsExactly("LOW", "HIGH");
        assertThat(fl.likelihoods()).hasNumberOfRows(2);
        assertThat(fl.likelihoods()[0]).containsExactly(0.8, 0.2);
        assertThat(fl.likelihoods()[1]).containsExactly(0.3, 0.7);
    }

    @Test
    void nullValuesIsRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new FeatureLikelihood(null, new double[][]{{0.5, 0.5}}));
    }

    @Test
    void emptyValuesIsRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new FeatureLikelihood(List.of(), new double[][]{{0.5}}));
    }

    @Test
    void nullLikelihoodsIsRejected() {
        assertThatNullPointerException()
                .isThrownBy(() -> new FeatureLikelihood(List.of("A"), null));
    }

    @Test
    void likelihoodsRowLengthMustMatchValuesSize() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new FeatureLikelihood(
                        List.of("A", "B"),
                        new double[][]{{0.5, 0.3, 0.2}}))
                .withMessageContaining("row 0")
                .withMessageContaining("values size");
    }

    @Test
    void zeroLikelihoodIsRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new FeatureLikelihood(
                        List.of("A", "B"),
                        new double[][]{{0.5, 0.0}}))
                .withMessageContaining("[0][1]")
                .withMessageContaining("Laplace");
    }

    @Test
    void negativeLikelihoodIsRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new FeatureLikelihood(
                        List.of("A"),
                        new double[][]{{-0.1}}))
                .withMessageContaining("[0][0]");
    }

    @Test
    void nanLikelihoodIsRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new FeatureLikelihood(
                        List.of("A"),
                        new double[][]{{Double.NaN}}))
                .withMessageContaining("NaN");
    }

    @Test
    void likelihoodsAreDefensivelyCopied() {
        double[][] original = {{0.8, 0.2}, {0.3, 0.7}};
        var fl = new FeatureLikelihood(List.of("A", "B"), original);
        original[0][0] = 999.0;
        assertThat(fl.likelihoods()[0][0]).isEqualTo(0.8);
    }

    @Test
    void valuesAreDefensivelyCopied() {
        var mutable = new java.util.ArrayList<>(List.of("A", "B"));
        var fl = new FeatureLikelihood(mutable, new double[][]{{0.5, 0.5}});
        mutable.add("C");
        assertThat(fl.values()).containsExactly("A", "B");
    }
}
