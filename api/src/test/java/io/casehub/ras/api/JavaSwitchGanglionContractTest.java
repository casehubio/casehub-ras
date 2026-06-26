package io.casehub.ras.api;

import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;

import java.net.URI;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;

class JavaSwitchGanglionContractTest extends AbstractGanglionContractTest {

    @Override
    protected Ganglion createGanglion() {
        return new JavaSwitchGanglion("switch-contract-g", Set.of("test.event")) {
            @Override
            protected DetectionResult evaluate(CloudEvent event, SituationContext context) {
                return detected(0.75, Map.of("source", "contract-test"));
            }
        };
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
