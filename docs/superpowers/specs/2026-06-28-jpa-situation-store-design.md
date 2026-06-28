# JPA SituationStore — Design Spec

**Issue:** casehubio/casehub-ras#14
**Date:** 2026-06-28
**Depends on:** #2 (RAS Runtime — done)
**Prior spec:** `2026-06-25-epic2-ras-runtime-design.md` §10
**Protocols:** `persistence-backend-cdi-priority.md`, `reactive-vs-blocking-selection.md`
**Platform references:** `platform/persistence-jpa` (Tier 2 JPA pattern), `platform/memory-jpa` (JPA + JSONB pattern)

## Problem

`InMemorySituationStore` loses all situation state on restart. Production deployments need
durable persistence for situations that accumulate events across the correlation window.

## Design

### 1. New module: persistence-jpa/

`persistence-jpa/` artifact `casehub-ras-jpa`, package `io.casehub.ras.persistence.jpa`.
Follows the `persistence-memory/` and `platform/persistence-jpa` patterns — a Jandex
library jar with classpath activation.

**Package alignment:** `persistence-memory/` currently uses `io.casehub.ras.memory`. For
consistent sibling naming, rename to `io.casehub.ras.persistence.memory` — both modules
share `io.casehub.ras.persistence.*`. Breaking change; callers update their imports.

**CDI tier: plain `@ApplicationScoped`** (Tier 2 — primary backend per
`persistence-backend-cdi-priority` protocol). No `@Alternative`, no `@DefaultBean`. Adding
the module to the classpath activates it; removing falls back to in-memory.

**Dependencies:**
- `casehub-ras-api` (compile)
- `io.quarkus:quarkus-hibernate-orm` (compile) — blocking JPA
- `io.quarkus:quarkus-flyway` (compile) — migration discovery
- `io.quarkus:quarkus-jackson` (compile) — CDI-injectable ObjectMapper with JavaTimeModule
- `io.quarkus:quarkus-jdbc-postgresql` (optional) — driver; consuming app controls version
- `io.smallrye.reactive:mutiny` (provided) — SPI return types
- `jakarta.inject:jakarta.inject-api` (provided)
- `jakarta.enterprise:jakarta.enterprise.cdi-api` (provided)
- `jakarta.transaction:jakarta.transaction-api` (provided)

**Build plugins:**
- `quarkus-maven-plugin` with `build`, `generate-code`, `generate-code-tests` goals —
  required for Quarkus @Entity augmentation at build time. `jandex-maven-plugin` alone is
  insufficient for JPA integration.
- `jandex-maven-plugin` — CDI bean discovery

**Module description (pom.xml):** "Consumers must add `classpath:db/ras/migration` to
`quarkus.flyway.locations`." — matching the platform/persistence-jpa convention.

No dependency on `casehub-ras` (runtime), `casehub-engine-api`, or any transport library.

### 2. Fix InMemorySituationStore CDI priority

`InMemorySituationStore` is currently `@Alternative @Priority(1)` — the Tier 3 slot
(secondary/NoSQL backend) per the `persistence-backend-cdi-priority` protocol. In-memory
is Tier 4 and should be `@Alternative @Priority(100)`.

Reasons for the change:
- **Protocol compliance** — in-memory is ephemeral (Tier 4), not a secondary backend
  (Tier 3). `@Priority(1)` occupies the slot reserved for a hypothetical NoSQL
  implementation.
- **Collision avoidance** — if a NoSQL secondary (also `@Priority(1)`) were ever added,
  two `@Priority(1)` alternatives on the same SPI produce an ambiguous dependency at
  startup.

Note: this change does NOT affect whether in-memory beats JPA. `@Alternative @Priority(N)`
beats `@ApplicationScoped` regardless of N. The durability protection is: do not co-deploy
the in-memory module with JPA in production.

One-line change: `@Priority(1)` → `@Priority(100)` in `persistence-memory/`.

### 3. SituationEntity

Single JPA entity, surrogate UUID primary key, unique constraint on the natural key.
Private fields with getters/setters (non-Panache pattern).

```java
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
}
```

`detections` is a JSON string column (`JSONB` in PostgreSQL). `@JdbcTypeCode(SqlTypes.JSON)`
tells Hibernate to bind the String via PGobject (JSON type) instead of `setString()`
(VARCHAR type). Without it, PostgreSQL rejects the parameter: `column "detections" is of
type jsonb but expression is of type character varying`. This matches the casehub-engine
`EventLogEntity` pattern. `columnDefinition = "jsonb"` only controls DDL generation.

Jackson serializes `List<TimestampedDetection>` to/from this column. The Quarkus-managed
`ObjectMapper` (from `quarkus-jackson`) includes `JavaTimeModule` for `Instant` support.
`Map<String, Object>` in `DetectionResult.evidence` uses Jackson's natural JSON types.

### 4. JpaSituationStore

`@ApplicationScoped` CDI bean implementing `SituationStore`. Method-level `@Transactional`
with explicit `TxType` — reads don't pay transaction overhead.

**Blocking JPA wrapped in Uni (eager pattern).** The SPI returns `Uni<...>` but
`SituationEvaluator` calls `.await().indefinitely()` — blocking execution. Per the
reactive-vs-blocking protocol, blocking execution uses standard JPA/JDBC. JPA operations
execute during the method call; the computed result wraps in `Uni.createFrom().item(...)`
for SPI compliance. This matches `InMemorySituationStore`'s eager pattern.

```java
@ApplicationScoped
public class JpaSituationStore implements SituationStore {
    @Inject EntityManager em;
    @Inject ObjectMapper objectMapper;
}
```

**Operations:**

- **find(situationId, correlationKey, tenancyId):**
  `@Transactional(TxType.SUPPORTS)`. JPQL query by natural key. Returns
  `Uni<Optional<SituationContext>>`. Converts entity → SituationContext via Jackson
  deserialization of the detections column. The entity returned by the query is managed;
  no detach needed for read-only use.

- **save(context):**
  `@Transactional(TxType.REQUIRED)`. JPQL SELECT by natural key. If the query returns
  a managed entity, update its fields in place (firstSignal, lastSignal, detections) —
  Hibernate auto-flushes the dirty entity at transaction commit, no `em.merge()` needed.
  If no entity found, create a new `SituationEntity` and `em.persist()` it.

- **remove(situationId, correlationKey, tenancyId):**
  `@Transactional(TxType.REQUIRED)`. JPQL bulk DELETE by natural key. Returns
  `Uni<Void>`.

- **removeExpired(cutoff):**
  `@Transactional(TxType.REQUIRED)`. JPQL bulk DELETE where `lastSignal < :cutoff`.
  Uses the `last_signal` index.

**Tenancy model:** SituationStore's SPI passes `tenancyId` as a method parameter — tenant
isolation is at the application level via the composite key. No CurrentPrincipal, no RLS.
The SPI was designed for the CDI event integration tier where tenancyId comes from the
CloudEvent extension attribute.

### 5. Flyway migration

`V1__create_ras_situation.sql` shipped inside the module at
`persistence-jpa/src/main/resources/db/ras/migration/`.

Consumers must add `classpath:db/ras/migration` to `quarkus.flyway.locations`. The scoped
path (`db/ras/`) avoids collision with `db/migration/` (engine) and other module-scoped
migration directories (`db/platform/`, `db/memory/`, `db/work/`).

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

### 6. Mapper utility

Package-private `SituationMapper` class handles conversion between `SituationContext`
(domain record) and `SituationEntity` (JPA entity), including Jackson serialization of
the detections list. Single responsibility — no business logic.

### 7. Test infrastructure

`@QuarkusTest` with Quarkus Devservices. When `quarkus-jdbc-postgresql` is on the test
classpath and no datasource URL is configured, Quarkus auto-starts a PostgreSQL container
via Testcontainers. This is required because the `JSONB` column type is
PostgreSQL-specific (no H2 fallback).

Test dependencies:
- `io.quarkus:quarkus-jdbc-postgresql` (test) — triggers Devservices
- `io.quarkus:quarkus-junit5` (test)
- `org.assertj:assertj-core` (test)

Test `application.properties`:
```properties
quarkus.flyway.locations=classpath:db/ras/migration
quarkus.flyway.migrate-at-start=true
```

## Out of scope

- Hibernate Reactive / Panache — blocking execution model is correct for the current
  SituationEvaluator (see §4 rationale)
- PostgreSQL Row Level Security — tenancy is application-level via the composite key
- Batch upsert optimization — save() is called once per event per situation, not in bulk
- SituationEntity versioning / optimistic locking — the per-key lock in SituationEvaluator
  serializes all writes to the same situation key

## Test plan

1. save() then find() — round-trip: context saved, context retrieved with identical fields.
2. save() updates existing — save twice with different detections, find returns latest.
3. find() returns empty for unknown key — no exception.
4. remove() deletes by natural key — find returns empty after remove.
5. remove() non-existent key is no-op — no exception.
6. removeExpired() deletes entries with lastSignal before cutoff, keeps others.
7. Detections JSON round-trip — nested DetectionResult with evidence map survives
   serialization/deserialization.
8. Tenant isolation — same (situationId, correlationKey), different tenancyId → independent
   entries.
9. Correlation key isolation — same (situationId, tenancyId), different correlationKey →
   independent entries.
10. removeExpired is cross-tenant — expired entries swept regardless of tenancyId.
11. InMemorySituationStore @Priority(100) — verify CDI priority change.
12. InMemorySituationStore package rename — verify `io.casehub.ras.persistence.memory`.
