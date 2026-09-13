import Store6Kotlin

/// How fresh a read must be. Mirrors the Kotlin `Freshness` contract exactly.
public enum StoreFreshness: Hashable, Sendable {
    case cachedOrFetch
    case localOnly
    case mustBeFresh
    case staleIfError
    case maxAge(notOlderThanMilliseconds: Int64)

    var kotlin: any Freshness {
        switch self {
        case .cachedOrFetch: return FreshnessCachedOrFetch.shared
        case .localOnly: return FreshnessLocalOnly.shared
        case .mustBeFresh: return FreshnessMustBeFresh.shared
        case .staleIfError: return FreshnessStaleIfError.shared
        case .maxAge(let milliseconds): return SwiftInteropKt.maxAgeFreshness(notOlderThanMillis: milliseconds)
        }
    }
}
