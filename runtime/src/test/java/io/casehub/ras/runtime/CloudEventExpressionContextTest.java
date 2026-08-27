package io.casehub.ras.runtime;

import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CloudEventExpressionContextTest {

    @Test
    void buildsCompleteContext() {
        CloudEvent event = CloudEventBuilder.v1()
                .withId("evt-1")
                .withSource(URI.create("/test"))
                .withType("order.created")
                .withSubject("order-123")
                .withTime(OffsetDateTime.of(2026, 7, 18, 10, 0, 0, 0, ZoneOffset.UTC))
                .withExtension("tenancyid", "tenant-A")
                .withData("application/json", "{\"orderId\":\"X\",\"severity\":3}".getBytes())
                .build();

        Map<String, Object> ctx = CloudEventExpressionContext.build(event);

        assertThat(ctx.get("type")).isEqualTo("order.created");
        assertThat(ctx.get("source")).isEqualTo("/test");
        assertThat(ctx.get("subject")).isEqualTo("order-123");
        assertThat(ctx.get("id")).isEqualTo("evt-1");
        assertThat(ctx.get("tenancyid")).isEqualTo("tenant-A");
        assertThat(ctx.get("data")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) ctx.get("data");
        assertThat(data.get("orderId")).isEqualTo("X");
        assertThat(data.get("severity")).isEqualTo(3);
    }

    @Test
    void nullSubjectAndTimeIncludedAsNull() {
        CloudEvent event = CloudEventBuilder.v1()
                .withId("evt-1")
                .withSource(URI.create("/test"))
                .withType("test.event")
                .build();

        Map<String, Object> ctx = CloudEventExpressionContext.build(event);

        assertThat(ctx).containsKey("subject");
        assertThat(ctx.get("subject")).isNull();
        assertThat(ctx.get("time")).isNull();
    }

    @Test
    void nonJsonDataProducesEmptyMap() {
        CloudEvent event = CloudEventBuilder.v1()
                .withId("evt-1")
                .withSource(URI.create("/test"))
                .withType("test.event")
                .withData("text/plain", "hello".getBytes())
                .build();

        Map<String, Object> ctx = CloudEventExpressionContext.build(event);

        assertThat(ctx.get("data")).isEqualTo(Map.of());
    }

    @Test
    void noDataProducesEmptyMap() {
        CloudEvent event = CloudEventBuilder.v1()
                .withId("evt-1")
                .withSource(URI.create("/test"))
                .withType("test.event")
                .build();

        Map<String, Object> ctx = CloudEventExpressionContext.build(event);

        assertThat(ctx.get("data")).isEqualTo(Map.of());
    }

    @Test
    void jsonPlusContentTypeIsParsed() {
        CloudEvent event = CloudEventBuilder.v1()
                .withId("evt-1")
                .withSource(URI.create("/test"))
                .withType("test.event")
                .withData("application/vnd.example+json", "{\"key\":\"val\"}".getBytes())
                .build();

        Map<String, Object> ctx = CloudEventExpressionContext.build(event);

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) ctx.get("data");
        assertThat(data.get("key")).isEqualTo("val");
    }

    @Test
    void build_exposes_all_extensions_not_just_tenancyid() {
        CloudEvent event = CloudEventBuilder.v1()
                                            .withId("1")
                                            .withType("ras.situation.triggered")
                                            .withSource(URI.create("ras://bridge"))
                                            .withExtension("tenancyid", "t1")
                                            .withExtension("situationid", "service-health")
                                            .withExtension("correlationkey", "server-1")
                                            .withExtension("changetype", "TRIGGERED")
                                            .build();
        Map<String, Object> ctx = CloudEventExpressionContext.build(event);
        assertThat(ctx.get("tenancyid")).isEqualTo("t1");
        assertThat(ctx.get("situationid")).isEqualTo("service-health");
        assertThat(ctx.get("correlationkey")).isEqualTo("server-1");
        assertThat(ctx.get("changetype")).isEqualTo("TRIGGERED");
    }
}
