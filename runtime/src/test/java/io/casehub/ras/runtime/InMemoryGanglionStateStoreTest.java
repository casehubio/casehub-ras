package io.casehub.ras.runtime;

import io.casehub.ras.api.AbstractGanglionStateStoreContractTest;
import io.casehub.ras.api.GanglionStateStore;

class InMemoryGanglionStateStoreTest extends AbstractGanglionStateStoreContractTest {
    @Override
    protected GanglionStateStore createStore() {
        return new InMemoryGanglionStateStore();
    }
}
