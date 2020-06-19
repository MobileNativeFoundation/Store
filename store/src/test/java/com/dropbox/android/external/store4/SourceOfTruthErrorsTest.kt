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

import com.dropbox.android.external.store4.testutil.FakeFetcher
import com.dropbox.android.external.store4.testutil.InMemoryPersister
import com.dropbox.android.external.store4.testutil.asSourceOfTruth
import com.dropbox.android.external.store4.testutil.assertThat
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.test.TestCoroutineScope
import kotlinx.coroutines.test.runBlockingTest
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@FlowPreview
@RunWith(JUnit4::class)
class SourceOfTruthErrorsTest {
    private val testScope = TestCoroutineScope()

    @Test
    fun writeErrorCanBeCaught() = testScope.runBlockingTest {
        val persister = ThrowingPersister<Int, String>()
        val fetcher = FakeFetcher(
            3 to "three-1",
            3 to "three-2"
        )
        val pipeline = StoreBuilder
            .from(
                fetcher = fetcher,
                sourceOfTruth = persister.asSourceOfTruth())
            .scope(testScope)
            .build()
        val writeException = IllegalArgumentException("i fail")
        persister.writeHandler = { key, value ->
            throw writeException
        }
        assertThat(
            pipeline.stream(StoreRequest.fresh(3))
        ).emitsExactly(
            StoreResponse.Loading(ResponseOrigin.Fetcher),
            StoreResponse.Error.Exception(
                error = writeException,
                origin = ResponseOrigin.SourceOfTruth
            )
        )
    }

    class ThrowingPersister<Key:Any, Output:Any> : InMemoryPersister<Key, Output>() {
        var writeHandler : ((Key, Output) -> Unit)?=null
        override suspend fun write(key: Key, output: Output) {
            writeHandler?.invoke(key, output) ?: super.write(key, output)
        }
    }
}