package io.casehub.ras.runtime;

import io.casehub.ras.api.DetectionResult;
import io.casehub.ras.api.DetectionSignal;
import io.casehub.ras.api.SituationContext;
import io.casehub.ras.api.TimestampedDetection;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.*;

class SituationContextExpressionContextTest {

    @Test
    void buildsCompleteContext() {
        var detection = new DetectionResult("g1", 0.9, DetectionSignal.DETECTED, Map.of("key", "val"));
        var ctx = new SituationContext("sit-1", "corr-1", "tenant-A",
                Instant.parse("2026-07-18T10:00:00Z"),
                Instant.parse("2026-07-18T10:05:00Z"),
                List.of(new TimestampedDetection(detection, Instant.parse("2026-07-18T10:05:00Z"))),
                OptionalLong.empty(), Instant.parse("2026-07-18T10:04:00Z"), 1);

        Map<String, Object> result = SituationContextExpressionContext.build(ctx);

        assertThat(result.get("situationId")).isEqualTo("sit-1");
        assertThat(result.get("correlationKey")).isEqualTo("corr-1");
        assertThat(result.get("tenancyId")).isEqualTo("tenant-A");
        assertThat(result.get("triggerCount")).isEqualTo(1);
        assertThat(result.get("lastTriggered")).isEqualTo("2026-07-18T10:04:00Z");
        assertThat(result.get("detections")).isInstanceOf(List.class);
        assertThat(result).doesNotContainKey("storeVersion");
    }

    @Test
    void nullLastTriggeredIncluded() {
        var ctx = new SituationContext("sit-1", "corr-1", "tenant-A",
                Instant.now(), Instant.now(), List.of(), OptionalLong.empty(), null, 0);

        Map<String, Object> result = SituationContextExpressionContext.build(ctx);

        assertThat(result).containsKey("lastTriggered");
        assertThat(result.get("lastTriggered")).isNull();
    }
}
