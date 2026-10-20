package io.casehub.ras.runtime;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import io.casehub.platform.api.expression.ExpressionEvaluator;
import io.casehub.platform.api.expression.JQExpressionEvaluator;
import io.casehub.platform.api.expression.MvelExpressionEvaluator;

import java.io.IOException;

class ExpressionEvaluatorDeserializer extends StdDeserializer<ExpressionEvaluator> {

    ExpressionEvaluatorDeserializer() {
        super(ExpressionEvaluator.class);
    }

    @Override
    public ExpressionEvaluator deserialize(JsonParser p, DeserializationContext ctxt)
            throws IOException {
        JsonNode node = p.readValueAsTree();
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return new JQExpressionEvaluator(node.textValue());
        }
        if (node.isObject()) {
            JsonNode exprNode = node.get("expression");
            if (exprNode == null || !exprNode.isTextual()) {
                throw ctxt.weirdStringException(
                        node.toString(), ExpressionEvaluator.class,
                        "Expression object missing required 'expression' field");
            }
            JsonNode langNode = node.get("language");
            if (langNode == null || !langNode.isTextual()) {
                throw ctxt.weirdStringException(
                        node.toString(), ExpressionEvaluator.class,
                        "Expression object missing required 'language' field");
            }
            String expression = exprNode.textValue();
            String language = langNode.textValue();
            return switch (language) {
                case "jq" -> new JQExpressionEvaluator(expression);
                case "mvel" -> new MvelExpressionEvaluator(expression);
                default -> throw ctxt.weirdStringException(
                        language, ExpressionEvaluator.class,
                        "Unknown expression language '" + language + "'. Expected: jq, mvel");
            };
        }
        throw ctxt.weirdStringException(
                node.toString(), ExpressionEvaluator.class,
                "Expected expression as string or {expression, language} object, got " + node.getNodeType());
    }

    @Override
    public ExpressionEvaluator getNullValue(DeserializationContext ctxt) {
        return null;
    }
}
