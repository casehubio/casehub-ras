# io.casehub.ras.api.TenantHealth

**Package:** `io.casehub.ras.api`

**Kind:** `record`

## Fields

### `situations` (`java.util.List<io.casehub.ras.api.SituationSummary>`)

### `tenancyId` (`java.lang.String`)

### `totalEvents` (`long`)

### `windowEnd` (`java.time.Instant`)

### `windowStart` (`java.time.Instant`)

## Record Components

### `situations` (`java.util.List<io.casehub.ras.api.SituationSummary>`)

### `tenancyId` (`java.lang.String`)

### `totalEvents` (`long`)

### `windowEnd` (`java.time.Instant`)

### `windowStart` (`java.time.Instant`)

## Constructors

### `public TenantHealth(java.lang.String tenancyId, java.time.Instant windowStart, java.time.Instant windowEnd, long totalEvents, java.util.List<io.casehub.ras.api.SituationSummary> situations)`

#### Parameters

- `tenancyId` (`java.lang.String`)
- `windowStart` (`java.time.Instant`)
- `windowEnd` (`java.time.Instant`)
- `totalEvents` (`long`)
- `situations` (`java.util.List<io.casehub.ras.api.SituationSummary>`)

## Methods

### `public final boolean equals(java.lang.Object o)`

#### Parameters

- `o` (`java.lang.Object`)

### `public final int hashCode()`

### `public java.util.List<io.casehub.ras.api.SituationSummary> situations()`

### `public java.lang.String tenancyId()`

### `public final java.lang.String toString()`

### `public long totalEvents()`

### `public java.time.Instant windowEnd()`

### `public java.time.Instant windowStart()`
