package io.casehub.ras.drools;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

class DroolsGanglionConfigTest {

    @Test
    void validConfigWithClasspathRules() {
        var config = new DroolsGanglionConfig(
                "test-ganglion", Set.of("temp.reading"),
                SessionMode.LONG_LIVED, ClockMode.PSEUDO,
                List.of("rules/temp.drl"), List.of());
        assertThat(config.ganglionId()).isEqualTo("test-ganglion");
        assertThat(config.handledEventTypes()).containsExactly("temp.reading");
        assertThat(config.sessionMode()).isEqualTo(SessionMode.LONG_LIVED);
        assertThat(config.clockMode()).isEqualTo(ClockMode.PSEUDO);
        assertThat(config.classpathRules()).containsExactly("rules/temp.drl");
        assertThat(config.programmaticRules()).isEmpty();
    }

    @Test
    void validConfigWithProgrammaticRules() {
        var config = new DroolsGanglionConfig(
                "test-ganglion", Set.of("temp.reading"),
                SessionMode.EPHEMERAL, ClockMode.REALTIME,
                List.of(), List.of("rule \"x\" when then end"));
        assertThat(config.programmaticRules()).hasSize(1);
    }

    @Test
    void nullGanglionIdThrows() {
        assertThatThrownBy(() -> new DroolsGanglionConfig(
                null, Set.of("e"), SessionMode.LONG_LIVED, ClockMode.PSEUDO,
                List.of("r.drl"), List.of()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void emptyHandledEventTypesThrows() {
        assertThatThrownBy(() -> new DroolsGanglionConfig(
                "g", Set.of(), SessionMode.LONG_LIVED, ClockMode.PSEUDO,
                List.of("r.drl"), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("handledEventTypes");
    }

    @Test
    void noRuleSourcesThrows() {
        assertThatThrownBy(() -> new DroolsGanglionConfig(
                "g", Set.of("e"), SessionMode.LONG_LIVED, ClockMode.PSEUDO,
                List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rule source");
    }

    @Test
    void collectionsAreDefensivelyCopied() {
        var types = new java.util.HashSet<>(Set.of("a", "b"));
        var rules = new java.util.ArrayList<>(List.of("r.drl"));
        var config = new DroolsGanglionConfig("g", types,
                SessionMode.LONG_LIVED, ClockMode.PSEUDO, rules, List.of());
        types.add("c");
        rules.add("s.drl");
        assertThat(config.handledEventTypes()).containsExactlyInAnyOrder("a", "b");
        assertThat(config.classpathRules()).containsExactly("r.drl");
    }

    @Test
    void nullSessionModeThrows() {
        assertThatThrownBy(() -> new DroolsGanglionConfig(
                "g", Set.of("e"), null, ClockMode.PSEUDO,
                List.of("r.drl"), List.of()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullClockModeThrows() {
        assertThatThrownBy(() -> new DroolsGanglionConfig(
                "g", Set.of("e"), SessionMode.LONG_LIVED, null,
                List.of("r.drl"), List.of()))
                .isInstanceOf(NullPointerException.class);
    }
}
