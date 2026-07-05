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
        ChainMode streak = new ChainMode.Streak("g1", 1);
        ChainMode rate = new ChainMode.Rate(Set.of("g1"), 0.5, 10);

        assertThat(and).isInstanceOf(ChainMode.class);
        assertThat(or).isInstanceOf(ChainMode.class);
        assertThat(threshold).isInstanceOf(ChainMode.class);
        assertThat(sequence).isInstanceOf(ChainMode.class);
        assertThat(count).isInstanceOf(ChainMode.class);
        assertThat(streak).isInstanceOf(ChainMode.class);
        assertThat(rate).isInstanceOf(ChainMode.class);
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

    @Test
    void streakWithValidInput() {
        var streak = new ChainMode.Streak("g1", 3);
        assertThat(streak.ganglionId()).isEqualTo("g1");
        assertThat(streak.requiredCount()).isEqualTo(3);
    }

    @Test
    void streakRejectsNullGanglionId() {
        assertThatNullPointerException()
                .isThrownBy(() -> new ChainMode.Streak(null, 3))
                .withMessage("ganglionId");
    }

    @Test
    void streakRejectsZeroCount() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ChainMode.Streak("g1", 0))
                .withMessageContaining("0");
    }

    @Test
    void streakRejectsNegativeCount() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ChainMode.Streak("g1", -1))
                .withMessageContaining("-1");
    }

    @Test
    void referencedGangliaForStreak() {
        ChainMode mode = new ChainMode.Streak("g5", 3);
        assertThat(mode.referencedGanglia()).containsExactly("g5");
    }

    @Test
    void rateWithValidInput() {
        var rate = new ChainMode.Rate(Set.of("g1", "g2"), 0.6, 10);
        assertThat(rate.ganglia()).containsExactlyInAnyOrder("g1", "g2");
        assertThat(rate.minRate()).isEqualTo(0.6);
        assertThat(rate.windowSize()).isEqualTo(10);
    }

    @Test
    void rateRejectsEmptyGanglia() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ChainMode.Rate(Set.of(), 0.5, 10))
                .withMessageContaining("must not be empty");
    }

    @Test
    void rateRejectsNullGanglia() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ChainMode.Rate(null, 0.5, 10))
                .withMessageContaining("must not be empty");
    }

    @Test
    void rateRejectsZeroMinRate() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ChainMode.Rate(Set.of("g1"), 0.0, 10))
                .withMessageContaining("0.0");
    }

    @Test
    void rateRejectsNegativeMinRate() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ChainMode.Rate(Set.of("g1"), -0.5, 10))
                .withMessageContaining("-0.5");
    }

    @Test
    void rateRejectsMinRateAboveOne() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ChainMode.Rate(Set.of("g1"), 1.1, 10))
                .withMessageContaining("1.1");
    }

    @Test
    void rateAcceptsMinRateExactlyOne() {
        var rate = new ChainMode.Rate(Set.of("g1"), 1.0, 5);
        assertThat(rate.minRate()).isEqualTo(1.0);
    }

    @Test
    void rateRejectsZeroWindowSize() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ChainMode.Rate(Set.of("g1"), 0.5, 0))
                .withMessageContaining("0");
    }

    @Test
    void rateIsDefensivelyCopied() {
        var mutable = new java.util.HashSet<>(Set.of("g1"));
        var rate = new ChainMode.Rate(mutable, 0.5, 10);
        mutable.add("g2");
        assertThat(rate.ganglia()).containsExactly("g1");
    }

    @Test
    void referencedGangliaForRate() {
        ChainMode mode = new ChainMode.Rate(Set.of("g1", "g4"), 0.5, 10);
        assertThat(mode.referencedGanglia()).containsExactlyInAnyOrder("g1", "g4");
    }
}
