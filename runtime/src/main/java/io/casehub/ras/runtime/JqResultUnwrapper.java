package io.casehub.ras.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.platform.api.expression.CompiledExpression;

import java.util.List;
import java.util.Map;

@SuppressWarnings({"unchecked", "rawtypes"})
final class JqResultUnwrapper<R> implements CompiledExpression<Map, R> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CompiledExpression<Map, ?> delegate;
    private final Class<R>                   resultType;

    JqResultUnwrapper(CompiledExpression<Map, ?> delegate, Class<R> resultType) {
        this.delegate   = delegate;
        this.resultType = resultType;
    }

    @Override
    public String type() {return "jq";}

    @Override
    public R eval(Map context) {
        Object result = delegate.eval(context);
        if (result instanceof Boolean) {
            return (R) result;
        }
        if (result instanceof List<?> list) {
            if (list.isEmpty()) {return null;}
            JsonNode first = (JsonNode) list.getFirst();
            if (first.isNull()) {return null;}
            if (resultType == String.class) {
                return (R) first.asText();
            }
            return MAPPER.convertValue(first, resultType);
        }
        return (R) result;
    }
}
