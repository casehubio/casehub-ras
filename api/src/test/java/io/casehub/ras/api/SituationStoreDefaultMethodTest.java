package io.casehub.ras.api;

import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;

class SituationStoreDefaultMethodTest {

    private final SituationStore anonymous = new SituationStore() {
        @Override
        public Uni<Optional<SituationContext>> find(String situationId, String correlationKey,
                                                     String tenancyId) {
            return Uni.createFrom().item(Optional.empty());
        }

        @Override
        public Uni<Void> save(SituationContext context) {
            return Uni.createFrom().voidItem();
        }

        @Override
        public Uni<Void> remove(String situationId, String correlationKey, String tenancyId) {
            return Uni.createFrom().voidItem();
        }

        @Override
        public Uni<Void> removeExpired(Instant cutoff) {
            return Uni.createFrom().voidItem();
        }
    };

    @Test
    void tryClaimTriggerDefaultReturnsTrue() {
        Boolean result = anonymous.tryClaimTrigger("sit-1", "key-1", "tenant-a")
                .await().indefinitely();
        assertThat(result).isTrue();
    }

    @Test
    void resetTriggerClaimDefaultCompletesWithoutError() {
        assertThatNoException().isThrownBy(
                () -> anonymous.resetTriggerClaim("sit-1", "key-1", "tenant-a")
                        .await().indefinitely());
    }
}
