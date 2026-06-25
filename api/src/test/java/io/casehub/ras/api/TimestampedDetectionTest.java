package io.casehub.ras.api;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class TimestampedDetectionTest {

    private static final Instant T1 = Instant.parse("2026-06-20T10:00:00Z");
    private static final DetectionResult RESULT = new DetectionResult(
            "g1", 0.8, DetectionSignal.DETECTED, Map.of("key", "val"));

    @Test
    void constructionSucceeds() {
        var td = new TimestampedDetection(RESULT, T1);
        assertThat(td.result()).isSameAs(RESULT);
        assertThat(td.eventTime()).isEqualTo(T1);
    }

    @Test
    void nullResultIsRejected() {
        assertThatNullPointerException()
                .isThrownBy(() -> new TimestampedDetection(null, T1))
                .withMessage("result");
    }

    @Test
    void nullEventTimeIsRejected() {
        assertThatNullPointerException()
                .isThrownBy(() -> new TimestampedDetection(RESULT, null))
                .withMessage("eventTime");
    }
}
