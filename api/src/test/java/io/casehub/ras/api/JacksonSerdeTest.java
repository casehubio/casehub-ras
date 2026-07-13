package io.casehub.ras.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class JacksonSerdeTest {

    private static ObjectMapper mapper;

    @BeforeAll
    static void setUp() {
        mapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
    }

    // --- ChainMode round-trip ---

    static Stream<Arguments> chainModes() {
        return Stream.of(
                Arguments.of("and", new ChainMode.And(Set.of("g1", "g2"))),
                Arguments.of("or", new ChainMode.Or(Set.of("g1"))),
                Arguments.of("threshold", new ChainMode.Threshold(Set.of("g1", "g2"), 1.5)),
                Arguments.of("sequence", new ChainMode.Sequence(List.of("g1", "g2", "g3"))),
                Arguments.of("count", new ChainMode.Count("g1", 5)),
                Arguments.of("streak", new ChainMode.Streak("g1", 3)),
                Arguments.of("rate", new ChainMode.Rate(Set.of("g1", "g2"), 0.75, 10))
        );
    }

    @ParameterizedTest(name = "ChainMode.{0} round-trips through Jackson")
    @MethodSource("chainModes")
    void chainMode_roundTrip(String typeName, ChainMode original) throws Exception {
        String json = mapper.writeValueAsString(original);
        assertThat(json).contains("\"type\":\"" + typeName + "\"");
        ChainMode deserialized = mapper.readValue(json, ChainMode.class);
        assertThat(deserialized).isEqualTo(original);
    }

    @Test
    void chainMode_and_json_structure() throws Exception {
        var and = new ChainMode.And(Set.of("g1"));
        String json = mapper.writeValueAsString(and);
        assertThat(json).contains("\"type\":\"and\"");
        assertThat(json).contains("\"requiredGanglia\"");
    }

    @Test
    void chainMode_threshold_preserves_minConfidence() throws Exception {
        var threshold = new ChainMode.Threshold(Set.of("g1"), 2.5);
        String json = mapper.writeValueAsString(threshold);
        ChainMode roundTripped = mapper.readValue(json, ChainMode.class);
        assertThat(roundTripped).isInstanceOf(ChainMode.Threshold.class);
        assertThat(((ChainMode.Threshold) roundTripped).minConfidence()).isEqualTo(2.5);
    }

    @Test
    void chainMode_sequence_preserves_order() throws Exception {
        var seq = new ChainMode.Sequence(List.of("a", "b", "c"));
        String json = mapper.writeValueAsString(seq);
        ChainMode roundTripped = mapper.readValue(json, ChainMode.class);
        assertThat(roundTripped).isInstanceOf(ChainMode.Sequence.class);
        assertThat(((ChainMode.Sequence) roundTripped).orderedGanglia())
                .containsExactly("a", "b", "c");
    }

    // --- TriggerAction round-trip ---

    @Test
    void triggerAction_createCase_roundTrip() throws Exception {
        var config = new CaseTriggerConfig("ns", "case-name", "1.0", Map.of("key", "value"));
        var original = new TriggerAction.CreateCase(config);
        String json = mapper.writeValueAsString(original);
        assertThat(json).contains("\"type\":\"create-case\"");
        TriggerAction deserialized = mapper.readValue(json, TriggerAction.class);
        assertThat(deserialized).isEqualTo(original);
    }

    @Test
    void triggerAction_notifyOnly_roundTrip() throws Exception {
        var original = new TriggerAction.NotifyOnly();
        String json = mapper.writeValueAsString(original);
        assertThat(json).contains("\"type\":\"notify-only\"");
        TriggerAction deserialized = mapper.readValue(json, TriggerAction.class);
        assertThat(deserialized).isEqualTo(original);
    }

    @Test
    void triggerAction_createCase_emptyBaseCaseData_roundTrip() throws Exception {
        var config = new CaseTriggerConfig("ns", "name", "1.0", Map.of());
        var original = new TriggerAction.CreateCase(config);
        String json = mapper.writeValueAsString(original);
        TriggerAction deserialized = mapper.readValue(json, TriggerAction.class);
        assertThat(deserialized).isEqualTo(original);
    }

    // --- TriggerMode round-trip ---

    @Test
    void triggerMode_fireOnce_roundTrip() throws Exception {
        var original = new TriggerMode.FireOnce();
        String json = mapper.writeValueAsString(original);
        assertThat(json).contains("\"type\":\"fire-once\"");
        TriggerMode deserialized = mapper.readValue(json, TriggerMode.class);
        assertThat(deserialized).isEqualTo(original);
    }

    @Test
    void triggerMode_repeating_roundTrip() throws Exception {
        var original = new TriggerMode.Repeating(Duration.ofMinutes(5));
        String json = mapper.writeValueAsString(original);
        assertThat(json).contains("\"type\":\"repeating\"");
        TriggerMode deserialized = mapper.readValue(json, TriggerMode.class);
        assertThat(deserialized).isInstanceOf(TriggerMode.Repeating.class);
        assertThat(((TriggerMode.Repeating) deserialized).cooldown())
                .isEqualTo(Duration.ofMinutes(5));
    }

    // --- SituationDefinition round-trip (integration) ---

    @Test
    void situationDefinition_fullRoundTrip() throws Exception {
        var def = new SituationDefinition(
                "sit-1",
                Set.of("event.type.a", "event.type.b"),
                Duration.ofHours(1),
                Duration.ofSeconds(5),
                new ChainMode.Threshold(Set.of("g1", "g2"), 1.5),
                new TriggerAction.CreateCase(
                        new CaseTriggerConfig("ns", "case", "1.0", Map.of("k", "v"))),
                new TriggerMode.Repeating(Duration.ofMinutes(10))
        );
        String json = mapper.writeValueAsString(def);
        SituationDefinition deserialized = mapper.readValue(json, SituationDefinition.class);
        assertThat(deserialized.situationId()).isEqualTo("sit-1");
        assertThat(deserialized.chainMode()).isInstanceOf(ChainMode.Threshold.class);
        assertThat(deserialized.triggerAction()).isInstanceOf(TriggerAction.CreateCase.class);
        assertThat(deserialized.triggerMode()).isInstanceOf(TriggerMode.Repeating.class);
    }

    @Test
    void situationDefinition_nullableFields_roundTrip() throws Exception {
        var def = new SituationDefinition(
                "sit-2",
                Set.of("event.type.a"),
                null,
                null,
                new ChainMode.Or(Set.of("g1")),
                new TriggerAction.NotifyOnly(),
                null
        );
        String json = mapper.writeValueAsString(def);
        SituationDefinition deserialized = mapper.readValue(json, SituationDefinition.class);
        assertThat(deserialized.correlationWindow()).isNull();
        assertThat(deserialized.eventBufferDelay()).isNull();
        assertThat(deserialized.triggerMode()).isInstanceOf(TriggerMode.FireOnce.class);
    }
}
