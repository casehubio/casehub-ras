# io.casehub.ras.api.GanglionStateStore

**Package:** `io.casehub.ras.api`

**Kind:** `interface`

## Methods

### `public abstract java.util.Optional<io.casehub.ras.api.GanglionState> load(io.casehub.ras.api.GanglionStateKey key)`

#### Parameters

- `key` (`io.casehub.ras.api.GanglionStateKey`)

### `public abstract void remove(io.casehub.ras.api.GanglionStateKey key)`

#### Parameters

- `key` (`io.casehub.ras.api.GanglionStateKey`)

### `public abstract void removeForSituation(java.lang.String situationId)`

#### Parameters

- `situationId` (`java.lang.String`)

### `public abstract void save(io.casehub.ras.api.GanglionStateKey key, io.casehub.ras.api.GanglionState state)`

#### Parameters

- `key` (`io.casehub.ras.api.GanglionStateKey`)
- `state` (`io.casehub.ras.api.GanglionState`)
