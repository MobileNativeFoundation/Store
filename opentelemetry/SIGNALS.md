# Store6 OpenTelemetry vocabulary v0

This vocabulary is versioned but **EXPERIMENTAL**. Its names are stable within v0. It is an
instrumentation vocabulary for the OpenTelemetry API; the app's SDK owns exporters, sampling,
temporality, and transport. It is **not** the Store 6.1 wire format; that decision remains
open, exactly as `devtools/EVENTS.md` states for the logger vocabulary.

The sink observes identities and lifecycle facts only. Stored values never cross the
telemetry seam. `StoreError` messages and causes are never exported; failures carry only the
variant name.

## Instruments

| Instrument | Kind | Unit | Recorded on | Attributes |
| --- | --- | --- | --- | --- |
| `store6.fetch.attempts` | Counter | `{attempt}` | fetch-coroutine start | `store6.namespace` |
| `store6.fetch.duration` | Histogram | `s` | fetch success or terminal failure | `store6.namespace`; `error.type` on failure only |
| `store6.serves` | Counter | `{serve}` | every successful public serve | `store6.namespace`, `store6.origin` |
| `store6.invalidations` | Counter | `{invalidation}` | successful invalidation | `store6.namespace` |
| `store6.clears` | Counter | `{clear}` | successful clear | `store6.namespace` |

The histogram sets explicit bucket boundaries advice
`[0.005, 0.01, 0.025, 0.05, 0.075, 0.1, 0.25, 0.5, 0.75, 1.0, 2.5, 5.0, 7.5, 10.0]` (the
semantic conventions' HTTP client duration boundaries). Advice is a hint: the SDK honors it
under the default aggregation, and a registered view overrides it.

The histogram unit is **seconds**. The devtools v0 logger emits `fetch_ms` in milliseconds.

## Attributes

| Attribute | Values |
| --- | --- |
| `store6.namespace` | `StoreKey.namespace.value`, verbatim, subject to the cardinality bound below. `overflow` is a reserved value. |
| `store6.origin` | `MEMORY`, `SOT`, `FETCHER`, `OVERLAY` — the `Origin` enum names, matching `EVENTS.md`. |
| `error.type` | Exactly one of `Fetch`, `Persistence`, `Conversion`, `FreshnessUnsatisfiable`, `Conflict`, `Missing` — the `StoreError` variant names, frozen for the 6.x major and identical to the v0 error names in `EVENTS.md`. Present only on failures. |
| `store6.key` | `StoreKey.canonicalId()`. Spans only, only with `keyAttributeOnSpans = true`. Never on metrics. |

Constructor `extraAttributes` are merged into every metric point and span; an entry that
collides with a sink-owned attribute name is overwritten by the sink.

## Cardinality bound

Store's key-design guidance allows dynamic namespaces (one per organization, for example), so
the sink bounds namespace cardinality itself. Attribute sets are interned per namespace; once
`maxNamespaces` (default 512) distinct namespaces have been interned, later namespaces record
under `store6.namespace = "overflow"`. Under concurrent first observations the interned count
can exceed the bound by the number of racing namespaces — it is a memory and cardinality
guard, not an exact quota. There is no eviction. Per-entity analysis belongs on spans with
`keyAttributeOnSpans`, not on metrics. The SDK's own per-instrument cardinality limits remain
the backstop.

## Fetch span (opt-in, `emitSpans = true`)

| Field | Value |
| --- | --- |
| Name | `store6.fetch` (constant; the namespace is an attribute) |
| Kind | `INTERNAL` |
| Parent | none; the span never joins a caller trace |
| Timestamps | end = wall clock at the terminal hook; start = end − engine-measured duration, clamped at zero; the span duration equals the engine's measured duration exactly whenever the clamp does not engage, and engine-produced durations cannot engage it |
| Attributes | `store6.namespace`; `error.type` on failure; `store6.key` when opted in; plus `extraAttributes` |
| Status | unset on success; `ERROR` without description on failure (`error.type` carries the variant) |

One span per settled fetch. Superseded fetches have no terminal hook and therefore no span
and no `store6.fetch.duration` point — see the next section. Started-then-abandoned spans
cannot occur: the span is created complete at terminal time. Non-finite or negative durations
(impossible from the engine, possible through direct hook calls) are dropped entirely, from
both the histogram and the span.

## Attempts versus settlements

`store6.fetch.attempts` counts fetch starts; the `store6.fetch.duration` histogram counts
settlements. As recorded by one sink instance over its lifetime — for engine-produced hooks
recorded by a non-throwing SDK — attempts minus settlements equals superseded fetches plus
fetches currently in flight. A partially failing SDK or direct hook calls with invalid
durations can decouple the two instruments. After export, the arithmetic additionally
survives only under cumulative temporality with lossless collection; delta temporality,
process restarts, and dropped points all break it. Treat the subtraction as a
process-lifetime heuristic, not an invariant.

## Instrumentation scope

Meter and tracer use the scope name `org.mobilenativefoundation.store6.opentelemetry` with
the artifact version as the scope version. A unit test pins the version constant to the
module's Gradle `VERSION_NAME`.

## Change policy

As an experimental vocabulary, v0 may still change at an alpha boundary. Any v0 instrument,
attribute, value, or span-shape change must be recorded in the alpha notes. Cross-alpha
compatibility for dashboards is not guaranteed. v0 is not the Store 6.1 wire format; that
decision remains open.
