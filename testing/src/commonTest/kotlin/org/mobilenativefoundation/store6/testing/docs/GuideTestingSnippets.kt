@file:OptIn(ExperimentalStoreApi::class, DelicateStoreApi::class)
@file:Suppress("unused")

package org.mobilenativefoundation.store6.testing.docs

import org.mobilenativefoundation.store6.core.DelicateStoreApi
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.Store
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.store
import org.mobilenativefoundation.store6.testing.BookkeeperContractKit
import org.mobilenativefoundation.store6.testing.FakeBookkeeper
import org.mobilenativefoundation.store6.testing.FakeFetcher
import org.mobilenativefoundation.store6.testing.FakeSourceOfTruth
import org.mobilenativefoundation.store6.testing.SourceOfTruthContractKit
import org.mobilenativefoundation.store6.testing.TestWallClock

public class UserKey(
    private val id: String,
) : StoreKey {
    override val namespace: StoreNamespace = StoreNamespace("users")

    override fun canonicalId(): String = id
}

public data class User(
    val id: String,
    val name: String,
)

// docs:snippet:guides-testing-policy-fixtures
@OptIn(ExperimentalStoreApi::class)
fun userStoreForPolicyTests(): Store<UserKey, User> {
    val fetcher = FakeFetcher<UserKey, User>()
    val sourceOfTruth = FakeSourceOfTruth<UserKey, User>()
    val bookkeeper = FakeBookkeeper()
    val clock = TestWallClock()

    return store {
        fetcher(fetcher)
        persistence(sourceOfTruth)
        bookkeeper(bookkeeper)
        wallClock(clock)
    }
}
// docs:snippet:end

public class MyKey(
    namespace: String,
    private val id: String,
) : StoreKey {
    override val namespace: StoreNamespace = StoreNamespace(namespace)

    override fun canonicalId(): String = id
}

public data class MyValue(
    val value: String,
)

public typealias MySourceOfTruth = FakeSourceOfTruth<MyKey, MyValue>
public typealias MyBookkeeper = FakeBookkeeper

// docs:snippet:guides-source-of-truth-contract-kit
class MySourceOfTruthContractTest : SourceOfTruthContractKit<MyKey, MyValue>() {
    override fun createSourceOfTruth() = MySourceOfTruth()
    override val keyA = MyKey("users", "a")
    override val keyB = MyKey("users", "b")
    override val keyOtherNamespace = MyKey("teams", "a")
    override fun value(index: Int) = MyValue("value-$index")
}
// docs:snippet:end

// docs:snippet:guides-testing-bookkeeper-contract-kit
class MyBookkeeperContractTest : BookkeeperContractKit() {
    override fun createBookkeeper() = MyBookkeeper()
}
// docs:snippet:end
