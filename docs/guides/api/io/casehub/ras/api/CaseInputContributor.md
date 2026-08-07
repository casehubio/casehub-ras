# io.casehub.ras.api.CaseInputContributor

**Package:** `io.casehub.ras.api`

**Kind:** `interface`

SPI for contributing domain-specific data to a case at creation time.

<p>Implementations are discovered via CDI and called by `DefaultCaseTrigger`
during `buildInputData()`. Each contributor's output is merged into the
case input map after static `baseCaseData` and correlation metadata.

## Methods

### `public abstract java.util.Map<java.lang.String,java.lang.Object> contribute(io.casehub.ras.api.CaseTriggerConfig config, io.casehub.ras.api.SituationContext context)`

#### Parameters

- `config` (`io.casehub.ras.api.CaseTriggerConfig`)
- `context` (`io.casehub.ras.api.SituationContext`)
