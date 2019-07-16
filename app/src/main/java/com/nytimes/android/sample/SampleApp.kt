package com.nytimes.android.sample

import android.app.Application
import android.content.Context
import com.jakewharton.retrofit2.adapter.kotlin.coroutines.CoroutineCallAdapterFactory
import com.nytimes.android.external.fs3.SourcePersisterFactory
import com.nytimes.android.external.store3.base.Persister
import com.nytimes.android.external.store3.base.impl.BarCode
import com.nytimes.android.external.store3.base.impl.MemoryPolicy
import com.nytimes.android.external.store3.base.impl.Store
import com.nytimes.android.external.store3.base.impl.StoreBuilder
import com.nytimes.android.external.store3.middleware.moshi.MoshiParserFactory
import com.nytimes.android.external.store3.pipeline.*
import com.nytimes.android.sample.data.model.RedditData
import com.nytimes.android.sample.data.remote.Api
import com.squareup.moshi.Moshi
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import okhttp3.ResponseBody
import okio.BufferedSource
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.TimeUnit

class SampleApp : Application() {

    lateinit var nonPersistedStore: Store<RedditData, BarCode>
    lateinit var persistedStore: Store<RedditData, BarCode>
    lateinit var persistentPipelineStore: Store<RedditData, BarCode>
    lateinit var nonPersistentPipielineStore : Store<RedditData, BarCode>
    val moshi = Moshi.Builder().build()
    lateinit var persister: Persister<BufferedSource, BarCode>

    @FlowPreview
    @ExperimentalCoroutinesApi
    override fun onCreate() {
        super.onCreate()
        appContext = this
        initPersister();
        nonPersistedStore = provideRedditStore();
        persistedStore = providePersistedRedditStore();
        persistentPipelineStore = providePersistentPipelineStore()
        nonPersistentPipielineStore = provideMemoryCachedPipelineStore()
    }

    private fun initPersister() {
        try {
            persister = newPersister()
        } catch (exception: IOException) {
            throw RuntimeException(exception)
        }

    }

    /**
     * Provides a Store which only retains RedditData for 10 seconds in memory.
     */
    private fun provideRedditStore(): Store<RedditData, BarCode> {
        return StoreBuilder.barcode<RedditData>()
                .fetcher { key -> provideRetrofit().fetchSubreddit(key.key, "10").await() }
                .memoryPolicy(
                        MemoryPolicy
                                .builder()
                                .setExpireAfterWrite(10)
                                .setExpireAfterTimeUnit(TimeUnit.SECONDS)
                                .build()
                )
                .open()
    }

    /**
     * Provides a Store which will persist RedditData to the cache, and use Gson to parse the JSON
     * that comes back from the network into RedditData.
     */
    private fun providePersistedRedditStore(): Store<RedditData, BarCode> {
        return StoreBuilder.parsedWithKey<BarCode, BufferedSource, RedditData>()
                .fetcher { key -> fetcher(key).await().source() }
                .persister(newPersister())
                .parser(MoshiParserFactory.createSourceParser(moshi))
                .open()
    }

    @ExperimentalCoroutinesApi
    @FlowPreview
    private fun providePersistentPipelineStore(): Store<RedditData, BarCode> {
        val persister = providePersistedPipelineStore()
        val parser = MoshiParserFactory.createSourceParser<RedditData>(moshi)
        val pipeline = beginPipeline<BarCode, BufferedSource> {
            flow {
                emit(fetcher(it).await().source())
            }
        }.withNonFlowPersister(
                reader = persister::read,
                writer = { key: BarCode, source: BufferedSource ->
                    persister.write(key, source)
                }
        ).withConverter {
            parser.apply(it)
        }
        return pipeline.open()
    }

    @ExperimentalCoroutinesApi
    @FlowPreview
    private fun provideMemoryCachedPipelineStore(): Store<RedditData, BarCode> {
        val pipeline = beginPipeline<BarCode, RedditData> {
            flow {
                emit(provideRetrofit().fetchSubreddit(it.key, "10").await())
            }
        }.withCache(MemoryPolicy
                .builder()
                .setExpireAfterWrite(10)
                .setExpireAfterTimeUnit(TimeUnit.SECONDS)
                .build())
        return pipeline.open()
    }

    /**
     * Returns a new Persister that uses [newPersister] but maps FileNotFoundExceptions to null
     */
    @Throws(IOException::class)
    private fun providePersistedPipelineStore(): Persister<BufferedSource, BarCode> {
        val delegate = newPersister()
        return object : Persister<BufferedSource, BarCode> {
            override suspend fun read(key: BarCode): BufferedSource? = withContext(Dispatchers.IO){
                return@withContext try {
                    // TODO figure out why FSReader prefers to throw instead of returning null
                    delegate.read(key)
                } catch (ex : FileNotFoundException) {
                    null
                }
            }

            override suspend fun write(key: BarCode, raw: BufferedSource): Boolean = withContext(Dispatchers.IO) {
                delegate.write(key, raw)
            }
        }
    }

    /**
     * Returns a new Persister with the cache as the root.
     */
    @Throws(IOException::class)
    private fun newPersister(): Persister<BufferedSource, BarCode> {
        return SourcePersisterFactory.create(this.cacheDir)
    }

    /**
     * Returns a "fetcher" which will retrieve new data from the network.
     */
    private fun fetcher(barCode: BarCode): Deferred<ResponseBody> {
        return provideRetrofit().fetchSubredditForPersister(barCode.key, "10")

    }

    private fun provideRetrofit(): Api {
        return Retrofit.Builder()
                .baseUrl("https://reddit.com/")
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .addCallAdapterFactory(CoroutineCallAdapterFactory())
                .validateEagerly(BuildConfig.DEBUG)  // Fail early: check Retrofit configuration at creation time in Debug build.
                .build()
                .create(Api::class.java)
    }


    companion object {
        var appContext: Context? = null
    }
}
