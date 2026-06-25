package io.casehub.ras.runtime;

import java.util.List;

public interface SituationDefinitionProvider {
    List<SituationRegistration> registrations();
}
