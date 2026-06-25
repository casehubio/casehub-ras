package io.casehub.ras.drools;

import org.kie.api.runtime.KieSession;
import java.util.Optional;

public interface DroolsSessionStore {

    Optional<KieSession> get(String ganglionId, String situationId, String correlationKey, String tenancyId);

    void put(String ganglionId, String situationId, String correlationKey, String tenancyId, KieSession session);

    void remove(String ganglionId, String situationId, String correlationKey, String tenancyId);
}
