/*
 * Copyright 2020 Google LLC
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
import com.dropbox.android.external.store4.testutil.FakeFetcher
import com.dropbox.android.external.store4.testutil.InMemoryPersister
import com.dropbox.android.external.store4.testutil.asSourceOfTruth
import com.dropbox.android.external.store4.testutil.assertThat
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScope
import kotlinx.coroutines.test.runBlockingTest
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import kotlin.time.ExperimentalTime

@ExperimentalTime
@RunWith(JUnit4::class)
class SourceOfTruthErrorsTest {
    private val testScope = TestCoroutineScope()

    @Test
    fun `GIVEN Source of Truth WHEN write fails THEN exception should be send to the collector`() =
        testScope.runBlockingTest {
            val persister = InMemoryPersister<Int, String>()
            val fetcher = FakeFetcher(
                3 to "a",
                3 to "b"
            )
            val pipeline = StoreBuilder
                .from(
                    fetcher = fetcher,
                    sourceOfTruth = persister.asSourceOfTruth()
                )
                .scope(testScope)
                .build()
            persister.preWriteCallback = { _, _ ->
                throw TestException("i fail")
            }

            assertThat(
                pipeline.stream(StoreRequest.fresh(3))
            ).emitsExactly(
                StoreResponse.Loading(ResponseOrigin.Fetcher),
                StoreResponse.Error.Exception(
                    error = WriteException(
                        key = 3,
                        value = "a",
                        cause = TestException("i fail")
                    ),
                    origin = ResponseOrigin.SourceOfTruth
                )
            )
        }

    @Test
    fun `GIVEN Source of Truth WHEN read fails THEN exception should be send to the collector`() =
        testScope.runBlockingTest {
            val persister = InMemoryPersister<Int, String>()
            val fetcher = FakeFetcher(
                3 to "a",
                3 to "b"
            )
            val pipeline = StoreBuilder
                .from(
                    fetcher = fetcher,
                    sourceOfTruth = persister.asSourceOfTruth()
                )
                .scope(testScope)
                .build()

            persister.postReadCallback = { _, value ->
                throw TestException(value ?: "null")
            }

            assertThat(
                pipeline.stream(StoreRequest.cached(3, refresh = false))
            ).emitsExactly(
                StoreResponse.Error.Exception(
                    error = ReadException(
                        key = 3,
                        cause = TestException("null")
                    ),
                    origin = ResponseOrigin.SourceOfTruth
                ),
                // after disk fails, we should still invoke fetcher
                StoreResponse.Loading(
                    origin = ResponseOrigin.Fetcher
                ),
                // and after fetcher writes the value, it will trigger another read which will also
                // fail
                StoreResponse.Error.Exception(
                    error = ReadException(
                        key = 3,
                        cause = TestException("a")
                    ),
                    origin = ResponseOrigin.SourceOfTruth
                )
            )
        }

    @Test
    fun `GIVEN Source of Truth WHEN first write fails THEN it should keep reading from Fetcher`() =
        testScope.runBlockingTest {
            val persister = InMemoryPersister<Int, String>()
            val fetcher = Fetcher.ofFlow { _: Int ->
                flowOf("a", "b", "c", "d")
            }
            val pipeline = StoreBuilder
                .from(
                    fetcher = fetcher,
                    sourceOfTruth = persister.asSourceOfTruth()
                )
                .disableCache()
                .scope(testScope)
                .build()
            persister.preWriteCallback = { _, value ->
                if (value in listOf("a", "c")) {
                    throw TestException(value)
                }
                value
            }
            assertThat(
                pipeline.stream(StoreRequest.cached(3, refresh = true))
            ).emitsExactly(
                StoreResponse.Loading(
                    origin = ResponseOrigin.Fetcher
                ),
                StoreResponse.Error.Exception(
                    error = WriteException(
                        key = 3,
                        value = "a",
                        cause = TestException("a")
                    ),
                    origin = ResponseOrigin.SourceOfTruth
                ),
                StoreResponse.Data(
                    value = "b",
                    origin = ResponseOrigin.Fetcher
                ),
                StoreResponse.Error.Exception(
                    error = WriteException(
                        key = 3,
                        value = "c",
                        cause = TestException("c")
                    ),
                    origin = ResponseOrigin.SourceOfTruth
                ),
                // disk flow will restart after a failed write (because we stopped it before the
                // write attempt starts, so we will get the disk value again).
                StoreResponse.Data(
                    value = "b",
                    origin = ResponseOrigin.SourceOfTruth
                ),
                StoreResponse.Data(
                    value = "d",
                    origin = ResponseOrigin.Fetcher
                )
            )
        }

    @Test
    fun `GIVEN Source of Truth with failing write WHEN a passive reader arrives THEN it should receive the new write error`() =
        testScope.runBlockingTest {
            val persister = InMemoryPersister<Int, String>()
            val fetcher = Fetcher.ofFlow { _: Int ->
                flowOf("a", "b", "c", "d")
            }
            val pipeline = StoreBuilder
                .from(
                    fetcher = fetcher,
                    sourceOfTruth = persister.asSourceOfTruth()
                )
                .disableCache()
                .scope(testScope)
                .build()
            persister.preWriteCallback = { _, value ->
                if (value in listOf("a", "c")) {
                    delay(50)
                    throw TestException(value)
                } else {
                    delay(10)
                }
                value
            }
            // keep collection hot
            val collector = launch {
                pipeline.stream(
                    StoreRequest.cached(3, refresh = true)
                ).toList()
            }

            // miss writes for a and b and let the write operation for c start such that
            // we'll catch that write error
            delay(70)
            assertThat(
                pipeline.stream(StoreRequest.cached(3, refresh = true))
            ).emitsExactly(
                // we wanted the disk value but write failed so we don't get it
                StoreResponse.Error.Exception(
                    error = WriteException(
                        key = 3,
                        value = "c",
                        cause = TestException("c")
                    ),
                    origin = ResponseOrigin.SourceOfTruth
                ),
                // after the write error, we should get the value on disk
                StoreResponse.Data(
                    value = "b",
                    origin = ResponseOrigin.SourceOfTruth
                ),
                // now we'll unlock the fetcher after disk is read
                StoreResponse.Loading(
                    origin = ResponseOrigin.Fetcher
                ),
                StoreResponse.Data(
                    value = "d",
                    origin = ResponseOrigin.Fetcher
                )
            )
            collector.cancelAndJoin()
        }

    @Test
    fun `Given Source of Truth with failing write WHEN a passive reader arrives THEN it should not get errors happened before`() =
        testScope.runBlockingTest {
            val persister = InMemoryPersister<Int, String>()
            val fetcher = Fetcher.ofFlow<Int, String> {
                flow {
                    emit("a")
                    emit("b")
                    emit("c")
                    // now delay, wait for the new subscriber
                    delay(100)
                    emit("d")
                }
            }
            val pipeline = StoreBuilder
                .from(
                    fetcher = fetcher,
                    sourceOfTruth = persister.asSourceOfTruth()
                )
                .disableCache()
                .scope(testScope)
                .build()
            persister.preWriteCallback = { _, value ->
                if (value in listOf("a", "c")) {
                    throw TestException(value)
                }
                value
            }
            val collector = launch {
                pipeline.stream(
                    StoreRequest.cached(3, refresh = true)
                ).toList() // keep collection hot
            }

            // miss both failures but arrive before d is fetched
            delay(70)
            assertThat(
                pipeline.stream(StoreRequest.skipMemory(3, refresh = true))
            ).emitsExactly(
                StoreResponse.Data(
                    value = "b",
                    origin = ResponseOrigin.SourceOfTruth
                ),
                // don't receive the write exception because technically it started before we
                // started reading
                StoreResponse.Loading(
                    origin = ResponseOrigin.Fetcher
                ),
                StoreResponse.Data(
                    value = "d",
                    origin = ResponseOrigin.Fetcher
                )
            )
            collector.cancelAndJoin()
        }

    @Test
    fun `Given Source of Truth with failing write WHEN a fresh value reader arrives THEN it should not get disk errors from a pending write`() =
        testScope.runBlockingTest {
            val persister = InMemoryPersister<Int, String>()
            val fetcher = Fetcher.ofFlow<Int, String> {
                flowOf("a", "b", "c", "d")
            }
            val pipeline = StoreBuilder
                .from(
                    fetcher = fetcher,
                    sourceOfTruth = persister.asSourceOfTruth()
                )
                .disableCache()
                .scope(testScope)
                .build()
            persister.preWriteCallback = { _, value ->
                if (value == "c") {
                    // slow down read so that the new reader arrives
                    delay(50)
                }
                if (value in listOf("a", "c")) {
                    throw TestException(value)
                }
                value
            }
            val collector = launch {
                pipeline.stream(
                    StoreRequest.cached(3, refresh = true)
                ).toList() // keep collection hot
            }
            // miss both failures but arrive before d is fetched
            delay(20)
            assertThat(
                pipeline.stream(StoreRequest.fresh(3))
            ).emitsExactly(
                StoreResponse.Loading(
                    origin = ResponseOrigin.Fetcher
                ),
                StoreResponse.Data(
                    value = "d",
                    origin = ResponseOrigin.Fetcher
                )
            )
            collector.cancelAndJoin()
        }

    @Test
    fun `Given Source of Truth with read failure WHEN cached value reader arrives THEN fetcher should be called to get a new value`() {
        testScope.runBlockingTest {
            val persister = InMemoryPersister<Int, String>()
            val fetcher = Fetcher.of { _: Int -> "a" }
            val pipeline = StoreBuilder
                .from(
                    fetcher = fetcher,
                    sourceOfTruth = persister.asSourceOfTruth()
                )
                .disableCache()
                .scope(testScope)
                .build()
            persister.postReadCallback = { _, value ->
                if (value == null) {
                    throw TestException("first read")
                }
                value
            }
            assertThat(
                pipeline.stream(StoreRequest.cached(3, refresh = true))
            ).emitsExactly(
                StoreResponse.Error.Exception(
                    origin = ResponseOrigin.SourceOfTruth,
                    error = ReadException(
                        key = 3,
                        cause = TestException("first read")
                    )
                ),
                StoreResponse.Loading(
                    origin = ResponseOrigin.Fetcher
                ),
                StoreResponse.Data(
                    value = "a",
                    origin = ResponseOrigin.Fetcher
                )
            )
        }
    }

    private class TestException(val msg: String) : Exception(msg) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is TestException) return false
            return msg == other.msg
        }

        override fun hashCode(): Int {
            return msg.hashCode()
        }
    }
}
