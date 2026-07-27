package io.casehub.ras.runtime;

import io.casehub.api.engine.CaseHub;
import io.casehub.api.model.CaseDefinition;
import io.casehub.ras.api.CaseInputContributor;
import io.casehub.ras.api.CaseTrigger;
import io.casehub.ras.api.CaseTriggerConfig;
import io.casehub.ras.api.SituationContext;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class DefaultCaseTrigger implements CaseTrigger {

    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger(DefaultCaseTrigger.class.getName());

    private final List<CaseHub>               caseHubs;
    private final List<CaseInputContributor>  contributors;
    private final SituationDefinitionRegistry registry;

    @Inject
    public DefaultCaseTrigger(Instance<CaseHub> caseHubs, Instance<CaseInputContributor> contributors,
                              SituationDefinitionRegistry registry) {
        this.caseHubs = new ArrayList<>();
        caseHubs.forEach(this.caseHubs::add);
        this.contributors = new ArrayList<>();
        contributors.forEach(this.contributors::add);
        this.registry = registry;
    }

    DefaultCaseTrigger(List<CaseHub> caseHubs, List<CaseInputContributor> contributors,
                       SituationDefinitionRegistry registry) {
        this.caseHubs     = List.copyOf(caseHubs);
        this.contributors = List.copyOf(contributors);
        this.registry     = registry;
    }

    DefaultCaseTrigger(List<CaseHub> caseHubs, List<CaseInputContributor> contributors) {
        this(caseHubs, contributors, null);
    }

    @PostConstruct
    void warmUp() {
        for (CaseHub hub : caseHubs) {
            hub.getDefinition();
        }
    }

    @Override
    public UUID fire(CaseTriggerConfig triggerConfig, SituationContext context) {
        CaseHub             hub       = findCaseHub(triggerConfig);
        Map<String, Object> inputData = buildInputData(triggerConfig, context);
        return hub.startCase(inputData);
    }

    private CaseHub findCaseHub(CaseTriggerConfig config) {
        List<CaseHub> matches = caseHubs.stream()
                                        .filter(hub -> {
                                            CaseDefinition def = hub.getDefinition();
                                            return def.getNamespace().equals(config.caseNamespace())
                                                   && def.getName().equals(config.caseName())
                                                   && def.getVersion().equals(config.caseVersion());
                                        })
                                        .toList();
        if (matches.isEmpty()) {
            throw new IllegalStateException(
                    "No CaseHub found for (" + config.caseNamespace() + ", "
                    + config.caseName() + ", " + config.caseVersion() + ")");
        }
        if (matches.size() > 1) {
            throw new IllegalStateException(
                    "Multiple CaseHub beans match (" + config.caseNamespace() + ", "
                    + config.caseName() + ", " + config.caseVersion() + ")");
        }
        return matches.getFirst();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildInputData(CaseTriggerConfig config, SituationContext context) {
        Map<String, Object> data = new HashMap<>(config.baseCaseData());

        if (registry != null) {
            var compiled = registry.getCompiledDynamicData(context.situationId());
            if (compiled != null) {
                Map<String, Object> exprCtx = SituationContextExpressionContext.build(context);
                for (var entry : compiled.entrySet()) {
                    try {
                        data.put(entry.getKey(), entry.getValue().eval(exprCtx));
                    } catch (RuntimeException ex) {
                        LOG.warning("Dynamic case data expression error for key '"
                                    + entry.getKey() + "' in situation '"
                                    + context.situationId() + "': " + ex.getMessage());
                    }
                }
            }
        }

        data.put("situationId", context.situationId());
        data.put("correlationKey", context.correlationKey());
        data.put("tenancyId", context.tenancyId());
        data.put("detections", context.detections());
        for (CaseInputContributor contributor : contributors) {
            data.putAll(contributor.contribute(config, context));
        }
        return data;
    }
}
