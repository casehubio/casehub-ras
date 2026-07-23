package io.casehub.ras.api;

public interface OrphanedResourceCleaner {
    String cleanerType();

    int removeOrphaned();
}
