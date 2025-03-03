package org.mobilenativefoundation.store.store5

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.store5.impl.extensions.get

@OptIn(ExperimentalStoreApi::class)
@FlowPreview
@ExperimentalCoroutinesApi
class StoreWithInMemoryCacheTests {
  private val testScope = TestScope()

  @Test
  fun storeRequestsCanCompleteWhenInMemoryCacheWithAccessExpiryIsAtTheMaximumSize() =
    testScope.runTest {
      val store =
        StoreBuilder.from(Fetcher.of { _: Int -> "result" })
          .cachePolicy(
            MemoryPolicy.builder<Any, Any>().setExpireAfterAccess(1.hours).setMaxSize(1).build()
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
  fun storeDeadlock() = runTest {
    repeat(100) {
      val store: MutableStore<Int, String> =
        StoreBuilder.from(
            fetcher = Fetcher.of { key: Int -> "fetcher_$key" },
            sourceOfTruth =
              SourceOfTruth.of(
                reader = { key: Int -> flowOf("source_of_truth_$key") },
                writer = { key: Int, local: String -> },
              ),
          )
          .disableCache()
          .toMutableStoreBuilder(
            converter =
              object : Converter<String, String, String> {
                override fun fromNetworkToLocal(network: String): String = network

                override fun fromOutputToLocal(output: String): String = output
              }
          )
          .build(
            updater =
              object : Updater<Int, String, Unit> {
                var callCount = -1

                override suspend fun post(key: Int, value: String): UpdaterResult {
                  callCount += 1
                  return if (callCount % 2 == 0) {
                    throw IllegalArgumentException("$key value: $value")
                  } else {
                    UpdaterResult.Success.Untyped("")
                  }
                }

                override val onCompletion: OnUpdaterCompletion<Unit>? = null
              }
          )

      val jobs = mutableListOf<Job>()
      jobs.add(
        store
          .stream<Nothing>(StoreReadRequest.cached(1, refresh = true))
          .mapNotNull { it.dataOrNull() }
          .launchIn(this)
      )
      val job1 =
        store
          .stream<Nothing>(StoreReadRequest.cached(0, refresh = true))
          .mapNotNull { it.dataOrNull() }
          .launchIn(this)
      jobs.add(
        store
          .stream<Nothing>(StoreReadRequest.cached(2, refresh = true))
          .mapNotNull { it.dataOrNull() }
          .launchIn(this)
      )
      jobs.add(
        store
          .stream<Nothing>(StoreReadRequest.cached(3, refresh = true))
          .mapNotNull { it.dataOrNull() }
          .launchIn(this)
      )
      job1.cancel()
      assertEquals(
        expected = "source_of_truth_0",
        actual =
          store
            .stream<Nothing>(StoreReadRequest.cached(0, refresh = true))
            .mapNotNull { it.dataOrNull() }
            .first(),
      )
      jobs.forEach {
        it.cancel()
        assertEquals(
          expected = "source_of_truth_0",
          actual =
            store
              .stream<Nothing>(StoreReadRequest.cached(0, refresh = true))
              .mapNotNull { it.dataOrNull() }
              .first(),
        )
      }
    }
  }
}
