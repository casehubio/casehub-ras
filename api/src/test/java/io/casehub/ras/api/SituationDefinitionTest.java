package io.casehub.ras.api;

import io.casehub.platform.api.expression.ExpressionEvaluator;
import io.casehub.platform.api.expression.JQExpressionEvaluator;
import io.casehub.platform.api.expression.MvelExpressionEvaluator;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class SituationDefinitionTest {

    private static final CaseTriggerConfig TRIGGER = new CaseTriggerConfig(
            "io.casehub", "maintenance", "1.0", Map.of());
    private static final ChainMode CHAIN = new ChainMode.Or(Set.of("g1"));

    @Test
    void validDefinitionIsCreated() {
        var def = new SituationDefinition("equipment-failure",
                Set.of("iot.temperature"), Duration.ofMinutes(10), null, CHAIN,
                new TriggerAction.CreateCase(TRIGGER), null);

        assertThat(def.situationId()).isEqualTo("equipment-failure");
        assertThat(def.eventTypes()).containsExactly("iot.temperature");
        assertThat(def.correlationWindow()).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void nullCorrelationWindowMeansPersistent() {
        var def = new SituationDefinition("persistent-sit",
                Set.of("iot.temperature"), null, null, CHAIN, new TriggerAction.CreateCase(TRIGGER), null);
        assertThat(def.correlationWindow()).isNull();
    }

    @Test
    void emptyEventTypesRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SituationDefinition("sit-1",
                        Set.of(), null, null, CHAIN, new TriggerAction.CreateCase(TRIGGER), null))
                .withMessageContaining("must not be empty");
    }

    @Test
    void nullEventTypesRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SituationDefinition("sit-1",
                        null, null, null, CHAIN, new TriggerAction.CreateCase(TRIGGER), null))
                .withMessageContaining("must not be empty");
    }

    @Test
    void zeroCorrelationWindowRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SituationDefinition("sit-1",
                        Set.of("type"), Duration.ZERO, null, CHAIN, new TriggerAction.CreateCase(TRIGGER), null))
                .withMessageContaining("positive");
    }

    @Test
    void negativeCorrelationWindowRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SituationDefinition("sit-1",
                        Set.of("type"), Duration.ofMinutes(-5), null, CHAIN, new TriggerAction.CreateCase(TRIGGER), null))
                .withMessageContaining("positive");
    }

    @Test
    void nullChainModeRejected() {
        assertThatNullPointerException()
                .isThrownBy(() -> new SituationDefinition("sit-1",
                        Set.of("type"), null, null, null, new TriggerAction.CreateCase(TRIGGER), null))
                .withMessage("chainMode");
    }

    @Test
    void nullTriggerConfigRejected() {
        assertThatNullPointerException()
                .isThrownBy(() -> new SituationDefinition("sit-1",
                        Set.of("type"), null, null, CHAIN, null, null))
                .withMessage("triggerAction");
    }

    @Test
    void nullEventBufferDelayIsAllowed() {
        var def = new SituationDefinition("sit-1",
                Set.of("type"), null, null, CHAIN, new TriggerAction.CreateCase(TRIGGER), null);
        assertThat(def.eventBufferDelay()).isNull();
    }

    @Test
    void validEventBufferDelay() {
        var def = new SituationDefinition("sit-1",
                Set.of("type"), null, Duration.ofSeconds(5), CHAIN, new TriggerAction.CreateCase(TRIGGER), null);
        assertThat(def.eventBufferDelay()).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void zeroEventBufferDelayRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SituationDefinition("sit-1",
                        Set.of("type"), null, Duration.ZERO, CHAIN, new TriggerAction.CreateCase(TRIGGER), null))
                .withMessageContaining("positive");
    }

    @Test
    void negativeEventBufferDelayRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SituationDefinition("sit-1",
                        Set.of("type"), null, Duration.ofSeconds(-1), CHAIN, new TriggerAction.CreateCase(TRIGGER), null))
                .withMessageContaining("positive");
    }

    @Test
    void nullTriggerModeDefaultsToFireOnce() {
        var def = new SituationDefinition("sit-1", Set.of("e"),
                Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")),
                new TriggerAction.CreateCase(TRIGGER), null);
        assertThat(def.triggerMode()).isInstanceOf(TriggerMode.FireOnce.class);
    }

    @Test
    void explicitTriggerModeIsPreserved() {
        var mode = new TriggerMode.Repeating(Duration.ofMinutes(5));
        var def = new SituationDefinition("sit-1", Set.of("e"),
                Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")),
                new TriggerAction.CreateCase(TRIGGER), mode);
        assertThat(def.triggerMode()).isEqualTo(mode);
    }

    @Test
    void expressionFieldsDefaultToNull() {
        var def = new SituationDefinition("sit-1", Set.of("e"),
                                          Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")),
                                          new TriggerAction.CreateCase(new CaseTriggerConfig("ns", "c", "1", Map.of())),
                                          null);
        assertThat(def.correlationKeyExpression()).isNull();
        assertThat(def.eventFilter()).isNull();
        assertThat(def.dynamicCaseData()).isEmpty();
    }

    @Test
    void fullConstructorWithExpressions() {
        var corrExpr   = new JQExpressionEvaluator(".data.orderId");
        var filterExpr = new MvelExpressionEvaluator("data.severity >= 3");
        var dynamicData = Map.<String, ExpressionEvaluator>of(
                "orderId", new JQExpressionEvaluator(".correlationKey"));
        var def = new SituationDefinition("sit-1", Set.of("e"),
                                          Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")),
                                          new TriggerAction.CreateCase(new CaseTriggerConfig("ns", "c", "1", Map.of())),
                                          null, corrExpr, filterExpr, dynamicData);
        assertThat(def.correlationKeyExpression()).isEqualTo(corrExpr);
        assertThat(def.eventFilter()).isEqualTo(filterExpr);
        assertThat(def.dynamicCaseData()).containsKey("orderId");
    }

    @Test
    void dynamicCaseDataDefensiveCopy() {
        var mutable = new java.util.HashMap<String, ExpressionEvaluator>();
        mutable.put("key", new JQExpressionEvaluator(".data.x"));
        var def = new SituationDefinition("sit-1", Set.of("e"),
                                          Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")),
                                          new TriggerAction.CreateCase(new CaseTriggerConfig("ns", "c", "1", Map.of())),
                                          null, null, null, mutable);
        mutable.put("extra", new JQExpressionEvaluator(".data.y"));
        assertThat(def.dynamicCaseData()).hasSize(1);
    }
}
