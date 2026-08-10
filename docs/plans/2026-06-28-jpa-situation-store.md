# JPA SituationStore Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a JPA-backed SituationStore for durable situation persistence, replacing InMemorySituationStore in production deployments.

**Architecture:** New `persistence-jpa/` module with a single JPA entity (`SituationEntity`), JSONB detections column, and blocking JPA wrapped in Uni for SPI compliance. Also renames `persistence-memory/` package for consistency and fixes its CDI priority to match the platform protocol.

**Tech Stack:** Java 21, Quarkus 3.32 (Hibernate ORM, Flyway, Jackson, JDBC PostgreSQL), JUnit 5, AssertJ, @QuarkusTest with Devservices

## Global Constraints

- `persistence-memory/` package renames from `io.casehub.ras.memory` to `io.casehub.ras.persistence.memory` — all importers must update
- `InMemorySituationStore` changes from `@Priority(1)` to `@Priority(100)` — protocol compliance
- `persistence-jpa/` uses plain `@ApplicationScoped` (Tier 2, no @Alternative)
- `quarkus-jdbc-postgresql` is `<optional>true</optional>` — consumers control the driver
- Flyway migration path: `db/ras/migration/` (NOT `db/migration/`)
- `@JdbcTypeCode(SqlTypes.JSON)` required on JSONB String fields
- Method-level `@Transactional` with explicit `TxType`
- Spec: `docs/superpowers/specs/2026-06-28-jpa-situation-store-design.md`

---

### Task 1: Rename persistence-memory package + fix @Priority

Rename `io.casehub.ras.memory` → `io.casehub.ras.persistence.memory` and change `@Priority(1)` → `@Priority(100)`. All existing tests must pass with updated imports.

**Files:**
- Move: `persistence-memory/src/main/java/io/casehub/ras/memory/InMemorySituationStore.java` → `persistence-memory/src/main/java/io/casehub/ras/persistence/memory/InMemorySituationStore.java`
- Move: `persistence-memory/src/test/java/io/casehub/ras/memory/InMemorySituationStoreTest.java` → `persistence-memory/src/test/java/io/casehub/ras/persistence/memory/InMemorySituationStoreTest.java`
- Modify (imports): `runtime/src/test/java/io/casehub/ras/runtime/RasEngineTest.java`
- Modify (imports): `runtime/src/test/java/io/casehub/ras/runtime/SituationEvaluatorTest.java`
- Modify (imports): `runtime/src/test/java/io/casehub/ras/runtime/SituationExpiryJobTest.java`

**Interfaces:**
- Consumes: existing `InMemorySituationStore`
- Produces: same class at new package `io.casehub.ras.persistence.memory.InMemorySituationStore`, `@Priority(100)`

- [ ] **Step 1: Use IntelliJ to move InMemorySituationStore to new package**

Use `ide_move_file` to move the source file. IntelliJ handles package declaration and import updates across the project.

```
ide_move_file:
  file: persistence-memory/src/main/java/io/casehub/ras/memory/InMemorySituationStore.java
  destination: persistence-memory/src/main/java/io/casehub/ras/persistence/memory
```

- [ ] **Step 2: Use IntelliJ to move InMemorySituationStoreTest**

```
ide_move_file:
  file: persistence-memory/src/test/java/io/casehub/ras/memory/InMemorySituationStoreTest.java
  destination: persistence-memory/src/test/java/io/casehub/ras/persistence/memory
```

- [ ] **Step 3: Verify imports were updated in runtime/ tests**

Check that `RasEngineTest.java`, `SituationEvaluatorTest.java`, and `SituationExpiryJobTest.java` now import from `io.casehub.ras.persistence.memory.InMemorySituationStore`. If IntelliJ didn't update them (cross-module), fix manually.

- [ ] **Step 4: Change @Priority(1) to @Priority(100)**

In `InMemorySituationStore.java`, change:

```java
@Alternative
@Priority(100)
public class InMemorySituationStore implements SituationStore {
```

- [ ] **Step 5: Delete old empty package directories**

Remove the now-empty `persistence-memory/src/main/java/io/casehub/ras/memory/` and `persistence-memory/src/test/java/io/casehub/ras/memory/` directories.

- [ ] **Step 6: Run all tests**

Run: `/opt/homebrew/bin/mvn --batch-mode install`
Expected: All tests pass with the new package and priority.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "refactor(casehub-ras#14): rename persistence-memory package + fix @Priority

Rename io.casehub.ras.memory → io.casehub.ras.persistence.memory for
consistent sibling naming with persistence-jpa. Fix InMemorySituationStore
@Priority(1) → @Priority(100) per persistence-backend-cdi-priority protocol
(Tier 4 in-memory, not Tier 3 secondary backend).

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: persistence-jpa module — entity, mapper, store, migration, tests

Create the complete JPA SituationStore module with all production code and tests.

**Files:**
- Create: `persistence-jpa/pom.xml`
- Create: `persistence-jpa/src/main/java/io/casehub/ras/persistence/jpa/SituationEntity.java`
- Create: `persistence-jpa/src/main/java/io/casehub/ras/persistence/jpa/SituationMapper.java`
- Create: `persistence-jpa/src/main/java/io/casehub/ras/persistence/jpa/JpaSituationStore.java`
- Create: `persistence-jpa/src/main/resources/db/ras/migration/V1__create_ras_situation.sql`
- Create: `persistence-jpa/src/test/java/io/casehub/ras/persistence/jpa/JpaSituationStoreTest.java`
- Create: `persistence-jpa/src/test/resources/application.properties`
- Modify: `pom.xml` (add `<module>persistence-jpa</module>`)

**Interfaces:**
- Consumes: `SituationStore` SPI (api/), `SituationContext`, `TimestampedDetection`, `DetectionResult`, `DetectionSignal`
- Produces: `JpaSituationStore implements SituationStore` — `@ApplicationScoped`, CDI auto-activated

- [ ] **Step 1: Add persistence-jpa to parent pom.xml modules**

In the root `pom.xml`, add `<module>persistence-jpa</module>` to the `<modules>` section:

```xml
    <modules>
        <module>api</module>
        <module>runtime</module>
        <module>ras-drools</module>
        <module>ras-llm</module>
        <module>persistence-memory</module>
        <module>persistence-jpa</module>
        <module>testing</module>
    </modules>
```

Also add the dependency management entry in `<dependencyManagement>`:

```xml
            <dependency>
                <groupId>io.casehub</groupId>
                <artifactId>casehub-ras-jpa</artifactId>
                <version>${project.version}</version>
            </dependency>
```

- [ ] **Step 2: Create persistence-jpa/pom.xml**

Create `persistence-jpa/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>io.casehub</groupId>
        <artifactId>casehub-ras-parent</artifactId>
        <version>0.2-SNAPSHOT</version>
    </parent>
    <artifactId>casehub-ras-jpa</artifactId>
    <name>CaseHub RAS :: Persistence JPA</name>
    <description>JPA-backed SituationStore. @ApplicationScoped — displaces InMemorySituationStore
        when on the classpath. Consumers must add classpath:db/ras/migration to
        quarkus.flyway.locations.</description>
    <dependencies>
        <dependency>
            <groupId>io.casehub</groupId>
            <artifactId>casehub-ras-api</artifactId>
        </dependency>
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-hibernate-orm</artifactId>
        </dependency>
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-flyway</artifactId>
        </dependency>
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-jackson</artifactId>
        </dependency>
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-jdbc-postgresql</artifactId>
            <optional>true</optional>
        </dependency>
        <dependency>
            <groupId>io.smallrye.reactive</groupId>
            <artifactId>mutiny</artifactId>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>jakarta.inject</groupId>
            <artifactId>jakarta.inject-api</artifactId>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>jakarta.enterprise</groupId>
            <artifactId>jakarta.enterprise.cdi-api</artifactId>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>jakarta.transaction</groupId>
            <artifactId>jakarta.transaction-api</artifactId>
            <scope>provided</scope>
        </dependency>

        <!-- Test -->
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-junit5</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.assertj</groupId>
            <artifactId>assertj-core</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
    <build>
        <plugins>
            <plugin>
                <groupId>io.quarkus</groupId>
                <artifactId>quarkus-maven-plugin</artifactId>
                <version>${version.quarkus.platform}</version>
                <extensions>true</extensions>
                <executions>
                    <execution>
                        <goals>
                            <goal>build</goal>
                            <goal>generate-code</goal>
                            <goal>generate-code-tests</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>
            <plugin>
                <groupId>io.smallrye</groupId>
                <artifactId>jandex-maven-plugin</artifactId>
                <version>${jandex-maven-plugin.version}</version>
                <executions>
                    <execution>
                        <id>make-index</id>
                        <goals><goal>jandex</goal></goals>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 3: Create Flyway migration**

Create `persistence-jpa/src/main/resources/db/ras/migration/V1__create_ras_situation.sql`:

```sql
CREATE TABLE ras_situation (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    situation_id VARCHAR(255) NOT NULL,
    correlation_key VARCHAR(255) NOT NULL,
    tenancy_id VARCHAR(255) NOT NULL,
    first_signal TIMESTAMP WITH TIME ZONE NOT NULL,
    last_signal TIMESTAMP WITH TIME ZONE NOT NULL,
    detections JSONB NOT NULL DEFAULT '[]',
    CONSTRAINT uk_ras_situation UNIQUE (situation_id, correlation_key, tenancy_id)
);

CREATE INDEX idx_ras_situation_last_signal ON ras_situation (last_signal);
```

- [ ] **Step 4: Create test application.properties**

Create `persistence-jpa/src/test/resources/application.properties`:

```properties
quarkus.flyway.locations=classpath:db/ras/migration
quarkus.flyway.migrate-at-start=true
quarkus.hibernate-orm.database.generation=none
```

- [ ] **Step 5: Create SituationEntity**

Create `persistence-jpa/src/main/java/io/casehub/ras/persistence/jpa/SituationEntity.java`:

```java
package io.casehub.ras.persistence.jpa;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ras_situation",
       uniqueConstraints = @UniqueConstraint(
           columnNames = {"situation_id", "correlation_key", "tenancy_id"}))
public class SituationEntity {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "situation_id", nullable = false)
    private String situationId;

    @Column(name = "correlation_key", nullable = false)
    private String correlationKey;

    @Column(name = "tenancy_id", nullable = false)
    private String tenancyId;

    @Column(name = "first_signal", nullable = false)
    private Instant firstSignal;

    @Column(name = "last_signal", nullable = false)
    private Instant lastSignal;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detections", columnDefinition = "jsonb", nullable = false)
    private String detections;

    protected SituationEntity() {}

    public SituationEntity(String situationId, String correlationKey, String tenancyId,
                           Instant firstSignal, Instant lastSignal, String detections) {
        this.situationId = situationId;
        this.correlationKey = correlationKey;
        this.tenancyId = tenancyId;
        this.firstSignal = firstSignal;
        this.lastSignal = lastSignal;
        this.detections = detections;
    }

    public UUID getId() { return id; }
    public String getSituationId() { return situationId; }
    public String getCorrelationKey() { return correlationKey; }
    public String getTenancyId() { return tenancyId; }
    public Instant getFirstSignal() { return firstSignal; }
    public void setFirstSignal(Instant firstSignal) { this.firstSignal = firstSignal; }
    public Instant getLastSignal() { return lastSignal; }
    public void setLastSignal(Instant lastSignal) { this.lastSignal = lastSignal; }
    public String getDetections() { return detections; }
    public void setDetections(String detections) { this.detections = detections; }
}
```

- [ ] **Step 6: Create SituationMapper**

Create `persistence-jpa/src/main/java/io/casehub/ras/persistence/jpa/SituationMapper.java`:

```java
package io.casehub.ras.persistence.jpa;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.ras.api.SituationContext;
import io.casehub.ras.api.TimestampedDetection;
import java.util.List;

class SituationMapper {

    private static final TypeReference<List<TimestampedDetection>> DETECTIONS_TYPE =
            new TypeReference<>() {};

    private final ObjectMapper objectMapper;

    SituationMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    SituationContext toContext(SituationEntity entity) {
        List<TimestampedDetection> detections = deserializeDetections(entity.getDetections());
        return new SituationContext(
                entity.getSituationId(),
                entity.getCorrelationKey(),
                entity.getTenancyId(),
                entity.getFirstSignal(),
                entity.getLastSignal(),
                detections);
    }

    SituationEntity toEntity(SituationContext context) {
        return new SituationEntity(
                context.situationId(),
                context.correlationKey(),
                context.tenancyId(),
                context.firstSignal(),
                context.lastSignal(),
                serializeDetections(context.detections()));
    }

    void updateEntity(SituationEntity entity, SituationContext context) {
        entity.setFirstSignal(context.firstSignal());
        entity.setLastSignal(context.lastSignal());
        entity.setDetections(serializeDetections(context.detections()));
    }

    String serializeDetections(List<TimestampedDetection> detections) {
        try {
            return objectMapper.writeValueAsString(detections);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize detections", e);
        }
    }

    List<TimestampedDetection> deserializeDetections(String json) {
        try {
            return objectMapper.readValue(json, DETECTIONS_TYPE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize detections: " + json, e);
        }
    }
}
```

- [ ] **Step 7: Create JpaSituationStore**

Create `persistence-jpa/src/main/java/io/casehub/ras/persistence/jpa/JpaSituationStore.java`:

```java
package io.casehub.ras.persistence.jpa;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.ras.api.SituationContext;
import io.casehub.ras.api.SituationStore;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;
import java.time.Instant;
import java.util.Optional;

@ApplicationScoped
public class JpaSituationStore implements SituationStore {

    private final EntityManager em;
    private final SituationMapper mapper;

    @Inject
    public JpaSituationStore(EntityManager em, ObjectMapper objectMapper) {
        this.em = em;
        this.mapper = new SituationMapper(objectMapper);
    }

    @Override
    @Transactional(TxType.SUPPORTS)
    public Uni<Optional<SituationContext>> find(String situationId, String correlationKey,
                                                 String tenancyId) {
        SituationEntity entity = em.createQuery(
                        "SELECT s FROM SituationEntity s WHERE s.situationId = :sid " +
                        "AND s.correlationKey = :ck AND s.tenancyId = :tid",
                        SituationEntity.class)
                .setParameter("sid", situationId)
                .setParameter("ck", correlationKey)
                .setParameter("tid", tenancyId)
                .getResultStream().findFirst().orElse(null);
        if (entity == null) {
            return Uni.createFrom().item(Optional.empty());
        }
        return Uni.createFrom().item(Optional.of(mapper.toContext(entity)));
    }

    @Override
    @Transactional(TxType.REQUIRED)
    public Uni<Void> save(SituationContext context) {
        SituationEntity existing = em.createQuery(
                        "SELECT s FROM SituationEntity s WHERE s.situationId = :sid " +
                        "AND s.correlationKey = :ck AND s.tenancyId = :tid",
                        SituationEntity.class)
                .setParameter("sid", context.situationId())
                .setParameter("ck", context.correlationKey())
                .setParameter("tid", context.tenancyId())
                .getResultStream().findFirst().orElse(null);
        if (existing != null) {
            mapper.updateEntity(existing, context);
        } else {
            em.persist(mapper.toEntity(context));
        }
        return Uni.createFrom().voidItem();
    }

    @Override
    @Transactional(TxType.REQUIRED)
    public Uni<Void> remove(String situationId, String correlationKey, String tenancyId) {
        em.createQuery("DELETE FROM SituationEntity s WHERE s.situationId = :sid " +
                       "AND s.correlationKey = :ck AND s.tenancyId = :tid")
                .setParameter("sid", situationId)
                .setParameter("ck", correlationKey)
                .setParameter("tid", tenancyId)
                .executeUpdate();
        return Uni.createFrom().voidItem();
    }

    @Override
    @Transactional(TxType.REQUIRED)
    public Uni<Void> removeExpired(Instant cutoff) {
        em.createQuery("DELETE FROM SituationEntity s WHERE s.lastSignal < :cutoff")
                .setParameter("cutoff", cutoff)
                .executeUpdate();
        return Uni.createFrom().voidItem();
    }
}
```

- [ ] **Step 8: Create JpaSituationStoreTest with all 10 test scenarios**

Create `persistence-jpa/src/test/java/io/casehub/ras/persistence/jpa/JpaSituationStoreTest.java`:

```java
package io.casehub.ras.persistence.jpa;

import io.casehub.ras.api.*;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

@QuarkusTest
class JpaSituationStoreTest {

    @Inject
    JpaSituationStore store;

    private static final Instant T1 = Instant.parse("2026-06-28T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-06-28T10:05:00Z");
    private static final Instant T3 = Instant.parse("2026-06-28T10:10:00Z");

    @BeforeEach
    void cleanUp() {
        store.removeExpired(Instant.MAX).await().indefinitely();
    }

    @Test
    void saveAndFindRoundTrip() {
        var ctx = SituationContext.initial("sit-1", "key-1", "tenant-a", T1);
        var detection = new DetectionResult("g1", 0.8, DetectionSignal.DETECTED,
                Map.of("sensor", "temp-1", "value", 42.5));
        ctx = ctx.withDetection(detection, T2);

        store.save(ctx).await().indefinitely();

        var found = store.find("sit-1", "key-1", "tenant-a").await().indefinitely();
        assertThat(found).isPresent();
        var result = found.get();
        assertThat(result.situationId()).isEqualTo("sit-1");
        assertThat(result.correlationKey()).isEqualTo("key-1");
        assertThat(result.tenancyId()).isEqualTo("tenant-a");
        assertThat(result.firstSignal()).isEqualTo(T1);
        assertThat(result.lastSignal()).isEqualTo(T2);
        assertThat(result.detections()).hasSize(1);
        assertThat(result.detections().get(0).result().ganglionId()).isEqualTo("g1");
        assertThat(result.detections().get(0).result().confidence()).isEqualTo(0.8);
        assertThat(result.detections().get(0).result().signal()).isEqualTo(DetectionSignal.DETECTED);
        assertThat(result.detections().get(0).result().evidence())
                .containsEntry("sensor", "temp-1");
        assertThat(result.detections().get(0).eventTime()).isEqualTo(T2);
    }

    @Test
    void saveUpdatesExisting() {
        var ctx1 = SituationContext.initial("sit-1", "key-1", "tenant-a", T1);
        store.save(ctx1).await().indefinitely();

        var detection = new DetectionResult("g1", 0.8, DetectionSignal.DETECTED, Map.of());
        var ctx2 = ctx1.withDetection(detection, T2);
        store.save(ctx2).await().indefinitely();

        var found = store.find("sit-1", "key-1", "tenant-a").await().indefinitely();
        assertThat(found).isPresent();
        assertThat(found.get().detections()).hasSize(1);
        assertThat(found.get().lastSignal()).isEqualTo(T2);
    }

    @Test
    void findReturnsEmptyForUnknownKey() {
        var result = store.find("unknown", "key-1", "tenant-a").await().indefinitely();
        assertThat(result).isEmpty();
    }

    @Test
    void removeDeletesByNaturalKey() {
        var ctx = SituationContext.initial("sit-1", "key-1", "tenant-a", T1);
        store.save(ctx).await().indefinitely();
        store.remove("sit-1", "key-1", "tenant-a").await().indefinitely();
        assertThat(store.find("sit-1", "key-1", "tenant-a").await().indefinitely()).isEmpty();
    }

    @Test
    void removeNonExistentKeyIsNoOp() {
        assertThatNoException().isThrownBy(
                () -> store.remove("nonexistent", "key-1", "tenant-a").await().indefinitely());
    }

    @Test
    void removeExpiredEvictsOldEntries() {
        var old = SituationContext.initial("old-sit", "key-1", "tenant-a", T1);
        var recent = SituationContext.initial("recent-sit", "key-1", "tenant-a", T3);
        store.save(old).await().indefinitely();
        store.save(recent).await().indefinitely();

        store.removeExpired(T2).await().indefinitely();

        assertThat(store.find("old-sit", "key-1", "tenant-a").await().indefinitely()).isEmpty();
        assertThat(store.find("recent-sit", "key-1", "tenant-a").await().indefinitely()).isPresent();
    }

    @Test
    void detectionsJsonRoundTrip() {
        var ctx = SituationContext.initial("sit-1", "key-1", "tenant-a", T1);
        var d1 = new DetectionResult("g1", 0.9, DetectionSignal.DETECTED,
                Map.of("key1", "value1", "count", 42));
        var d2 = new DetectionResult("g2", 0.3, DetectionSignal.WEAK, Map.of());
        var d3 = new DetectionResult("g3", 0.0, DetectionSignal.NOISE, Map.of("flag", true));
        ctx = ctx.withDetection(d1, T1);
        ctx = ctx.withDetection(d2, T2);
        ctx = ctx.withDetection(d3, T3);

        store.save(ctx).await().indefinitely();

        var found = store.find("sit-1", "key-1", "tenant-a").await().indefinitely();
        assertThat(found).isPresent();
        var detections = found.get().detections();
        assertThat(detections).hasSize(3);
        assertThat(detections.get(0).result().ganglionId()).isEqualTo("g1");
        assertThat(detections.get(0).result().evidence()).containsEntry("key1", "value1");
        assertThat(detections.get(1).result().signal()).isEqualTo(DetectionSignal.WEAK);
        assertThat(detections.get(2).result().evidence()).containsEntry("flag", true);
    }

    @Test
    void tenantIsolation() {
        var ctxA = SituationContext.initial("sit-1", "key-1", "tenant-a", T1);
        var ctxB = SituationContext.initial("sit-1", "key-1", "tenant-b", T1);
        store.save(ctxA).await().indefinitely();
        store.save(ctxB).await().indefinitely();

        assertThat(store.find("sit-1", "key-1", "tenant-a").await().indefinitely())
                .isPresent().get().extracting(SituationContext::tenancyId).isEqualTo("tenant-a");
        assertThat(store.find("sit-1", "key-1", "tenant-b").await().indefinitely())
                .isPresent().get().extracting(SituationContext::tenancyId).isEqualTo("tenant-b");
    }

    @Test
    void correlationKeyIsolation() {
        var ctx1 = SituationContext.initial("sit-1", "machine-1", "tenant-a", T1);
        var ctx2 = SituationContext.initial("sit-1", "machine-2", "tenant-a", T1);
        store.save(ctx1).await().indefinitely();
        store.save(ctx2).await().indefinitely();

        assertThat(store.find("sit-1", "machine-1", "tenant-a").await().indefinitely())
                .isPresent().get().extracting(SituationContext::correlationKey).isEqualTo("machine-1");
        assertThat(store.find("sit-1", "machine-2", "tenant-a").await().indefinitely())
                .isPresent().get().extracting(SituationContext::correlationKey).isEqualTo("machine-2");
    }

    @Test
    void removeExpiredIsCrossTenant() {
        var ctxA = SituationContext.initial("sit-1", "key-1", "tenant-a", T1);
        var ctxB = SituationContext.initial("sit-2", "key-1", "tenant-b", T1);
        store.save(ctxA).await().indefinitely();
        store.save(ctxB).await().indefinitely();

        store.removeExpired(T2).await().indefinitely();

        assertThat(store.find("sit-1", "key-1", "tenant-a").await().indefinitely()).isEmpty();
        assertThat(store.find("sit-2", "key-1", "tenant-b").await().indefinitely()).isEmpty();
    }
}
```

- [ ] **Step 9: Run tests**

Run: `/opt/homebrew/bin/mvn --batch-mode -pl persistence-jpa -am test`
Expected: All 10 tests pass. Devservices auto-starts PostgreSQL. Flyway runs the migration.

If the build fails because `quarkus-maven-plugin` version property doesn't match, check the parent pom for the correct property name (`version.quarkus.platform` or `quarkus.platform.version`) and adjust the pom.xml accordingly.

- [ ] **Step 10: Run full project build**

Run: `/opt/homebrew/bin/mvn --batch-mode install`
Expected: All modules build. All tests pass across the entire project.

- [ ] **Step 11: Commit**

```bash
git add persistence-jpa/ pom.xml
git commit -m "feat(casehub-ras#14): JPA SituationStore — persistent situation storage

New persistence-jpa/ module with SituationEntity (JSONB detections via
@JdbcTypeCode), JpaSituationStore (@ApplicationScoped, blocking JPA
wrapped in Uni), Flyway migration at db/ras/migration/. Ten @QuarkusTest
tests with Devservices PostgreSQL covering round-trip, upsert, isolation,
expiry, and JSON serialization.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"
```
