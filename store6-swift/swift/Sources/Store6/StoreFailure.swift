import Foundation
import Store6Kotlin

/// The Store6 error taxonomy as an exhaustive Swift enum.
///
/// The Kotlin `StoreError` hierarchy is sealed and frozen at 6.0, which is what makes this enum
/// exhaustive rather than open-ended: there is deliberately no `unknown` case.
public enum StoreFailure: Error, Hashable, Sendable {
    case conflict(message: String)
    case conversion(message: String)
    case fetch(message: String)
    case freshnessUnsatisfiable(message: String)
    case missing(message: String, namespace: String, id: String)
    case persistence(message: String)

    init(kotlin: StoreError) {
        switch onEnum(of: kotlin) {
        case .conflict(let error):
            self = .conflict(message: error.message)
        case .conversion(let error):
            self = .conversion(message: error.message)
        case .fetch(let error):
            self = .fetch(message: error.message)
        case .freshnessUnsatisfiable(let error):
            self = .freshnessUnsatisfiable(message: error.message)
        case .missing(let error):
            self = .missing(
                message: error.message,
                namespace: error.key.namespace.value,
                id: error.key.canonicalId()
            )
        case .persistence(let error):
            self = .persistence(message: error.message)
        }
    }

    /// Extracts a typed failure from an error thrown across the Kotlin bridge, if it carries one.
    static func extract(from error: any Error) -> StoreFailure? {
        let nsError = error as NSError
        guard let exception = nsError.userInfo["KotlinException"] as? StoreException else {
            return nil
        }
        return StoreFailure(kotlin: exception.error)
    }
}
