# Paging Technical Design Doc

## 1. Motivations

Powerful and extensible solution for paging in KMP projects, under the Mobile Native Foundation.
Address these Android Paging limitations:

- [x] Seamless integration with Store and Mutable Store
- [x] Local mutations and streaming of child items within the list of paging items
- [x] Custom reducer, middleware, and post-reducer effects

## 2. Overview

[//]: # (TODO: Arch diagram)

Modular and flexible architecture. Using builder, reducer, middleware, and post-reducer effect patterns. Unidirectional
data flow. The `Pager` is the main component. Actions are dispatched through the `Pager`. The `PagerBuilder` creates
the `Pager`.
It allows
configuration of the paging behavior. The `PagingSource` defines the data loading logic. The `PageFetchingStrategy`
determines when to fetch the next page.
The `PageAggregatingStrategy` combines loaded pages into a single list. The `PagingReducer` handles state changes based
on actions. When an action is dispatched, it goes through the middleware
pipeline. The middleware can modify the action. The reducer then updates the
state based on the action. After the reducer, we invoke post-reducer effects associated with the action and new state.
The updated state is sent back the `Pager` and emitted to the UI.

## 3. The Actual Design

### 3.1 Key Components

- `Pager`: The main entry point, responsible for coordinating the paging process and providing access to the paging
  state and data.
- `PagingSource`: Defines the data source and loading logic for paged data.
- `PagingState`: Represents the current state of the paging data, including loaded pages, errors, and loading status.
- `PagingAction`: Defines the actions that can be dispatched to modify the paging state.
- `PagingReducer`: Reduces the paging state based on dispatched actions.
- `PagingMiddleware`: Intercepts and modifies paging actions before they reach the reducer.
- `PostReducerEffect`: Performs side effects after reducing the paging state.
- `PageFetchingStrategy`: Determines when to fetch the next page of data.
- `PageAggregatingStrategy`: Aggregates loaded pages into a single list.
- `MutablePagingBuffer`: Efficiently stores and retrieves paging data.
- `JobCoordinator`: Coordinates the execution of paging-related jobs.
- `QueueManager`: Manages the queue of pages to be loaded.
- `PagingStreamProvider`: Provides a stream of paging data.
- `PagingKeyFactory`: Creates keys for paging data.
- `PagingConfig`: Configures the paging behavior.
- `ErrorHandlingStrategy`: Defines how to handle errors during the paging process.
- `Logger`: Logs paging-related events and actions.

### 3.2 Customizations

We are providing many extension points and customization options to tailor the paging behavior. Some of the key
customization points:

- `PagingSource`: Developers can implement their own `PagingSource` to define how paged data is loaded from the data
  source. This allows for integration with different data sources and loading mechanisms.
- `PagingMiddleware`: Custom `PagingMiddleware` can be implemented to intercept and modify paging actions before they
  reach the reducer. This enables preprocessing, logging, or any other custom logic.
- `PagingReducer`: The `PagingReducer` can be customized to define how the paging state is reduced based on dispatched
  actions. This allows for fine-grained control over the paging state transitions.
- `PostReducerEffect`: Custom `PostReducerEffect` instances can be configured to perform side effects after reducing the
  paging state. This is useful for triggering UI updates, analytics events, or any other necessary actions.
- `PageFetchingStrategy`: Developers can implement their own `PageFetchingStrategy` to determine when to fetch the next
  page of data based on the current state and configuration. This allows for customizing the prefetching behavior.
- `PageAggregatingStrategy`: Custom `PageAggregatingStrategy` implementations can be provided to define how loaded pages
  are aggregated into a single list. This enables different aggregation strategies based on the specific requirements of
  the application.
- `ErrorHandlingStrategy`: Developers can implement their own `ErrorHandlingStrategy` to define how errors during the
  paging process are handled. This allows for custom error handling, retry mechanisms, or fallback behaviors.

### 3.3 Data Flow

[//]: # (TODO: Data flow diagram)

Unidirectional data flow. Main steps:

1. `Pager` is configured using `PagerBuilder` and provided an initial key, flow of anchor position, and paging config.
2. `Pager` subscribes to the `PagingSource` to receive paging data updates.
3. When a `PagingAction` is dispatched, it goes through the configured `PagingMiddleware` chain. This enables
   interception and modification of the action.
4. The modified action reaches the `PagingReducer`, which reduces the current `PagingState` based on the action and
   returns a new `PagingState`.
5. After reduction, any configured `PostReducerEffect` instances are executed, enabling side effects to be performed
   based on the new `PagingState`.
6. `Pager` updates `PagingStateManager` with the new `PagingState`.
7. `PageFetchingStrategy` determines when to fetch the next page of data based on the `PagingConfig` and
   current `PagingState`.
8. When a new page needs to be fetched, `QueueManager` enqueues the page key, and the `JobCoordinator` coordinates the
   execution of the paging job.
9. `PagingSource` loads the requested page and emits the loaded data through the `PagingStreamProvider`.
10. The loaded page is stored in the `MutablePagingBuffer` for efficient retrieval and aggregation.
11. The `PageAggregatingStrategy` aggregates the loaded pages into a single list, which is then emitted through
    the `Pager` for consumption by the UI.

## 4. Sample Code

Configuring the Pager using `PagerBuilder`:

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

Observing the paging state and dispatching actions:

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