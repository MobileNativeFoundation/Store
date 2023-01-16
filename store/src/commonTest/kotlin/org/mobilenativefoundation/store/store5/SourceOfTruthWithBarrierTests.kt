/*
 * Copyright 2019 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.mobilenativefoundation.store.store5

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store.store5.SourceOfTruth.ReadException
import org.mobilenativefoundation.store.store5.SourceOfTruth.WriteException
import org.mobilenativefoundation.store.store5.impl.PersistentSourceOfTruth
import org.mobilenativefoundation.store.store5.impl.SourceOfTruthWithBarrier
import org.mobilenativefoundation.store.store5.util.InMemoryPersister
import org.mobilenativefoundation.store.store5.util.assertEmitsExactly
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@FlowPreview
@ExperimentalCoroutinesApi
class SourceOfTruthWithBarrierTests {
    private val testScope = TestScope()
    private val persister = InMemoryPersister<Int, String>()
    private val delegate: SourceOfTruth<Int, String, String> =
        PersistentSourceOfTruth(
            realReader = { key ->
                flow {
                    emit(persister.read(key))
                }
            },
            realWriter = persister::write,
            realDelete = persister::deleteByKey,
            realDeleteAll = persister::deleteAll
        )
    private val source = SourceOfTruthWithBarrier(
        delegate = delegate
    )

    @Test
    fun simple() = testScope.runTest {
        val collection = mutableListOf<StoreResponse<String?>>()

        launch {
            source.reader(1, CompletableDeferred(Unit)).take(2).collect {
                collection.add(it)
            }
        }
        delay(500)
        source.write(1, "a")
        advanceUntilIdle()
        assertEquals(
            listOf<StoreResponse<String?>>(
                StoreResponse.Data(
                    origin = ResponseOrigin.SourceOfTruth,
                    value = null
                ),
                StoreResponse.Data(
                    origin = ResponseOrigin.Fetcher,
                    value = "a"
                )
            ),
            collection
        )
        assertEquals(0, source.barrierCount())
    }

    @Test
    fun givenASourceOfTruthWhenDeleteIsCalledThenItIsDelegatedToThePersister() = testScope.runTest {
        persister.write(1, "a")
        source.delete(1)
        assertNull(persister.read(1))
    }

    @Test
    fun givenASourceOfTruthWhenDeleteAllIsCalledThenItIsDelegatedToThePersister() = testScope.runTest {
        persister.write(1, "a")
        persister.write(2, "b")
        source.deleteAll()
        assertNull(persister.read(1))
        assertNull(persister.read(2))
    }

    @Test
    fun preAndPostWrites() = testScope.runTest {
        val collection = mutableListOf<StoreResponse<String?>>()
        source.write(1, "a")

        launch {
            source.reader(1, CompletableDeferred(Unit)).take(2).collect {
                collection.add(it)
            }
        }

        delay(200)

        source.write(1, "b")

        advanceUntilIdle()

        assertEquals(
            listOf<StoreResponse<String?>>(
                StoreResponse.Data(
                    origin = ResponseOrigin.SourceOfTruth,
                    value = "a"
                ),
                StoreResponse.Data(
                    origin = ResponseOrigin.Fetcher,
                    value = "b"
                )
            ),
            collection
        )

        assertEquals(0, source.barrierCount())
    }

    @Test
    fun givenSourceOfTruthWhenReadFailsThenErrorShouldPropagate() = testScope.runTest {
        val exception = RuntimeException("read fails")
        persister.postReadCallback = { key, value ->
            throw exception
        }
        assertEmitsExactly(
            source.reader(1, CompletableDeferred(Unit)),
            listOf(
                StoreResponse.Error.Exception(
                    origin = ResponseOrigin.SourceOfTruth,
                    error = ReadException(
                        key = 1,
                        cause = exception
                    )
                )
            )
        )
    }

    @Test
    fun givenSourceOfTruthWhenReadFailsButThenSucceedsThenErrorShouldPropagateButAlsoTheValue() = testScope.runTest {
        var hasThrown = false
        val exception = RuntimeException("read fails")
        persister.postReadCallback = { _, value ->
            if (!hasThrown) {
                hasThrown = true
                throw exception
            }
            value
        }
        val reader = source.reader(1, CompletableDeferred(Unit))
        val collected = mutableListOf<StoreResponse<String?>>()
        val collection = async {
            reader.collect {
                collected.add(it)
            }
        }
        advanceUntilIdle()
        assertEquals(
            StoreResponse.Error.Exception(
                origin = ResponseOrigin.SourceOfTruth,
                error = ReadException(
                    key = 1,
                    cause = exception
                )
            ),
            collected.first()
        )
        // make sure it is not cancelled for the read error
        assertEquals(true, collection.isActive)
        // now insert another, it should trigger another read and emitted to the reader
        source.write(1, "a")
        advanceUntilIdle()
        assertEquals(
            listOf<StoreResponse<String?>>(
                StoreResponse.Error.Exception(
                    origin = ResponseOrigin.SourceOfTruth,
                    error = ReadException(
                        key = 1,
                        cause = exception
                    )
                ),
                StoreResponse.Data(
                    // this is fetcher since we are using the write API
                    origin = ResponseOrigin.Fetcher,
                    value = "a"
                )
            ),
            collected
        )
        collection.cancelAndJoin()
    }

    @Test
    fun givenSourceOfTruthWhenWriteFailsThenErrorShouldPropagate() {
        val failValue = "will fail"
        testScope.runTest {
            val exception = RuntimeException("write fails")
            persister.preWriteCallback = { key, value ->
                if (value == failValue) {
                    throw exception
                }
                value
            }
            val reader = source.reader(1, CompletableDeferred(Unit))
            val collected = mutableListOf<StoreResponse<String?>>()
            val collection = async {
                reader.collect {
                    collected.add(it)
                }
            }
            advanceUntilIdle()
            source.write(1, failValue)
            advanceUntilIdle()
            // make sure collection does not cancel for a write error
            assertEquals(true, collection.isActive)
            val eventsUntilFailure = listOf(
                StoreResponse.Data<String?>(
                    origin = ResponseOrigin.SourceOfTruth,
                    value = null
                ),
                StoreResponse.Error.Exception(
                    origin = ResponseOrigin.SourceOfTruth,
                    error = WriteException(
                        key = 1,
                        value = failValue,
                        cause = exception
                    )
                ),
                StoreResponse.Data<String?>(
                    origin = ResponseOrigin.SourceOfTruth,
                    value = null
                )
            )
            assertEquals(eventsUntilFailure, collected)
            advanceUntilIdle()
            assertEquals(true, collection.isActive)
            // send another write that will succeed
            source.write(1, "succeed")
            advanceUntilIdle()
            assertEquals(
                eventsUntilFailure + StoreResponse.Data<String?>(
                    origin = ResponseOrigin.Fetcher,
                    value = "succeed"
                ),
                collected
            )
            collection.cancelAndJoin()
        }
    }
}
