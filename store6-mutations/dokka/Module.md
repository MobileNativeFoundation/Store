# Module store6-mutations

Store 6 is in development and nothing is published yet. This reference describes the API as it
stands on `main`; install coordinates begin with 6.0.0-alpha01.

## Experimental tier

`store6-mutations` is an experimental, separate artifact. Every public symbol is marked
`@ExperimentalStoreApi`, its shapes may change in any release, and no mutations signature is
frozen. The first graduation review is at 6.1 with a target window of roughly 6.3, but graduation is
criteria-gated rather than date-driven.

## Durable acknowledgement

After the server returns an acknowledgement, Store records the receipt, any pending alias or
tombstone, and the `ACKED` phase in one journal transaction. Only after that commit does Store adopt
the result, apply effects, and finalize retirement.

If the server accepts a push before the local acknowledgement transaction commits, replay can send
the same immutable generation with the same idempotency key again, so endpoints must be idempotent
or keyed by mutation identity. Once `ACKED` is durable, recovery may repeat adoption, effects, or
retirement but never calls `push` again for that generation.

Return to [Docs home](https://store.mobilenativefoundation.org/docs), or use the
[Store 6 overview](https://store.mobilenativefoundation.org/docs/store6/overview) for guides and
examples. Read APIs are in the
[store6-core reference](https://store.mobilenativefoundation.org/reference/store6-core/index.html).
