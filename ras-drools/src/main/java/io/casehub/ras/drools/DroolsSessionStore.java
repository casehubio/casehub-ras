package io.casehub.ras.drools;

import org.kie.api.KieBase;
import org.kie.api.runtime.KieSession;
import org.kie.api.runtime.KieSessionConfiguration;

public interface DroolsSessionStore {

    KieSession computeIfAbsent(DroolsSessionKey key,
                               KieBase kieBase,
                               KieSessionConfiguration config,
                               long generation);

    void remove(DroolsSessionKey key);
}
