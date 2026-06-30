package io.casehub.ras.api;

import java.time.Duration;
import java.util.Objects;

public sealed interface TriggerMode {
    record FireOnce() implements TriggerMode {}
    record Repeating(Duration cooldown) implements TriggerMode {
        public Repeating {
            Objects.requireNonNull(cooldown, "cooldown");
            if (cooldown.isZero() || cooldown.isNegative()) {
                throw new IllegalArgumentException(
                        "cooldown must be positive, got: " + cooldown);
            }
        }
    }
}
