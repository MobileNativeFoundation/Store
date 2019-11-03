package com.nytimes.android.external.store4

import com.nytimes.android.external.store3.pipeline.FlowStoreTest
import com.nytimes.android.external.store4.impl.DataWithOrigin
import com.nytimes.android.external.store4.impl.PersistentSourceOfTruth
import com.nytimes.android.external.store4.impl.SourceOfTruth
import com.nytimes.android.external.store4.impl.SourceOfTruthWithBarrier
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestCoroutineScope
import kotlinx.coroutines.test.runBlockingTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

@FlowPreview
@ExperimentalCoroutinesApi
class SourceOfTruthWithBarrierTest {
    private val testScope = TestCoroutineScope()
    private val persister = FlowStoreTest.InMemoryPersister<Int, String>()
    private val delegate: SourceOfTruth<Int, String, String> =
            PersistentSourceOfTruth(
                    realReader = { key ->
                        flow {
                            emit(persister.read(key))
                        }
                    },
                    realWriter = persister::write,
                    realDelete = null
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
