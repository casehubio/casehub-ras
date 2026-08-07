package io.casehub.ras.runtime;

import io.casehub.ras.api.AbstractOutcomeLedgerContractTest;
import io.casehub.ras.api.OutcomeLedger;

class InMemoryOutcomeLedgerTest extends AbstractOutcomeLedgerContractTest {
    @Override
    protected OutcomeLedger createLedger() {
        return new InMemoryOutcomeLedger();
    }
}
