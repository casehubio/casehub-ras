package io.casehub.ras.api;

import java.util.Objects;

public sealed interface TriggerAction {
    record CreateCase(CaseTriggerConfig config) implements TriggerAction {
        public CreateCase {
            Objects.requireNonNull(config, "config");
        }
    }
    record NotifyOnly() implements TriggerAction {}
}
