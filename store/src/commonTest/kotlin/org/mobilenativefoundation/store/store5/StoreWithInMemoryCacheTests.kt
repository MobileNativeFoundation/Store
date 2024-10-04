package org.mobilenativefoundation.store.store5

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store.store5.impl.extensions.get
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.hours

@FlowPreview
@ExperimentalCoroutinesApi
class StoreWithInMemoryCacheTests {
    private val testScope = TestScope()

    @Test
    fun storeRequestsCanCompleteWhenInMemoryCacheWithAccessExpiryIsAtTheMaximumSize() =
        testScope.runTest {
            val store =
                StoreBuilder
                    .from(Fetcher.of { _: Int -> "result" })
                    .cachePolicy(
                        MemoryPolicy
                            .builder<Any, Any>()
                            .setExpireAfterAccess(1.hours)
                            .setMaxSize(1)
                            .build(),
                    )
                    .build()

            val a = store.get(0)
            val b = store.get(0)
            val c = store.get(1)
            val d = store.get(2)

            assertEquals("result", a)
            assertEquals("result", b)
            assertEquals("result", c)
            assertEquals("result", d)
        }

    @Test
    fun storeDeadlock() =
        testScope.runTest {
            repeat(1000) {
                val store =
                    StoreBuilder
                        .from(
                            fetcher = Fetcher.of { key: Int -> "fetcher_${key}" },
                            sourceOfTruth = SourceOfTruth.Companion.of(
                                reader = { key ->
                                    flow<String> {
                                        emit("source_of_truth_${key}")
                                    }
                                },
                                writer = { key: Int, local: String ->

                                }
                            )
                        )
                        .disableCache()
                        .toMutableStoreBuilder(
                            converter = object : Converter<String, String, String> {
                                override fun fromNetworkToLocal(network: String): String {
                                    return network
                                }

                                override fun fromOutputToLocal(output: String): String {
                                    return output
                                }
                            },
                        )
                        .build(
                            updater = object : Updater<Int, String, Unit> {
                                var callCount = -1
                                override suspend fun post(key: Int, value: String): UpdaterResult {
                                    callCount += 1
                                    if (callCount % 2 == 0) {
                                        throw IllegalArgumentException(key.toString() + "value:$value")
                                    } else {
                                        return UpdaterResult.Success.Untyped("")
                                    }
                                }

                                override val onCompletion: OnUpdaterCompletion<Unit>?
                                    get() = null

                            }
                        )

                val jobs = mutableListOf<Job>()
                jobs.add(
                    store.stream<Nothing>(StoreReadRequest.cached(1, refresh = true))
                        .mapNotNull { it.dataOrNull() }
                        .launchIn(CoroutineScope(Dispatchers.Default))
                )
                val job1 = store.stream<Nothing>(StoreReadRequest.cached(0, refresh = true))
                    .mapNotNull { it.dataOrNull() }
                    .launchIn(CoroutineScope(Dispatchers.Default))
                jobs.add(
                    store.stream<Nothing>(StoreReadRequest.cached(2, refresh = true))
                        .mapNotNull { it.dataOrNull() }
                        .launchIn(CoroutineScope(Dispatchers.Default)))
                jobs.add(
                    store.stream<Nothing>(StoreReadRequest.cached(3, refresh = true))
                        .mapNotNull { it.dataOrNull() }
                        .launchIn(CoroutineScope(Dispatchers.Default)))
                job1.cancel()
                assertEquals(
                    expected = "source_of_truth_0",
                    actual = store.stream<Nothing>(StoreReadRequest.cached(0, refresh = true))
                        .mapNotNull { it.dataOrNull() }.first()
                )
                jobs.forEach {
                    it.cancel()
                    assertEquals(
                        expected = "source_of_truth_0",
                        actual = store.stream<Nothing>(StoreReadRequest.cached(0, refresh = true))
                            .mapNotNull { it.dataOrNull() }.first()
                    )
                }
            }
        }
}
