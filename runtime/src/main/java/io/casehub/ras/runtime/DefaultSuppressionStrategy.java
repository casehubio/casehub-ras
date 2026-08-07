package io.casehub.ras.runtime;

import io.casehub.ras.api.FeedbackConfig;
import io.casehub.ras.api.SuppressionStrategy;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;
import java.util.Optional;

@ApplicationScoped
@DefaultBean
public class DefaultSuppressionStrategy implements SuppressionStrategy {

    @Override
    public boolean shouldSuppress(String situationId, String correlationKey, String tenancyId,
                                   FeedbackConfig config, Optional<Instant> lastNoiseDismissalTime) {
        if (lastNoiseDismissalTime.isEmpty()) return false;
        Instant cooldownEnd = lastNoiseDismissalTime.get().plus(config.suppressionCooldown());
        return cooldownEnd.isAfter(Instant.now());
    }
}
