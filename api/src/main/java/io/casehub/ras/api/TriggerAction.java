package io.casehub.ras.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.Map;
import java.util.Objects;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = TriggerAction.CreateCase.class, name = "create-case"),
        @JsonSubTypes.Type(value = TriggerAction.NotifyOnly.class, name = "notify-only")
})
public sealed interface TriggerAction {
    record CreateCase(
            @com.fasterxml.jackson.annotation.JsonUnwrapped CaseTriggerConfig config) implements TriggerAction {
        public CreateCase {
            Objects.requireNonNull(config, "config");
        }

        @JsonCreator
        public static CreateCase fromYaml(
                @JsonProperty("caseNamespace") String caseNamespace,
                @JsonProperty("caseName") String caseName,
                @JsonProperty("caseVersion") String caseVersion,
                @JsonProperty("baseCaseData") Map<String, Object> baseCaseData) {
            return new CreateCase(new CaseTriggerConfig(
                    caseNamespace, caseName, caseVersion,
                    baseCaseData != null ? baseCaseData : Map.of()));
        }
    }

    record NotifyOnly() implements TriggerAction {}
}
