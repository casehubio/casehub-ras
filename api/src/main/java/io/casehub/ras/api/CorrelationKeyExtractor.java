package io.casehub.ras.api;

import io.cloudevents.CloudEvent;

@FunctionalInterface
public interface CorrelationKeyExtractor {
    String extract(CloudEvent event);
}
