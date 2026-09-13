import Store6Kotlin

/// A Swift value that identifies an entry in a Store.
///
/// Conformances stay pure Swift; the facade converts to the Kotlin key type at the boundary, so
/// key identity (equality, hashing) on the Kotlin side always follows namespace + id.
public protocol StoreKeyRepresentable: Hashable {
    var storeNamespace: String { get }
    var storeID: String { get }

    /// Rebuilds a key from its bridge coordinates so fetcher callbacks receive typed keys.
    ///
    /// Key types whose stored identity is more than (namespace, id) must implement this; types
    /// wrapping exactly those coordinates can rely on the default only if they are
    /// `StoreNamespaceKey` itself — otherwise implement it (usually one line).
    static func reconstruct(namespace: String, id: String) -> Self
}

extension StoreKeyRepresentable {
    var kotlinKey: SwiftStoreKey {
        SwiftStoreKey(namespace: storeNamespace, id: storeID)
    }

    public static func reconstruct(namespace: String, id: String) -> Self {
        if let simple = StoreNamespaceKey(namespace: namespace, id: id) as? Self {
            return simple
        }
        fatalError("""
            \(Self.self) cannot be reconstructed from (namespace, id) alone. \
            Implement static reconstruct(namespace:id:) for this key type.
            """)
    }
}

/// Ready-made key for callers that do not define their own key type.
public struct StoreNamespaceKey: StoreKeyRepresentable, Sendable {
    public let namespace: String
    public let id: String

    public init(namespace: String, id: String) {
        self.namespace = namespace
        self.id = id
    }

    public var storeNamespace: String { namespace }
    public var storeID: String { id }
}
