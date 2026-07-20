package io.casehub.ras.runtime;

import io.casehub.platform.api.expression.CompiledExpression;
import io.cloudevents.CloudEvent;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

@SuppressWarnings({"unchecked", "rawtypes"})
final class ExpressionFeatureExtractor implements NaiveBayesFeatureExtractor {

    private static final Logger LOG = Logger.getLogger(ExpressionFeatureExtractor.class.getName());

    private final String                                        ganglionId;
    private final Map<String, CompiledExpression<Map, String>>  featureExpressions;
    private final MeterRegistry                                 meterRegistry;

    ExpressionFeatureExtractor(String ganglionId,
                               Map<String, CompiledExpression<Map, String>> featureExpressions,
                               MeterRegistry meterRegistry) {
        this.ganglionId         = ganglionId;
        this.featureExpressions = Map.copyOf(featureExpressions);
        this.meterRegistry      = meterRegistry;
    }

    @Override
    public Map<String, String> extract(CloudEvent event) {
        Map<String, Object> ctx    = CloudEventExpressionContext.build(event);
        Map<String, String> result = new LinkedHashMap<>();
        for (var entry : featureExpressions.entrySet()) {
            try {
                String value = entry.getValue().eval(ctx);
                if (value != null) {
                    result.put(entry.getKey(), value);
                }
            } catch (Exception e) {
                LOG.warning("Feature expression '" + entry.getKey()
                            + "' failed for ganglion '" + ganglionId + "': " + e.getMessage());
                if (meterRegistry != null) {
                    meterRegistry.counter("ras.expression.error",
                            "ganglion_id", ganglionId,
                            "feature_name", entry.getKey(),
                            "expression_point", "feature_extraction").increment();
                }
            }
        }
        return result;
    }
}
