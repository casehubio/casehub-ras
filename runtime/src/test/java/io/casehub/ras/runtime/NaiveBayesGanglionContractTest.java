package io.casehub.ras.runtime;

import io.casehub.ras.api.AbstractGanglionContractTest;
import io.casehub.ras.api.Ganglion;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;

import java.net.URI;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

class NaiveBayesGanglionContractTest extends AbstractGanglionContractTest {

    @Override
    protected Ganglion createGanglion() {
        return new NaiveBayesGanglion(new NaiveBayesConfig(
                "nb-contract-g",
                Set.of("test.event"),
                List.of("A", "B"),
                new double[]{0.5, 0.5},
                Map.of("f", new FeatureLikelihood(
                        List.of("X", "Y"),
                        new double[][]{{0.7, 0.3}, {0.4, 0.6}})),
                event -> Map.of("f", "X"),
                new NaiveBayesSignalMapping("B", 0.75, 0.30)), new InMemoryGanglionStateStore());
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
