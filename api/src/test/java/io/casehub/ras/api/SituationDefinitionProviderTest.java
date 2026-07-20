package io.casehub.ras.api;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SituationDefinitionProviderTest {

    @Test
    void ganglionDescriptorsDefaultReturnsEmptyList() {
        SituationDefinitionProvider provider = List::of;
        assertThat(provider.ganglionDescriptors()).isEmpty();
    }
}
