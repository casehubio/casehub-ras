# io.casehub.ras.api.SituationContext

**Package:** `io.casehub.ras.api`

**Kind:** `record`

## Fields

### `correlationKey` (`java.lang.String`)

### `detections` (`java.util.List<io.casehub.ras.api.TimestampedDetection>`)

### `firstSignal` (`java.time.Instant`)

### `lastSignal` (`java.time.Instant`)

### `lastTriggered` (`java.time.Instant`)

### `situationId` (`java.lang.String`)

### `storeVersion` (`java.util.OptionalLong`)

### `tenancyId` (`java.lang.String`)

### `triggerCount` (`int`)

## Record Components

### `correlationKey` (`java.lang.String`)

### `detections` (`java.util.List<io.casehub.ras.api.TimestampedDetection>`)

### `firstSignal` (`java.time.Instant`)

### `lastSignal` (`java.time.Instant`)

### `lastTriggered` (`java.time.Instant`)

### `situationId` (`java.lang.String`)

### `storeVersion` (`java.util.OptionalLong`)

### `tenancyId` (`java.lang.String`)

### `triggerCount` (`int`)

## Constructors

### `public SituationContext(java.lang.String situationId, java.lang.String correlationKey, java.lang.String tenancyId, java.time.Instant firstSignal, java.time.Instant lastSignal, java.util.List<io.casehub.ras.api.TimestampedDetection> detections, java.util.OptionalLong storeVersion, java.time.Instant lastTriggered, int triggerCount)`

#### Parameters

- `situationId` (`java.lang.String`)
- `correlationKey` (`java.lang.String`)
- `tenancyId` (`java.lang.String`)
- `firstSignal` (`java.time.Instant`)
- `lastSignal` (`java.time.Instant`)
- `detections` (`java.util.List<io.casehub.ras.api.TimestampedDetection>`)
- `storeVersion` (`java.util.OptionalLong`)
- `lastTriggered` (`java.time.Instant`)
- `triggerCount` (`int`)

## Methods

### `public java.lang.String correlationKey()`

### `public java.util.List<io.casehub.ras.api.TimestampedDetection> detections()`

### `public final boolean equals(java.lang.Object o)`

#### Parameters

- `o` (`java.lang.Object`)

### `public java.time.Instant firstSignal()`

### `public final int hashCode()`

### `public static io.casehub.ras.api.SituationContext initial(java.lang.String situationId, java.lang.String correlationKey, java.lang.String tenancyId, java.time.Instant eventTime)`

#### Parameters

- `situationId` (`java.lang.String`)
- `correlationKey` (`java.lang.String`)
- `tenancyId` (`java.lang.String`)
- `eventTime` (`java.time.Instant`)

### `public java.time.Instant lastSignal()`

### `public java.time.Instant lastTriggered()`

### `public java.lang.String situationId()`

### `public java.util.OptionalLong storeVersion()`

### `public java.lang.String tenancyId()`

### `public final java.lang.String toString()`

### `public int triggerCount()`

### `public io.casehub.ras.api.SituationContext withDetection(io.casehub.ras.api.DetectionResult result, java.time.Instant eventTime)`

#### Parameters

- `result` (`io.casehub.ras.api.DetectionResult`)
- `eventTime` (`java.time.Instant`)

### `public io.casehub.ras.api.SituationContext withStoreVersion(long version)`

#### Parameters

- `version` (`long`)
