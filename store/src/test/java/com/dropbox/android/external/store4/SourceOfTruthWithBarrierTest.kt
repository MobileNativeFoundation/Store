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

import com.dropbox.android.external.store4.impl.DataWithOrigin
import com.dropbox.android.external.store4.impl.PersistentSourceOfTruth
import com.dropbox.android.external.store4.impl.SourceOfTruthWithBarrier
import com.dropbox.android.external.store4.testutil.InMemoryPersister
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestCoroutineScope
import kotlinx.coroutines.test.runBlockingTest
import org.junit.Test

@FlowPreview
@ExperimentalCoroutinesApi
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
                DataWithOrigin(delegate.defaultOrigin, null),
                DataWithOrigin(ResponseOrigin.Fetcher, "a")
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
                DataWithOrigin(delegate.defaultOrigin, "a"),
                DataWithOrigin(ResponseOrigin.Fetcher, "b")
            )
        )
        assertThat(source.barrierCount()).isEqualTo(0)
    }
}
