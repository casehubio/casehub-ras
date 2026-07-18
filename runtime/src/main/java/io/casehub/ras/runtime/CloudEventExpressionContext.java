package io.casehub.ras.runtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cloudevents.CloudEvent;

import java.util.LinkedHashMap;
import java.util.Map;

final class CloudEventExpressionContext {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private CloudEventExpressionContext() {}

    static Map<String, Object> build(CloudEvent event) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("type", event.getType());
        ctx.put("source", event.getSource() != null ? event.getSource().toString() : null);
        ctx.put("subject", event.getSubject());
        ctx.put("id", event.getId());
        ctx.put("time", event.getTime() != null ? event.getTime().toString() : null);
        ctx.put("tenancyid", event.getExtension("tenancyid"));
        ctx.put("data", parseJsonData(event));
        return ctx;
    }

    private static Map<String, Object> parseJsonData(CloudEvent event) {
        if (event.getData() == null) {
            return Map.of();
        }
        String contentType = event.getDataContentType();
        if (contentType == null || !isJsonContentType(contentType)) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(event.getData().toBytes(), MAP_TYPE);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static boolean isJsonContentType(String contentType) {
        return contentType.equals("application/json")
                || (contentType.startsWith("application/") && contentType.endsWith("+json"));
    }
}
