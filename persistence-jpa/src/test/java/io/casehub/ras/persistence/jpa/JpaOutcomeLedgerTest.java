package io.casehub.ras.persistence.jpa;

import io.casehub.ras.api.AbstractOutcomeLedgerContractTest;
import io.casehub.ras.api.OutcomeLedger;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;

@QuarkusTest
class JpaOutcomeLedgerTest extends AbstractOutcomeLedgerContractTest {

    @Inject
    JpaOutcomeLedger jpaLedger;

    @Override
    protected OutcomeLedger createLedger() {
        return jpaLedger;
    }

    @BeforeEach
    @Transactional
    void cleanTable() {
        jpaLedger.removeRecordsBefore("s1", java.time.Instant.now().plusSeconds(3600));
    }
}
