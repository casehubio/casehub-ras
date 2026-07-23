package io.casehub.ras.testing;

import io.casehub.ras.api.DetectionResult;
import io.casehub.ras.api.Ganglion;
import io.casehub.ras.api.SituationContext;
import io.cloudevents.CloudEvent;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public class MockGanglion implements Ganglion {

    private final String ganglionId;
    private final Set<String> handledEventTypes;
    private final DetectionResult fixedResult;
    private final AtomicInteger calls = new AtomicInteger();

    public MockGanglion(String ganglionId, Set<String> handledEventTypes,
            DetectionResult fixedResult) {
        this.ganglionId = ganglionId;
        this.handledEventTypes = Set.copyOf(handledEventTypes);
        this.fixedResult = fixedResult;
    }

    @Override
    public String ganglionId() { return ganglionId; }

    @Override
    public Set<String> handledEventTypes() { return handledEventTypes; }

    @Override
    public DetectionResult detect(CloudEvent event, SituationContext context) {
        calls.incrementAndGet();
        return fixedResult;
    }

    public int callCount() { return calls.get(); }

    public void reset() { calls.set(0); }
}
