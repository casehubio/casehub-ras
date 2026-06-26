package io.casehub.ras.api;

import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

class JavaSwitchGanglionTest {

    private static CloudEvent testEvent() {
        return CloudEventBuilder.v1()
                .withId("evt-1")
                .withSource(URI.create("/test"))
                .withType("test.event")
                .withTime(OffsetDateTime.ofInstant(
                        Instant.parse("2026-06-26T10:00:00Z"), ZoneOffset.UTC))
                .build();
    }

    private static SituationContext testContext() {
        return SituationContext.initial("sit-1", "key-1", "tenant-a",
                Instant.parse("2026-06-26T10:00:00Z"));
    }

    static class FixedGanglion extends JavaSwitchGanglion {
        private final DetectionResult fixedResult;

        FixedGanglion(DetectionResult fixedResult) {
            super("fixed-g", Set.of("test.event"));
            this.fixedResult = fixedResult;
        }

        @Override
        protected DetectionResult evaluate(CloudEvent event, SituationContext context) {
            return fixedResult;
        }
    }

    // --- Constructor ---

    @Test
    void ganglionIdAndHandledEventTypesReturnConstructorValues() {
        var ganglion = new FixedGanglion(null);
        assertThat(ganglion.ganglionId()).isEqualTo("fixed-g");
        assertThat(ganglion.handledEventTypes()).containsExactly("test.event");
    }

    @Test
    void handledEventTypesIsUnmodifiable() {
        var ganglion = new FixedGanglion(null);
        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> ganglion.handledEventTypes().add("extra"));
    }

    @Test
    void nullGanglionIdIsRejected() {
        assertThatNullPointerException()
                .isThrownBy(() -> new JavaSwitchGanglion(null, Set.of("test.event")) {
                    @Override
                    protected DetectionResult evaluate(CloudEvent event, SituationContext context) {
                        return null;
                    }
                })
                .withMessage("ganglionId");
    }

    @Test
    void nullHandledEventTypesIsRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new JavaSwitchGanglion("g1", null) {
                    @Override
                    protected DetectionResult evaluate(CloudEvent event, SituationContext context) {
                        return null;
                    }
                });
    }

    @Test
    void emptyHandledEventTypesIsRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new JavaSwitchGanglion("g1", Set.of()) {
                    @Override
                    protected DetectionResult evaluate(CloudEvent event, SituationContext context) {
                        return null;
                    }
                });
    }

    // --- detect() delegates to evaluate() ---

    @Test
    void detectDelegatesToEvaluate() {
        var expected = new DetectionResult("fixed-g", 0.85, DetectionSignal.DETECTED,
                Map.of("celsius", 95.0));
        var ganglion = new FixedGanglion(expected);

        DetectionResult result = ganglion.detect(testEvent(), testContext())
                .await().indefinitely();

        assertThat(result).isSameAs(expected);
    }

    @Test
    void evaluateReturningNullPropagates() {
        var ganglion = new FixedGanglion(null);

        DetectionResult result = ganglion.detect(testEvent(), testContext())
                .await().indefinitely();

        assertThat(result).isNull();
    }

    // --- Helper methods ---

    @Test
    void detectedHelperWithEvidence() {
        var ganglion = new JavaSwitchGanglion("helper-g", Set.of("test.event")) {
            @Override
            protected DetectionResult evaluate(CloudEvent event, SituationContext context) {
                return detected(0.85, Map.of("key", "value"));
            }
        };

        DetectionResult result = ganglion.detect(testEvent(), testContext())
                .await().indefinitely();

        assertThat(result.ganglionId()).isEqualTo("helper-g");
        assertThat(result.confidence()).isEqualTo(0.85);
        assertThat(result.signal()).isEqualTo(DetectionSignal.DETECTED);
        assertThat(result.evidence()).containsEntry("key", "value");
    }

    @Test
    void detectedHelperWithoutEvidence() {
        var ganglion = new JavaSwitchGanglion("helper-g", Set.of("test.event")) {
            @Override
            protected DetectionResult evaluate(CloudEvent event, SituationContext context) {
                return detected(0.70);
            }
        };

        DetectionResult result = ganglion.detect(testEvent(), testContext())
                .await().indefinitely();

        assertThat(result.signal()).isEqualTo(DetectionSignal.DETECTED);
        assertThat(result.confidence()).isEqualTo(0.70);
        assertThat(result.evidence()).isEmpty();
    }

    @Test
    void weakHelperWithEvidence() {
        var ganglion = new JavaSwitchGanglion("helper-g", Set.of("test.event")) {
            @Override
            protected DetectionResult evaluate(CloudEvent event, SituationContext context) {
                return weak(0.35, Map.of("reason", "borderline"));
            }
        };

        DetectionResult result = ganglion.detect(testEvent(), testContext())
                .await().indefinitely();

        assertThat(result.ganglionId()).isEqualTo("helper-g");
        assertThat(result.signal()).isEqualTo(DetectionSignal.WEAK);
        assertThat(result.confidence()).isEqualTo(0.35);
        assertThat(result.evidence()).containsEntry("reason", "borderline");
    }

    @Test
    void weakHelperWithoutEvidence() {
        var ganglion = new JavaSwitchGanglion("helper-g", Set.of("test.event")) {
            @Override
            protected DetectionResult evaluate(CloudEvent event, SituationContext context) {
                return weak(0.20);
            }
        };

        DetectionResult result = ganglion.detect(testEvent(), testContext())
                .await().indefinitely();

        assertThat(result.signal()).isEqualTo(DetectionSignal.WEAK);
        assertThat(result.evidence()).isEmpty();
    }

    @Test
    void noiseHelper() {
        var ganglion = new JavaSwitchGanglion("helper-g", Set.of("test.event")) {
            @Override
            protected DetectionResult evaluate(CloudEvent event, SituationContext context) {
                return noise();
            }
        };

        DetectionResult result = ganglion.detect(testEvent(), testContext())
                .await().indefinitely();

        assertThat(result.ganglionId()).isEqualTo("helper-g");
        assertThat(result.signal()).isEqualTo(DetectionSignal.NOISE);
        assertThat(result.confidence()).isEqualTo(0.0);
        assertThat(result.evidence()).isEmpty();
    }

    @Test
    void antiHelperWithEvidence() {
        var ganglion = new JavaSwitchGanglion("helper-g", Set.of("test.event")) {
            @Override
            protected DetectionResult evaluate(CloudEvent event, SituationContext context) {
                return anti(0.60, Map.of("contra", true));
            }
        };

        DetectionResult result = ganglion.detect(testEvent(), testContext())
                .await().indefinitely();

        assertThat(result.ganglionId()).isEqualTo("helper-g");
        assertThat(result.signal()).isEqualTo(DetectionSignal.ANTI);
        assertThat(result.confidence()).isEqualTo(0.60);
        assertThat(result.evidence()).containsEntry("contra", true);
    }

    @Test
    void antiHelperWithoutEvidence() {
        var ganglion = new JavaSwitchGanglion("helper-g", Set.of("test.event")) {
            @Override
            protected DetectionResult evaluate(CloudEvent event, SituationContext context) {
                return anti(0.40);
            }
        };

        DetectionResult result = ganglion.detect(testEvent(), testContext())
                .await().indefinitely();

        assertThat(result.signal()).isEqualTo(DetectionSignal.ANTI);
        assertThat(result.confidence()).isEqualTo(0.40);
        assertThat(result.evidence()).isEmpty();
    }
}
