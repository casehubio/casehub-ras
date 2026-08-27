package io.casehub.ras.api;

import io.casehub.platform.api.expression.ExpressionEvaluator;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record SituationDefinition(
        String situationId,
        Set<String> eventTypes,
        Duration correlationWindow,
        Duration eventBufferDelay,
        ChainMode chainMode,
        TriggerAction triggerAction,
        TriggerMode triggerMode,
        ExpressionEvaluator correlationKeyExpression,
        ExpressionEvaluator eventFilter,
        Map<String, ExpressionEvaluator> dynamicCaseData,
        FeedbackConfig feedbackConfig,
        Duration deadline
) {
    public SituationDefinition {
        Objects.requireNonNull(situationId, "situationId");
        Objects.requireNonNull(chainMode, "chainMode");
        Objects.requireNonNull(triggerAction, "triggerAction");
        if (eventTypes == null || eventTypes.isEmpty()) {
            throw new IllegalArgumentException("eventTypes must not be empty");
        }
        eventTypes = Set.copyOf(eventTypes);
        if (correlationWindow != null
            && (correlationWindow.isZero() || correlationWindow.isNegative())) {
            throw new IllegalArgumentException(
                    "correlationWindow must be positive when set, got: " + correlationWindow);
        }
        if (eventBufferDelay != null
            && (eventBufferDelay.isZero() || eventBufferDelay.isNegative())) {
            throw new IllegalArgumentException(
                    "eventBufferDelay must be positive when set, got: " + eventBufferDelay);
        }
        if (deadline != null && (deadline.isZero() || deadline.isNegative())) {
            throw new IllegalArgumentException(
                    "deadline must be positive when set, got: " + deadline);
        }
        triggerMode     = triggerMode != null ? triggerMode : new TriggerMode.FireOnce();
        dynamicCaseData = dynamicCaseData != null ? Map.copyOf(dynamicCaseData) : Map.of();
    }

    public SituationDefinition(String situationId, Set<String> eventTypes,
                               Duration correlationWindow, Duration eventBufferDelay,
                               ChainMode chainMode, TriggerAction triggerAction,
                               TriggerMode triggerMode,
                               ExpressionEvaluator correlationKeyExpression,
                               ExpressionEvaluator eventFilter,
                               Map<String, ExpressionEvaluator> dynamicCaseData) {
        this(situationId, eventTypes, correlationWindow, eventBufferDelay,
             chainMode, triggerAction, triggerMode,
             correlationKeyExpression, eventFilter, dynamicCaseData, null, null);
    }

    public SituationDefinition(String situationId, Set<String> eventTypes,
                               Duration correlationWindow, Duration eventBufferDelay,
                               ChainMode chainMode, TriggerAction triggerAction,
                               TriggerMode triggerMode) {
        this(situationId, eventTypes, correlationWindow, eventBufferDelay,
             chainMode, triggerAction, triggerMode, null, null, Map.of(), null, null);
    }

    public SituationDefinition withChainMode(ChainMode newChainMode) {
        return new SituationDefinition(situationId, eventTypes, correlationWindow,
                                       eventBufferDelay, newChainMode, triggerAction, triggerMode,
                                       correlationKeyExpression, eventFilter, dynamicCaseData,
                                       feedbackConfig, deadline);
    }
}
