package io.casehub.ras.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChainModeJacksonTest {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    @Test
    void andDeserializesGangliaKey() throws Exception {
        String yaml = """
                type: and
                ganglia: [g1, g2]
                """;
        ChainMode mode = YAML.readValue(yaml, ChainMode.class);
        assertThat(mode).isInstanceOf(ChainMode.And.class);
        assertThat(((ChainMode.And) mode).requiredGanglia()).containsExactlyInAnyOrder("g1", "g2");
    }

    @Test
    void sequenceDeserializesGangliaKey() throws Exception {
        String yaml = """
                type: sequence
                ganglia: [g1, g2, g3]
                """;
        ChainMode mode = YAML.readValue(yaml, ChainMode.class);
        assertThat(mode).isInstanceOf(ChainMode.Sequence.class);
        assertThat(((ChainMode.Sequence) mode).orderedGanglia()).containsExactly("g1", "g2", "g3");
    }

    @Test
    void orDeserializes() throws Exception {
        String yaml = """
                type: or
                ganglia: [g1]
                """;
        ChainMode mode = YAML.readValue(yaml, ChainMode.class);
        assertThat(mode).isInstanceOf(ChainMode.Or.class);
        assertThat(((ChainMode.Or) mode).ganglia()).containsExactly("g1");
    }

    @Test
    void thresholdDeserializes() throws Exception {
        String yaml = """
                type: threshold
                ganglia: [g1, g2]
                minConfidence: 0.7
                """;
        ChainMode mode = YAML.readValue(yaml, ChainMode.class);
        assertThat(mode).isInstanceOf(ChainMode.Threshold.class);
        var t = (ChainMode.Threshold) mode;
        assertThat(t.ganglia()).containsExactlyInAnyOrder("g1", "g2");
        assertThat(t.minConfidence()).isEqualTo(0.7);
    }

    @Test
    void countDeserializes() throws Exception {
        String yaml = """
                type: count
                ganglionId: g1
                requiredCount: 3
                """;
        ChainMode mode = YAML.readValue(yaml, ChainMode.class);
        assertThat(mode).isInstanceOf(ChainMode.Count.class);
        assertThat(((ChainMode.Count) mode).requiredCount()).isEqualTo(3);
    }

    @Test
    void createCaseDeserializesFromFlatYaml() throws Exception {
        String yaml = """
                type: create-case
                caseNamespace: test
                caseName: test-case
                caseVersion: "1"
                """;
        TriggerAction action = YAML.readValue(yaml, TriggerAction.class);
        assertThat(action).isInstanceOf(TriggerAction.CreateCase.class);
        var cc = (TriggerAction.CreateCase) action;
        assertThat(cc.config().caseNamespace()).isEqualTo("test");
        assertThat(cc.config().caseName()).isEqualTo("test-case");
        assertThat(cc.config().caseVersion()).isEqualTo("1");
    }

    @Test
    void createCaseDeserializesWithBaseCaseData() throws Exception {
        String yaml = """
                type: create-case
                caseNamespace: ns
                caseName: name
                caseVersion: "2"
                baseCaseData:
                  key1: value1
                """;
        TriggerAction action = YAML.readValue(yaml, TriggerAction.class);
        var cc = (TriggerAction.CreateCase) action;
        assertThat(cc.config().baseCaseData()).containsEntry("key1", "value1");
    }

    @Test
    void notifyOnlyDeserializes() throws Exception {
        String yaml = """
                type: notify-only
                """;
        TriggerAction action = YAML.readValue(yaml, TriggerAction.class);
        assertThat(action).isInstanceOf(TriggerAction.NotifyOnly.class);
    }
}
