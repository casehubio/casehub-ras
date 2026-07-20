package io.casehub.ras.runtime;

import io.casehub.platform.api.expression.CompiledExpression;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExpressionFeatureExtractorTest {

    private static CloudEvent testEvent() {
        return CloudEventBuilder.v1()
                .withId("evt-1")
                .withSource(URI.create("/test"))
                .withType("sensor.reading")
                .withSubject("sensor-42")
                .build();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static CompiledExpression<Map, String> mapExpr(
            java.util.function.Function<Map<String, Object>, String> fn) {
        return new CompiledExpression<>() {
            @Override public String type() { return "test"; }
            @Override public String eval(Map context) { return fn.apply(context); }
        };
    }

    @Test
    void extractsMultipleFeaturesFromEvent() {
        var exprs = new LinkedHashMap<String, CompiledExpression<Map, String>>();
        exprs.put("type", mapExpr(ctx -> (String) ctx.get("type")));
        exprs.put("subject", mapExpr(ctx -> (String) ctx.get("subject")));

        var extractor = new ExpressionFeatureExtractor("g1", exprs, null);

        Map<String, String> result = extractor.extract(testEvent());
        assertThat(result).containsEntry("type", "sensor.reading");
        assertThat(result).containsEntry("subject", "sensor-42");
    }

    @Test
    void nullResultOmitsFeature() {
        var exprs = new LinkedHashMap<String, CompiledExpression<Map, String>>();
        exprs.put("missing", mapExpr(ctx -> null));

        var extractor = new ExpressionFeatureExtractor("g1", exprs, null);

        Map<String, String> result = extractor.extract(testEvent());
        assertThat(result).isEmpty();
    }

    @Test
    void expressionErrorSkipsFeatureAndIncrementsMetric() {
        MeterRegistry registry = new SimpleMeterRegistry();

        var exprs = new LinkedHashMap<String, CompiledExpression<Map, String>>();
        exprs.put("failing", mapExpr(ctx -> { throw new RuntimeException("boom"); }));
        exprs.put("ok", mapExpr(ctx -> "value"));

        var extractor = new ExpressionFeatureExtractor("g1", exprs, registry);

        Map<String, String> result = extractor.extract(testEvent());
        assertThat(result).containsEntry("ok", "value");
        assertThat(result).doesNotContainKey("failing");

        double errorCount = registry.counter("ras.expression.error",
                "ganglion_id", "g1",
                "feature_name", "failing",
                "expression_point", "feature_extraction").count();
        assertThat(errorCount).isEqualTo(1.0);
    }

    @Test
    void noMetricIncrementedWhenRegistryNull() {
        var exprs = new LinkedHashMap<String, CompiledExpression<Map, String>>();
        exprs.put("failing", mapExpr(ctx -> { throw new RuntimeException("boom"); }));

        var extractor = new ExpressionFeatureExtractor("g1", exprs, null);

        Map<String, String> result = extractor.extract(testEvent());
        assertThat(result).isEmpty();
    }
}
