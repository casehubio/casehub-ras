# io.casehub.ras.api.SituationEvent

**Package:** `io.casehub.ras.api`

**Kind:** `record`

## Fields

### `changeType` (`io.casehub.ras.api.SituationChangeEvent.ChangeType`)

### `confidence` (`double`)

### `correlationKey` (`java.lang.String`)

### `detectionCount` (`int`)

### `eventTime` (`java.time.Instant`)

### `evidence` (`java.util.Map<java.lang.String,java.lang.Object>`)

### `firstSeen` (`java.time.Instant`)

### `metadata` (`java.util.Map<java.lang.String,java.lang.Object>`)

### `situationId` (`java.lang.String`)

### `tenancyId` (`java.lang.String`)

### `triggerCount` (`int`)

## Record Components

### `changeType` (`io.casehub.ras.api.SituationChangeEvent.ChangeType`)

### `confidence` (`double`)

### `correlationKey` (`java.lang.String`)

### `detectionCount` (`int`)

### `eventTime` (`java.time.Instant`)

### `evidence` (`java.util.Map<java.lang.String,java.lang.Object>`)

### `firstSeen` (`java.time.Instant`)

### `metadata` (`java.util.Map<java.lang.String,java.lang.Object>`)

### `situationId` (`java.lang.String`)

### `tenancyId` (`java.lang.String`)

### `triggerCount` (`int`)

## Constructors

### `public SituationEvent(java.lang.String situationId, java.lang.String correlationKey, java.lang.String tenancyId, io.casehub.ras.api.SituationChangeEvent.ChangeType changeType, java.time.Instant eventTime, java.time.Instant firstSeen, double confidence, int detectionCount, int triggerCount, java.util.Map<java.lang.String,java.lang.Object> evidence, java.util.Map<java.lang.String,java.lang.Object> metadata)`

#### Parameters

- `situationId` (`java.lang.String`)
- `correlationKey` (`java.lang.String`)
- `tenancyId` (`java.lang.String`)
- `changeType` (`io.casehub.ras.api.SituationChangeEvent.ChangeType`)
- `eventTime` (`java.time.Instant`)
- `firstSeen` (`java.time.Instant`)
- `confidence` (`double`)
- `detectionCount` (`int`)
- `triggerCount` (`int`)
- `evidence` (`java.util.Map<java.lang.String,java.lang.Object>`)
- `metadata` (`java.util.Map<java.lang.String,java.lang.Object>`)

## Methods

### `public io.casehub.ras.api.SituationChangeEvent.ChangeType changeType()`

### `public double confidence()`

### `public java.lang.String correlationKey()`

### `public int detectionCount()`

### `public final boolean equals(java.lang.Object o)`

#### Parameters

- `o` (`java.lang.Object`)

### `public java.time.Instant eventTime()`

### `public java.util.Map<java.lang.String,java.lang.Object> evidence()`

### `public java.time.Instant firstSeen()`

### `public static io.casehub.ras.api.SituationEvent from(io.casehub.ras.api.SituationChangeEvent changeEvent, java.time.Instant eventTime)`

#### Parameters

- `changeEvent` (`io.casehub.ras.api.SituationChangeEvent`)
- `eventTime` (`java.time.Instant`)

### `public final int hashCode()`

### `public java.util.Map<java.lang.String,java.lang.Object> metadata()`

### `public java.lang.String situationId()`

### `public java.lang.String tenancyId()`

### `public final java.lang.String toString()`

### `public int triggerCount()`
