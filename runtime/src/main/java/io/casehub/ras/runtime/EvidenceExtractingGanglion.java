package io.casehub.ras.runtime;

import io.casehub.platform.api.expression.CompiledExpression;
import io.casehub.ras.api.DetectionResult;
import io.casehub.ras.api.Ganglion;
import io.casehub.ras.api.SituationContext;
import io.cloudevents.CloudEvent;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Logger;

@SuppressWarnings("rawtypes")
class EvidenceExtractingGanglion implements Ganglion {

    private static final Logger LOG = Logger.getLogger(EvidenceExtractingGanglion.class.getName());

    private final Ganglion                                     delegate;
    private final Map<String, CompiledExpression<Map, Object>> templates;
    private final MeterRegistry                                meterRegistry;

    EvidenceExtractingGanglion(Ganglion delegate,
                               Map<String, CompiledExpression<Map, Object>> templates,
                               MeterRegistry meterRegistry) {
        this.delegate      = Objects.requireNonNull(delegate);
        this.templates     = Map.copyOf(templates);
        this.meterRegistry = meterRegistry;
    }

    @Override
    public String ganglionId() {return delegate.ganglionId();}

    @Override
    public Set<String> handledEventTypes() {return delegate.handledEventTypes();}

    @Override
    public DetectionResult detect(CloudEvent event, SituationContext context) {
        return enrichEvidence(delegate.detect(event, context), event);
    }

    @Override
    public SituationContext compact(SituationContext context) {
        return delegate.compact(context);
    }

    @Override
    public void close(String situationId, String correlationKey, String tenancyId) {
        delegate.close(situationId, correlationKey, tenancyId);
    }

    @SuppressWarnings("unchecked")
    private DetectionResult enrichEvidence(DetectionResult result, CloudEvent event) {
        Map<String, Object> ctx    = CloudEventExpressionContext.build(event);
        Map<String, Object> merged = new LinkedHashMap<>(result.evidence());
        for (var entry : templates.entrySet()) {
            try {
                Object value = entry.getValue().eval(ctx);
                if (value != null) {
                    merged.put(entry.getKey(), value);
                }
            } catch (Exception e) {
                LOG.warning("Evidence template '" + entry.getKey()
                            + "' failed for ganglion '" + delegate.ganglionId() + "': " + e.getMessage());
                if (meterRegistry != null) {
                    meterRegistry.counter("ras.expression.error",
                                          "ganglion_id", delegate.ganglionId(),
                                          "evidence_key", entry.getKey(),
                                          "expression_point", "evidence_extraction").increment();
                }
            }
        }
        return new DetectionResult(result.ganglionId(), result.confidence(), result.signal(), merged);
    }
}
