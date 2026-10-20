package io.casehub.ras.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.casehub.platform.api.expression.ExpressionEvaluator;
import io.casehub.platform.api.expression.JQExpressionEvaluator;
import io.casehub.platform.api.expression.MvelExpressionEvaluator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExpressionEvaluatorDeserializerTest {

    private static final ObjectMapper YAML;
    static {
        YAML = new ObjectMapper(new YAMLFactory());
        var module = new SimpleModule();
        module.addDeserializer(ExpressionEvaluator.class, new ExpressionEvaluatorDeserializer());
        YAML.registerModule(module);
    }

    record Wrapper(ExpressionEvaluator expr) {}

    @Test
    void deserializesJqExpression() throws Exception {
        String yaml = """
                expr:
                  expression: ".data.value"
                  language: jq
                """;
        Wrapper w = YAML.readValue(yaml, Wrapper.class);
        assertThat(w.expr()).isInstanceOf(JQExpressionEvaluator.class);
        assertThat(w.expr().expression()).isEqualTo(".data.value");
    }

    @Test
    void deserializesMvelExpression() throws Exception {
        String yaml = """
                expr:
                  expression: "value > 10"
                  language: mvel
                """;
        Wrapper w = YAML.readValue(yaml, Wrapper.class);
        assertThat(w.expr()).isInstanceOf(MvelExpressionEvaluator.class);
        assertThat(w.expr().expression()).isEqualTo("value > 10");
    }

    @Test
    void bareStringDefaultsToJq() throws Exception {
        String yaml = """
                expr: ".data.severity"
                """;
        Wrapper w = YAML.readValue(yaml, Wrapper.class);
        assertThat(w.expr()).isInstanceOf(JQExpressionEvaluator.class);
        assertThat(w.expr().expression()).isEqualTo(".data.severity");
    }

    @Test
    void nullReturnsNull() throws Exception {
        String yaml = """
                expr: null
                """;
        Wrapper w = YAML.readValue(yaml, Wrapper.class);
        assertThat(w.expr()).isNull();
    }

    @Test
    void absentFieldReturnsNull() throws Exception {
        String yaml = """
                expr:
                """;
        Wrapper w = YAML.readValue(yaml, Wrapper.class);
        assertThat(w.expr()).isNull();
    }

    @Test
    void unknownLanguageThrows() {
        String yaml = """
                expr:
                  expression: "x"
                  language: groovy
                """;
        assertThatThrownBy(() -> YAML.readValue(yaml, Wrapper.class))
                .hasMessageContaining("Unknown expression language");
    }

    @Test
    void missingExpressionFieldThrows() {
        String yaml = """
                expr:
                  language: jq
                """;
        assertThatThrownBy(() -> YAML.readValue(yaml, Wrapper.class))
                .hasMessageContaining("expression");
    }

    @Test
    void missingLanguageFieldThrows() {
        String yaml = """
                expr:
                  expression: ".data"
                """;
        assertThatThrownBy(() -> YAML.readValue(yaml, Wrapper.class))
                .hasMessageContaining("language");
    }

    @Test
    void rasYamlMapperDeserializesFullSituation() throws Exception {
        String yaml = """
                situationId: integration-test
                eventTypes: [event.a, event.b]
                correlationWindow: PT1H
                chainMode:
                  type: threshold
                  ganglia: [g1, g2]
                  minConfidence: 0.5
                triggerAction:
                  type: create-case
                  caseNamespace: test
                  caseName: case
                  caseVersion: "1"
                correlationKey:
                  expression: ".subject"
                  language: jq
                """;
        var def = RasYamlMapper.instance().readValue(yaml,
                io.casehub.ras.api.SituationDefinition.class);
        assertThat(def.situationId()).isEqualTo("integration-test");
        assertThat(def.correlationKeyExpression()).isNotNull();
        assertThat(def.correlationKeyExpression().expression()).isEqualTo(".subject");
    }

    @Test
    void rasYamlMapperCaseInsensitiveEnums() throws Exception {
        String yaml = """
                type: expression-rules
                ganglionId: test
                handledEventTypes: [event.a]
                rules:
                  - otherwise: true
                    signal: detected
                    confidence: 0.9
                """;
        var desc = RasYamlMapper.instance().readValue(yaml,
                io.casehub.ras.api.GanglionDescriptor.class);
        assertThat(desc).isInstanceOf(io.casehub.ras.api.GanglionDescriptor.ExpressionRules.class);
    }
}
