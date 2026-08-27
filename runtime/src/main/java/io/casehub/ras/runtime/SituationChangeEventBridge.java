package io.casehub.ras.runtime;

import io.casehub.ras.api.SituationChangeEvent;
import io.casehub.ras.api.SituationContext;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.logging.Logger;

@ApplicationScoped
public class SituationChangeEventBridge {

    private static final Logger LOG = Logger.getLogger(SituationChangeEventBridge.class.getName());
    private static final URI BRIDGE_SOURCE = URI.create("ras://bridge");

    private final Event<CloudEvent> cloudEvent;
    private final RasMetrics metrics;

    @Inject
    public SituationChangeEventBridge(Event<CloudEvent> cloudEvent, RasMetrics metrics) {
        this.cloudEvent = cloudEvent;
        this.metrics = metrics;
    }

    void onSituationChange(@ObservesAsync SituationChangeEvent change) {
        try {
            String changeTypeLower = change.changeType().name().toLowerCase();
            SituationContext ctx = change.context();

            CloudEvent bridged = CloudEventBuilder.v1()
                    .withId(UUID.randomUUID().toString())
                    .withType("ras.situation." + changeTypeLower)
                    .withSource(BRIDGE_SOURCE)
                    .withSubject(change.situationId())
                    .withTime(OffsetDateTime.now())
                    .withExtension("tenancyid", change.tenancyId())
                    .withExtension("situationid", change.situationId())
                    .withExtension("correlationkey", change.correlationKey())
                    .withExtension("changetype", change.changeType().name())
                    .withDataContentType("application/json")
                    .withData(serializeSummary(change, ctx))
                    .build();

            cloudEvent.fireAsync(bridged);
            if (metrics != null) {
                metrics.bridgeEventEmitted(change.situationId(), changeTypeLower);
            }
        } catch (Exception ex) {
            LOG.warning("SituationChangeEventBridge failed for situation '"
                        + change.situationId() + "': " + ex.getMessage());
            if (metrics != null) {
                metrics.bridgeEventFailed(change.situationId());
            }
        }
    }

    private byte[] serializeSummary(SituationChangeEvent change, SituationContext ctx) {
        String json = "{\"situationId\":\"" + change.situationId()
                + "\",\"correlationKey\":\"" + change.correlationKey()
                + "\",\"tenancyId\":\"" + change.tenancyId()
                + "\",\"changeType\":\"" + change.changeType().name()
                + "\",\"firstSignal\":\"" + ctx.firstSignal()
                + "\",\"lastSignal\":\"" + ctx.lastSignal()
                + "\",\"triggerCount\":" + ctx.triggerCount()
                + ",\"detectionCount\":" + ctx.detections().size()
                + "}";
        return json.getBytes(StandardCharsets.UTF_8);
    }
}
