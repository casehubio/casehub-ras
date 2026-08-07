# io.casehub.ras.api.JavaSwitchGanglion

**Package:** `io.casehub.ras.api`

**Kind:** `class`

## Fields

### `ganglionId` (`java.lang.String`)

### `handledEventTypes` (`java.util.Set<java.lang.String>`)

## Constructors

### `protected JavaSwitchGanglion(java.lang.String ganglionId, java.util.Set<java.lang.String> handledEventTypes)`

#### Parameters

- `ganglionId` (`java.lang.String`)
- `handledEventTypes` (`java.util.Set<java.lang.String>`)

## Methods

### `protected io.casehub.ras.api.DetectionResult anti(double confidence)`

#### Parameters

- `confidence` (`double`)

### `protected io.casehub.ras.api.DetectionResult anti(double confidence, java.util.Map<java.lang.String,java.lang.Object> evidence)`

#### Parameters

- `confidence` (`double`)
- `evidence` (`java.util.Map<java.lang.String,java.lang.Object>`)

### `public final io.casehub.ras.api.DetectionResult detect(CloudEvent event, io.casehub.ras.api.SituationContext context)`

#### Parameters

- `event` (`CloudEvent`)
- `context` (`io.casehub.ras.api.SituationContext`)

### `protected io.casehub.ras.api.DetectionResult detected(double confidence)`

#### Parameters

- `confidence` (`double`)

### `protected io.casehub.ras.api.DetectionResult detected(double confidence, java.util.Map<java.lang.String,java.lang.Object> evidence)`

#### Parameters

- `confidence` (`double`)
- `evidence` (`java.util.Map<java.lang.String,java.lang.Object>`)

### `protected abstract io.casehub.ras.api.DetectionResult evaluate(CloudEvent event, io.casehub.ras.api.SituationContext context)`

#### Parameters

- `event` (`CloudEvent`)
- `context` (`io.casehub.ras.api.SituationContext`)

### `public final java.lang.String ganglionId()`

### `public final java.util.Set<java.lang.String> handledEventTypes()`

### `protected io.casehub.ras.api.DetectionResult noise()`

### `protected io.casehub.ras.api.DetectionResult weak(double confidence)`

#### Parameters

- `confidence` (`double`)

### `protected io.casehub.ras.api.DetectionResult weak(double confidence, java.util.Map<java.lang.String,java.lang.Object> evidence)`

#### Parameters

- `confidence` (`double`)
- `evidence` (`java.util.Map<java.lang.String,java.lang.Object>`)
