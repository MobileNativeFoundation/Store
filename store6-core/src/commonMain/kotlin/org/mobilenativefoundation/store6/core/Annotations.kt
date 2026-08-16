package org.mobilenativefoundation.store6.core

/**
 * Marks API that is under active development and may change or be removed in any release.
 *
 * Experimental API ships in separate artifacts wherever possible; this marker exists for the
 * cases where an experimental member must live beside stable API.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "This Store API is experimental and may change or be removed in any release.",
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.TYPEALIAS,
)
@MustBeDocumented
public annotation class ExperimentalStoreApi

/**
 * Marks API that is stable but easy to misuse, such as implementing [Store] directly instead of
 * building one through the [store] DSL. Opting in asserts that the caller upholds the documented
 * contract of the marked declaration.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message =
        "This is a delicate Store API. Read the contract documentation of the declaration " +
            "before opting in; implementations must uphold every documented semantic.",
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.TYPEALIAS,
)
@MustBeDocumented
public annotation class DelicateStoreApi

/**
 * Marks API that is internal to the Store libraries. It may change or disappear without notice
 * even in patch releases and must never be used outside org.mobilenativefoundation.store
 * artifacts.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "This API is internal to Store and must not be used outside Store artifacts.",
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.TYPEALIAS,
)
@MustBeDocumented
public annotation class InternalStoreApi
