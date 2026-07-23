package io.casehub.ras.testing;

import io.casehub.ras.api.CaseTrigger;
import io.casehub.ras.api.CaseTriggerConfig;
import io.casehub.ras.api.SituationContext;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

public class MockCaseTrigger implements CaseTrigger {

    private final List<FiredCase> firedCases = new CopyOnWriteArrayList<>();

    @Override
    public UUID fire(CaseTriggerConfig triggerConfig, SituationContext context) {
        UUID caseId = UUID.randomUUID();
        firedCases.add(new FiredCase(caseId, triggerConfig, context));
        return caseId;
    }

    public List<FiredCase> firedCases() { return List.copyOf(firedCases); }

    public void reset() { firedCases.clear(); }

    public record FiredCase(UUID caseId, CaseTriggerConfig triggerConfig, SituationContext context) {}
}
