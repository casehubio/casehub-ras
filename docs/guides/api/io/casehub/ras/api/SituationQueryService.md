# io.casehub.ras.api.SituationQueryService

**Package:** `io.casehub.ras.api`

**Kind:** `interface`

## Methods

### `public abstract io.casehub.ras.api.TenantHealth health(java.lang.String tenancyId, java.time.Duration window, java.time.Instant asOf)`

#### Parameters

- `tenancyId` (`java.lang.String`)
- `window` (`java.time.Duration`)
- `asOf` (`java.time.Instant`)

### `public abstract java.util.List<io.casehub.ras.api.SituationEvent> history(java.lang.String tenancyId, java.lang.String situationId, java.lang.String correlationKey, java.time.Instant from, java.time.Instant to)`

#### Parameters

- `tenancyId` (`java.lang.String`)
- `situationId` (`java.lang.String`)
- `correlationKey` (`java.lang.String`)
- `from` (`java.time.Instant`)
- `to` (`java.time.Instant`)

### `public abstract java.util.List<io.casehub.ras.api.SituationEvent> history(java.lang.String tenancyId, java.lang.String situationId, java.time.Instant from, java.time.Instant to)`

#### Parameters

- `tenancyId` (`java.lang.String`)
- `situationId` (`java.lang.String`)
- `from` (`java.time.Instant`)
- `to` (`java.time.Instant`)

### `public abstract java.util.List<io.casehub.ras.api.SituationEvent> history(java.lang.String tenancyId, java.time.Instant from, java.time.Instant to)`

#### Parameters

- `tenancyId` (`java.lang.String`)
- `from` (`java.time.Instant`)
- `to` (`java.time.Instant`)

### `public abstract io.casehub.ras.api.TrendResult trend(java.lang.String tenancyId, java.lang.String situationId, java.time.Duration window, java.time.Duration baseline, java.time.Instant asOf)`

#### Parameters

- `tenancyId` (`java.lang.String`)
- `situationId` (`java.lang.String`)
- `window` (`java.time.Duration`)
- `baseline` (`java.time.Duration`)
- `asOf` (`java.time.Instant`)

### `public abstract long triggerCount(java.lang.String tenancyId, java.lang.String situationId, java.time.Instant from, java.time.Instant to)`

#### Parameters

- `tenancyId` (`java.lang.String`)
- `situationId` (`java.lang.String`)
- `from` (`java.time.Instant`)
- `to` (`java.time.Instant`)
