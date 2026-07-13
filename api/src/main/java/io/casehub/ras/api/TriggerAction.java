package io.casehub.ras.api;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.Objects;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = TriggerAction.CreateCase.class, name = "create-case"),
        @JsonSubTypes.Type(value = TriggerAction.NotifyOnly.class, name = "notify-only")
})
public sealed interface TriggerAction {
    record CreateCase(CaseTriggerConfig config) implements TriggerAction {
        public CreateCase {
            Objects.requireNonNull(config, "config");
        }
    }

    record NotifyOnly() implements TriggerAction {}
}
