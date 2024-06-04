# Paging

[![codecov](https://codecov.io/gh/matt-ramotar/Paging/graph/badge.svg?token=62YL5HZR9Q)](https://codecov.io/gh/matt-ramotar/Paging)

A solution for efficient paging in Kotlin Multiplatform projects.

## Features

- Prioritizes extensibility with support for custom middleware, reducers, post-reducer effects, paging strategies, and data sources
- Supports delegating to [Store](https://github.com/MobileNativeFoundation/Store) for optimized data loading and caching
- Opens up mutations and streaming of child items within the list of paging items
- Includes built-in hooks for logging and error handling
- Uses a modular architecture with unidirectional data flow to make it easier to reason about and maintain

## Installation

Add the following dependency to your project:

```kotlin
dependencies {
    implementation("org.mobilenativefoundation.paging:core:1.0.0-SNAPSHOT")
}
```

## Getting Started

### 1. Create a `PagingConfig` to configure the paging behavior:

```kotlin
val pagingConfig = PagingConfig(
    pageSize = 20,
    prefetchDistance = 10,
    insertionStrategy = InsertionStrategy.APPEND
)
```

### 2. Implement a `PagingSource` to provide data for pagination:

```kotlin
val pagingSource = DefaultPagingSource(
    streamProvider = store.pagingSourceStreamProvider(keyFactory)
)
```

### 3. Configure the `Pager` using `PagerBuilder`:

```kotlin
val pager = PagerBuilder<MyId, MyKey, MyParams, MyData, MyCustomError, MyCustomAction>(
    initialKey = PagingKey(key = 1, params = MyParams()),
    anchorPosition = anchorPositionFlow,
)
    .pagingConfig(pagingConfig)

    .pagerBufferMaxSize(100)

    // Provide a custom paging source
    .pagingSource(MyCustomPagingSource())

    // Or, use the default paging source
    .defaultPagingSource(MyPagingSourceStreamProvider())

    // Or, use Store as your paging source
    .mutableStorePagingSource(mutableStore)

    // Use the default reducer
    .defaultReducer {
        errorHandlingStrategy(ErrorHandlingStrategy.RetryLast(3))
        customActionReducer(MyCustomActionReducer())
    }

    // Or, provide a custom reducer
    .reducer(MyCustomReducer())

    // Add custom middleware
    .middleware(MyCustomMiddleware1())
    .middleware(MyCustomMiddleware2())

    // Add custom post-reducer effects
    .effect<SomePagingAction, SomePagingState>(
        action = SomePagingAction::class,
        state = SomePagingState::class,
        effect = MyCustomEffect1()
    )

    .effect<SomePagingAction, SomePagingState>(
        action = SomePagingAction::class,
        state = SomePagingState::class,
        effect = MyCustomEffect2()
    )

    // Use the default logger
    .defaultLogger()

    .build()
```

### 4. Observe the paging state and dispatch actions:

```kotlin
pager.state.collect { state ->
    when (state) {
        is PagingState.Loading -> {
            // Show loading indicator
            InitialLoadingView()
        }

        is PagingState.Data.Idle -> {
            // Update UI with loaded data
            DataView(pagingItems = state.data) { action ->
                pager.dispatch(action)
            }
        }

        is PagingState.Error -> {
            // Handle error state
            ErrorViewCoordinator(errorState = state) { action ->
                pager.dispatch(action)
            }
        }
    }
}
```

## Advanced Usage

### Using Type Aliases

```kotlin
typealias Id = MyId
typealias K = MyKey
typealias P = MyParams
typealias D = MyData
typealias E = MyCustomError
typealias A = MyCustomAction
```

### Handling Errors

This library supports different error handling strategies to handle errors that occur during the paging process.

#### 1. **Built-In Error Handling**: You can configure error handling strategy using the `errorHandlingStrategy` function when building the pager.

```kotlin
val pager = PagerBuilder<Id, K, P, D, E, A>(
    scope,
    initialKey,
    initialState,
    anchorPosition
)
    .defaultReducer {
        // Retry without emitting the error
        errorHandlingStrategy(ErrorHandlingStrategy.RetryLast(3))

        // Emit the error
        errorHandlingStrategy(ErrorHandlingStrategy.PassThrough)

        // Ignore the error
        errorHandlingStrategy(ErrorHandlingStrategy.Ignore)
    }
```

#### 2. **Custom Middleware**: You can add custom middleware for handling errors.

```kotlin
sealed class CustomError {
    data class Enriched(
        val throwable: Throwable,
        val context: CustomContext
    ) : CustomError()
}

class ErrorEnrichingMiddleware(
    private val contextProvider: CustomContextProvider
) : Middleware<Id, K, P, D, E, A> {
    override suspend fun apply(
        action: PagingAction<Id, K, P, D, E, A>,
        next: suspend (PagingAction<Id, K, P, D, E, A>) -> Unit
    ) {
        if (action is PagingAction.UpdateError) {
            val modifiedError = CustomError.Enriched(action.error, contextProvider.requireContext())
            next(action.copy(error = modifiedError))
        } else {
            next(action)
        }
    }
}

val pager = PagerBuilder<Id, K, P, D, E, A>(
    scope,
    initialKey,
    initialState,
    anchorPosition
)
    .middleware(ErrorEnrichingMiddleware(contextProvider))
```

#### 3. **Custom Effects**: You can add custom post-reducer effects for handling errors.

```kotlin
class ErrorLoggingEffect(private val logger: Logger) :
    Effect<Id, K, P, D, E, A, PagingAction.UpdateError<Id, K, P, D, E, A>, PagingState.Error.Exception<Id, K, P, D, E>> {
    override fun invoke(
        action: PagingAction.UpdateError<Id, K, P, D, E, A>,
        state: PagingState.Error.Exception<Id, K, P, D, E>,
        dispatch: (PagingAction<Id, K, P, D, E, A>) -> Unit
    ) {
        when (val error = action.error) {
            is PagingSource.LoadResult.Error.Custom -> {}
            is PagingSource.LoadResult.Error.Exception -> {
                logger.log(error)
            }
        }
    }
}

val pager = PagerBuilder<Id, K, P, D, E, A>(
    scope,
    initialKey,
    initialState,
    anchorPosition
)
    .effect(PagingAction.UpdateError::class, PagingState.Error.Exception::class, errorLoggingEffect)
```

### Reducing Custom Actions

```kotlin
sealed interface MyCustomAction {
    data object ClearData : TimelineAction
}

class MyCustomActionReducer : UserCustomActionReducer<Id, K, P, D, E, A> {
    override fun reduce(action: PagingAction.User.Custom<Id, K, P, D, E, A>, state: PagingState<Id, K, P, D, E>): PagingState<Id, K, P, D, E> {
        return when (action.action) {
            MyCustomAction.ClearData -> {
                when (state) {
                    is PagingState.Data.ErrorLoadingMore<Id, K, P, D, E, *> -> state.copy(data = emptyList())
                    is PagingState.Data.Idle -> state.copy(data = emptyList())
                    is PagingState.Data.LoadingMore -> state.copy(data = emptyList())
                    is PagingState.Error.Custom,
                    is PagingState.Error.Exception,
                    is PagingState.Initial,
                    is PagingState.Loading -> state
                }
            }
        }
    }
}

val pager = PagerBuilder<Id, K, P, D, E, A>(
    scope,
    initialKey,
    initialState,
    anchorPosition
)
    .defaultReducer {
        customActionReducer(MyCustomActionReducer())
    }
```

### Intercepting and Modifying Actions

```kotlin
class AuthMiddleware(private val authTokenProvider: () -> String) : Middleware<Id, K, P, D, E, A> {
    private fun setAuthToken(headers: MutableMap<String, String>) = headers.apply {
        this["auth"] = authTokenProvider()
    }

    override suspend fun apply(action: PagingAction<Id, K, P, D, E, A>, next: suspend (PagingAction<Id, K, P, D, E, A>) -> Unit) {
        when (action) {
            is PagingAction.User.Load -> {
                setAuthToken(action.key.params.headers)
                next(action)
            }

            is PagingAction.Load -> {
                setAuthToken(action.key.params.headers)
                next(action)
            }

            else -> next(action)
        }
    }
}

val pager = PagerBuilder<Id, K, P, D, E, A>(
    scope,
    initialKey,
    initialState,
    anchorPosition
)
    .middleware(AuthMiddleware(authTokenProvider))
```

### Performing Side Effects After State Has Been Reduced

See the [Custom Effects](#3-custom-effects-you-can-add-custom-post-reducer-effects-for-handling-errors) section under [Handling Errors](#handling-errors).

## License

```
Copyright 2024 Mobile Native Foundation

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```
