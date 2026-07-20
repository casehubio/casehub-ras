package io.casehub.ras.api;

import java.util.List;

public interface SituationDefinitionProvider {
    List<SituationRegistration> registrations();

    default List<GanglionDescriptor> ganglionDescriptors() {return List.of();}
}
