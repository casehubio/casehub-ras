package io.casehub.ras.api;

public interface SituationRegistrar {
    void register(SituationRegistration registration);
    void deregister(String situationId);
    boolean exists(String situationId);
}
