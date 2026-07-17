package io.casehub.ras.api;

import io.smallrye.mutiny.Uni;

public interface OrphanedResourceCleaner {
    String cleanerType();
    Uni<Integer> removeOrphaned();
}
