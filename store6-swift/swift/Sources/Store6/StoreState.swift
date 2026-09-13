import Foundation
import Store6Kotlin

/// Where a served value came from.
public enum StoreOrigin: Hashable, Sendable {
    case memory
    case sourceOfTruth
    case fetcher
    case overlay

    init(kotlin: Origin) {
        switch kotlin {
        case .memory: self = .memory
        case .sot: self = .sourceOfTruth
        case .fetcher: self = .fetcher
        case .overlay: self = .overlay
        }
    }
}

/// One emission of a Store stream, typed.
///
/// `stream` never throws (cancellation aside): failures arrive as `.error` states, so consuming
/// this enum covers every outcome of a read.
public enum StoreState<Value> {
    case loading
    case data(Value, origin: StoreOrigin, ageMilliseconds: Int64, isStale: Bool, refreshing: Bool)
    case revalidated(ageMilliseconds: Int64)
    case error(StoreFailure, servedStale: Bool)

    init(bridge: StoreStateBridge) {
        switch bridge.kind {
        case .loading:
            self = .loading
        case .data:
            guard let value = bridge.value as? Value else {
                self = .error(
                    .conversion(message: "Stream value of type \(type(of: bridge.value)) is not \(Value.self)"),
                    servedStale: false
                )
                return
            }
            self = .data(
                value,
                origin: bridge.origin.map(StoreOrigin.init(kotlin:)) ?? .fetcher,
                ageMilliseconds: bridge.ageMillis,
                isStale: bridge.isStale,
                refreshing: bridge.refreshing
            )
        case .revalidated:
            self = .revalidated(ageMilliseconds: bridge.ageMillis)
        case .error:
            guard let kotlinError = bridge.error else {
                self = .error(.conversion(message: "Error state crossed the bridge without an error payload"), servedStale: bridge.servedStale)
                return
            }
            self = .error(StoreFailure(kotlin: kotlinError), servedStale: bridge.servedStale)
        }
    }
}
