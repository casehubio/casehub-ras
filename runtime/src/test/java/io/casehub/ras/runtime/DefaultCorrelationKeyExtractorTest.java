package io.casehub.ras.runtime;

import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import org.junit.jupiter.api.Test;
import java.net.URI;
import static org.assertj.core.api.Assertions.*;

class DefaultCorrelationKeyExtractorTest {

    @Test
    void returnsSubjectWhenPresent() {
        CloudEvent event = CloudEventBuilder.v1()
                .withId("1").withSource(URI.create("/test")).withType("t")
                .withSubject("machine-42")
                .build();

        assertThat(DefaultCorrelationKeyExtractor.INSTANCE.extract(event))
                .isEqualTo("machine-42");
    }

    @Test
    void returnsSingletonWhenSubjectNull() {
        CloudEvent event = CloudEventBuilder.v1()
                .withId("1").withSource(URI.create("/test")).withType("t")
                .build();

        assertThat(DefaultCorrelationKeyExtractor.INSTANCE.extract(event))
                .isEqualTo("_singleton");
    }
}
