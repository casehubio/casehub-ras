package io.casehub.ras.api;

import io.cloudevents.CloudEvent;

@FunctionalInterface
public interface EventFilter {
    boolean accepts(CloudEvent event);
}
