package io.casehub.ras.testing;

import io.casehub.ras.api.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

class MockGanglionTest {

    @Test
    void returnsConfiguredResult() {
        var expected = new DetectionResult("mock", 0.9, DetectionSignal.DETECTED, Map.of());
        var ganglion = new MockGanglion("mock", Set.of("test.event"), expected);

        var ctx = SituationContext.initial("sit-1", "tenant-a",
                Instant.parse("2026-06-20T10:00:00Z"));

        var result = ganglion.detect(null, ctx).await().indefinitely();
        assertThat(result).isEqualTo(expected);
    }

    @Test
    void recordsCallCount() {
        var expected = new DetectionResult("mock", 0.5, DetectionSignal.WEAK, Map.of());
        var ganglion = new MockGanglion("mock", Set.of("test.event"), expected);

        var ctx = SituationContext.initial("sit-1", "tenant-a",
                Instant.parse("2026-06-20T10:00:00Z"));

        ganglion.detect(null, ctx).await().indefinitely();
        ganglion.detect(null, ctx).await().indefinitely();

        assertThat(ganglion.callCount()).isEqualTo(2);
    }

    @Test
    void exposesGanglionIdAndHandledTypes() {
        var ganglion = new MockGanglion("temp-mock", Set.of("iot.temperature", "iot.pressure"),
                FixedDetectionResult.noise("temp-mock"));

        assertThat(ganglion.ganglionId()).isEqualTo("temp-mock");
        assertThat(ganglion.handledEventTypes()).containsExactlyInAnyOrder(
                "iot.temperature", "iot.pressure");
    }
}
