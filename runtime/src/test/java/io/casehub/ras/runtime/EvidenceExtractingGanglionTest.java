package io.casehub.ras.runtime;

import io.casehub.platform.api.expression.CompiledExpression;
import io.casehub.ras.api.DetectionResult;
import io.casehub.ras.api.DetectionSignal;
import io.casehub.ras.api.SituationContext;
import io.casehub.ras.testing.MockGanglion;
import io.cloudevents.core.builder.CloudEventBuilder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

@SuppressWarnings("rawtypes")
class EvidenceExtractingGanglionTest {

    private static final SituationContext CTX = SituationContext.initial(
            "sit-1", "key-1", "tenant-a", Instant.parse("2026-07-21T10:00:00Z"));

    @SuppressWarnings("unchecked")
    private static CompiledExpression<Map, Object> expr(Function<Map, Object> fn) {
        return new CompiledExpression<>() {
            @Override public String type() { return "test"; }
            @Override public Object eval(Map context) { return fn.apply(context); }
        };
    }

    private static CompiledExpression<Map, Object> failingExpr() {
        return new CompiledExpression<>() {
            @Override public String type() { return "test"; }
            @Override public Object eval(Map context) { throw new RuntimeException("boom"); }
        };
    }

    @Test
    void mergesTemplateEvidenceWithDelegateEvidence() {
        var delegate = new MockGanglion("g1", Set.of("test.event"),
                new DetectionResult("g1", 0.8, DetectionSignal.DETECTED, Map.of("inner", "value")));
        Map<String, CompiledExpression<Map, Object>> templates = Map.of("extracted", expr(ctx -> "template-val"));
        var ganglion = new EvidenceExtractingGanglion(delegate, templates, null);
        var result = ganglion.detect(CloudEventBuilder.v1().withId("e1").withSource(URI.create("/t")).withType("test.event").withTime(OffsetDateTime.of(2026, 7, 21, 10, 0, 0, 0, ZoneOffset.UTC)).withData("application/json", "{\"severity\":\"HIGH\"}".getBytes()).build(), CTX);
        assertThat(result.evidence()).containsEntry("inner", "value");
        assertThat(result.evidence()).containsEntry("extracted", "template-val");
    }

    @Test
    void templateKeysOverwriteDelegateKeysOnClash() {
        var delegate = new MockGanglion("g1", Set.of("test.event"),
                new DetectionResult("g1", 0.8, DetectionSignal.DETECTED, Map.of("key", "old")));
        Map<String, CompiledExpression<Map, Object>> templates = Map.of("key", expr(ctx -> "new"));
        var ganglion = new EvidenceExtractingGanglion(delegate, templates, null);
        var result = ganglion.detect(CloudEventBuilder.v1().withId("e1").withSource(URI.create("/t")).withType("test.event").withTime(OffsetDateTime.of(2026, 7, 21, 10, 0, 0, 0, ZoneOffset.UTC)).build(), CTX);
        assertThat(result.evidence()).containsEntry("key", "new");
    }

    @Test
    void nullExpressionResultOmitsKey() {
        var delegate = new MockGanglion("g1", Set.of("test.event"),
                new DetectionResult("g1", 0.8, DetectionSignal.DETECTED, Map.of()));
        Map<String, CompiledExpression<Map, Object>> templates = Map.of("missing", expr(ctx -> null));
        var ganglion = new EvidenceExtractingGanglion(delegate, templates, null);
        var result = ganglion.detect(CloudEventBuilder.v1().withId("e1").withSource(URI.create("/t")).withType("test.event").withTime(OffsetDateTime.of(2026, 7, 21, 10, 0, 0, 0, ZoneOffset.UTC)).build(), CTX);
        assertThat(result.evidence()).doesNotContainKey("missing");
    }

    @Test
    void perTemplateErrorIsolation() {
        var delegate = new MockGanglion("g1", Set.of("test.event"),
                new DetectionResult("g1", 0.8, DetectionSignal.DETECTED, Map.of()));
        Map<String, CompiledExpression<Map, Object>> templates = new LinkedHashMap<>();
        templates.put("fails", failingExpr());
        templates.put("succeeds", expr(ctx -> "ok"));
        var ganglion = new EvidenceExtractingGanglion(delegate, Map.copyOf(templates), null);
        var result = ganglion.detect(CloudEventBuilder.v1().withId("e1").withSource(URI.create("/t")).withType("test.event").withTime(OffsetDateTime.of(2026, 7, 21, 10, 0, 0, 0, ZoneOffset.UTC)).build(), CTX);
        assertThat(result.evidence()).doesNotContainKey("fails");
        assertThat(result.evidence()).containsEntry("succeeds", "ok");
    }

    @Test
    void expressionErrorMetricIncremented() {
        var delegate = new MockGanglion("g1", Set.of("test.event"),
                new DetectionResult("g1", 0.8, DetectionSignal.DETECTED, Map.of()));
        Map<String, CompiledExpression<Map, Object>> templates = Map.of("bad", failingExpr());
        var registry = new SimpleMeterRegistry();
        var ganglion = new EvidenceExtractingGanglion(delegate, templates, registry);
        ganglion.detect(CloudEventBuilder.v1().withId("e1").withSource(URI.create("/t")).withType("test.event").withTime(OffsetDateTime.of(2026, 7, 21, 10, 0, 0, 0, ZoneOffset.UTC)).build(), CTX);
        var counter = registry.find("ras.expression.error").tag("ganglion_id", "g1").tag("evidence_key", "bad").tag("expression_point", "evidence_extraction").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void delegatesGanglionId() {
        var delegate = new MockGanglion("test-id", Set.of("test.event"),
                new DetectionResult("test-id", 0.0, DetectionSignal.NOISE, Map.of()));
        var ganglion = new EvidenceExtractingGanglion(delegate, Map.of(), null);
        assertThat(ganglion.ganglionId()).isEqualTo("test-id");
    }

    @Test
    void delegatesHandledEventTypes() {
        var delegate = new MockGanglion("g1", Set.of("a.event", "b.event"),
                new DetectionResult("g1", 0.0, DetectionSignal.NOISE, Map.of()));
        var ganglion = new EvidenceExtractingGanglion(delegate, Map.of(), null);
        assertThat(ganglion.handledEventTypes()).containsExactlyInAnyOrder("a.event", "b.event");
    }

    @Test
    void delegatesCompact() {
        var delegate = new MockGanglion("g1", Set.of("test.event"),
                new DetectionResult("g1", 0.0, DetectionSignal.NOISE, Map.of()));
        var ganglion = new EvidenceExtractingGanglion(delegate, Map.of(), null);
        SituationContext compacted = ganglion.compact(CTX);
        assertThat(compacted).isNotNull();
    }

    @Test
    void delegatesClose() {
        var delegate = new MockGanglion("g1", Set.of("test.event"),
                new DetectionResult("g1", 0.0, DetectionSignal.NOISE, Map.of()));
        var ganglion = new EvidenceExtractingGanglion(delegate, Map.of(), null);
        ganglion.close("sit-1", "key-1", "tenant-a");
    }
}
