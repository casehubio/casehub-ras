# io.casehub.ras.api.SituationStore

**Package:** `io.casehub.ras.api`

**Kind:** `interface`

## Methods

### `public abstract java.util.Optional<io.casehub.ras.api.SituationContext> find(java.lang.String situationId, java.lang.String correlationKey, java.lang.String tenancyId)`

#### Parameters

- `situationId` (`java.lang.String`)
- `correlationKey` (`java.lang.String`)
- `tenancyId` (`java.lang.String`)

### `public default java.util.List<io.casehub.ras.api.SituationContext> findActive(java.lang.String tenancyId)`

#### Parameters

- `tenancyId` (`java.lang.String`)

### `public abstract void remove(java.lang.String situationId, java.lang.String correlationKey, java.lang.String tenancyId)`

#### Parameters

- `situationId` (`java.lang.String`)
- `correlationKey` (`java.lang.String`)
- `tenancyId` (`java.lang.String`)

### `public abstract void removeAllForSituation(java.lang.String situationId)`

#### Parameters

- `situationId` (`java.lang.String`)

### `public abstract int removeExpired(java.time.Instant cutoff)`

#### Parameters

- `cutoff` (`java.time.Instant`)

### `public default int removeTriggeredBefore(java.time.Instant triggerCutoff)`

#### Parameters

- `triggerCutoff` (`java.time.Instant`)

### `public default void resetTriggerClaim(java.lang.String situationId, java.lang.String correlationKey, java.lang.String tenancyId)`

#### Parameters

- `situationId` (`java.lang.String`)
- `correlationKey` (`java.lang.String`)
- `tenancyId` (`java.lang.String`)

### `public abstract io.casehub.ras.api.SituationContext save(io.casehub.ras.api.SituationContext context)`

#### Parameters

- `context` (`io.casehub.ras.api.SituationContext`)

### `public default boolean tryClaimTrigger(java.lang.String situationId, java.lang.String correlationKey, java.lang.String tenancyId, java.time.Instant triggerTime)`

#### Parameters

- `situationId` (`java.lang.String`)
- `correlationKey` (`java.lang.String`)
- `tenancyId` (`java.lang.String`)
- `triggerTime` (`java.time.Instant`)
