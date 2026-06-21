package io.casehub.ras.drools;

import io.cloudevents.CloudEvent;
import java.util.List;
import java.util.Set;

public interface DroolsObjectExtractor {

    Set<String> handledEventTypes();

    List<Object> extract(CloudEvent event);
}
