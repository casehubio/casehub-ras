# io.casehub.ras.api.TrendResult

**Package:** `io.casehub.ras.api`

**Kind:** `record`

## Fields

### `baselineCount` (`long`)

### `currentCount` (`long`)

### `direction` (`io.casehub.ras.api.TrendResult.TrendDirection`)

## Record Components

### `baselineCount` (`long`)

### `currentCount` (`long`)

### `direction` (`io.casehub.ras.api.TrendResult.TrendDirection`)

## Constructors

### `public TrendResult(long currentCount, long baselineCount, io.casehub.ras.api.TrendResult.TrendDirection direction)`

#### Parameters

- `currentCount` (`long`)
- `baselineCount` (`long`)
- `direction` (`io.casehub.ras.api.TrendResult.TrendDirection`)

## Methods

### `public long baselineCount()`

### `public static io.casehub.ras.api.TrendResult compute(long currentCount, long baselineCount, java.time.Duration window, java.time.Duration baseline)`

#### Parameters

- `currentCount` (`long`)
- `baselineCount` (`long`)
- `window` (`java.time.Duration`)
- `baseline` (`java.time.Duration`)

### `public long currentCount()`

### `public io.casehub.ras.api.TrendResult.TrendDirection direction()`

### `public final boolean equals(java.lang.Object o)`

#### Parameters

- `o` (`java.lang.Object`)

### `public final int hashCode()`

### `public final java.lang.String toString()`
