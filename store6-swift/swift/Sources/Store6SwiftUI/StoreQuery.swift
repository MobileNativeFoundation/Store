import Combine
import Foundation
import Store6

/// Observable stream of states for one key, for SwiftUI views.
///
/// Owns one streaming task at a time. `start()` after `start()` is a no-op; `rekey(to:)` cancels
/// the current stream and starts the new key's stream; deallocation cancels the stream.
@MainActor
public final class StoreQuery<Key: StoreKeyRepresentable, Value>: ObservableObject {

    @Published public private(set) var state: StoreState<Value> = .loading

    private let client: StoreClient<Key, Value>
    private let freshness: StoreFreshness
    private var key: Key
    private var streamTask: Task<Void, Never>?

    public init(
        client: StoreClient<Key, Value>,
        key: Key,
        freshness: StoreFreshness = .cachedOrFetch
    ) {
        self.client = client
        self.key = key
        self.freshness = freshness
    }

    public func start() {
        guard streamTask == nil else { return }
        streamTask = makeStreamTask(for: key)
    }

    public func stop() {
        streamTask?.cancel()
        streamTask = nil
    }

    public func rekey(to newKey: Key) {
        stop()
        key = newKey
        state = .loading
        streamTask = makeStreamTask(for: newKey)
    }

    private func makeStreamTask(for key: Key) -> Task<Void, Never> {
        // [weak self] is load-bearing: a strong capture would keep the query alive for the
        // lifetime of an unbounded stream, so deinit-driven cancellation could never run.
        Task { [weak self, client, freshness] in
            for await next in client.states(for: key, freshness: freshness) {
                guard let self, !Task.isCancelled else { return }
                self.state = next
            }
        }
    }

    deinit {
        streamTask?.cancel()
    }
}
