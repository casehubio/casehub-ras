# io.casehub.ras.api.GanglionDescriptor.ExpressionRules

**Package:** `io.casehub.ras.api`

**Kind:** `record`

## Fields

### `evidenceTemplates` (`java.util.Map<java.lang.String,ExpressionEvaluator>`)

### `ganglionId` (`java.lang.String`)

### `handledEventTypes` (`java.util.Set<java.lang.String>`)

### `rules` (`java.util.List<io.casehub.ras.api.GanglionDescriptor.ExpressionRules.Rule>`)

## Record Components

### `evidenceTemplates` (`java.util.Map<java.lang.String,ExpressionEvaluator>`)

### `ganglionId` (`java.lang.String`)

### `handledEventTypes` (`java.util.Set<java.lang.String>`)

### `rules` (`java.util.List<io.casehub.ras.api.GanglionDescriptor.ExpressionRules.Rule>`)

## Constructors

### `public ExpressionRules(java.lang.String ganglionId, java.util.Set<java.lang.String> handledEventTypes, java.util.List<io.casehub.ras.api.GanglionDescriptor.ExpressionRules.Rule> rules, java.util.Map<java.lang.String,ExpressionEvaluator> evidenceTemplates)`

#### Parameters

- `ganglionId` (`java.lang.String`)
- `handledEventTypes` (`java.util.Set<java.lang.String>`)
- `rules` (`java.util.List<io.casehub.ras.api.GanglionDescriptor.ExpressionRules.Rule>`)
- `evidenceTemplates` (`java.util.Map<java.lang.String,ExpressionEvaluator>`)

## Methods

### `public final boolean equals(java.lang.Object o)`

#### Parameters

- `o` (`java.lang.Object`)

### `public java.util.Map<java.lang.String,ExpressionEvaluator> evidenceTemplates()`

### `public java.lang.String ganglionId()`

### `public java.util.Set<java.lang.String> handledEventTypes()`

### `public final int hashCode()`

### `public java.util.List<io.casehub.ras.api.GanglionDescriptor.ExpressionRules.Rule> rules()`

### `public final java.lang.String toString()`
