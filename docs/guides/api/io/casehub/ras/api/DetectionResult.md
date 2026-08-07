# io.casehub.ras.api.DetectionResult

**Package:** `io.casehub.ras.api`

**Kind:** `record`

## Fields

### `confidence` (`double`)

### `evidence` (`java.util.Map<java.lang.String,java.lang.Object>`)

### `ganglionId` (`java.lang.String`)

### `signal` (`io.casehub.ras.api.DetectionSignal`)

## Record Components

### `confidence` (`double`)

### `evidence` (`java.util.Map<java.lang.String,java.lang.Object>`)

### `ganglionId` (`java.lang.String`)

### `signal` (`io.casehub.ras.api.DetectionSignal`)

## Constructors

### `public DetectionResult(java.lang.String ganglionId, double confidence, io.casehub.ras.api.DetectionSignal signal, java.util.Map<java.lang.String,java.lang.Object> evidence)`

#### Parameters

- `ganglionId` (`java.lang.String`)
- `confidence` (`double`)
- `signal` (`io.casehub.ras.api.DetectionSignal`)
- `evidence` (`java.util.Map<java.lang.String,java.lang.Object>`)

## Methods

### `public double confidence()`

### `public final boolean equals(java.lang.Object o)`

#### Parameters

- `o` (`java.lang.Object`)

### `public java.util.Map<java.lang.String,java.lang.Object> evidence()`

### `public java.lang.String ganglionId()`

### `public final int hashCode()`

### `public io.casehub.ras.api.DetectionSignal signal()`

### `public final java.lang.String toString()`
