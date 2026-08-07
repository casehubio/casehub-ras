# io.casehub.ras.api.GanglionDescriptor.NaiveBayes

**Package:** `io.casehub.ras.api`

**Kind:** `record`

## Fields

### `evidenceTemplates` (`java.util.Map<java.lang.String,ExpressionEvaluator>`)

### `features` (`java.util.Map<java.lang.String,io.casehub.ras.api.GanglionDescriptor.NaiveBayes.Feature>`)

### `ganglionId` (`java.lang.String`)

### `handledEventTypes` (`java.util.Set<java.lang.String>`)

### `outcomeEvidenceTemplates` (`java.util.Map<java.lang.String,java.util.Map<java.lang.String,ExpressionEvaluator>>`)

### `outcomes` (`java.util.List<java.lang.String>`)

### `priors` (`double[]`)

### `signalMapping` (`io.casehub.ras.api.GanglionDescriptor.NaiveBayes.SignalMapping`)

## Record Components

### `evidenceTemplates` (`java.util.Map<java.lang.String,ExpressionEvaluator>`)

### `features` (`java.util.Map<java.lang.String,io.casehub.ras.api.GanglionDescriptor.NaiveBayes.Feature>`)

### `ganglionId` (`java.lang.String`)

### `handledEventTypes` (`java.util.Set<java.lang.String>`)

### `outcomeEvidenceTemplates` (`java.util.Map<java.lang.String,java.util.Map<java.lang.String,ExpressionEvaluator>>`)

### `outcomes` (`java.util.List<java.lang.String>`)

### `priors` (`double[]`)

### `signalMapping` (`io.casehub.ras.api.GanglionDescriptor.NaiveBayes.SignalMapping`)

## Constructors

### `public NaiveBayes(java.lang.String ganglionId, java.util.Set<java.lang.String> handledEventTypes, java.util.List<java.lang.String> outcomes, double[] priors, java.util.Map<java.lang.String,io.casehub.ras.api.GanglionDescriptor.NaiveBayes.Feature> features, io.casehub.ras.api.GanglionDescriptor.NaiveBayes.SignalMapping signalMapping, java.util.Map<java.lang.String,ExpressionEvaluator> evidenceTemplates, java.util.Map<java.lang.String,java.util.Map<java.lang.String,ExpressionEvaluator>> outcomeEvidenceTemplates)`

#### Parameters

- `ganglionId` (`java.lang.String`)
- `handledEventTypes` (`java.util.Set<java.lang.String>`)
- `outcomes` (`java.util.List<java.lang.String>`)
- `priors` (`double[]`)
- `features` (`java.util.Map<java.lang.String,io.casehub.ras.api.GanglionDescriptor.NaiveBayes.Feature>`)
- `signalMapping` (`io.casehub.ras.api.GanglionDescriptor.NaiveBayes.SignalMapping`)
- `evidenceTemplates` (`java.util.Map<java.lang.String,ExpressionEvaluator>`)
- `outcomeEvidenceTemplates` (`java.util.Map<java.lang.String,java.util.Map<java.lang.String,ExpressionEvaluator>>`)

## Methods

### `public final boolean equals(java.lang.Object o)`

#### Parameters

- `o` (`java.lang.Object`)

### `public java.util.Map<java.lang.String,ExpressionEvaluator> evidenceTemplates()`

### `public java.util.Map<java.lang.String,io.casehub.ras.api.GanglionDescriptor.NaiveBayes.Feature> features()`

### `public java.lang.String ganglionId()`

### `public java.util.Set<java.lang.String> handledEventTypes()`

### `public final int hashCode()`

### `public java.util.Map<java.lang.String,java.util.Map<java.lang.String,ExpressionEvaluator>> outcomeEvidenceTemplates()`

### `public java.util.List<java.lang.String> outcomes()`

### `public double[] priors()`

### `public io.casehub.ras.api.GanglionDescriptor.NaiveBayes.SignalMapping signalMapping()`

### `public final java.lang.String toString()`
