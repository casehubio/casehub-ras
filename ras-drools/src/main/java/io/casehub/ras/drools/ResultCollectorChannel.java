package io.casehub.ras.drools;

import io.casehub.ras.api.DetectionResult;
import org.kie.api.runtime.Channel;

class ResultCollectorChannel implements Channel {

    private DetectionResult result;

    @Override
    public void send(Object object) {
        if (object instanceof DetectionResult dr) {
            result = dr;
        }
    }

    DetectionResult getResult() { return result; }
}
