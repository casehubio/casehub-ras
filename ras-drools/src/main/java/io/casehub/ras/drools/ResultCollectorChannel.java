package io.casehub.ras.drools;

import io.casehub.ras.api.DetectionResult;
import org.kie.api.runtime.Channel;
import java.util.ArrayList;
import java.util.List;

class ResultCollectorChannel implements Channel {

    private final List<DetectionResult> results = new ArrayList<>();

    @Override
    public void send(Object object) {
        if (object instanceof DetectionResult dr) {
            results.add(dr);
        }
    }

    List<DetectionResult> results() { return List.copyOf(results); }
}
