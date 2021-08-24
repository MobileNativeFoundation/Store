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
package com.dropbox.android.external.store4

import com.dropbox.android.external.store4.SourceOfTruth.ReadException
import com.dropbox.android.external.store4.SourceOfTruth.WriteException
import com.dropbox.android.external.store4.impl.PersistentSourceOfTruth
import com.dropbox.android.external.store4.impl.SourceOfTruthWithBarrier
import com.dropbox.android.external.store4.testutil.InMemoryPersister
import com.dropbox.android.external.store4.testutil.assertThat
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestCoroutineScope
import kotlinx.coroutines.test.runBlockingTest
import org.junit.Test

class SourceOfTruthWithBarrierTest {
    private val testScope = TestCoroutineScope()
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
    fun simple() = testScope.runBlockingTest {
        val collector = async {
            source.reader(1, CompletableDeferred(Unit)).take(2).toList()
        }
        source.write(1, "a")
        assertThat(collector.await()).isEqualTo(
            listOf(
                StoreResponse.Data(
                    origin = ResponseOrigin.SourceOfTruth,
                    value = null
                ),
                StoreResponse.Data(
                    origin = ResponseOrigin.Fetcher,
                    value = "a"
                )
            )
        )
        assertThat(source.barrierCount()).isEqualTo(0)
    }

    @Test
    fun `Given a Source Of Truth WHEN delete is called THEN it is delegated to the persister`() =
        testScope.runBlockingTest {
            persister.write(1, "a")
            source.delete(1)
            assertThat(persister.read(1)).isNull()
        }

    @Test
    fun `Given a Source Of Truth WHEN deleteAll is called THEN it is delegated to the persister`() =
        testScope.runBlockingTest {
            persister.write(1, "a")
            persister.write(2, "b")
            source.deleteAll()
            assertThat(persister.read(1)).isNull()
            assertThat(persister.read(2)).isNull()
        }

    @Test
    fun preAndPostWrites() = testScope.runBlockingTest {
        source.write(1, "a")
        val collector = async {
            source.reader(1, CompletableDeferred(Unit)).take(2).toList()
        }
        source.write(1, "b")
        assertThat(collector.await()).isEqualTo(
            listOf(
                StoreResponse.Data(
                    origin = ResponseOrigin.SourceOfTruth,
                    value = "a"
                ),
                StoreResponse.Data(
                    origin = ResponseOrigin.Fetcher,
                    value = "b"
                )
            )
        )
        assertThat(source.barrierCount()).isEqualTo(0)
    }

    @Test
    fun `Given Source Of Truth WHEN read fails THEN error should propogate`() =
        testScope.runBlockingTest {
            val exception = RuntimeException("read fails")
            persister.postReadCallback = { key, value ->
                throw exception
            }
            assertThat(
                source.reader(1, CompletableDeferred(Unit))
            ).emitsExactly(
                StoreResponse.Error.Exception(
                    origin = ResponseOrigin.SourceOfTruth,
                    error = ReadException(
                        key = 1,
                        cause = exception
                    )
                )
            )
        }

    @Test
    fun `Given Source Of Truth WHEN read fails but then succeeds THEN error should propogate but also the value`() =
        testScope.runBlockingTest {
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
            assertThat(collected).containsExactly(
                StoreResponse.Error.Exception(
                    origin = ResponseOrigin.SourceOfTruth,
                    error = ReadException(
                        key = 1,
                        cause = exception
                    )
                )
            )
            // make sure it is not cancelled for the read error
            assertThat(collection.isActive).isTrue()
            // now insert another, it should trigger another read and emitted to the reader
            source.write(1, "a")
            advanceUntilIdle()
            assertThat(collected).containsExactly(
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
            )
            collection.cancelAndJoin()
        }

    @Test
    fun `Given Source Of Truth WHEN write fails THEN error should propogate`() {
        val failValue = "will fail"
        testScope.runBlockingTest {
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
            assertThat(collection.isActive).isTrue()
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
            assertThat(
                collected
            ).containsExactlyElementsIn(
                eventsUntilFailure
            )
            advanceUntilIdle()
            assertThat(collection.isActive).isTrue()
            // send another write that will succeed
            source.write(1, "succeed")
            advanceUntilIdle()
            assertThat(
                collected
            ).containsExactlyElementsIn(
                eventsUntilFailure + StoreResponse.Data<String?>(
                    origin = ResponseOrigin.Fetcher,
                    value = "succeed"
                )
            )
            collection.cancelAndJoin()
        }
    }
}
