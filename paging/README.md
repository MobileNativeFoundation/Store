# Paging

This is a Kotlin Multiplatform paging library developed by the Mobile Native Foundation. It provides a flexible and
customizable way to implement pagination in Kotlin Multiplatform projects.

## Features

- Flexible and customizable KMP pagination with builder pattern
- Supports custom actions, reducers, middleware, and post reducer effects
- Supports customization of strategies for error handling, page aggregating, and data fetching
- Supports retrying failed requests
- Provides default implementations for common scenarios

## Getting Started

To use Paging in your project, follow these steps:

1. Add the library dependency to your project:

```kotlin
dependencies {
    implementation("org.mobilenativefoundation.store:paging:5.1.0")
}
```

2. Creating a `PagingConfig` object to configure the pagination behavior

```kotlin
val pagingConfig = PagingConfig(
    pageSize = 20,
    prefetchDistance = 10,
)
```

3. Creating a `PagingSource` with the default paging stream provider:

If you use the default post reducer effects, to provide data for pagination, you need to implement a `PagingSource`. We
provide a `DefaultPagingSource`that you can use as a starting point. You need to provide a `PagingStreamProvider` that
defines how to fetch data for each page. We also provide `MutableStore.defaultPagingStreamProvider()`
and `Store.defaultPagingStreamProvider()` extensions. The default `PagingStreamProvider` delegates to Store and supports
continuous streaming of children items.

```kotlin
val streamProvider = store.defaultPagingStreamProvider(
    keyFactory = object : PagingKeyFactory<Int, SingleKey, SingleData> {
        override fun createKeyFor(data: SingleData): SingleKey {
            // Implement custom logic to create a single key from single data
        }
    }
)
```

```kotlin
val pagingSource = DefaultPagingSource(
    streamProvider = streamProvider
) 
```

3. Configuring the Pager using `PagerBuilder`:

```kotlin
val pager =
    PagerBuilder<Int, CollectionKey, SingleData, CustomAction, CustomError>(
        initialKey = CollectionKey(0),
        anchorPosition = anchorPositionFlow,
        pagingConfig = pagingConfig
    )
        .dispatcher(
            logger = DefaultLogger(),
        ) {

            // Use the default reducer
            defaultReducer {
                errorHandlingStrategy(ErrorHandlingStrategy.PassThrough)
                pagingBufferMaxSize(100)
            }

            middlewares(
                listOf(
                    // Add custom middleware
                )
            )

            // Add custom post reducer effects
            postReducerEffect<MyPagingState, MyPagingAction>(
                state = MyPagingState::class,
                action = MyPagingAction::class,
                effect = MyPostReducerEffect
            )

            // Use the default post reducer effects
            defaultPostReducerEffects(pagingSource = pagingSource)
        }

        .build()
```

4. Observing the paging state and dispatching actions:

```kotlin
pager.state.collect { state ->
    when (state) {
        is PagingState.Data.Idle -> {
            // Update UI with loaded data and provide dispatch callback
            DataView(pagingItems = state.data) { action: PagingAction.User ->
                pager.dispatch(action)
            }
        }
        is PagingState.LoadingInitial -> {
            // Show loading indicator
            InitialLoadingView()
        }
        is PagingState.Error -> {
            // Handle error state and provide dispatch callback
            InitialErrorViewCoordinator(errorState = state) { action: PagingAction.User ->
                pager.dispatch(action)
            }
        }
    }
}
```

## Customization

This library provides various customization options to tailor the pagination behavior to your needs:

1. `ErrorHandlingStrategy`: Defines how errors should be handled.
2. `PageAggregatingStrategy`: Specifies how pages should be aggregated.
3. `PageFetchingStrategy`: Determines when and how pages should be fetched.
4. `CustomActionReducer`: Allows handling custom actions in the default reducer.
5. `PagingBufferMaxSize`: Sets the maximum size of the paging buffer.

Apply the custom components when building the `Pager`:

```kotlin
val pager =
    PagerBuilder<Int, CollectionKey, SingleData, CustomAction, CustomError>(
        // ...
    ).dispatcher {
        defaultReducer {
            errorHandlingStrategy(ErrorHandlingStrategy.Ignore)
            aggregatingStrategy(MyPageAggregatingStrategy())
            pageFetchingStrategy(MyPageFetchingStrategy())
            customActionReducer(MyCustomActionReducer())
            pagingBufferMaxSize(500)
        }
    }.build()
```

## Paging Source

If you do not use the default post reducer effects, you will need to implement and provide post reducer effects for
providing data for pagination.

```kotlin
class MyPagingSource : PagingSource<Int, CollectionKey, SingleData> {
    override fun stream(params: LoadParams<Int, CollectionKey>): Flow<LoadResult> {
        return flow {
            // Implement custom loading of paged data based on the provided parameters
            // Emit the paged data
        }
    }
}
```

## Error Handling

This library supports different error handling strategies:

1. Custom reducer: You can add your own reducer and have total control.
2. Custom middleware: You can add custom middleware for handling errors.
3. Custom post reducer effects: You can add custom post reducer effects for handling errors.
4. `CustomActionReducer`: If using our default reducer, you can provide a custom action reducer
   in `DefaultReducerBuilder`.
5. `ErrorHandlingStrategy.Ignore`: Ignores errors and continues with the previous state. If using our default reducer,
   you can specify this in `DefaultReducerBuilder`.
6. `ErrorHandlingStrategy.PassThrough`: Passes the error to the UI layer for handling. If using our default reducer, you
   can specify this in `DefaultReducerBuilder`.

## Retrying Failed Requests

This library also supports different mechanisms for retrying failed requests:

1. Custom middleware: You can add custom middleware for retries.
2. `DefaultRetryLast`: Default retry middleware. You can specify the maximum number of retries when configuring the
   pager using the `defaultMiddleware` function in `DispatcherBuilder`.
