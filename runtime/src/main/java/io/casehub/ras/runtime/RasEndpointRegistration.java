package io.casehub.ras.runtime;

import io.casehub.platform.api.endpoints.*;
import io.casehub.platform.api.identity.TenancyConstants;
import io.casehub.platform.api.path.Path;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class RasEndpointRegistration {

    @Inject
    EndpointRegistry registry;

    void onStart(@Observes StartupEvent event) {
        registry.register(new EndpointDescriptor(
                Path.of("ras", "situations"),
                TenancyConstants.PLATFORM_TENANT_ID,
                EndpointType.SERVICE,
                EndpointProtocol.HTTP,
                Map.of(),
                null,
                Set.of(EndpointCapability.QUERY)));
    }
}
