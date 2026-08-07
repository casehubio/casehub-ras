# io.casehub.ras.api.ActiveSituation

**Package:** `io.casehub.ras.api`

**Kind:** `record`

## Fields

### `confidence` (`double`)

### `correlationKey` (`java.lang.String`)

### `evidence` (`java.util.Map<java.lang.String,java.lang.Object>`)

### `lastSignal` (`java.time.Instant`)

### `since` (`java.time.Instant`)

### `situationId` (`java.lang.String`)

### `tenancyId` (`java.lang.String`)

### `triggerCount` (`int`)

## Record Components

### `confidence` (`double`)

### `correlationKey` (`java.lang.String`)

### `evidence` (`java.util.Map<java.lang.String,java.lang.Object>`)

### `lastSignal` (`java.time.Instant`)

### `since` (`java.time.Instant`)

### `situationId` (`java.lang.String`)

### `tenancyId` (`java.lang.String`)

### `triggerCount` (`int`)

## Constructors

### `public ActiveSituation(java.lang.String situationId, java.lang.String correlationKey, java.lang.String tenancyId, double confidence, java.util.Map<java.lang.String,java.lang.Object> evidence, java.time.Instant since, java.time.Instant lastSignal, int triggerCount)`

#### Parameters

- `situationId` (`java.lang.String`)
- `correlationKey` (`java.lang.String`)
- `tenancyId` (`java.lang.String`)
- `confidence` (`double`)
- `evidence` (`java.util.Map<java.lang.String,java.lang.Object>`)
- `since` (`java.time.Instant`)
- `lastSignal` (`java.time.Instant`)
- `triggerCount` (`int`)

## Methods

### `public double confidence()`

### `public java.lang.String correlationKey()`

### `public final boolean equals(java.lang.Object o)`

#### Parameters

- `o` (`java.lang.Object`)

### `public java.util.Map<java.lang.String,java.lang.Object> evidence()`

### `public final int hashCode()`

### `public java.time.Instant lastSignal()`

### `public java.time.Instant since()`

### `public java.lang.String situationId()`

### `public java.lang.String tenancyId()`

### `public final java.lang.String toString()`

### `public int triggerCount()`
