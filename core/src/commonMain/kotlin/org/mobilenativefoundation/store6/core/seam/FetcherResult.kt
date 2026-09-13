package org.mobilenativefoundation.store6.core.seam

import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.StoreBuilder
import org.mobilenativefoundation.store6.core.StoreError
import org.mobilenativefoundation.store6.core.StoreResult

/**
 * The result vocabulary for a fetcher registered with [StoreBuilder.fetcherOfResult] or the seam
 * [Fetcher] overload.
 *
 * A plain [StoreBuilder.fetcher] is success-or-throw sugar: a returned value becomes [Success],
 * while a thrown exception follows the store's fetch-failure path. [NotModified] refreshes the
 * resident value's metadata and emits [StoreResult.Revalidated]; without a resident value it
 * produces [StoreError.Missing]. A seam [Fetcher] receives the ETag selected by a conditional
 * plan; the lambda sugar ignores conditional ETags because its signatures do not accept them.
 * [Error] is equivalent to throwing [Error.cause] from the fetcher. [Deleted] destructively clears
 * the resident value and forgets its freshness; streams and waiters receive [StoreError.Missing],
 * and the deletion does not trigger an automatic refetch.
 *
 * @param V the non-null value type produced by the fetcher
 */
@ExperimentalStoreApi
public sealed interface FetcherResult<out V : Any> {
    /** A fetched `value`, optionally identified by `etag`. */
    public class Success<V : Any>(
        public val value: V,
        public val etag: String? = null,
    ) : FetcherResult<V>

    /**
     * The resident value is unchanged and its metadata should be refreshed with `etag`.
     *
     * @property etag the refreshed ETag, or null to keep the previously recorded tag
     */
    public class NotModified(
        public val etag: String? = null,
    ) : FetcherResult<Nothing>

    /** A fetch failure equivalent to throwing `cause` from the fetcher. */
    public class Error(
        public val cause: Throwable,
    ) : FetcherResult<Nothing>

    /** A destructive remote deletion that clears the resident value without auto-refetching. */
    public data object Deleted : FetcherResult<Nothing>
}
