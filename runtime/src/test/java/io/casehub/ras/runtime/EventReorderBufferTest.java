package io.casehub.ras.runtime;

import io.casehub.ras.api.*;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

class EventReorderBufferTest {

    private static final Duration BUFFER_DELAY = Duration.ofSeconds(5);
    private static final SituationDefinition DEF = new SituationDefinition(
            "sit-1", Set.of("test.event"), null, BUFFER_DELAY,
            new ChainMode.Or(Set.of("g1")),
            new CaseTriggerConfig("ns", "case", "1.0", Map.of()));

    private static final Instant NOW = Instant.parse("2026-06-27T10:00:00Z");

    private CloudEvent eventAt(Instant time) {
        return CloudEventBuilder.v1()
                .withId("evt-" + time.getEpochSecond())
                .withSource(URI.create("/test"))
                .withType("test.event")
                .withTime(OffsetDateTime.ofInstant(time, ZoneOffset.UTC))
                .build();
    }

    @Test
    void singleEventWithinDelayStaysBuffered() {
        var buffer = new EventReorderBuffer(BUFFER_DELAY, DEF);
        var event = eventAt(Instant.parse("2026-06-27T10:00:10Z"));
        List<CloudEvent> released = buffer.submit(event, NOW);
        assertThat(released).isEmpty();
        assertThat(buffer.isEmpty()).isFalse();
    }

    @Test
    void eventPastWatermarkIsReleased() {
        var buffer = new EventReorderBuffer(BUFFER_DELAY, DEF);
        var e1 = eventAt(Instant.parse("2026-06-27T10:00:00Z"));
        var e2 = eventAt(Instant.parse("2026-06-27T10:00:10Z"));
        buffer.submit(e1, NOW);
        List<CloudEvent> released = buffer.submit(e2, NOW.plusSeconds(1));
        // watermark = 10s - 5s = 5s. e1 at 0s <= 5s → released
        assertThat(released).containsExactly(e1);
    }

    @Test
    void outOfOrderEventsReorderedByEventTime() {
        var buffer = new EventReorderBuffer(BUFFER_DELAY, DEF);
        var t5 = Instant.parse("2026-06-27T10:00:05Z");
        var t10 = Instant.parse("2026-06-27T10:00:10Z");
        var t3 = Instant.parse("2026-06-27T10:00:03Z");

        // Submit T=10 first (out of order)
        buffer.submit(eventAt(t10), NOW);
        // Submit T=5 (also out of order but within buffer)
        List<CloudEvent> r1 = buffer.submit(eventAt(t5), NOW.plusSeconds(1));
        // watermark = 10s - 5s = 5s. t3 and t5 are <= 5s
        assertThat(r1).hasSize(1);
        assertThat(r1.get(0).getTime().toInstant()).isEqualTo(t5);

        // Submit T=3 (late — below watermark, immediately released)
        List<CloudEvent> r2 = buffer.submit(eventAt(t3), NOW.plusSeconds(2));
        assertThat(r2).hasSize(1);
        assertThat(r2.get(0).getTime().toInstant()).isEqualTo(t3);
    }

    @Test
    void drainAllReturnsEventsInOrder() {
        var buffer = new EventReorderBuffer(BUFFER_DELAY, DEF);
        var t1 = Instant.parse("2026-06-27T10:00:01Z");
        var t2 = Instant.parse("2026-06-27T10:00:02Z");
        buffer.submit(eventAt(t2), NOW);
        buffer.submit(eventAt(t1), NOW.plusSeconds(1));
        List<CloudEvent> all = buffer.drainAll();
        assertThat(all).hasSize(2);
        assertThat(all.get(0).getTime().toInstant()).isEqualTo(t1);
        assertThat(all.get(1).getTime().toInstant()).isEqualTo(t2);
    }

    @Test
    void isIdleReturnsTrueAfterInactivity() {
        var buffer = new EventReorderBuffer(BUFFER_DELAY, DEF);
        buffer.submit(eventAt(Instant.parse("2026-06-27T10:00:01Z")), NOW);
        assertThat(buffer.isIdle(NOW.plusSeconds(3))).isFalse();
        assertThat(buffer.isIdle(NOW.plusSeconds(6))).isTrue();
    }

    @Test
    void isIdleReturnsFalseWhenEmpty() {
        var buffer = new EventReorderBuffer(BUFFER_DELAY, DEF);
        assertThat(buffer.isIdle(NOW.plusSeconds(100))).isFalse();
    }

    @Test
    void watermarkAdvancesWithMaxEventTime() {
        var buffer = new EventReorderBuffer(BUFFER_DELAY, DEF);
        var t1 = Instant.parse("2026-06-27T10:00:01Z");
        var t10 = Instant.parse("2026-06-27T10:00:10Z");
        var t8 = Instant.parse("2026-06-27T10:00:08Z");

        buffer.submit(eventAt(t1), NOW);
        buffer.submit(eventAt(t10), NOW.plusSeconds(1));
        // watermark = 10 - 5 = 5. t1 at 1s <= 5s → released
        // t10 at 10s > 5s → stays

        // Submit t8 — doesn't advance maxEventTime (8 < 10). watermark stays at 5s.
        List<CloudEvent> r = buffer.submit(eventAt(t8), NOW.plusSeconds(2));
        // t8 at 8s > 5s → stays. Nothing new released.
        assertThat(r).isEmpty();
    }
}
