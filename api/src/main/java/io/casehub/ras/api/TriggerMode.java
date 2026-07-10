package io.casehub.ras.api;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.time.Duration;
import java.util.Objects;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = TriggerMode.FireOnce.class, name = "FireOnce"),
    @JsonSubTypes.Type(value = TriggerMode.Repeating.class, name = "Repeating")
})
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
