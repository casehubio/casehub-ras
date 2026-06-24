package io.casehub.ras.drools;

import io.casehub.ras.api.DetectionResult;
import io.casehub.ras.api.DetectionSignal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public enum ResultCollectionStrategy {

    HIGHEST_CONFIDENCE {
        @Override
        public DetectionResult resolve(List<DetectionResult> results, String ganglionId) {
            if (results.isEmpty()) return noiseResult(ganglionId);
            DetectionResult best = results.getFirst();
            for (int i = 1; i < results.size(); i++) {
                if (results.get(i).confidence() > best.confidence()) {
                    best = results.get(i);
                }
            }
            return best;
        }
    },

    FIRST_MATCH {
        @Override
        public DetectionResult resolve(List<DetectionResult> results, String ganglionId) {
            if (results.isEmpty()) return noiseResult(ganglionId);
            return results.getFirst();
        }
    },

    LAST_WINS {
        @Override
        public DetectionResult resolve(List<DetectionResult> results, String ganglionId) {
            if (results.isEmpty()) return noiseResult(ganglionId);
            return results.getLast();
        }
    },

    ACCUMULATE {
        @Override
        public DetectionResult resolve(List<DetectionResult> results, String ganglionId) {
            if (results.isEmpty()) return noiseResult(ganglionId);
            DetectionSignal strongestSignal = DetectionSignal.NOISE;
            double maxConfidence = 0.0;
            Map<String, Object> mergedEvidence = new HashMap<>();
            for (var r : results) {
                if (r.signal().ordinal() > strongestSignal.ordinal()) {
                    strongestSignal = r.signal();
                }
                if (r.confidence() > maxConfidence) {
                    maxConfidence = r.confidence();
                }
                mergedEvidence.putAll(r.evidence());
            }
            return new DetectionResult(ganglionId, maxConfidence, strongestSignal, mergedEvidence);
        }
    };

    public abstract DetectionResult resolve(List<DetectionResult> results, String ganglionId);

    static DetectionResult noiseResult(String ganglionId) {
        return new DetectionResult(ganglionId, 0.0, DetectionSignal.NOISE, Map.of());
    }
}
