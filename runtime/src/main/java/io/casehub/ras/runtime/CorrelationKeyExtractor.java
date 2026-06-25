package io.casehub.ras.runtime;

import io.cloudevents.CloudEvent;

@FunctionalInterface
public interface CorrelationKeyExtractor {
    String extract(CloudEvent event);
}
