# io.casehub.ras.api.GanglionDescriptor.ExpressionRules.Rule

**Package:** `io.casehub.ras.api`

**Kind:** `record`

## Fields

### `confidence` (`double`)

### `confidenceExpression` (`ExpressionEvaluator`)

### `evidenceTemplates` (`java.util.Map<java.lang.String,ExpressionEvaluator>`)

### `signal` (`io.casehub.ras.api.DetectionSignal`)

### `when` (`ExpressionEvaluator`)

## Record Components

### `confidence` (`double`)

### `confidenceExpression` (`ExpressionEvaluator`)

### `evidenceTemplates` (`java.util.Map<java.lang.String,ExpressionEvaluator>`)

### `signal` (`io.casehub.ras.api.DetectionSignal`)

### `when` (`ExpressionEvaluator`)

## Constructors

### `public Rule(ExpressionEvaluator when, io.casehub.ras.api.DetectionSignal signal, double confidence, ExpressionEvaluator confidenceExpression, java.util.Map<java.lang.String,ExpressionEvaluator> evidenceTemplates)`

#### Parameters

- `when` (`ExpressionEvaluator`)
- `signal` (`io.casehub.ras.api.DetectionSignal`)
- `confidence` (`double`)
- `confidenceExpression` (`ExpressionEvaluator`)
- `evidenceTemplates` (`java.util.Map<java.lang.String,ExpressionEvaluator>`)

## Methods

### `public double confidence()`

### `public ExpressionEvaluator confidenceExpression()`

### `public final boolean equals(java.lang.Object o)`

#### Parameters

- `o` (`java.lang.Object`)

### `public java.util.Map<java.lang.String,ExpressionEvaluator> evidenceTemplates()`

### `public final int hashCode()`

### `public io.casehub.ras.api.DetectionSignal signal()`

### `public final java.lang.String toString()`

### `public ExpressionEvaluator when()`
