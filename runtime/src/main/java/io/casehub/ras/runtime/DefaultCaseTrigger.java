package io.casehub.ras.runtime;

import io.casehub.api.engine.CaseHub;
import io.casehub.api.model.CaseDefinition;
import io.casehub.ras.api.CaseInputContributor;
import io.casehub.ras.api.CaseTrigger;
import io.casehub.ras.api.CaseTriggerConfig;
import io.casehub.ras.api.SituationContext;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.*;
import java.util.concurrent.CompletionStage;

@ApplicationScoped
public class DefaultCaseTrigger implements CaseTrigger {

    private final List<CaseHub> caseHubs;
    private final List<CaseInputContributor> contributors;

    @Inject
    public DefaultCaseTrigger(Instance<CaseHub> caseHubs, Instance<CaseInputContributor> contributors) {
        this.caseHubs = new ArrayList<>();
        caseHubs.forEach(this.caseHubs::add);
        this.contributors = new ArrayList<>();
        contributors.forEach(this.contributors::add);
    }

    DefaultCaseTrigger(List<CaseHub> caseHubs, List<CaseInputContributor> contributors) {
        this.caseHubs = List.copyOf(caseHubs);
        this.contributors = List.copyOf(contributors);
    }

    @PostConstruct
    void warmUp() {
        for (CaseHub hub : caseHubs) {
            hub.getDefinition();
        }
    }

    @Override
    public Uni<UUID> fire(CaseTriggerConfig triggerConfig, SituationContext context) {
        CaseHub hub = findCaseHub(triggerConfig);
        Map<String, Object> inputData = buildInputData(triggerConfig, context);
        CompletionStage<UUID> cs = hub.startCase(inputData);
        return Uni.createFrom().completionStage(cs);
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

    private Map<String, Object> buildInputData(CaseTriggerConfig config, SituationContext context) {
        Map<String, Object> data = new HashMap<>(config.baseCaseData());
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
