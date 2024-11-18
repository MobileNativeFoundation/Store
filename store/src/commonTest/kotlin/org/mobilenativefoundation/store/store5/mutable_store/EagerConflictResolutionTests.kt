package org.mobilenativefoundation.store.store5.mutable_store

import app.cash.turbine.test
import dev.mokkery.MockMode.autoUnit
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.matcher.eq
import dev.mokkery.mock
import dev.mokkery.verify
import dev.mokkery.verify.VerifyMode.Companion.exactly
import dev.mokkery.verify.VerifyMode.Companion.not
import dev.mokkery.verifySuspend
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store.store5.Bookkeeper
import org.mobilenativefoundation.store.store5.Logger
import org.mobilenativefoundation.store.store5.StoreReadRequest
import org.mobilenativefoundation.store.store5.StoreReadResponse
import org.mobilenativefoundation.store.store5.StoreReadResponseOrigin
import org.mobilenativefoundation.store.store5.Updater
import org.mobilenativefoundation.store.store5.UpdaterResult
import org.mobilenativefoundation.store.store5.impl.RealMutableStore
import org.mobilenativefoundation.store.store5.impl.RealStore
import org.mobilenativefoundation.store.store5.test_utils.model.Note
import kotlin.test.Test
import kotlin.test.assertEquals

class EagerConflictResolutionTests {
    private val testScope = TestScope()

    private val delegate = mock<RealStore<String, Note, Note, Note>>(autoUnit)
    private val updater = mock<Updater<String, Note, Boolean>>(autoUnit)
    private val bookkeeper = mock<Bookkeeper<String>>(autoUnit)
    private val logger = mock<Logger>(autoUnit)


    private val mutableStore = RealMutableStore(
        delegate,
        updater,
        bookkeeper,
        logger
    )

    @Test
    fun stream_givenConflicts_whenExceptionResolvingConflicts_thenShouldLog() = testScope.runTest {
        // Given
        val latestNote = Note("id", "Title", "Content")
        val readResponse = StoreReadResponse.Data(latestNote, StoreReadResponseOrigin.Cache)
        val delegateFlow = flowOf(readResponse)
        val exception = Exception("Error updating network.")
        val readRequest = StoreReadRequest.fresh("id")

        every {
            delegate.stream(any())
        } returns delegateFlow

        everySuspend {
            delegate.latestOrNull(any())
        } returns latestNote

        everySuspend {
            bookkeeper.getLastFailedSync(any())
        } returns 1L

        everySuspend {
            updater.post(any(), any())
        } returns UpdaterResult.Error.Exception(exception)


        // When
        val stream = mutableStore.stream<Boolean>(
            readRequest
        )

        // Then

        stream.test {

            verifySuspend(exactly(1)) {
                updater.post(eq("id"), eq(latestNote))
            }

            verifySuspend(not) {
                bookkeeper.clear(eq("id"))
            }

            verify(exactly(1)) {
                logger.error(eq(exception.toString()))
            }

            verify(exactly(1)) {
                delegate.stream(eq(readRequest))
            }

            assertEquals(readResponse, awaitItem())

            awaitComplete()
        }

    }

    @Test
    fun stream_givenConflicts_whenErrorMessageResolvingConflicts_thenShouldLog() = testScope.runTest {
        // Given
        val latestNote = Note("id", "Title", "Content")
        val readResponse = StoreReadResponse.Data(latestNote, StoreReadResponseOrigin.Cache)
        val delegateFlow = flowOf(readResponse)
        val errorMessage = "Error updating network."
        val readRequest = StoreReadRequest.fresh("id")

        every {
            delegate.stream(any())
        } returns delegateFlow

        everySuspend {
            delegate.latestOrNull(any())
        } returns latestNote

        everySuspend {
            bookkeeper.getLastFailedSync(any())
        } returns 1L

        everySuspend {
            updater.post(any(), any())
        } returns UpdaterResult.Error.Message(errorMessage)


        // When
        val stream = mutableStore.stream<Boolean>(
            readRequest
        )

        // Then

        stream.test {

            verifySuspend(exactly(1)) {
                updater.post(eq("id"), eq(latestNote))
            }

            verifySuspend(not) {
                bookkeeper.clear(eq("id"))
            }

            verify(exactly(1)) {
                logger.error(eq(errorMessage))
            }

            verify(exactly(1)) {
                delegate.stream(eq(readRequest))
            }

            assertEquals(readResponse, awaitItem())

            awaitComplete()
        }

    }

    @Test
    fun stream_givenNoConflicts_whenCalled_thenShouldLog() = testScope.runTest {
        // Given
        val latestNote = Note("id", "Title", "Content")
        val readResponse = StoreReadResponse.Data(latestNote, StoreReadResponseOrigin.Cache)
        val delegateFlow = flowOf(readResponse)
        val readRequest = StoreReadRequest.fresh("id")

        every {
            delegate.stream(any())
        } returns delegateFlow

        everySuspend {
            delegate.latestOrNull(any())
        } returns latestNote

        everySuspend {
            bookkeeper.getLastFailedSync(any())
        } returns null

        everySuspend {
            updater.post(any(), any())
        } returns UpdaterResult.Success.Typed(true)

        every {
            updater.onCompletion
        } returns null

        everySuspend {
            bookkeeper.clear(any())
        } returns true


        // When
        val stream = mutableStore.stream<Boolean>(
            readRequest
        )

        // Then

        stream.test {

            verifySuspend(not) {
                updater.post(eq("id"), eq(latestNote))
            }

            verify(exactly(1)) {
                logger.debug(eq("No conflicts."))
            }

            verifySuspend(not) {
                bookkeeper.clear(eq("id"))
            }

            verify(exactly(1)) {
                delegate.stream(eq(readRequest))
            }

            assertEquals(readResponse, awaitItem())

            awaitComplete()
        }

    }

    @Test
    fun stream_givenConflicts_whenSuccessResolvingConflicts_thenShouldLog() = testScope.runTest {
        // Given
        val latestNote = Note("id", "Title", "Content")
        val readResponse = StoreReadResponse.Data(latestNote, StoreReadResponseOrigin.Cache)
        val delegateFlow = flowOf(readResponse)
        val readRequest = StoreReadRequest.fresh("id")

        every {
            delegate.stream(any())
        } returns delegateFlow

        everySuspend {
            delegate.latestOrNull(any())
        } returns latestNote

        everySuspend {
            bookkeeper.getLastFailedSync(any())
        } returns 1L

        everySuspend {
            updater.post(any(), any())
        } returns UpdaterResult.Success.Typed(true)

        every {
            updater.onCompletion
        } returns null

        everySuspend {
            bookkeeper.clear(any())
        } returns true


        // When
        val stream = mutableStore.stream<Boolean>(
            readRequest
        )

        // Then

        stream.test {

            verifySuspend(exactly(1)) {
                updater.post(eq("id"), eq(latestNote))
            }

            verify(exactly(1)) {
                logger.debug(eq("true"))
            }

            verifySuspend(exactly(1)) {
                bookkeeper.clear(eq("id"))
            }

            verify(exactly(1)) {
                delegate.stream(eq(readRequest))
            }

            assertEquals(readResponse, awaitItem())

            awaitComplete()
        }

    }


}