# MutableStore and Updater → the journalled mutation path

`mutations` is a separate experimental artifact: every public symbol carries `@ExperimentalStoreApi`, so shapes may change or be removed in any release. Confirm the consuming team has accepted that before porting writes. Reads can migrate to core first.

`mutationStore(...)` returns a `MutationStore`, which implements `Store`, so read, freshness, invalidation, clear, and close behavior follows the core read contract.

## The write vocabulary

Store 5's `MutableStore.write(...)` and `Updater.post(...)` move into one journalled path:

1. Register named, typed write shapes once in a `MutatorRegistry` (`mutator`, `update`, `create`, `delete`, or `upsert`). No call-site closure becomes a durable intent. `update` declines when the confirmed base is absent, `delete` always applies absence, `upsert` cannot decline.
2. Enqueue with `mutate(key, ref, args)`. It returns an opaque mutation id and does not push.
3. Push one foreground pass with `drain(key)` or `drain()`. A drain performs no retry or backoff and never fetches.
4. Implement the app-owned `MutationServer` transport contract: exactly two methods, `push(request): MutationAck` and `retire(request): MutationRetirementAck`.
5. Inspect durable truth with `pending(key)`, `pendingWrites()`, and `deadLetters()`.

Restart-safe key recovery is compile-time required. The registry, server, key resolver, and value codec/version are factory inputs:

```kotlin
@OptIn(ExperimentalStoreApi::class)   // the whole module is experimental
val users = mutationStore(
    registry = registry,
    server = server,
    keyResolver = MutationKeyResolver { identity -> UserKey(identity.canonicalId) },
    valueCodecVersion = 1,
    valueCodec = userJsonCodec,
) {
    fetcher { key -> api.load(key) }
}

users.mutate(key, renameRef, Rename("new name"))   // journalled — the only write path
users.drain(key)                                   // push pending intents and adopt each ack
```

## Contract points that differ from Store 5

- Optimistic values appear only on `stream`, with `origin == Origin.OVERLAY`, `age = Duration.ZERO`, and `isStale = false`. `get` remains a point read of committed truth. Drive pending-write UI from the origin, never from `isStale`.
- `runtime()` on a mutation store returns `null`: consumer writes cannot bypass the journal through the raw engine write handle.
- Conflict handling is an optional `conflicts { precondition(...); merge(...) }` block. Without a registered merge, server-wins is the non-removable terminal.
- The per-key write queue becomes a durable FIFO ordered by client sequence.
- The default journal is in-memory. Use the SQLDelight journal adapter (or another conforming durable implementation) when queued work must survive process restart.
- The server contract requires idempotency: a repeated idempotency key must be treated as the same request. If remote acceptance lands before the local acknowledgement-receipt commits, the durable phase stays `INFLIGHT` and a later drain may replay the same immutable generation. Once `ACKED` is durable, recovery may repeat local adoption, effects, and retirement, but never calls `MutationServer.push` again for that generation.

## Store 5 Bookkeeper users

The failed-sync `Bookkeeper` job is replaced by the journal's durable records and the inspection surfaces above, not by a component with the same name. See the name-collision note in [component-map.md](component-map.md).
