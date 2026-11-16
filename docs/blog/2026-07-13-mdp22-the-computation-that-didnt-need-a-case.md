---
layout: post
title: "The Computation That Didn't Need a Case"
date: 2026-07-13
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-ras]
tags: [persistence, ganglion, case-engine, architecture, first-principles]
---

Issue #31 proposed putting ganglion internal state on a case blackboard — Drools CEP sessions, Bayesian posteriors, anything a stateful ganglion accumulates over time. The original RAS spec had this as an optional pattern: long-running detection state lives on a case, survives restarts via the case engine's persistence.

The hypothesis was reasonable when it was written. RAS had no persistence of its own. Since then, two purpose-built stores landed: `ReliableDroolsSessionStore` for KieSession state via drools-reliability + H2MVStore, and `JpaSituationStore` for accumulated detection context. The question was whether the hypothesis still held.

I went through every stateful ganglion and asked what case-backed persistence would actually provide that the existing stores don't.

For `DroolsGanglion`, the answer was nothing. drools-reliability is Drools' own persistence mechanism — it understands KieSession internals (working memory, agenda, timers, temporal windows). Case-backed would replace only the storage backend with a slower one: JPA round-trip instead of local embedded H2MVStore. Same serialisation layer, worse latency, no new capability.

For `NaiveBayesGanglion`, the answer was also nothing — but for a different reason. The ganglion holds a `double[]` of log-posteriors per situation instance, purely in memory. On restart, the accumulation restarts from priors. That's a real gap. But persisting eight doubles doesn't need case lifecycle management, step tracking, or an audit trail. It needs a key-value store, same pattern as `DroolsSessionStore`. Case engine is overkill by an order of magnitude.

The dependency direction sealed it. `casehub-engine → casehub-ras` for detection. `casehub-ras → casehub-engine` for case-backed state. Circular. The current architecture is clean: RAS manages its own persistence, cases observe via `SituationChangeEvent`. One-way dependency.

The deeper point is about abstraction fit. Ganglia are detection units — their internal state is computation state (rule engine working memory, probability vectors). Case engine manages workflow state (steps, milestones, coordination). These are different things. A case blackboard is a coordination medium, not a computation buffer. Using it for high-frequency KieSession safepoints would be like storing loop variables in a database.

I closed #31 as won't-do and filed #36 for the NaiveBayesGanglion persistence gap — the one real finding the investigation surfaced. RAS owns its persistence. Cases coordinate via events. Anything RAS needs from the platform lives in `casehub-platform-api` where it already sits.
