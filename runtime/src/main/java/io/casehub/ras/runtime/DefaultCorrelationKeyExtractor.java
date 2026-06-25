package io.casehub.ras.runtime;

import io.cloudevents.CloudEvent;

public final class DefaultCorrelationKeyExtractor implements CorrelationKeyExtractor {

    public static final DefaultCorrelationKeyExtractor INSTANCE = new DefaultCorrelationKeyExtractor();
    static final String SINGLETON_KEY = "_singleton";

    private DefaultCorrelationKeyExtractor() {}

    @Override
    public String extract(CloudEvent event) {
        String subject = event.getSubject();
        return subject != null ? subject : SINGLETON_KEY;
    }
}
