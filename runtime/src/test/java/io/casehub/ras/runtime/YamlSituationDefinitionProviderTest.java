package io.casehub.ras.runtime;

import io.casehub.ras.api.*;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class YamlSituationDefinitionProviderTest {

    private YamlSituationDefinitionProvider provider(String yaml) {
        InputStream is = new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8));
        return new YamlSituationDefinitionProvider(is);
    }

    @Test
    void parsesAndChainMode() {
        var regs = provider("""
                situations:
                  - situationId: sit1
                    eventTypes: [e1, e2]
                    correlationWindow: PT5M
                    chainMode:
                      type: and
                      ganglia: [g1, g2]
                    triggerConfig:
                      caseNamespace: ns
                      caseName: case1
                      caseVersion: "1.0"
                """).registrations();

        assertThat(regs).hasSize(1);
        var def = regs.get(0).definition();
        assertThat(def.situationId()).isEqualTo("sit1");
        assertThat(def.eventTypes()).containsExactlyInAnyOrder("e1", "e2");
        assertThat(def.correlationWindow()).isEqualTo(Duration.ofMinutes(5));
        assertThat(def.chainMode()).isInstanceOf(ChainMode.And.class);
        var and = (ChainMode.And) def.chainMode();
        assertThat(and.requiredGanglia()).containsExactlyInAnyOrder("g1", "g2");
    }

    @Test
    void parsesOrChainMode() {
        var regs = provider("""
                situations:
                  - situationId: sit1
                    eventTypes: [e1]
                    chainMode:
                      type: or
                      ganglia: [g1, g2]
                    triggerConfig:
                      caseNamespace: ns
                      caseName: c
                      caseVersion: "1"
                """).registrations();

        assertThat(regs).hasSize(1);
        var def = regs.get(0).definition();
        assertThat(def.correlationWindow()).isNull();
        assertThat(def.chainMode()).isInstanceOf(ChainMode.Or.class);
        assertThat(((ChainMode.Or) def.chainMode()).ganglia()).containsExactlyInAnyOrder("g1", "g2");
    }

    @Test
    void parsesThresholdChainMode() {
        var regs = provider("""
                situations:
                  - situationId: sit1
                    eventTypes: [e1]
                    chainMode:
                      type: threshold
                      ganglia: [g1, g2]
                      minConfidence: 0.8
                    triggerConfig:
                      caseNamespace: ns
                      caseName: c
                      caseVersion: "1"
                """).registrations();

        var threshold = (ChainMode.Threshold) regs.get(0).definition().chainMode();
        assertThat(threshold.ganglia()).containsExactlyInAnyOrder("g1", "g2");
        assertThat(threshold.minConfidence()).isEqualTo(0.8);
    }

    @Test
    void parsesSequenceChainMode() {
        var regs = provider("""
                situations:
                  - situationId: sit1
                    eventTypes: [e1, e2]
                    chainMode:
                      type: sequence
                      ganglia: [g1, g2, g3]
                    triggerConfig:
                      caseNamespace: ns
                      caseName: c
                      caseVersion: "1"
                """).registrations();

        var seq = (ChainMode.Sequence) regs.get(0).definition().chainMode();
        assertThat(seq.orderedGanglia()).containsExactly("g1", "g2", "g3");
    }

    @Test
    void parsesCountChainMode() {
        var regs = provider("""
                situations:
                  - situationId: sit1
                    eventTypes: [e1]
                    chainMode:
                      type: count
                      ganglionId: g1
                      requiredCount: 5
                    triggerConfig:
                      caseNamespace: ns
                      caseName: c
                      caseVersion: "1"
                """).registrations();

        var count = (ChainMode.Count) regs.get(0).definition().chainMode();
        assertThat(count.ganglionId()).isEqualTo("g1");
        assertThat(count.requiredCount()).isEqualTo(5);
    }

    @Test
    void parsesBaseCaseData() {
        var regs = provider("""
                situations:
                  - situationId: sit1
                    eventTypes: [e1]
                    chainMode:
                      type: or
                      ganglia: [g1]
                    triggerConfig:
                      caseNamespace: ns
                      caseName: c
                      caseVersion: "1"
                      baseCaseData:
                        priority: HIGH
                        severity: 5
                """).registrations();

        var config = regs.get(0).definition().triggerConfig();
        assertThat(config.caseNamespace()).isEqualTo("ns");
        assertThat(config.baseCaseData()).containsEntry("priority", "HIGH");
        assertThat(config.baseCaseData()).containsEntry("severity", 5);
    }

    @Test
    void numericCaseVersionConvertedToString() {
        var regs = provider("""
                situations:
                  - situationId: sit1
                    eventTypes: [e1]
                    chainMode:
                      type: or
                      ganglia: [g1]
                    triggerConfig:
                      caseNamespace: ns
                      caseName: c
                      caseVersion: 2.0
                """).registrations();

        assertThat(regs.get(0).definition().triggerConfig().caseVersion()).isEqualTo("2.0");
    }

    @Test
    void multipleSituationsParsed() {
        var regs = provider("""
                situations:
                  - situationId: sit1
                    eventTypes: [e1]
                    chainMode:
                      type: or
                      ganglia: [g1]
                    triggerConfig:
                      caseNamespace: ns
                      caseName: c1
                      caseVersion: "1"
                  - situationId: sit2
                    eventTypes: [e2]
                    chainMode:
                      type: or
                      ganglia: [g2]
                    triggerConfig:
                      caseNamespace: ns
                      caseName: c2
                      caseVersion: "1"
                """).registrations();

        assertThat(regs).hasSize(2);
        assertThat(regs.get(0).definition().situationId()).isEqualTo("sit1");
        assertThat(regs.get(1).definition().situationId()).isEqualTo("sit2");
    }

    @Test
    void emptyYamlReturnsEmptyList() {
        var regs = provider("").registrations();
        assertThat(regs).isEmpty();
    }

    @Test
    void noSituationsKeyReturnsEmptyList() {
        var regs = provider("other: value").registrations();
        assertThat(regs).isEmpty();
    }

    @Test
    void usesDefaultCorrelationKeyExtractor() {
        var regs = provider("""
                situations:
                  - situationId: sit1
                    eventTypes: [e1]
                    chainMode:
                      type: or
                      ganglia: [g1]
                    triggerConfig:
                      caseNamespace: ns
                      caseName: c
                      caseVersion: "1"
                """).registrations();

        assertThat(regs.get(0).correlationKeyExtractor())
                .isSameAs(DefaultCorrelationKeyExtractor.INSTANCE);
    }

    @Test
    void unknownChainModeTypeThrows() {
        assertThatThrownBy(() -> provider("""
                situations:
                  - situationId: sit1
                    eventTypes: [e1]
                    chainMode:
                      type: unknown
                      ganglia: [g1]
                    triggerConfig:
                      caseNamespace: ns
                      caseName: c
                      caseVersion: "1"
                """).registrations())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown");
    }

    @Test
    void missingSituationIdThrows() {
        assertThatThrownBy(() -> provider("""
                situations:
                  - eventTypes: [e1]
                    chainMode:
                      type: or
                      ganglia: [g1]
                    triggerConfig:
                      caseNamespace: ns
                      caseName: c
                      caseVersion: "1"
                """).registrations())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("situationId");
    }

    @Test
    void parsesEventBufferDelay() {
        var regs = provider("""
                situations:
                  - situationId: buffered-sit
                    eventTypes: [test.event]
                    correlationWindow: PT5M
                    eventBufferDelay: PT3S
                    chainMode:
                      type: or
                      ganglia: [g1]
                    triggerConfig:
                      caseNamespace: ns
                      caseName: case
                      caseVersion: "1.0"
                """).registrations();
        assertThat(regs).hasSize(1);
        assertThat(regs.get(0).definition().eventBufferDelay())
                .isEqualTo(Duration.ofSeconds(3));
    }

    @Test
    void absentEventBufferDelayIsNull() {
        var regs = provider("""
                situations:
                  - situationId: no-buffer
                    eventTypes: [test.event]
                    chainMode:
                      type: or
                      ganglia: [g1]
                    triggerConfig:
                      caseNamespace: ns
                      caseName: case
                      caseVersion: "1.0"
                """).registrations();
        assertThat(regs).hasSize(1);
        assertThat(regs.get(0).definition().eventBufferDelay()).isNull();
    }

    @Test
    void missingChainModeThrows() {
        assertThatThrownBy(() -> provider("""
                situations:
                  - situationId: sit1
                    eventTypes: [e1]
                    triggerConfig:
                      caseNamespace: ns
                      caseName: c
                      caseVersion: "1"
                """).registrations())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("chainMode");
    }

    @Test
    void missingTriggerConfigThrows() {
        assertThatThrownBy(() -> provider("""
                situations:
                  - situationId: sit1
                    eventTypes: [e1]
                    chainMode:
                      type: or
                      ganglia: [g1]
                """).registrations())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("triggerConfig");
    }

    @Test
    void parsesFireOnceTriggerMode() {
        var regs = provider("""
                situations:
                  - situationId: sit1
                    eventTypes: [e1]
                    chainMode:
                      type: or
                      ganglia: [g1]
                    triggerConfig:
                      caseNamespace: ns
                      caseName: c
                      caseVersion: "1"
                    triggerMode:
                      type: fire-once
                """).registrations();

        assertThat(regs).hasSize(1);
        var triggerMode = regs.get(0).definition().triggerMode();
        assertThat(triggerMode).isInstanceOf(TriggerMode.FireOnce.class);
    }

    @Test
    void parsesRepeatingTriggerMode() {
        var regs = provider("""
                situations:
                  - situationId: sit1
                    eventTypes: [e1]
                    chainMode:
                      type: or
                      ganglia: [g1]
                    triggerConfig:
                      caseNamespace: ns
                      caseName: c
                      caseVersion: "1"
                    triggerMode:
                      type: repeating
                      cooldown: PT5M
                """).registrations();

        assertThat(regs).hasSize(1);
        var triggerMode = regs.get(0).definition().triggerMode();
        assertThat(triggerMode).isInstanceOf(TriggerMode.Repeating.class);
        var repeating = (TriggerMode.Repeating) triggerMode;
        assertThat(repeating.cooldown()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void defaultsToFireOnceWhenTriggerModeAbsent() {
        var regs = provider("""
                situations:
                  - situationId: sit1
                    eventTypes: [e1]
                    chainMode:
                      type: or
                      ganglia: [g1]
                    triggerConfig:
                      caseNamespace: ns
                      caseName: c
                      caseVersion: "1"
                """).registrations();

        assertThat(regs).hasSize(1);
        var triggerMode = regs.get(0).definition().triggerMode();
        assertThat(triggerMode).isInstanceOf(TriggerMode.FireOnce.class);
    }

    @Test
    void parsesStreakChainMode() {
        var regs = provider("""
                situations:
                  - situationId: sit1
                    eventTypes: [e1]
                    chainMode:
                      type: streak
                      ganglionId: g1
                      requiredCount: 3
                    triggerConfig:
                      caseNamespace: ns
                      caseName: c
                      caseVersion: "1"
                """).registrations();

        var streak = (ChainMode.Streak) regs.get(0).definition().chainMode();
        assertThat(streak.ganglionId()).isEqualTo("g1");
        assertThat(streak.requiredCount()).isEqualTo(3);
    }

    @Test
    void parsesRateChainMode() {
        var regs = provider("""
                situations:
                  - situationId: sit1
                    eventTypes: [e1]
                    chainMode:
                      type: rate
                      ganglia: [g1, g2]
                      minRate: 0.6
                      windowSize: 10
                    triggerConfig:
                      caseNamespace: ns
                      caseName: c
                      caseVersion: "1"
                """).registrations();

        var rate = (ChainMode.Rate) regs.get(0).definition().chainMode();
        assertThat(rate.ganglia()).containsExactlyInAnyOrder("g1", "g2");
        assertThat(rate.minRate()).isEqualTo(0.6);
        assertThat(rate.windowSize()).isEqualTo(10);
    }
}
