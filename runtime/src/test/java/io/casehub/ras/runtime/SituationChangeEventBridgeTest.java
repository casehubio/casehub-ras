package io.casehub.ras.runtime;

import io.casehub.ras.api.SituationChangeEvent;
import io.casehub.ras.api.SituationContext;
import io.cloudevents.CloudEvent;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.NotificationOptions;
import jakarta.enterprise.util.TypeLiteral;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class SituationChangeEventBridgeTest {

    private CollectingCloudEvent collectingEvent;
    private SituationChangeEventBridge bridge;

    @BeforeEach
    void setUp() {
        collectingEvent = new CollectingCloudEvent();
        bridge = new SituationChangeEventBridge(collectingEvent, null);
    }

    @Test
    void bridge_produces_correct_cloud_event_shape() {
        var context = SituationContext.initial("sit-1", "key-1", "tenant-1",
                Instant.parse("2026-01-01T00:00:00Z"));
        var change = new SituationChangeEvent("tenant-1", "sit-1", "key-1",
                SituationChangeEvent.ChangeType.TRIGGERED, context);

        bridge.onSituationChange(change);

        assertThat(collectingEvent.fired).hasSize(1);
        CloudEvent bridged = collectingEvent.fired.get(0);
        assertThat(bridged.getType()).isEqualTo("ras.situation.triggered");
        assertThat(bridged.getSubject()).isEqualTo("sit-1");
        assertThat(bridged.getSource().toString()).isEqualTo("ras://bridge");
        assertThat(bridged.getExtension("tenancyid")).isEqualTo("tenant-1");
        assertThat(bridged.getExtension("situationid")).isEqualTo("sit-1");
        assertThat(bridged.getExtension("correlationkey")).isEqualTo("key-1");
        assertThat(bridged.getExtension("changetype")).isEqualTo("TRIGGERED");
        assertThat(bridged.getId()).isNotNull();
        assertThat(bridged.getTime()).isNotNull();
        assertThat(bridged.getDataContentType()).isEqualTo("application/json");
    }

    @Test
    void bridge_maps_all_change_types() {
        for (SituationChangeEvent.ChangeType ct : SituationChangeEvent.ChangeType.values()) {
            var context = SituationContext.initial("s", "k", "t", Instant.now());
            var change = new SituationChangeEvent("t", "s", "k", ct, context);
            collectingEvent.fired.clear();
            bridge.onSituationChange(change);
            assertThat(collectingEvent.fired).hasSize(1);
            assertThat(collectingEvent.fired.get(0).getType())
                    .isEqualTo("ras.situation." + ct.name().toLowerCase());
        }
    }

    @Test
    void bridge_includes_json_summary_data() {
        var context = SituationContext.initial("sit-1", "key-1", "tenant-1",
                Instant.parse("2026-01-01T00:00:00Z"));
        var change = new SituationChangeEvent("tenant-1", "sit-1", "key-1",
                SituationChangeEvent.ChangeType.TRIGGERED, context);

        bridge.onSituationChange(change);

        CloudEvent bridged = collectingEvent.fired.get(0);
        assertThat(bridged.getData()).isNotNull();
        String json = new String(bridged.getData().toBytes());
        assertThat(json).contains("\"situationId\":\"sit-1\"");
        assertThat(json).contains("\"correlationKey\":\"key-1\"");
        assertThat(json).contains("\"tenancyId\":\"tenant-1\"");
        assertThat(json).contains("\"changeType\":\"TRIGGERED\"");
    }

    @Test
    void bridge_swallows_exceptions() {
        var failingEvent = new FailingCloudEvent();
        var safeBridge = new SituationChangeEventBridge(failingEvent, null);
        var context = SituationContext.initial("s", "k", "t", Instant.now());
        var change = new SituationChangeEvent("t", "s", "k",
                SituationChangeEvent.ChangeType.TRIGGERED, context);
        assertThatCode(() -> safeBridge.onSituationChange(change)).doesNotThrowAnyException();
    }

    static class CollectingCloudEvent implements Event<CloudEvent> {
        final List<CloudEvent> fired = new CopyOnWriteArrayList<>();
        @Override public void fire(CloudEvent event) { fired.add(event); }
        @Override public <U extends CloudEvent> CompletionStage<U> fireAsync(U event) {
            fired.add(event);
            return CompletableFuture.completedFuture(event);
        }
        @Override public <U extends CloudEvent> CompletionStage<U> fireAsync(U event, NotificationOptions options) {
            fired.add(event);
            return CompletableFuture.completedFuture(event);
        }
        @Override public Event<CloudEvent> select(Annotation... qualifiers) { return this; }
        @Override public <U extends CloudEvent> Event<U> select(Class<U> subtype, Annotation... qualifiers) { throw new UnsupportedOperationException(); }
        @Override public <U extends CloudEvent> Event<U> select(TypeLiteral<U> subtype, Annotation... qualifiers) { throw new UnsupportedOperationException(); }
    }

    static class FailingCloudEvent implements Event<CloudEvent> {
        @Override public void fire(CloudEvent event) { throw new RuntimeException("fail"); }
        @Override public <U extends CloudEvent> CompletionStage<U> fireAsync(U event) { throw new RuntimeException("fail"); }
        @Override public <U extends CloudEvent> CompletionStage<U> fireAsync(U event, NotificationOptions options) { throw new RuntimeException("fail"); }
        @Override public Event<CloudEvent> select(Annotation... qualifiers) { return this; }
        @Override public <U extends CloudEvent> Event<U> select(Class<U> subtype, Annotation... qualifiers) { throw new UnsupportedOperationException(); }
        @Override public <U extends CloudEvent> Event<U> select(TypeLiteral<U> subtype, Annotation... qualifiers) { throw new UnsupportedOperationException(); }
    }
}
