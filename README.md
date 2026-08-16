<img src=".github/images/hero-light.svg" width="100%"/>

# Store5

[![codecov](https://codecov.io/gh/MobileNativeFoundation/Store/branch/main/graph/badge.svg?token=0UCmG3QHPf)](https://codecov.io/gh/MobileNativeFoundation/Store)

## Store 6

Store 6 is the next major line, published as `core`, `testing`, `mutations`, and the other Store 6
artifacts in the `org.mobilenativefoundation.store` group, alongside Store 5 for the whole 6.x major. It is a Kotlin Multiplatform library for reading and writing data that lives in
more than one place: a network, a local database, and memory. You describe a key and a fetcher, and
Store handles single-flighting concurrent demand, staleness, invalidation, and bounded memory, with
every zero-config behavior named and covered by a conformance test you can read.

**Status: in development, targeting 6.0.0-alpha01.** Nothing is published yet.

Two things about the first alpha, stated up front rather than discovered later:

- **Mutations ship experimental.** `mutations` is a separate artifact and every public symbol
  is `@ExperimentalStoreApi`. The tier is on the artifact, never annotation-gated inside a stable
  one.
- **Mutations ship the two-step durable ack posture.** The non-transactional acknowledgement path
  adopts the server echo first and retires the journal row last, so a crash inside that window
  leaves a replayable pending intent rather than losing the write. The consequence is that the same
  push can be re-sent after such a crash, so design those endpoints to be idempotent. Making the ack
  path atomic is beta01 work, not alpha01 work.

The full policy — API tiers, the deprecation cycle, the cadence commitment, and how you can verify
all of it from a released tag — is in [STABILITY.md](./STABILITY.md). The public roadmap is at
[ROADMAP.md](./ROADMAP.md), and the quickstart is at
[docs/store6/quickstart.md](./docs/store6/quickstart.md).

---

#### Documentation

Comprehensive guides, tutorials, and API reference: [store.mobilenativefoundation.org](https://store.mobilenativefoundation.org).

#### Getting Started

1. Start with the [Quickstart](https://store.mobilenativefoundation.org/docs/quickstart) to build your first Store.
2. Dive into [Store Foundations](https://store.mobilenativefoundation.org/docs/concepts) to learn how Store works.
3. Check out [Handling CRUD](https://store.mobilenativefoundation.org/docs/use-cases/store5/setting-up-store-for-crud-operations) for an advanced guide on supporting create, read, update, and delete operations.

#### Getting Help

Join our community in the [#store](https://kotlinlang.slack.com/archives/C06007Z01HU) channel on the official Kotlin Slack.

#### Getting Involved

Store has a vibrant community of contributors. We welcome contributions of all kinds. Please see our [Contributing Guidelines](CONTRIBUTING.md) for more information on how to get involved.

#### Backed By

<div style="display: flex; align-items: center; gap: 20px;">
    <img src=".github/images/mobile-native-foundation.png" width="200"/>
    <img src=".github/images/kotlin-foundation.png" width="200"/>
</div>

#### License

```text
Copyright (c) 2024 Mobile Native Foundation.
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
```
