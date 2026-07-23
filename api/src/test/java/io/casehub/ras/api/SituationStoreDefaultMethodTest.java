package io.casehub.ras.api;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

class SituationStoreDefaultMethodTest {

    private final SituationStore anonymous = new SituationStore() {
        @Override
        public Optional<SituationContext> find(String situationId, String correlationKey,
                                               String tenancyId) {
            return Optional.empty();
        }

        @Override
        public SituationContext save(SituationContext context) {
            return context;
        }

        @Override
        public void remove(String situationId, String correlationKey, String tenancyId) {
        }

        @Override
        public int removeExpired(Instant cutoff) {
            return 0;
        }

        @Override
        public void removeAllForSituation(String situationId) {
        }
    };

    @Test
    void tryClaimTriggerDefaultReturnsTrue() {
        boolean result = anonymous.tryClaimTrigger("sit-1", "key-1", "tenant-a",
                                                   Instant.now());
        assertThat(result).isTrue();
    }

    @Test
    void resetTriggerClaimDefaultCompletesWithoutError() {
        assertThatNoException().isThrownBy(
                () -> anonymous.resetTriggerClaim("sit-1", "key-1", "tenant-a"));
    }

    @Test
    void removeTriggeredBeforeDefaultReturnsZero() {
        int result = anonymous.removeTriggeredBefore(Instant.now());
        assertThat(result).isZero();
    }

}
