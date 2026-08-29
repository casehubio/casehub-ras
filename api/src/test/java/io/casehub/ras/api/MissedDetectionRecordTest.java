package io.casehub.ras.api;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MissedDetectionRecordTest {

    @Test
    void record_construction() {
        var record = new MissedDetectionRecord("sit-1", "key-1", "tenant-a",
                Instant.parse("2026-08-28T10:00:00Z"), "operator@example.com",
                UUID.randomUUID(), Instant.now());
        assertThat(record.situationId()).isEqualTo("sit-1");
        assertThat(record.correlationKey()).isEqualTo("key-1");
        assertThat(record.tenancyId()).isEqualTo("tenant-a");
        assertThat(record.reportedBy()).isEqualTo("operator@example.com");
    }

    @Test
    void record_rejects_null_situationId() {
        assertThatThrownBy(() -> new MissedDetectionRecord(null, "key", "tenant",
                Instant.now(), "operator", UUID.randomUUID(), Instant.now()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void record_rejects_null_reportId() {
        assertThatThrownBy(() -> new MissedDetectionRecord("sit", "key", "tenant",
                Instant.now(), "operator", null, Instant.now()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void record_rejects_null_eventTime() {
        assertThatThrownBy(() -> new MissedDetectionRecord("sit", "key", "tenant",
                null, "operator", UUID.randomUUID(), Instant.now()))
                .isInstanceOf(NullPointerException.class);
    }
}
