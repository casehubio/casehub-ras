package io.casehub.ras.api;

import io.cloudevents.CloudEvent;
import io.smallrye.mutiny.Uni;
import java.util.Set;

public interface Ganglion {

    String ganglionId();

    Set<String> handledEventTypes();

    Uni<DetectionResult> detect(CloudEvent event, SituationContext context);

    default Uni<SituationContext> compact(SituationContext context) {
        return Uni.createFrom().item(context);
    }

    default Uni<Void> close(String situationId, String tenancyId) {
        return Uni.createFrom().voidItem();
    }
}
