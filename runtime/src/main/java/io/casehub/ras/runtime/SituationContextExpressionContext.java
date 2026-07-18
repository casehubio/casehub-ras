package io.casehub.ras.runtime;

import io.casehub.ras.api.SituationContext;

import java.util.LinkedHashMap;
import java.util.Map;

final class SituationContextExpressionContext {

    private SituationContextExpressionContext() {}

    static Map<String, Object> build(SituationContext context) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("situationId", context.situationId());
        ctx.put("correlationKey", context.correlationKey());
        ctx.put("tenancyId", context.tenancyId());
        ctx.put("firstSignal", context.firstSignal() != null ? context.firstSignal().toString() : null);
        ctx.put("lastSignal", context.lastSignal() != null ? context.lastSignal().toString() : null);
        ctx.put("lastTriggered", context.lastTriggered() != null ? context.lastTriggered().toString() : null);
        ctx.put("triggerCount", context.triggerCount());
        ctx.put("detections", context.detections());
        return ctx;
    }
}
