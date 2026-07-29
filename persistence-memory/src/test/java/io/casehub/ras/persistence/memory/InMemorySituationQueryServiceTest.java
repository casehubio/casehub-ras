package io.casehub.ras.persistence.memory;

import io.casehub.ras.api.AbstractSituationQueryServiceContractTest;
import io.casehub.ras.api.SituationEvent;
import io.casehub.ras.api.SituationEventRetention;
import io.casehub.ras.api.SituationQueryService;

class InMemorySituationQueryServiceTest extends AbstractSituationQueryServiceContractTest {

    private InMemorySituationQueryService service;

    @Override
    protected SituationQueryService createQueryService() {
        service = new InMemorySituationQueryService();
        return service;
    }

    @Override
    protected SituationEventRetention createRetention() {
        return service;
    }

    @Override
    protected void seed(SituationEvent event) {
        service.record(event);
    }
}
