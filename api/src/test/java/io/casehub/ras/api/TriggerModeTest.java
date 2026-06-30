package io.casehub.ras.api;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.assertj.core.api.Assertions.*;

class TriggerModeTest {

    @Test
    void fireOnceCreates() {
        var mode = new TriggerMode.FireOnce();
        assertThat(mode).isInstanceOf(TriggerMode.class);
    }

    @Test
    void repeatingWithValidCooldown() {
        var mode = new TriggerMode.Repeating(Duration.ofMinutes(5));
        assertThat(mode.cooldown()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void repeatingRejectsNullCooldown() {
        assertThatThrownBy(() -> new TriggerMode.Repeating(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void repeatingRejectsZeroCooldown() {
        assertThatThrownBy(() -> new TriggerMode.Repeating(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
    }

    @Test
    void repeatingRejectsNegativeCooldown() {
        assertThatThrownBy(() -> new TriggerMode.Repeating(Duration.ofMinutes(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
    }

    @Test
    void sealedInterfacePermitsOnlyTwoVariants() {
        assertThat(TriggerMode.class.getPermittedSubclasses())
                .extracting(Class::getSimpleName)
                .containsExactlyInAnyOrder("FireOnce", "Repeating");
    }
}
