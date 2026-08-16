# Results

## Baseline (no skill): fails

Run: 2026-08-15, fresh general-purpose agent, scenario as specified.

The agent accepted the "mostly a rename" premise and produced a mechanical package rename: it changed the eight `org.mobilenativefoundation.store.store5.*` imports to `org.mobilenativefoundation.store6.*` and kept every type, member, and call shape verbatim, including `StoreBuilder.from`, `Fetcher.of`, `SourceOfTruth.of`, `Validator.by`, `StoreReadRequest.cached(key, refresh = true)`, the seven-branch `StoreReadResponse` `when`, `store.fresh(id)`, and a plain `String` key. None of those spellings exist in Store 6, and the guessed package root is also wrong (real code lives under `org.mobilenativefoundation.store6.core`). None of it can compile.

The agent's notes were honest about the epistemics while still shipping a wrong deliverable. Verbatim from its notes: "I have no independent knowledge of Store 6" and "Treat every `org.mobilenativefoundation.store6.*` spelling as unverified until it compiles against the real dependency." Its listed guesses included "No new opt-in requirements" (wrong: persistence and seams require `@ExperimentalStoreApi`, and seam implementation requires `DelicateStoreApi`) and "Sealed hierarchy of `StoreReadResponse` unchanged" (wrong: the type is gone, and `StoreResult` has four different kinds).

Failure pattern the skill must counter: **extrapolation from Store 5 plus a rename premise**, with uncertainty disclosed in notes but a wrong deliverable still produced.

## With skill: pass

Run: 2026-08-15, fresh general-purpose agent, same scenario plus the skill directory in the sandbox (introduced only by its description, as an installed skill would be).

The agent's notes source every Store 6 spelling to the skill files and reject the rename premise ("Store 6 is a redesigned API, not a package rename"). Its port met every pass criterion: `store<UserKey, User> { fetcher { }; persistence(...) }`, a `StoreKey` implementation, `org.mobilenativefoundation.store6.core`/`.core.seam` imports, `get(key, Freshness.MustBeFresh)` for pull-to-refresh, an exhaustive four-kind `StoreResult` `when` including `Revalidated`, both opt-ins on the seam implementation, `clearAll()` for sign-out, an added `close()` with an ownership note, and no invented API or coordinates. Its notes state verbatim: "I used no API that is absent from the skill."

It did not copy the reference's worked port: for `cached(refresh = true)` it chose the table's invalidate-plus-default-stream pattern over the worked example's `MaxAge`, matched to the fixture's stated refresh-on-subscribe contract, and disclosed the resulting behavioral differences (stale data visible during revalidation, validator bound subsumed) instead of claiming equivalence.

## Refactor pass

The with-skill notes surfaced two places the agent had to guess. Both were closed in `references/store5-to-store6.md` after the run (the run above executed against the pre-edit skill):

- Whether maintenance operations are suspending. Now stated: `invalidate*`/`clear*` suspend and can throw `StoreException`, and `close()` is a plain function. Also stated that a durable stale mark does not require a resident value.
- Whether `StoreException` exposes the underlying failure. Now stated: it carries `error: StoreError` and a nullable `cause`, with the catch-type migration consequence for Store 5 `fresh` callers.

Future eval variant worth adding: a fixture the worked example does not mirror (no source of truth, `skipMemory` usage, or an Rx consumer) to test table use beyond the example.
