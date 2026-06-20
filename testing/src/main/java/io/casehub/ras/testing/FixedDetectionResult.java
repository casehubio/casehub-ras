package io.casehub.ras.testing;

import io.casehub.ras.api.DetectionResult;
import io.casehub.ras.api.DetectionSignal;
import java.util.Map;

public final class FixedDetectionResult {

    private FixedDetectionResult() {}

    public static DetectionResult detected(String ganglionId, double confidence,
            Map<String, Object> evidence) {
        return new DetectionResult(ganglionId, confidence, DetectionSignal.DETECTED, evidence);
    }

    public static DetectionResult detected(String ganglionId, double confidence) {
        return detected(ganglionId, confidence, Map.of());
    }

    public static DetectionResult weak(String ganglionId, double confidence) {
        return new DetectionResult(ganglionId, confidence, DetectionSignal.WEAK, Map.of());
    }

    public static DetectionResult noise(String ganglionId) {
        return new DetectionResult(ganglionId, 0.0, DetectionSignal.NOISE, Map.of());
    }

    public static DetectionResult anti(String ganglionId, double confidence) {
        return new DetectionResult(ganglionId, confidence, DetectionSignal.ANTI, Map.of());
    }
}
