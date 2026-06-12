# casehub-ras

[![Build](https://github.com/casehubio/casehub-ras/actions/workflows/publish.yml/badge.svg?branch=main)](https://github.com/casehubio/casehub-ras/actions/workflows/publish.yml) [![Open PRs](https://img.shields.io/github/issues-pr/casehubio/casehub-ras)](https://github.com/casehubio/casehub-ras/pulls)

**Reticular Activating System** — situational awareness and reactive case creation for the [casehubio](https://github.com/casehubio) platform.

Monitors `SensoryEvent` streams, routes to pluggable `Ganglion` detection strategies, correlates composite events, and triggers case creation when a situation threshold is crossed. The platform watches — cases happen automatically.

| Module | Purpose |
|--------|---------|
| `api` | `Ganglion` SPI, `SensoryEvent`, `DetectionResult`, `SituationContext`, `RasTriggerPolicy` |
| `runtime` | `RasEngine`, `CompositeEventCorrelator`, `CaseTriggerService` |
| `ras-drools` | `DroolsGanglion` — Drools CEP (sliding windows, temporal correlation) |
| `ras-llm` | `LlmGanglion` — narrative detection via `casehub-platform-agent-api` |
| `testing` | `MockGanglion`, test fixtures |

**Key design:** casehub-ras contains no stream infrastructure. Quarkus/Camel stream modules in `casehub-platform` produce `SensoryEvent` CDI events; the RAS observes them.

See the [design spec](docs/superpowers/specs/2026-06-12-casehub-ras-design.md) for architecture, SPI contracts, composite event chains, and open design questions.
