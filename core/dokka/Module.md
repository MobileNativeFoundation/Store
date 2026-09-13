# Module core

Store 6 is in development and nothing is published yet. This reference describes the API as it
stands on `main`; install coordinates begin with 6.0.0-alpha01.

## Stability tier

`core` is stable-track. Its API is not frozen until the beta01 freeze candidate.

The `org.mobilenativefoundation.store6.core.seam` package is a freeze candidate, not frozen. Its
types are currently marked `@ExperimentalStoreApi`, so implementing a fetcher, source of truth,
bookkeeper, clock, telemetry sink, or overlay is an explicit opt-in.

Return to [Docs home](https://store.mobilenativefoundation.org/docs), or use the
[Store 6 overview](https://store.mobilenativefoundation.org/docs/store6/overview) for guides and
examples. Writing APIs are in the
[mutations reference](https://store.mobilenativefoundation.org/reference/mutations/index.html).
