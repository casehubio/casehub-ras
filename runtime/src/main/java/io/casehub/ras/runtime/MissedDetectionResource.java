package io.casehub.ras.runtime;

import io.casehub.ras.api.MissedDetectionRecord;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Path("/api/ras/feedback/missed")
@ApplicationScoped
public class MissedDetectionResource {

    private final MissedDetectionRecorder recorder;

    @Inject
    public MissedDetectionResource(MissedDetectionRecorder recorder) {
        this.recorder = recorder;
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response reportMissed(MissedDetectionRequest request) {
        Instant recordedAt = Instant.now();
        var record = new MissedDetectionRecord(
                request.situationId(), request.correlationKey(), request.tenancyId(),
                request.eventTime(), request.reportedBy(), request.reportId(), recordedAt,
                request.ganglionIds());

        var result = recorder.record(record);
        if (!result.accepted()) {
            return Response.status(400)
                    .entity(Map.of("error", result.rejectionReason(),
                                   "message", describeRejection(result.rejectionReason(), request.situationId())))
                    .build();
        }
        if (result.isNew()) {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("reportId", record.reportId().toString());
            response.put("status", "RECORDED");
            response.put("recordedAt", recordedAt.toString());
            response.put("possiblyDetected", result.possiblyDetected());
            response.put("crossRefConclusive", result.crossRefConclusive());
            if (result.lastTriggerTime() != null) {
                response.put("lastTriggerTime", result.lastTriggerTime().toString());
            }
            if (result.possiblyDetected()) {
                boolean ganglionLevel = request.ganglionIds() != null && !request.ganglionIds().isEmpty();
                response.put("advisory", ganglionLevel
                        ? "A trigger was found for this situation within the cross-reference window, suggesting other ganglia may have detected the event. The per-ganglion miss has been recorded."
                        : "A trigger was found for this situation within the cross-reference window. The report has been recorded. If the detection was correct, this report may inflate the missed count.");
            } else if (!result.crossRefConclusive()) {
                response.put("advisory", "The event occurred outside the trigger history retention window. Cross-reference results may be incomplete.");
            }
            return Response.status(201).entity(response).build();
        }
        return Response.ok(Map.of("reportId", record.reportId().toString(),
                                  "status", "DUPLICATE")).build();
    }

    private String describeRejection(String reason, String situationId) {
        return switch (reason) {
            case "UNKNOWN_SITUATION" -> "Situation '" + situationId + "' is not registered";
            case "FEEDBACK_NOT_CONFIGURED" -> "Situation '" + situationId + "' has no feedback configuration";
            case "EVENT_OUTSIDE_WINDOW" -> "eventTime is outside the retention window";
            case "UNKNOWN_GANGLION" -> "One or more ganglionIds are not referenced by situation '" + situationId + "'";
            default -> reason;
        };
    }

    public record MissedDetectionRequest(
            String situationId, String correlationKey, String tenancyId,
            Instant eventTime, String reportedBy, UUID reportId,
            List<String> ganglionIds) {}
}
