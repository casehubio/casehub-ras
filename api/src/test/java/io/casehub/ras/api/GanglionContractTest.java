package io.casehub.ras.api;

import io.cloudevents.CloudEvent;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

class GanglionContractTest {

    @Test
    void compactDefaultReturnsContextUnchanged() {
        Ganglion ganglion = new Ganglion() {
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

        var ctx = SituationContext.initial("sit-1", "tenant-a",
                java.time.Instant.parse("2026-06-20T10:00:00Z"));

        SituationContext compacted = ganglion.compact(ctx).await().indefinitely();
        assertThat(compacted).isSameAs(ctx);
    }
}
