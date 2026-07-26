# Store6 devtools event vocabulary v0

This vocabulary is versioned but **EXPERIMENTAL**. Its names and field order are stable within v0.
The Store 6.1 web-panel wire format described by `technical-design.md` OQ-3 will stabilize from
this vocabulary and is deliberately **not** decided here (DR-3).

The logger and monitor observe identities and lifecycle facts only. Logger lines and inspector
presentation never include stored values or `StoreError` message and cause payloads. The
in-memory monitor projection does retain the structured `StoreError`; application code holding
`monitor.state` can inspect that object, including its message and cause.

## Logger line

Each event is one line with this order:

```text
<label> v0 seq=<Long> t_ms=<Long> evt=<kind> ns=<String> key=<String> [origin=<Origin>] [fetch_ms=<Long>] [error=<StoreError variant>]
```

`seq` is a one-based sequence within one sink. `t_ms` is monotonic elapsed whole milliseconds
since that sink was created. `ns` and `key` are the two components of `StoreKey` identity.
Optional fields use the canonical order `origin`, then `fetch_ms`, then `error`; a kind emits only
the fields that apply to it.

`label` is a nonblank structural token. It cannot contain whitespace, control characters, `"`,
`=`, or `\`.

Handlers format the line and invoke the configured `emit` callback synchronously and inline on
the caller's thread. The callback must return promptly and be thread-safe. The default `println`
callback is intended for development diagnostics and may perform platform I/O. Installed cost
includes both line formatting and callback work.

Concurrent handlers receive unique `seq` values, but formatting and callback delivery are not
serialized. A callback with a higher sequence may arrive before one with a lower sequence;
consumers must use `seq` as the canonical ordering key.

| `evt` kind | Additional fields and types | Meaning | Example |
| --- | --- | --- | --- |
| `fetch_started` | none | A fetch attempt started. | `store6 v0 seq=1 t_ms=0 evt=fetch_started ns=users key=user-1` |
| `fetch_succeeded` | `fetch_ms: Long` | A fetch committed or revalidated successfully. | `store6 v0 seq=2 t_ms=1500 evt=fetch_succeeded ns=users key=user-1 fetch_ms=120` |
| `fetch_failed` | `fetch_ms: Long`, `error: StoreError v0 name` | A fetch settled with an error. | `store6 v0 seq=3 t_ms=2000 evt=fetch_failed ns=users key=user-1 fetch_ms=80 error=Fetch` |
| `serve` | `origin: Origin` | A public read served a visible value. | `store6 v0 seq=4 t_ms=2100 evt=serve ns=users key=user-1 origin=FETCHER` |
| `invalidate` | none | Invalidation completed successfully. | `store6 v0 seq=5 t_ms=2200 evt=invalidate ns=users key=user-1` |
| `clear` | none | Clearing completed successfully. | `store6 v0 seq=6 t_ms=2300 evt=clear ns=users key=user-1` |

`error` is exactly one of the six literal v0 names: `Fetch`, `Persistence`, `Conversion`,
`FreshnessUnsatisfiable`, `Conflict`, or `Missing`. The error's message and cause are
review-gated diagnostics and are not logger fields.

## Quoting and escaping

Identity values containing a space, `"`, `=`, `\`, a C0 control (`U+0000`–`U+001F`), `DEL`
(`U+007F`), a C1 control (`U+0080`–`U+009F`), `U+2028 LINE SEPARATOR`, or
`U+2029 PARAGRAPH SEPARATOR` are wrapped in double quotes. Inside a quoted value, `"` becomes
`\"`, `\` becomes `\\`, newline becomes `\n`, carriage return becomes `\r`, and tab becomes
`\t`. Every other listed control or separator becomes `\u` followed by four lowercase hexadecimal
digits. Spaces and equals signs remain literal inside the quotes.

```text
store6 v0 seq=7 t_ms=2400 evt=fetch_started ns=users key="user 1"
store6 v0 seq=8 t_ms=2500 evt=fetch_started ns=users key="user\"1\\mobile\nactive=true"
store6 v0 seq=9 t_ms=2600 evt=fetch_started ns=users key="user\rdesktop\tactive=false"
store6 v0 seq=10 t_ms=2700 evt=fetch_started ns=users key="user\u0000mobile\u001b\u0085line\u2028paragraph\u2029"
```

## Derived key state

`DevtoolsKeyState` is a projection of observed events, not engine freshness authority.

| Observed event | Derived state | Other projection changes |
| --- | --- | --- |
| `fetch_started` | `FETCHING` | Increment `fetchCount`. |
| `fetch_succeeded` | `FRESH` | Record the event time as `lastFetchSucceededAt`; clear `lastError`. |
| `fetch_failed` | `ERROR` | Record the structured variant as `lastError`. |
| `serve` | Preserve the prior state, or `OBSERVED` for a newly seen key. | Record `lastOrigin`; increment `serveCount`. |
| `invalidate` | `STALE` | Preserve the other observed facts. |
| `clear` | `CLEARED` | Clear `lastFetchSucceededAt`; preserve the other observed facts. |

Policy or `MaxAge` staleness emits no telemetry event and is never inferred. In particular,
`FRESH` means only that no invalidation, clear, or failure has been observed since the latest
success; it does not assert that the engine would satisfy a freshness policy.

The inspector's timeline row labels reuse the same six `evt` names. There is one v0 vocabulary,
not a separate UI vocabulary.

## Change policy

As an experimental vocabulary, v0 may still change at an alpha boundary. Any v0 field-name,
field-order, kind, or quoting change must be recorded in the alpha notes. Version v1 is cut only
by the Store 6.1 OQ-3 wire-format decision.
