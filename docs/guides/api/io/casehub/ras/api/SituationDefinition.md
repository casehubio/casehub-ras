# io.casehub.ras.api.SituationDefinition

**Package:** `io.casehub.ras.api`

**Kind:** `record`

## Fields

### `chainMode` (`io.casehub.ras.api.ChainMode`)

### `correlationKeyExpression` (`ExpressionEvaluator`)

### `correlationWindow` (`java.time.Duration`)

### `dynamicCaseData` (`java.util.Map<java.lang.String,ExpressionEvaluator>`)

### `eventBufferDelay` (`java.time.Duration`)

### `eventFilter` (`ExpressionEvaluator`)

### `eventTypes` (`java.util.Set<java.lang.String>`)

### `situationId` (`java.lang.String`)

### `triggerAction` (`io.casehub.ras.api.TriggerAction`)

### `triggerMode` (`io.casehub.ras.api.TriggerMode`)

## Record Components

### `chainMode` (`io.casehub.ras.api.ChainMode`)

### `correlationKeyExpression` (`ExpressionEvaluator`)

### `correlationWindow` (`java.time.Duration`)

### `dynamicCaseData` (`java.util.Map<java.lang.String,ExpressionEvaluator>`)

### `eventBufferDelay` (`java.time.Duration`)

### `eventFilter` (`ExpressionEvaluator`)

### `eventTypes` (`java.util.Set<java.lang.String>`)

### `situationId` (`java.lang.String`)

### `triggerAction` (`io.casehub.ras.api.TriggerAction`)

### `triggerMode` (`io.casehub.ras.api.TriggerMode`)

## Constructors

### `public SituationDefinition(java.lang.String situationId, java.util.Set<java.lang.String> eventTypes, java.time.Duration correlationWindow, java.time.Duration eventBufferDelay, io.casehub.ras.api.ChainMode chainMode, io.casehub.ras.api.TriggerAction triggerAction, io.casehub.ras.api.TriggerMode triggerMode)`

#### Parameters

- `situationId` (`java.lang.String`)
- `eventTypes` (`java.util.Set<java.lang.String>`)
- `correlationWindow` (`java.time.Duration`)
- `eventBufferDelay` (`java.time.Duration`)
- `chainMode` (`io.casehub.ras.api.ChainMode`)
- `triggerAction` (`io.casehub.ras.api.TriggerAction`)
- `triggerMode` (`io.casehub.ras.api.TriggerMode`)

### `public SituationDefinition(java.lang.String situationId, java.util.Set<java.lang.String> eventTypes, java.time.Duration correlationWindow, java.time.Duration eventBufferDelay, io.casehub.ras.api.ChainMode chainMode, io.casehub.ras.api.TriggerAction triggerAction, io.casehub.ras.api.TriggerMode triggerMode, ExpressionEvaluator correlationKeyExpression, ExpressionEvaluator eventFilter, java.util.Map<java.lang.String,ExpressionEvaluator> dynamicCaseData)`

#### Parameters

- `situationId` (`java.lang.String`)
- `eventTypes` (`java.util.Set<java.lang.String>`)
- `correlationWindow` (`java.time.Duration`)
- `eventBufferDelay` (`java.time.Duration`)
- `chainMode` (`io.casehub.ras.api.ChainMode`)
- `triggerAction` (`io.casehub.ras.api.TriggerAction`)
- `triggerMode` (`io.casehub.ras.api.TriggerMode`)
- `correlationKeyExpression` (`ExpressionEvaluator`)
- `eventFilter` (`ExpressionEvaluator`)
- `dynamicCaseData` (`java.util.Map<java.lang.String,ExpressionEvaluator>`)

## Methods

### `public io.casehub.ras.api.ChainMode chainMode()`

### `public ExpressionEvaluator correlationKeyExpression()`

### `public java.time.Duration correlationWindow()`

### `public java.util.Map<java.lang.String,ExpressionEvaluator> dynamicCaseData()`

### `public final boolean equals(java.lang.Object o)`

#### Parameters

- `o` (`java.lang.Object`)

### `public java.time.Duration eventBufferDelay()`

### `public ExpressionEvaluator eventFilter()`

### `public java.util.Set<java.lang.String> eventTypes()`

### `public final int hashCode()`

### `public java.lang.String situationId()`

### `public final java.lang.String toString()`

### `public io.casehub.ras.api.TriggerAction triggerAction()`

### `public io.casehub.ras.api.TriggerMode triggerMode()`
