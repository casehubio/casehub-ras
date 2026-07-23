package io.casehub.ras.api;

import io.cloudevents.CloudEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

public abstract class AbstractGanglionContractTest {

    protected abstract Ganglion createGanglion();

    protected abstract CloudEvent createTestEvent();

    @Test
    void ganglionIdIsNonNull() {
        assertThat(createGanglion().ganglionId()).isNotNull();
    }

    @Test
    void handledEventTypesIsNonEmpty() {
        assertThat(createGanglion().handledEventTypes()).isNotEmpty();
    }

    @Test
    void detectReturnsNonNull() {
        Ganglion ganglion = createGanglion();
        var ctx = SituationContext.initial("sit-1", "key-1", "tenant-a",
                                           Instant.parse("2026-06-20T10:00:00Z"));
        DetectionResult result = ganglion.detect(createTestEvent(), ctx);
        assertThat(result).isNotNull();
        assertThat(result.ganglionId()).isEqualTo(ganglion.ganglionId());
    }

    @Test
    void compactReturnsNonNullContext() {
        var ctx = SituationContext.initial("sit-1", "key-1", "tenant-a",
                                           Instant.parse("2026-06-20T10:00:00Z"));
        SituationContext compacted = createGanglion().compact(ctx);
        assertThat(compacted).isNotNull();
    }

    @Test
    void closeCompletes() {
        createGanglion().close("sit-1", "key-1", "tenant-a");
    }
}
