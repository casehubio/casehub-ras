# io.casehub.ras.api.Ganglion

**Package:** `io.casehub.ras.api`

**Kind:** `interface`

## Methods

### `public default void close(java.lang.String situationId, java.lang.String correlationKey, java.lang.String tenancyId)`

#### Parameters

- `situationId` (`java.lang.String`)
- `correlationKey` (`java.lang.String`)
- `tenancyId` (`java.lang.String`)

### `public default io.casehub.ras.api.SituationContext compact(io.casehub.ras.api.SituationContext context)`

#### Parameters

- `context` (`io.casehub.ras.api.SituationContext`)

### `public abstract io.casehub.ras.api.DetectionResult detect(CloudEvent event, io.casehub.ras.api.SituationContext context)`

Detect a signal from the given event in the context of an accumulating situation.

<p><b>Design invariant — DetectionResult portability:</b> The returned result may be
applied to a different `SituationContext` than the one passed to this method
(e.g. after a concurrent-modification retry). Implementations must not base detection
decisions on `context.detections()` or other accumulated state, as these may
differ between detection time and application time.

#### Parameters

- `event` (`CloudEvent`)
- `context` (`io.casehub.ras.api.SituationContext`)

### `public abstract java.lang.String ganglionId()`

### `public abstract java.util.Set<java.lang.String> handledEventTypes()`
