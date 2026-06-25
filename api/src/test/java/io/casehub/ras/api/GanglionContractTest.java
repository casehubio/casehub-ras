package io.casehub.ras.api;

import io.cloudevents.CloudEvent;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

class GanglionContractTest {

    private Ganglion minimalGanglion() {
        return new Ganglion() {
            @Override
            public String ganglionId() { return "test-ganglion"; }

            @Override
            public Set<String> handledEventTypes() { return Set.of("test.event"); }

            @Override
            public Uni<DetectionResult> detect(CloudEvent event, SituationContext context) {
                return Uni.createFrom().item(
                        new DetectionResult("test-ganglion", 0.5, DetectionSignal.DETECTED, null));
            }
        };
    }

    @Test
    void compactDefaultReturnsContextUnchanged() {
        Ganglion ganglion = minimalGanglion();
        var ctx = SituationContext.initial("sit-1", "key-1", "tenant-a",
                java.time.Instant.parse("2026-06-20T10:00:00Z"));

        SituationContext compacted = ganglion.compact(ctx).await().indefinitely();
        assertThat(compacted).isSameAs(ctx);
    }

    @Test
    void closeDefaultReturnsCompletedUni() {
        Ganglion ganglion = minimalGanglion();
        Void result = ganglion.close("sit-1", "key-1", "tenant-a").await().indefinitely();
        assertThat(result).isNull();
    }
}
