package io.casehub.ras.api;

import io.cloudevents.CloudEvent;
import io.smallrye.mutiny.Uni;
import java.util.Set;

public interface Ganglion {

    String ganglionId();

    Set<String> handledEventTypes();

    /**
     * Detect a signal from the given event in the context of an accumulating situation.
     *
     * <p><b>Design invariant — DetectionResult portability:</b> The returned result may be
     * applied to a different {@code SituationContext} than the one passed to this method
     * (e.g. after a concurrent-modification retry). Implementations must not base detection
     * decisions on {@code context.detections()} or other accumulated state, as these may
     * differ between detection time and application time.
     */
    Uni<DetectionResult> detect(CloudEvent event, SituationContext context);

    default Uni<SituationContext> compact(SituationContext context) {
        return Uni.createFrom().item(context);
    }

    default Uni<Void> close(String situationId, String correlationKey, String tenancyId) {
        return Uni.createFrom().voidItem();
    }
}
