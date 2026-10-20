package io.casehub.ras.runtime;

import io.casehub.ras.api.SituationRegistrar;
import io.casehub.ras.api.SituationRegistration;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SituationYamlRegistrarTest {

    @Test
    void registersParsedSituationDefinition() {
        String yaml = """
                situationId: bootstrap-test
                eventTypes: [event.type.a]
                chainMode:
                  type: or
                  ganglia: [g1]
                triggerAction:
                  type: notify-only
                """;
        var captured = new ArrayList<SituationRegistration>();
        var registrar = new SituationYamlRegistrar();
        registrar.situationRegistrar = new CapturingSituationRegistrar(captured);

        registrar.register("situations/test.yaml",
                new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));

        assertThat(captured).hasSize(1);
        assertThat(captured.get(0).definition().situationId()).isEqualTo("bootstrap-test");
        assertThat(captured.get(0).definition().eventTypes()).containsExactly("event.type.a");
    }

    @Test
    void registersWithCreateCaseTriggerAction() {
        String yaml = """
                situationId: case-test
                eventTypes: [event.type.b]
                correlationWindow: PT1H
                chainMode:
                  type: threshold
                  ganglia: [g1, g2]
                  minConfidence: 0.7
                triggerAction:
                  type: create-case
                  caseNamespace: test
                  caseName: test-case
                  caseVersion: "1"
                """;
        var captured = new ArrayList<SituationRegistration>();
        var registrar = new SituationYamlRegistrar();
        registrar.situationRegistrar = new CapturingSituationRegistrar(captured);

        registrar.register("situations/case.yaml",
                new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));

        assertThat(captured).hasSize(1);
        var def = captured.get(0).definition();
        assertThat(def.situationId()).isEqualTo("case-test");
        assertThat(def.triggerAction()).isInstanceOf(io.casehub.ras.api.TriggerAction.CreateCase.class);
        var cc = (io.casehub.ras.api.TriggerAction.CreateCase) def.triggerAction();
        assertThat(cc.config().caseNamespace()).isEqualTo("test");
    }

    @Test
    void badYamlLogsErrorDoesNotThrow() {
        String yaml = "{{invalid yaml content";
        var captured = new ArrayList<SituationRegistration>();
        var registrar = new SituationYamlRegistrar();
        registrar.situationRegistrar = new CapturingSituationRegistrar(captured);

        registrar.register("situations/bad.yaml",
                new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));

        assertThat(captured).isEmpty();
    }

    @Test
    void missingSituationIdLogsErrorDoesNotThrow() {
        String yaml = """
                eventTypes: [event.type.a]
                chainMode:
                  type: or
                  ganglia: [g1]
                triggerAction:
                  type: notify-only
                """;
        var captured = new ArrayList<SituationRegistration>();
        var registrar = new SituationYamlRegistrar();
        registrar.situationRegistrar = new CapturingSituationRegistrar(captured);

        registrar.register("situations/no-id.yaml",
                new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));

        assertThat(captured).isEmpty();
    }

    @Test
    void sectionKeyIsSituations() {
        var registrar = new SituationYamlRegistrar();
        assertThat(registrar.sectionKey()).isEqualTo("situations");
    }

    @Test
    void classpathPathsContainsSituations() {
        var registrar = new SituationYamlRegistrar();
        assertThat(registrar.classpathPaths()).containsExactly("situations");
    }

    @Test
    void priorityIs300() {
        var registrar = new SituationYamlRegistrar();
        assertThat(registrar.priority()).isEqualTo(300);
    }

    private record CapturingSituationRegistrar(
            List<SituationRegistration> captured
    ) implements SituationRegistrar {
        @Override
        public void register(SituationRegistration registration) {
            captured.add(registration);
        }

        @Override
        public void deregister(String situationId) {}

        @Override
        public boolean exists(String situationId) {
            return captured.stream().anyMatch(
                    r -> r.definition().situationId().equals(situationId));
        }
    }
}
