package io.casehub.ras.persistence.memory;

import io.casehub.ras.api.AbstractSituationStoreContractTest;
import io.casehub.ras.api.SituationStore;

class InMemorySituationStoreTest extends AbstractSituationStoreContractTest {

    @Override
    protected SituationStore createStore() {
        return new InMemorySituationStore();
    }
}
