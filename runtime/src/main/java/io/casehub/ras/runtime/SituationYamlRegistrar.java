package io.casehub.ras.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.platform.bootstrap.spi.ClasspathYamlRegistrar;
import io.casehub.ras.api.SituationDefinition;
import io.casehub.ras.api.SituationRegistrar;
import io.casehub.ras.api.SituationRegistration;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;

@ApplicationScoped
public class SituationYamlRegistrar implements ClasspathYamlRegistrar {

    private static final Logger LOG = Logger.getLogger(SituationYamlRegistrar.class);
    private static final ObjectMapper YAML_MAPPER = RasYamlMapper.instance();

    @Inject
    SituationRegistrar situationRegistrar;

    @Override
    public String sectionKey() {
        return "situations";
    }

    @Override
    public Set<String> classpathPaths() {
        return Set.of("situations");
    }

    @Override
    public int priority() {
        return 300;
    }

    @Override
    public void register(String resourcePath, InputStream content) {
        try {
            SituationDefinition definition = YAML_MAPPER.readValue(content, SituationDefinition.class);
            situationRegistrar.register(new SituationRegistration(definition));
            LOG.debugf("Registered situation definition from %s: %s",
                    resourcePath, definition.situationId());
        } catch (IOException e) {
            LOG.errorf(e, "Failed to load situation definition from %s", resourcePath);
        } catch (Exception e) {
            LOG.errorf(e, "Failed to register situation definition from %s", resourcePath);
        }
    }
}
