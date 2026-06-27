package io.casehub.ras.runtime;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;

@ApplicationScoped
public class EventBufferFlushJob {

    private final SituationEvaluator evaluator;

    @Inject
    public EventBufferFlushJob(SituationEvaluator evaluator) {
        this.evaluator = evaluator;
    }

    @Scheduled(every = "PT1S")
    void flush() {
        evaluator.flushIdleBuffers(Instant.now());
    }
}
