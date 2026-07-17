package io.casehub.ras.drools;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DroolsSessionKeyTest {

    @Test
    void fromStorageKeyRoundTrip() {
        var original = new DroolsSessionKey("g1", "sit-1", "key-1", "tenant-a");
        var restored = DroolsSessionKey.fromStorageKey(original.toStorageKey());
        assertThat(restored).isEqualTo(original);
    }

    @Test
    void fromStorageKeyWithComplexValues() {
        var original = new DroolsSessionKey("my.ganglion", "situation-def-1", "order-12345", "tenant-xyz");
        var restored = DroolsSessionKey.fromStorageKey(original.toStorageKey());
        assertThat(restored).isEqualTo(original);
    }

    @Test
    void fromStorageKeyMalformedThrows() {
        assertThatThrownBy(() -> DroolsSessionKey.fromStorageKey("only-two|parts"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Malformed storage key")
                .hasMessageContaining("only-two|parts");
    }

    @Test
    void fromStorageKeyEmptyThrows() {
        assertThatThrownBy(() -> DroolsSessionKey.fromStorageKey(""))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
