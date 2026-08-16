# graphql

GraphQL operation fetcher kit for Store v6. A store built with this module is a document
cache: each execution of one GraphQL operation, identified by operation name plus canonical
variables, caches one decoded response value. Everything here is `@ExperimentalStoreApi`.
The seam it consumes is a freeze candidate, not frozen — see [STABILITY.md](../STABILITY.md).

The module has no dependency beyond `core` and ships the same Kotlin Multiplatform
target set as `core`. You bring the transport: a `GraphQlExecutor` you implement on
your HTTP client owns JSON encoding of variables, decoding of response data, and
translation of the response `errors` array into `GraphQlError` values. The kit never parses
GraphQL documents and never normalizes responses into entities.

## Install

Until the snapshot is published remotely, publish `core` and `graphql` to
Maven Local:

```shell
./gradlew :core:publishToMavenLocal :graphql:publishToMavenLocal
```

```kotlin
repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("org.mobilenativefoundation.store:graphql:6.0.0-SNAPSHOT")
}
```

## First result

```kotlin
val getUser = GraphQlOperation(
    document = "query GetUser(\$id: ID!) { user(id: \$id) { id name } }",
    name = "GetUser",
)

val store = store<GraphQlOperationKey, User> {
    graphQlFetcher(getUser) { request ->
        // Your transport: encode request.variables, send request.operation.document,
        // decode response data to User and response errors to GraphQlError values.
        val response = api.execute(request.operation.document, request.variables)
        GraphQlExecutorResult.Data(data = response.user, errors = response.errors)
    }
}

val ada = store.get(getUser.key(graphQlVariables { put("id", "1") }))
```

A second `get` of the same key, in any variable insertion order, serves the cached response
without executing. Every Store read policy applies unchanged: `Freshness.MustBeFresh`
re-executes, `Freshness.LocalOnly` never executes, and `store.invalidate(key)` marks one
variable set stale.

## Entry points

- `GraphQlOperation(document, name)` — an executable document plus the operation name.
  The document is opaque to Store and reaches the executor unparsed.
- `GraphQlOperation.key(variables)` — the `GraphQlOperationKey` for one execution.
- `graphQlVariables { }` — builder for `GraphQlVariables` over the `GraphQlValue` model
  (`NullValue`, `BooleanValue`, `IntValue`, `FloatValue`, `StringValue`, `ListValue`,
  `ObjectValue`).
- `graphQlFetcher(operation, partialDataPolicy, executor)` — the seam `Fetcher`, also
  installable through the `StoreBuilder.graphQlFetcher(...)` extension.
- `GraphQlExecutor<V>` — `suspend execute(GraphQlRequest): GraphQlExecutorResult<V>`.
  Throw for transport failure. Return `Data(data, errors, etag)` for any response the
  server produced, or `NotModified(etag)` to answer a conditional request.
- `GraphQlOperationException(operationName, errors)` — the `StoreError.Fetch` cause when a
  response reports errors the fetcher does not adopt.

## Cache identity

`GraphQlOperationKey.canonicalId()` is `<operationName>(<canonical variables>)`. The
variable rendering is JSON-shaped with no whitespace. Object keys sort in UTF-16 code-unit
order at every nesting depth, list order is significant, strings use JSON escaping, and an
explicit `null` variable is a different identity than an absent one. Two keys with equal
variables are one cache entry regardless of insertion order.

`FloatValue` renders through the runtime's `Double.toString`, which differs across Kotlin
targets (JS drops the trailing `.0` of whole numbers). Prefer int or string variables when
canonical ids must match across runtimes, for example in a shared persistent source of
truth.

The default namespace is `graphql:<operationName>`, so
`store.invalidateNamespace(key.namespace)` marks every cached variable set of that one
operation stale. Pass a custom namespace to `GraphQlOperationKey` to partition differently.

## Response mapping

| Executor outcome | Store outcome |
| --- | --- |
| `Data(data != null, no errors)` | `FetcherResult.Success(data, etag)` |
| `Data(data != null, errors)` + `FailOnErrors` (default) | `FetcherResult.Error(GraphQlOperationException)` |
| `Data(data != null, errors)` + `AdoptPartialData` | `FetcherResult.Success(data, etag)` |
| `Data(data == null, errors)` | `FetcherResult.Error(GraphQlOperationException)` |
| `Data(data == null, no errors)` | `FetcherResult.Error` naming the protocol violation |
| `NotModified(etag)` | `FetcherResult.NotModified(etag)` — streams observe `StoreResult.Revalidated` |
| thrown exception | `FetcherResult.Error(cause)`; `CancellationException` propagates |
| key whose `operationName` is not the fetcher's operation | `FetcherResult.Error` naming both operations, without executing |

`GraphQlPartialDataPolicy.FailOnErrors` is the default so a cached value never contains
error-substituted nulls. Opt into `AdoptPartialData` only when the decoded type tolerates
missing fields, because the store caches the partial value as if it were complete.

## Conditional requests

When the engine plans a conditional fetch for a key with a recorded ETag,
`GraphQlRequest.etag` is non-null. An executor that can revalidate (for example HTTP
`If-None-Match` on a GET-shaped operation) returns `NotModified` to confirm the cached
response. Executors without revalidation support ignore the ETag and execute normally.

## Sample

```shell
./gradlew :graphql-sample:run
```

The headless JVM sample asserts four scenes over an in-process scripted executor: key
identity across variable orders, document-cache serve without re-execution, the
fail-vs-adopt partial-response split, and `NotModified` revalidation with recorded ETags.
