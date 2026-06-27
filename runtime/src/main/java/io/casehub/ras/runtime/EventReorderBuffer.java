package io.casehub.ras.runtime;

import io.casehub.ras.api.SituationDefinition;
import io.cloudevents.CloudEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

class EventReorderBuffer {

    private final Duration bufferDelay;
    private final SituationDefinition definition;
    private final TreeMap<Instant, List<CloudEvent>> pending = new TreeMap<>();
    private Instant maxEventTime;
    private Instant lastArrivalTime;

    EventReorderBuffer(Duration bufferDelay, SituationDefinition definition) {
        this.bufferDelay = bufferDelay;
        this.definition = definition;
    }

    List<CloudEvent> submit(CloudEvent event, Instant now) {
        lastArrivalTime = now;
        Instant eventTime = event.getTime().toInstant();
        pending.computeIfAbsent(eventTime, k -> new ArrayList<>()).add(event);
        maxEventTime = (maxEventTime == null) ? eventTime
                : eventTime.isAfter(maxEventTime) ? eventTime : maxEventTime;
        Instant watermark = maxEventTime.minus(bufferDelay);
        return drain(watermark);
    }

    List<CloudEvent> drainAll() {
        List<CloudEvent> result = new ArrayList<>();
        for (List<CloudEvent> events : pending.values()) {
            result.addAll(events);
        }
        pending.clear();
        return result;
    }

    boolean isIdle(Instant now) {
        return lastArrivalTime != null
                && now.isAfter(lastArrivalTime.plus(bufferDelay))
                && !pending.isEmpty();
    }

    boolean isEmpty() {
        return pending.isEmpty();
    }

    SituationDefinition definition() {
        return definition;
    }

    private List<CloudEvent> drain(Instant watermark) {
        List<CloudEvent> result = new ArrayList<>();
        var head = pending.headMap(watermark, true);
        for (List<CloudEvent> events : head.values()) {
            result.addAll(events);
        }
        head.clear();
        return result;
    }
}
