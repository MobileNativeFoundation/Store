@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalStoreApi::class)

package org.mobilenativefoundation.store.store5.mutablestore

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.store5.FetcherResult
import org.mobilenativefoundation.store.store5.StoreReadRequest
import org.mobilenativefoundation.store.store5.StoreReadResponse
import org.mobilenativefoundation.store.store5.StoreWriteRequest
import org.mobilenativefoundation.store.store5.StoreWriteResponse
import org.mobilenativefoundation.store.store5.impl.RealMutableStore
import org.mobilenativefoundation.store.store5.impl.RealStore
import org.mobilenativefoundation.store.store5.mutablestore.util.TestCache
import org.mobilenativefoundation.store.store5.mutablestore.util.TestConverter
import org.mobilenativefoundation.store.store5.mutablestore.util.TestFetcher
import org.mobilenativefoundation.store.store5.mutablestore.util.TestInMemoryBookkeeper
import org.mobilenativefoundation.store.store5.mutablestore.util.TestLogger
import org.mobilenativefoundation.store.store5.mutablestore.util.TestSourceOfTruth
import org.mobilenativefoundation.store.store5.mutablestore.util.TestUpdater
import org.mobilenativefoundation.store.store5.mutablestore.util.TestValidator
import org.mobilenativefoundation.store.store5.mutablestore.util.testStore

private data class Note(val id: String, val content: String)

private data class NetworkNote(val id: String, val content: String)

private data class DatabaseNote(val id: String, val content: String)

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalStoreApi::class)
class RealMutableStoreTest {
  private lateinit var testFetcher: TestFetcher<String, NetworkNote>
  private lateinit var testConverter: TestConverter<NetworkNote, DatabaseNote, Note>
  private lateinit var testValidator: TestValidator<Note>
  private lateinit var testSourceOfTruth: TestSourceOfTruth<String, DatabaseNote, Note>
  private lateinit var testCache: TestCache<String, Note>

  private lateinit var testUpdater: TestUpdater<String, Note, NetworkNote>
  private lateinit var testBookkeeper: TestInMemoryBookkeeper<String>
  private lateinit var testLogger: TestLogger

  private lateinit var delegateStore: RealStore<String, NetworkNote, Note, DatabaseNote>
  private lateinit var mutableStore: RealMutableStore<String, NetworkNote, Note, DatabaseNote>

  @BeforeTest
  fun setUp() {
    testFetcher = TestFetcher()
    val defaultLocalValue = DatabaseNote("defaultLocalId", "defaultLocalContent")
    testConverter =
      TestConverter(
        defaultNetworkToLocalConverter = { defaultLocalValue },
        defaultOutputToLocalConverter = { defaultLocalValue },
      )
    testValidator = TestValidator()
    testSourceOfTruth = TestSourceOfTruth()
    testCache = TestCache()

    testFetcher.whenever("key1") {
      flowOf(FetcherResult.Data(NetworkNote("networkId", "networkContent")))
    }

    testUpdater = TestUpdater()
    testBookkeeper = TestInMemoryBookkeeper()
    testLogger = TestLogger()

    delegateStore =
      testStore(
        fetcher = testFetcher,
        sourceOfTruth = testSourceOfTruth,
        converter = testConverter,
        validator = testValidator,
        memoryCache = testCache,
      )

    mutableStore =
      RealMutableStore(
        delegate = delegateStore,
        updater = testUpdater,
        bookkeeper = testBookkeeper,
        logger = testLogger,
      )
  }

  @Test
  fun stream_givenNoConflicts_whenReading_thenEmitsFromDelegate() = runTest {
    // Given
    val request = StoreReadRequest.Companion.cached("key1", refresh = true)

    // When
    val results = mutableStore.stream<Unit>(request).take(2).toList()

    // Then
    assertTrue(results.size >= 2)
    assertIs<StoreReadResponse.Loading>(results[0])
    assertIs<StoreReadResponse.Data<Note>>(results[1])
  }

  @Test
  fun stream_givenConflictsAndBookkeeper_whenReading_thenAttemptsEagerConflictResolution() =
    runTest {
      // Given
      val request = StoreReadRequest.Companion.cached("key2", refresh = true)
      delegateStore.write("key2", Note("localId", "localContent"))
      testBookkeeper.setLastFailedSync("key2")
      testUpdater.successValue = NetworkNote("resolvedId", "resolvedContent")

      // When
      val results = mutableStore.stream<Unit>(request).take(2).toList()

      // Then
      assertTrue(results.isNotEmpty())
      val foundResolutionLog =
        testLogger.debugLogs.any { it.contains("resolvedContent") } ||
          testLogger.debugLogs.any { it.contains("No conflicts.") }
      assertTrue(foundResolutionLog, "Expected conflict resolution attempt in debug logs")
      assertEquals(null, testBookkeeper.getLastFailedSync("key2"))
      assertIs<StoreReadResponse.Data<Note>>(results.last())
    }

  @Test
  fun stream_givenConflictResolutionFails_whenReading_thenLogsErrorButContinues() = runTest {
    // Given
    val request = StoreReadRequest.Companion.cached("key3", refresh = true)
    val errorMessage = "Conflict not resolved"

    delegateStore.write("key3", Note("localId3", "localContent3"))
    testBookkeeper.setLastFailedSync("key3")
    testUpdater.errorMessage = errorMessage

    // When
    val results = mutableStore.stream<Unit>(request).take(2).toList()

    // Then
    assertTrue(results.size >= 2)
    assertTrue(
      testLogger.errorLogs.any { (msg, _) -> msg.contains(errorMessage) },
      "Expected error logs due to conflict resolution failing",
    )
    assertNotNull(testBookkeeper.getLastFailedSync("key3"))
  }

  @Test
  fun stream_givenWriteFlowAndNoConflicts_whenCollecting_thenLocalAndNetworkAreUpdated() = runTest {
    // Given
    val requestsFlow = MutableSharedFlow<StoreWriteRequest<String, Note, Unit>>(replay = 1)

    // When
    val responsesDeferred = async { mutableStore.stream(requestsFlow).take(1).toList() }

    requestsFlow.emit(
      StoreWriteRequest.Companion.of(
        key = "writeKey1",
        value = Note("localNoteId1", "localNoteContent1"),
        created = 1111L,
        onCompletions = null,
      )
    )

    val responses = responsesDeferred.await()
    assertTrue(responses.first() is StoreWriteResponse.Success)

    // Then
    val read = delegateStore.latestOrNull("writeKey1")
    assertEquals("localNoteContent1", read?.content)
    assertEquals(null, testBookkeeper.getLastFailedSync("writeKey1"))
  }

  @Test
  fun stream_givenWriteFlowAndNetworkFailure_whenCollecting_thenLocalIsUpdatedButConflictRemains() =
    runTest {
      // Given
      val requestsFlow = MutableSharedFlow<StoreWriteRequest<String, Note, NetworkNote>>(replay = 1)
      testUpdater.errorMessage = "Network failure"

      // When
      val responsesDeferred = async { mutableStore.stream(requestsFlow).take(1).toList() }

      requestsFlow.emit(
        StoreWriteRequest.Companion.of(
          key = "writeKey2",
          value = Note("localNoteId2", "localNoteContent2"),
          created = 1111L,
          onCompletions = null,
        )
      )

      val responses = responsesDeferred.await()

      // Then
      val firstResponse = responses.first()
      assertTrue(firstResponse is StoreWriteResponse.Error.Message)
      assertTrue(firstResponse.message.contains("Network failure"))
      val read = delegateStore.latestOrNull("writeKey2")
      assertEquals("localNoteContent2", read?.content)
      assertNotNull(testBookkeeper.getLastFailedSync("writeKey2"))
    }

  @Test
  fun stream_givenMultipleWritesForSameKey_whenAllSucceed_thenOlderRequestsAreClearedFromQueue() =
    runTest {
      // Given
      val requestsFlow = MutableSharedFlow<StoreWriteRequest<String, Note, NetworkNote>>(replay = 2)
      testUpdater.successValue = NetworkNote("someNetId", "someNetContent")
      val responsesDeferred = async { mutableStore.stream(requestsFlow).take(2).toList() }

      // When
      requestsFlow.emit(
        StoreWriteRequest.Companion.of(
          key = "multiKey",
          value = Note("first", "firstContent"),
          created = 100,
          onCompletions = null,
        )
      )
      requestsFlow.emit(
        StoreWriteRequest.Companion.of(
          key = "multiKey",
          value = Note("second", "secondContent"),
          created = 200,
          onCompletions = null,
        )
      )

      // Then
      val responses = responsesDeferred.await()
      assertTrue(responses[0] is StoreWriteResponse.Success)
      assertTrue(responses[1] is StoreWriteResponse.Success)
      val read = delegateStore.latestOrNull("multiKey")
      assertEquals("secondContent", read?.content)
      assertNull(testBookkeeper.getLastFailedSync("multiKey"))
    }

  @Test
  fun write_givenSingleRequestAndNoNetworkIssues_whenCalled_thenSucceeds() = runTest {
    // Given
    val request =
      StoreWriteRequest.Companion.of<String, Note, Unit>(
        key = "singleWriteKey",
        value = Note("id", "content"),
        created = 9999L,
        onCompletions = null,
      )

    // When
    val response = mutableStore.write(request)

    // Then
    assertIs<StoreWriteResponse.Success>(response)
    assertEquals("content", delegateStore.latestOrNull("singleWriteKey")?.content)
  }

  @Test
  fun write_givenSingleRequestAndNetworkException_whenCalled_thenFailsButLocalUpdated() = runTest {
    // Given
    testUpdater.exception = IllegalStateException("Network error!")
    val request =
      StoreWriteRequest.Companion.of<String, Note, Unit>(
        key = "exceptionKey",
        value = Note("exceptionId", "contentException"),
        created = 2222L,
        onCompletions = null,
      )

    // When
    val response = mutableStore.write(request)

    // Then
    assertIs<StoreWriteResponse.Error.Exception>(response)
    assertEquals("contentException", delegateStore.latestOrNull("exceptionKey")?.content)
    assertNotNull(testBookkeeper.getLastFailedSync("exceptionKey"))
  }

  @Test
  fun clearAll_givenSomeKeys_whenCalled_thenDelegateIsCleared() = runTest {
    // Given
    delegateStore.write("clearKey1", Note("id1", "content1"))
    delegateStore.write("clearKey2", Note("id2", "content2"))
    assertNotNull(delegateStore.latestOrNull("clearKey1"))
    assertNotNull(delegateStore.latestOrNull("clearKey2"))

    // When
    mutableStore.clear()

    // Then
    assertNull(delegateStore.latestOrNull("clearKey1"))
    assertNull(delegateStore.latestOrNull("clearKey2"))
  }

  @Test
  fun clear_givenKey_whenCalled_thenDelegateIsClearedForThatKey() = runTest {
    // Given
    delegateStore.write("clearKey", Note("idCleared", "contentCleared"))
    assertNotNull(delegateStore.latestOrNull("clearKey"))

    // When
    mutableStore.clear("clearKey")

    // Then
    assertNull(delegateStore.latestOrNull("clearKey"))
  }

  @Test
  fun stream_givenNoBookkeeper_whenConflictsMightExistIsCalled_thenNoEagerResolutionIsAttempted() =
    runTest {
      // Given
      val storeNoBookkeeper =
        RealMutableStore(
          delegate = delegateStore,
          updater = testUpdater,
          bookkeeper = null,
          logger = testLogger,
        )
      delegateStore.write("keyNoBook", Note("idNoBook", "contentNoBook"))
      val request = StoreReadRequest.Companion.cached("keyNoBook", refresh = false)

      // When
      val results = storeNoBookkeeper.stream<Unit>(request).take(2).toList()

      // Then
      assertTrue(results.isNotEmpty())
      assertTrue(
        testLogger.debugLogs.none { it.contains("ConflictsResolved") },
        "No conflict resolution logs expected because no Bookkeeper",
      )
    }

  @Test
  fun write_givenKeyNotInitialized_whenCalled_thenStoreIsSafelyInitialized() = runTest {
    // Given
    val request =
      StoreWriteRequest.Companion.of<String, Note, Unit>(
        key = "newKey",
        value = Note("someId", "someContent"),
        created = 777L,
        onCompletions = null,
      )

    // When
    val response = mutableStore.write(request)

    // Then
    assertIs<StoreWriteResponse.Success>(response)
    assertEquals("someContent", delegateStore.latestOrNull("newKey")?.content)
  }
}
