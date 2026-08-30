package io.casehub.ras.api;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MissedDetectionRecordGanglionTest {

    @Test
    void eightArgConstructorStoresGanglionIds() {
        var record = new MissedDetectionRecord("sit", "ck", "t1",
                Instant.now(), "op", UUID.randomUUID(), Instant.now(),
                List.of("ganglion-a", "ganglion-b"));
        assertEquals(List.of("ganglion-a", "ganglion-b"), record.ganglionIds());
    }

    @Test
    void sevenArgConstructorDefaultsNull() {
        var record = new MissedDetectionRecord("sit", "ck", "t1",
                Instant.now(), "op", UUID.randomUUID(), Instant.now());
        assertNull(record.ganglionIds());
    }

    @Test
    void nullGanglionIdsAllowed() {
        var record = new MissedDetectionRecord("sit", "ck", "t1",
                Instant.now(), "op", UUID.randomUUID(), Instant.now(), null);
        assertNull(record.ganglionIds());
    }

    @Test
    void emptyGanglionIdsAllowed() {
        var record = new MissedDetectionRecord("sit", "ck", "t1",
                Instant.now(), "op", UUID.randomUUID(), Instant.now(), List.of());
        assertTrue(record.ganglionIds().isEmpty());
    }

    @Test
    void ganglionIdsDefensivelyCopied() {
        var mutable = new java.util.ArrayList<>(List.of("g1", "g2"));
        var record = new MissedDetectionRecord("sit", "ck", "t1",
                Instant.now(), "op", UUID.randomUUID(), Instant.now(), mutable);
        mutable.add("g3");
        assertEquals(2, record.ganglionIds().size());
    }
}
