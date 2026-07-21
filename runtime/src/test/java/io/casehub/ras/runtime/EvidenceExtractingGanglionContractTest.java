package io.casehub.ras.runtime;

import io.casehub.ras.api.AbstractGanglionContractTest;
import io.casehub.ras.api.DetectionResult;
import io.casehub.ras.api.DetectionSignal;
import io.casehub.ras.api.Ganglion;
import io.casehub.ras.testing.MockGanglion;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;

import java.net.URI;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;

class EvidenceExtractingGanglionContractTest extends AbstractGanglionContractTest {

    @Override
    protected Ganglion createGanglion() {
        Ganglion inner = new MockGanglion("contract-wrapped", Set.of("test.event"),
                new DetectionResult("contract-wrapped", 0.5, DetectionSignal.DETECTED, Map.of()));
        return new EvidenceExtractingGanglion(inner, Map.of(), null);
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
