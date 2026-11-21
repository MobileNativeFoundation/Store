package org.mobilenativefoundation.store.store5

import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.store5.impl.extensions.asMutableStore
import org.mobilenativefoundation.store.store5.impl.extensions.fresh
import org.mobilenativefoundation.store.store5.impl.extensions.get
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalStoreApi::class)
class MutableStoreDeviceTest {

    @Test
    fun mutableStoreGetAndFreshRunOnAndroidDeviceWithoutExplicitResponseGeneric() = runTest {
        val store = StoreBuilder.from(
            Fetcher.of<String, String> { "remote_$it" },
        ).build()

        val mutableStore: MutableStore<String, String> = store.asMutableStore<String, String, String, String, String>(
            updater = Updater.by(
                post = { key: String, value: String ->
                    UpdaterResult.Success.Untyped("saved")
                },
            ),
            bookkeeper = null,
        )

        // Type inference: no <String, String, Unit> generic parameters required!
        val getResult = mutableStore.get("item1")
        assertEquals("remote_item1", getResult)

        val freshResult = mutableStore.fresh("item1")
        assertEquals("remote_item1", freshResult)
    }
}
