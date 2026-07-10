package io.casehub.ras.api;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.Objects;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = TriggerAction.CreateCase.class, name = "CreateCase"),
    @JsonSubTypes.Type(value = TriggerAction.NotifyOnly.class, name = "NotifyOnly")
})
public sealed interface TriggerAction {
    record CreateCase(CaseTriggerConfig config) implements TriggerAction {
        public CreateCase {
            Objects.requireNonNull(config, "config");
        }
    }
    record NotifyOnly() implements TriggerAction {}
}
