package io.casehub.ras.api;

import java.time.Instant;

public interface SituationEventRetention {
    int removeEventsBefore(Instant cutoff);
}
