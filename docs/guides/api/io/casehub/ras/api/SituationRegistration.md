# io.casehub.ras.api.SituationRegistration

**Package:** `io.casehub.ras.api`

**Kind:** `record`

## Fields

### `compiledDynamicData` (`java.util.Map<java.lang.String,CompiledExpression<java.util.Map,java.lang.Object>>`)

### `correlationKeyExtractor` (`io.casehub.ras.api.CorrelationKeyExtractor`)

### `definition` (`io.casehub.ras.api.SituationDefinition`)

### `eventFilter` (`io.casehub.ras.api.EventFilter`)

## Record Components

### `compiledDynamicData` (`java.util.Map<java.lang.String,CompiledExpression<java.util.Map,java.lang.Object>>`)

### `correlationKeyExtractor` (`io.casehub.ras.api.CorrelationKeyExtractor`)

### `definition` (`io.casehub.ras.api.SituationDefinition`)

### `eventFilter` (`io.casehub.ras.api.EventFilter`)

## Constructors

### `public SituationRegistration(io.casehub.ras.api.SituationDefinition definition)`

#### Parameters

- `definition` (`io.casehub.ras.api.SituationDefinition`)

### `public SituationRegistration(io.casehub.ras.api.SituationDefinition definition, io.casehub.ras.api.CorrelationKeyExtractor correlationKeyExtractor)`

#### Parameters

- `definition` (`io.casehub.ras.api.SituationDefinition`)
- `correlationKeyExtractor` (`io.casehub.ras.api.CorrelationKeyExtractor`)

### `public SituationRegistration(io.casehub.ras.api.SituationDefinition definition, io.casehub.ras.api.CorrelationKeyExtractor correlationKeyExtractor, io.casehub.ras.api.EventFilter eventFilter, java.util.Map<java.lang.String,CompiledExpression<java.util.Map,java.lang.Object>> compiledDynamicData)`

#### Parameters

- `definition` (`io.casehub.ras.api.SituationDefinition`)
- `correlationKeyExtractor` (`io.casehub.ras.api.CorrelationKeyExtractor`)
- `eventFilter` (`io.casehub.ras.api.EventFilter`)
- `compiledDynamicData` (`java.util.Map<java.lang.String,CompiledExpression<java.util.Map,java.lang.Object>>`)

## Methods

### `public java.util.Map<java.lang.String,CompiledExpression<java.util.Map,java.lang.Object>> compiledDynamicData()`

### `public io.casehub.ras.api.CorrelationKeyExtractor correlationKeyExtractor()`

### `public io.casehub.ras.api.SituationDefinition definition()`

### `public final boolean equals(java.lang.Object o)`

#### Parameters

- `o` (`java.lang.Object`)

### `public io.casehub.ras.api.EventFilter eventFilter()`

### `public final int hashCode()`

### `public final java.lang.String toString()`
