package io.casehub.ras.api;

import java.time.Instant;
import java.util.Optional;

public interface SuppressionStrategy {

    boolean shouldSuppress(String situationId, String correlationKey, String tenancyId,
                           FeedbackConfig config, Optional<Instant> lastNoiseDismissalTime);
}
