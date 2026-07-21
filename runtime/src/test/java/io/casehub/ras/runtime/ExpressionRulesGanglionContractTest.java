package io.casehub.ras.runtime;

import io.casehub.ras.api.AbstractGanglionContractTest;
import io.casehub.ras.api.DetectionSignal;
import io.casehub.ras.api.Ganglion;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;

import java.net.URI;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

class ExpressionRulesGanglionContractTest extends AbstractGanglionContractTest {

    @Override
    protected Ganglion createGanglion() {
        return new ExpressionRulesGanglion(
                "contract-rules", Set.of("test.event"),
                List.of(new ExpressionRulesGanglion.CompiledRule(
                        new io.casehub.platform.api.expression.CompiledExpression<>() {
                            @Override public String type() { return "test"; }
                            @Override public Boolean eval(Map context) { return true; }
                        },
                        DetectionSignal.DETECTED, 0.8)),
                null);
    }

    @Override
    protected CloudEvent createTestEvent() {
        return CloudEventBuilder.v1()
                .withId("evt-1")
                .withSource(URI.create("/test"))
                .withType("test.event")
                .withTime(OffsetDateTime.of(2026, 7, 21, 10, 0, 0, 0, ZoneOffset.UTC))
                .build();
    }
}
