package io.casehub.ras.runtime;

import io.cloudevents.CloudEvent;

import java.util.Map;

@FunctionalInterface
public interface NaiveBayesFeatureExtractor {
    Map<String, String> extract(CloudEvent event);
}
