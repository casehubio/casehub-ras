package io.casehub.ras.api;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

class ChainModeTest {

    @Test
    void andWithValidGanglia() {
        var and = new ChainMode.And(Set.of("g1", "g2"));
        assertThat(and.requiredGanglia()).containsExactlyInAnyOrder("g1", "g2");
    }

    @Test
    void andRejectsEmptySet() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ChainMode.And(Set.of()))
                .withMessageContaining("must not be empty");
    }

    @Test
    void andRejectsNull() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ChainMode.And(null))
                .withMessageContaining("must not be empty");
    }

    @Test
    void orRejectsEmptySet() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ChainMode.Or(Set.of()))
                .withMessageContaining("must not be empty");
    }

    @Test
    void thresholdRejectsZeroConfidence() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ChainMode.Threshold(Set.of("g1"), 0.0))
                .withMessageContaining("0.0");
    }

    @Test
    void thresholdRejectsNegativeConfidence() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ChainMode.Threshold(Set.of("g1"), -1.0))
                .withMessageContaining("-1.0");
    }

    @Test
    void thresholdAcceptsValuesAboveOne() {
        var threshold = new ChainMode.Threshold(Set.of("g1", "g2"), 2.5);
        assertThat(threshold.minConfidence()).isEqualTo(2.5);
    }

    @Test
    void thresholdRejectsEmptyGanglia() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ChainMode.Threshold(Set.of(), 0.5))
                .withMessageContaining("must not be empty");
    }

    @Test
    void sequenceRejectsEmptyList() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ChainMode.Sequence(List.of()))
                .withMessageContaining("must not be empty");
    }

    @Test
    void sequencePreservesOrder() {
        var seq = new ChainMode.Sequence(List.of("g1", "g2", "g3"));
        assertThat(seq.orderedGanglia()).containsExactly("g1", "g2", "g3");
    }

    @Test
    void countRejectsZero() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ChainMode.Count("g1", 0))
                .withMessageContaining("0");
    }

    @Test
    void countRejectsNullGanglionId() {
        assertThatNullPointerException()
                .isThrownBy(() -> new ChainMode.Count(null, 3))
                .withMessage("ganglionId");
    }

    @Test
    void andIsDefensivelyCopied() {
        var mutable = new java.util.HashSet<>(Set.of("g1"));
        var and = new ChainMode.And(mutable);
        mutable.add("g2");
        assertThat(and.requiredGanglia()).containsExactly("g1");
    }

    @Test
    void sealedInterfacePermitsAllVariants() {
        ChainMode and = new ChainMode.And(Set.of("g1"));
        ChainMode or = new ChainMode.Or(Set.of("g1"));
        ChainMode threshold = new ChainMode.Threshold(Set.of("g1"), 0.5);
        ChainMode sequence = new ChainMode.Sequence(List.of("g1"));
        ChainMode count = new ChainMode.Count("g1", 1);

        assertThat(and).isInstanceOf(ChainMode.class);
        assertThat(or).isInstanceOf(ChainMode.class);
        assertThat(threshold).isInstanceOf(ChainMode.class);
        assertThat(sequence).isInstanceOf(ChainMode.class);
        assertThat(count).isInstanceOf(ChainMode.class);
    }

    @Test
    void referencedGangliaForAnd() {
        ChainMode mode = new ChainMode.And(Set.of("g1", "g2"));
        assertThat(mode.referencedGanglia()).containsExactlyInAnyOrder("g1", "g2");
    }

    @Test
    void referencedGangliaForOr() {
        ChainMode mode = new ChainMode.Or(Set.of("g3"));
        assertThat(mode.referencedGanglia()).containsExactly("g3");
    }

    @Test
    void referencedGangliaForThreshold() {
        ChainMode mode = new ChainMode.Threshold(Set.of("g1", "g4"), 1.0);
        assertThat(mode.referencedGanglia()).containsExactlyInAnyOrder("g1", "g4");
    }

    @Test
    void referencedGangliaForSequence() {
        ChainMode mode = new ChainMode.Sequence(List.of("g1", "g2", "g3"));
        assertThat(mode.referencedGanglia()).containsExactlyInAnyOrder("g1", "g2", "g3");
    }

    @Test
    void referencedGangliaForCount() {
        ChainMode mode = new ChainMode.Count("g5", 3);
        assertThat(mode.referencedGanglia()).containsExactly("g5");
    }
}
