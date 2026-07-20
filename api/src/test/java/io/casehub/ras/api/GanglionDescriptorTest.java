package io.casehub.ras.api;

import io.casehub.platform.api.expression.JQExpressionEvaluator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class GanglionDescriptorTest {

    @Test
    void naiveBayesRecordCarriesAllFields() {
        var feature = new GanglionDescriptor.NaiveBayes.Feature(
                new JQExpressionEvaluator(".data.severity"),
                List.of("LOW", "HIGH"),
                new double[][]{{0.8, 0.2}, {0.3, 0.7}});

        var mapping = new GanglionDescriptor.NaiveBayes.SignalMapping(
                "ANOMALY", 0.75, 0.30, 0.05);

        var descriptor = new GanglionDescriptor.NaiveBayes(
                "bayes-1",
                Set.of("sensor.reading"),
                List.of("NORMAL", "ANOMALY"),
                new double[]{0.9, 0.1},
                Map.of("severity", feature),
                mapping);

        assertThat(descriptor.ganglionId()).isEqualTo("bayes-1");
        assertThat(descriptor.handledEventTypes()).containsExactly("sensor.reading");
        assertThat(descriptor.outcomes()).containsExactly("NORMAL", "ANOMALY");
        assertThat(descriptor.priors()).containsExactly(0.9, 0.1);
        assertThat(descriptor.features()).containsKey("severity");
        assertThat(descriptor.signalMapping().targetOutcome()).isEqualTo("ANOMALY");
        assertThat(descriptor.signalMapping().antiThreshold()).isEqualTo(0.05);
    }

    @Test
    void signalMappingWithNullAntiThreshold() {
        var mapping = new GanglionDescriptor.NaiveBayes.SignalMapping(
                "ANOMALY", 0.75, 0.30, null);

        assertThat(mapping.antiThreshold()).isNull();
    }

    @Test
    void sealedInterfacePermitsOnlyNaiveBayes() {
        assertThat(GanglionDescriptor.class.isSealed()).isTrue();
        assertThat(GanglionDescriptor.class.getPermittedSubclasses())
                .hasSize(1)
                .allSatisfy(c -> assertThat(c.getSimpleName()).isEqualTo("NaiveBayes"));
    }
}
