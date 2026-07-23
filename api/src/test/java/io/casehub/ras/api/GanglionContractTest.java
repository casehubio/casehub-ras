package io.casehub.ras.api;

import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;

import java.net.URI;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;

class GanglionContractTest extends AbstractGanglionContractTest {

    @Override
    protected Ganglion createGanglion() {
        return new Ganglion() {
            @Override
            public String ganglionId() {return "test-ganglion";}

            @Override
            public Set<String> handledEventTypes() {return Set.of("test.event");}

            @Override
            public DetectionResult detect(CloudEvent event, SituationContext context) {
                return new DetectionResult("test-ganglion", 0.5, DetectionSignal.DETECTED, null);
            }
        };
    }

    @Override
    protected CloudEvent createTestEvent() {
        return CloudEventBuilder.v1()
                .withId("evt-1")
                .withSource(URI.create("/test"))
                .withType("test.event")
                .withTime(OffsetDateTime.of(2026, 6, 20, 10, 0, 0, 0, ZoneOffset.UTC))
                .build();
    }
}
