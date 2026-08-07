# io.casehub.ras.api.SituationChangeEvent

**Package:** `io.casehub.ras.api`

**Kind:** `record`

## Fields

### `changeType` (`io.casehub.ras.api.SituationChangeEvent.ChangeType`)

### `context` (`io.casehub.ras.api.SituationContext`)

### `correlationKey` (`java.lang.String`)

### `metadata` (`java.util.Map<java.lang.String,java.lang.Object>`)

### `situationId` (`java.lang.String`)

### `tenancyId` (`java.lang.String`)

## Record Components

### `changeType` (`io.casehub.ras.api.SituationChangeEvent.ChangeType`)

### `context` (`io.casehub.ras.api.SituationContext`)

### `correlationKey` (`java.lang.String`)

### `metadata` (`java.util.Map<java.lang.String,java.lang.Object>`)

### `situationId` (`java.lang.String`)

### `tenancyId` (`java.lang.String`)

## Constructors

### `public SituationChangeEvent(java.lang.String tenancyId, java.lang.String situationId, java.lang.String correlationKey, io.casehub.ras.api.SituationChangeEvent.ChangeType changeType, io.casehub.ras.api.SituationContext context)`

#### Parameters

- `tenancyId` (`java.lang.String`)
- `situationId` (`java.lang.String`)
- `correlationKey` (`java.lang.String`)
- `changeType` (`io.casehub.ras.api.SituationChangeEvent.ChangeType`)
- `context` (`io.casehub.ras.api.SituationContext`)

### `public SituationChangeEvent(java.lang.String tenancyId, java.lang.String situationId, java.lang.String correlationKey, io.casehub.ras.api.SituationChangeEvent.ChangeType changeType, io.casehub.ras.api.SituationContext context, java.util.Map<java.lang.String,java.lang.Object> metadata)`

#### Parameters

- `tenancyId` (`java.lang.String`)
- `situationId` (`java.lang.String`)
- `correlationKey` (`java.lang.String`)
- `changeType` (`io.casehub.ras.api.SituationChangeEvent.ChangeType`)
- `context` (`io.casehub.ras.api.SituationContext`)
- `metadata` (`java.util.Map<java.lang.String,java.lang.Object>`)

## Methods

### `public io.casehub.ras.api.SituationChangeEvent.ChangeType changeType()`

### `public io.casehub.ras.api.SituationContext context()`

### `public java.lang.String correlationKey()`

### `public final boolean equals(java.lang.Object o)`

#### Parameters

- `o` (`java.lang.Object`)

### `public final int hashCode()`

### `public java.util.Map<java.lang.String,java.lang.Object> metadata()`

### `public java.lang.String situationId()`

### `public java.lang.String tenancyId()`

### `public final java.lang.String toString()`
