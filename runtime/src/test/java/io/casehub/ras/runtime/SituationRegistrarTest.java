package io.casehub.ras.runtime;

import io.casehub.ras.api.*;
import io.casehub.ras.api.DetectionResult;
import io.casehub.ras.testing.MockGanglion;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

class SituationRegistrarTest {

    @Test
    void registryImplementsSituationRegistrar() {
        SituationRegistrar registrar = createRegistrar();
        assertThat(registrar).isInstanceOf(SituationDefinitionRegistry.class);
    }

    @Test
    void registerMakesSituationExist() {
        SituationRegistrar registrar = createRegistrar();
        assertThat(registrar.exists("test.situation")).isFalse();

        registrar.register(testRegistration("test.situation"));
        assertThat(registrar.exists("test.situation")).isTrue();
    }

    @Test
    void deregisterRemovesSituation() {
        SituationRegistrar registrar = createRegistrar();
        registrar.register(testRegistration("test.situation"));
        assertThat(registrar.exists("test.situation")).isTrue();

        registrar.deregister("test.situation");
        assertThat(registrar.exists("test.situation")).isFalse();
    }

    @Test
    void deregisterNonExistentIsNoOp() {
        SituationRegistrar registrar = createRegistrar();
        assertThatNoException().isThrownBy(() ->
                registrar.deregister("nonexistent"));
    }

    @Test
    void registerDuplicateThrows() {
        SituationRegistrar registrar = createRegistrar();
        registrar.register(testRegistration("test.situation"));
        assertThatThrownBy(() ->
                registrar.register(testRegistration("test.situation")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate");
    }

    private SituationRegistrar createRegistrar() {
        var ganglion = new MockGanglion("test-ganglion", Set.of("test.event"),
                new DetectionResult("test-ganglion", 0.0, DetectionSignal.NOISE, Map.of()));
        return SituationDefinitionRegistry.forTesting(List.of(), List.of(ganglion));
    }

    private SituationRegistration testRegistration(String situationId) {
        return new SituationRegistration(
                new SituationDefinition(
                        situationId,
                        Set.of("test.event"),
                        Duration.ofMinutes(5),
                        null,
                        new ChainMode.Streak("test-ganglion", 3),
                        new TriggerAction.NotifyOnly(),
                        new TriggerMode.FireOnce()),
                null);
    }
}
