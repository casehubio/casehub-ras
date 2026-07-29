package io.casehub.ras.api;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record TenantHealth(
        String tenancyId,
        Instant windowStart,
        Instant windowEnd,
        long totalEvents,
        List<SituationSummary> situations
) {
    public TenantHealth {
        Objects.requireNonNull(tenancyId, "tenancyId");
        Objects.requireNonNull(windowStart, "windowStart");
        Objects.requireNonNull(windowEnd, "windowEnd");
        situations = situations != null ? List.copyOf(situations) : List.of();
    }
}
