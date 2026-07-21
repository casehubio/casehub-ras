package io.casehub.ras.runtime;

import io.casehub.platform.api.expression.CompiledExpression;
import io.casehub.ras.api.DetectionResult;
import io.casehub.ras.api.DetectionSignal;
import io.casehub.ras.api.Ganglion;
import io.casehub.ras.api.SituationContext;
import io.cloudevents.CloudEvent;
import io.micrometer.core.instrument.MeterRegistry;
import io.smallrye.mutiny.Uni;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Logger;

@SuppressWarnings("rawtypes")
class ExpressionRulesGanglion implements Ganglion {

    private static final Logger LOG = Logger.getLogger(ExpressionRulesGanglion.class.getName());

    private final String ganglionId;
    private final Set<String> handledEventTypes;
    private final List<CompiledRule> rules;
    private final MeterRegistry meterRegistry;

    record CompiledRule(
            CompiledExpression<Map, Boolean> when,
            DetectionSignal signal,
            double confidence
    ) {}

    ExpressionRulesGanglion(String ganglionId,
                             Set<String> handledEventTypes,
                             List<CompiledRule> rules,
                             MeterRegistry meterRegistry) {
        this.ganglionId = Objects.requireNonNull(ganglionId);
        if (handledEventTypes == null || handledEventTypes.isEmpty()) {
            throw new IllegalArgumentException("handledEventTypes must not be empty");
        }
        this.handledEventTypes = Set.copyOf(handledEventTypes);
        this.rules = List.copyOf(rules);
        this.meterRegistry = meterRegistry;
    }

    @Override
    public String ganglionId() { return ganglionId; }

    @Override
    public Set<String> handledEventTypes() { return handledEventTypes; }

    @Override
    @SuppressWarnings("unchecked")
    public Uni<DetectionResult> detect(CloudEvent event, SituationContext context) {
        Map<String, Object> ctx = CloudEventExpressionContext.build(event);
        for (int i = 0; i < rules.size(); i++) {
            CompiledRule rule = rules.get(i);
            if (rule.when() == null) {
                return Uni.createFrom().item(new DetectionResult(
                        ganglionId, rule.confidence(), rule.signal(),
                        Map.of("matchedRuleIndex", i)));
            }
            try {
                Boolean match = rule.when().eval(ctx);
                if (Boolean.TRUE.equals(match)) {
                    return Uni.createFrom().item(new DetectionResult(
                            ganglionId, rule.confidence(), rule.signal(),
                            Map.of("matchedRuleIndex", i)));
                }
            } catch (RuntimeException ex) {
                LOG.warning("Rule " + i + " expression failed for ganglion '"
                            + ganglionId + "': " + ex.getMessage());
                if (meterRegistry != null) {
                    meterRegistry.counter("ras.expression.error",
                            "ganglion_id", ganglionId,
                            "rule_index", String.valueOf(i),
                            "expression_point", "rule_evaluation").increment();
                }
            }
        }
        return Uni.createFrom().item(new DetectionResult(
                ganglionId, 0.0, DetectionSignal.NOISE,
                Map.of("matchedRuleIndex", -1)));
    }
}
