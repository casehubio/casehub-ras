package io.casehub.ras.runtime;

import io.casehub.platform.api.expression.CompiledExpression;
import io.casehub.ras.api.DetectionResult;
import io.casehub.ras.api.DetectionSignal;
import io.casehub.ras.api.SituationContext;
import io.cloudevents.core.builder.CloudEventBuilder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@SuppressWarnings("rawtypes")
class ExpressionRulesGanglionTest {

    private static final SituationContext CTX = SituationContext.initial(
            "sit-1", "key-1", "tenant-a", Instant.parse("2026-07-21T10:00:00Z"));

    private static io.cloudevents.CloudEvent event() {
        return CloudEventBuilder.v1()
                .withId("e1").withSource(URI.create("/t")).withType("test.event")
                .withTime(OffsetDateTime.of(2026, 7, 21, 10, 0, 0, 0, ZoneOffset.UTC))
                .withData("application/json", "{\"severity\":\"HIGH\"}".getBytes())
                .build();
    }

    private static CompiledExpression<Map, Boolean> matching() {
        return new CompiledExpression<>() {
            @Override public String type() { return "test"; }
            @Override public Boolean eval(Map context) { return true; }
        };
    }

    private static CompiledExpression<Map, Boolean> nonMatching() {
        return new CompiledExpression<>() {
            @Override public String type() { return "test"; }
            @Override public Boolean eval(Map context) { return false; }
        };
    }

    private static CompiledExpression<Map, Boolean> nullReturning() {
        return new CompiledExpression<>() {
            @Override public String type() { return "test"; }
            @Override public Boolean eval(Map context) { return null; }
        };
    }

    private static CompiledExpression<Map, Boolean> throwing() {
        return new CompiledExpression<>() {
            @Override public String type() { return "test"; }
            @Override public Boolean eval(Map context) { throw new RuntimeException("boom"); }
        };
    }

    @Test
    void firstMatchingRuleWins() {
        var ganglion = new ExpressionRulesGanglion("g1", Set.of("test.event"), List.of(
                new ExpressionRulesGanglion.CompiledRule(matching(), DetectionSignal.DETECTED, 0.9),
                new ExpressionRulesGanglion.CompiledRule(matching(), DetectionSignal.WEAK, 0.5)), null);
        DetectionResult result = ganglion.detect(event(), CTX).await().indefinitely();
        assertThat(result.signal()).isEqualTo(DetectionSignal.DETECTED);
        assertThat(result.confidence()).isEqualTo(0.9);
    }

    @Test
    void secondRuleMatchesWhenFirstDoesNot() {
        var ganglion = new ExpressionRulesGanglion("g1", Set.of("test.event"), List.of(
                new ExpressionRulesGanglion.CompiledRule(nonMatching(), DetectionSignal.DETECTED, 0.9),
                new ExpressionRulesGanglion.CompiledRule(matching(), DetectionSignal.WEAK, 0.5)), null);
        DetectionResult result = ganglion.detect(event(), CTX).await().indefinitely();
        assertThat(result.signal()).isEqualTo(DetectionSignal.WEAK);
        assertThat(result.confidence()).isEqualTo(0.5);
    }

    @Test
    void otherwiseMatchesWhenNoRuleMatches() {
        var ganglion = new ExpressionRulesGanglion("g1", Set.of("test.event"), List.of(
                new ExpressionRulesGanglion.CompiledRule(nonMatching(), DetectionSignal.DETECTED, 0.9),
                new ExpressionRulesGanglion.CompiledRule(null, DetectionSignal.NOISE, 0.0)), null);
        DetectionResult result = ganglion.detect(event(), CTX).await().indefinitely();
        assertThat(result.signal()).isEqualTo(DetectionSignal.NOISE);
        assertThat(result.confidence()).isEqualTo(0.0);
    }

    @Test
    void noRuleNoOtherwiseReturnsNoise() {
        var ganglion = new ExpressionRulesGanglion("g1", Set.of("test.event"), List.of(
                new ExpressionRulesGanglion.CompiledRule(nonMatching(), DetectionSignal.DETECTED, 0.9)), null);
        DetectionResult result = ganglion.detect(event(), CTX).await().indefinitely();
        assertThat(result.signal()).isEqualTo(DetectionSignal.NOISE);
        assertThat(result.confidence()).isEqualTo(0.0);
    }

    @Test
    void matchedRuleIndexInEvidence() {
        var ganglion = new ExpressionRulesGanglion("g1", Set.of("test.event"), List.of(
                new ExpressionRulesGanglion.CompiledRule(nonMatching(), DetectionSignal.DETECTED, 0.9),
                new ExpressionRulesGanglion.CompiledRule(matching(), DetectionSignal.WEAK, 0.5)), null);
        DetectionResult result = ganglion.detect(event(), CTX).await().indefinitely();
        assertThat(result.evidence()).containsEntry("matchedRuleIndex", 1);
    }

    @Test
    void matchedRuleIndexMinusOneForImplicitFallback() {
        var ganglion = new ExpressionRulesGanglion("g1", Set.of("test.event"), List.of(
                new ExpressionRulesGanglion.CompiledRule(nonMatching(), DetectionSignal.DETECTED, 0.9)), null);
        DetectionResult result = ganglion.detect(event(), CTX).await().indefinitely();
        assertThat(result.evidence()).containsEntry("matchedRuleIndex", -1);
    }

    @Test
    void nullExpressionResultTreatedAsNoMatch() {
        var ganglion = new ExpressionRulesGanglion("g1", Set.of("test.event"), List.of(
                new ExpressionRulesGanglion.CompiledRule(nullReturning(), DetectionSignal.DETECTED, 0.9),
                new ExpressionRulesGanglion.CompiledRule(matching(), DetectionSignal.WEAK, 0.3)), null);
        DetectionResult result = ganglion.detect(event(), CTX).await().indefinitely();
        assertThat(result.signal()).isEqualTo(DetectionSignal.WEAK);
    }

    @Test
    void expressionExceptionSkipsRuleTrysNext() {
        var ganglion = new ExpressionRulesGanglion("g1", Set.of("test.event"), List.of(
                new ExpressionRulesGanglion.CompiledRule(throwing(), DetectionSignal.DETECTED, 0.9),
                new ExpressionRulesGanglion.CompiledRule(matching(), DetectionSignal.WEAK, 0.5)), null);
        DetectionResult result = ganglion.detect(event(), CTX).await().indefinitely();
        assertThat(result.signal()).isEqualTo(DetectionSignal.WEAK);
    }

    @Test
    void expressionErrorMetricIncremented() {
        var registry = new SimpleMeterRegistry();
        var ganglion = new ExpressionRulesGanglion("g1", Set.of("test.event"), List.of(
                new ExpressionRulesGanglion.CompiledRule(throwing(), DetectionSignal.DETECTED, 0.9)), registry);
        ganglion.detect(event(), CTX).await().indefinitely();
        var counter = registry.find("ras.expression.error").tag("ganglion_id", "g1").tag("rule_index", "0").tag("expression_point", "rule_evaluation").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void antiSignalSupported() {
        var ganglion = new ExpressionRulesGanglion("g1", Set.of("test.event"), List.of(
                new ExpressionRulesGanglion.CompiledRule(matching(), DetectionSignal.ANTI, 0.7)), null);
        DetectionResult result = ganglion.detect(event(), CTX).await().indefinitely();
        assertThat(result.signal()).isEqualTo(DetectionSignal.ANTI);
        assertThat(result.confidence()).isEqualTo(0.7);
    }

    @Test
    void ganglionIdReturned() {
        var ganglion = new ExpressionRulesGanglion("my-id", Set.of("test.event"), List.of(), null);
        assertThat(ganglion.ganglionId()).isEqualTo("my-id");
    }

    @Test
    void handledEventTypesReturned() {
        var ganglion = new ExpressionRulesGanglion("g1", Set.of("a.event", "b.event"), List.of(), null);
        assertThat(ganglion.handledEventTypes()).containsExactlyInAnyOrder("a.event", "b.event");
    }
}
