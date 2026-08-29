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
                request.eventTime(), request.reportedBy(), request.reportId(), recordedAt);

        var result = recorder.record(record);
        if (!result.accepted()) {
            return Response.status(400)
                    .entity(Map.of("error", result.rejectionReason(),
                                   "message", describeRejection(result.rejectionReason(), request.situationId())))
                    .build();
        }
        if (result.isNew()) {
            return Response.status(201)
                    .entity(Map.of("reportId", record.reportId().toString(),
                                   "status", "RECORDED",
                                   "recordedAt", recordedAt.toString()))
                    .build();
        }
        return Response.ok(Map.of("reportId", record.reportId().toString(),
                                  "status", "DUPLICATE")).build();
    }

    private String describeRejection(String reason, String situationId) {
        return switch (reason) {
            case "UNKNOWN_SITUATION" -> "Situation '" + situationId + "' is not registered";
            case "FEEDBACK_NOT_CONFIGURED" -> "Situation '" + situationId + "' has no feedback configuration";
            case "EVENT_OUTSIDE_WINDOW" -> "eventTime is outside the retention window";
            default -> reason;
        };
    }

    public record MissedDetectionRequest(
            String situationId, String correlationKey, String tenancyId,
            Instant eventTime, String reportedBy, UUID reportId) {}
}
