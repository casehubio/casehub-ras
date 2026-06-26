package io.casehub.ras.drools;

import io.casehub.ras.api.AbstractGanglionContractTest;
import io.casehub.ras.api.Ganglion;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;

import java.net.URI;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

class DroolsGanglionContractTest extends AbstractGanglionContractTest {

    @Override
    protected Ganglion createGanglion() {
        var config = new DroolsGanglionConfig(
                "test-ganglion", Set.of("test.event"),
                SessionMode.EPHEMERAL, ClockMode.PSEUDO,
                List.of("io/casehub/ras/drools/test-threshold.drl"), List.of());
        return new DroolsGanglion(config, new InMemoryDroolsSessionStore(), List.of());
    }

    @Override
    protected CloudEvent createTestEvent() {
        return CloudEventBuilder.v1()
                .withId("evt-1")
                .withSource(URI.create("/test"))
                .withType("test.event")
                .withTime(OffsetDateTime.of(2026, 6, 26, 10, 0, 0, 0, ZoneOffset.UTC))
                .build();
    }
}
