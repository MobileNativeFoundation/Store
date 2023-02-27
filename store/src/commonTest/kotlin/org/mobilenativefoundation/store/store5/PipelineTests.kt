package org.mobilenativefoundation.store.store5

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store.store5.impl.extensions.inHours
import org.mobilenativefoundation.store.store5.impl.extensions.inMinutes
import org.mobilenativefoundation.store.store5.impl.extensions.now
import org.mobilenativefoundation.store.store5.util.assertEmitsExactly
import org.mobilenativefoundation.store.store5.util.fake.OfflineFileCountApi
import org.mobilenativefoundation.store.store5.util.fake.OfflineFileCountDataStore
import org.mobilenativefoundation.store.store5.util.fake.OfflineFilesStatusApi
import org.mobilenativefoundation.store.store5.util.fake.OfflineFilesStatusDataStore
import org.mobilenativefoundation.store.store5.util.fake.ProcessedSettingDatabase
import org.mobilenativefoundation.store.store5.util.fake.SettingApi
import org.mobilenativefoundation.store.store5.util.fake.Settings
import org.mobilenativefoundation.store.store5.util.fake.UnprocessedSettingDatabase
import org.mobilenativefoundation.store.store5.util.model.Perishable
import org.mobilenativefoundation.store.store5.util.model.Setting
import org.mobilenativefoundation.store.store5.util.model.SettingVariable
import kotlin.test.BeforeTest
import kotlin.test.Test

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalStoreApi::class)
class PipelineTests {
    private val testScope = TestScope()
    private lateinit var settingApi: SettingApi
    private lateinit var offlineFileCountApi: OfflineFileCountApi
    private lateinit var offlineFilesStatusApi: OfflineFilesStatusApi
    private lateinit var unprocessedSettingDatabase: UnprocessedSettingDatabase
    private lateinit var offlineFileCountDataStore: OfflineFileCountDataStore
    private lateinit var offlineFilesStatusDataStore: OfflineFilesStatusDataStore
    private lateinit var processedSettingDatabase: ProcessedSettingDatabase

    @BeforeTest
    fun before() {
        settingApi = SettingApi()
        offlineFileCountApi = OfflineFileCountApi()
        offlineFilesStatusApi = OfflineFilesStatusApi()
        unprocessedSettingDatabase = UnprocessedSettingDatabase()
        offlineFileCountDataStore = OfflineFileCountDataStore()
        offlineFilesStatusDataStore = OfflineFilesStatusDataStore()
        processedSettingDatabase = ProcessedSettingDatabase()
    }

    @Test
    fun givenPipelineWhenFreshThenProcessed() = testScope.runTest {

        val templateTTL = inHours(1)
        val valuesTTL = inMinutes(5)

        val unprocessedSettingStore = StoreBuilder.from<String, Setting.Unprocessed, Setting.Unprocessed, Setting.Unprocessed>(
            fetcher = Fetcher.of { key -> settingApi.get(key, false, templateTTL) },
            sourceOfTruth = SourceOfTruth.of(
                nonFlowReader = { key -> unprocessedSettingDatabase.get(key) },
                writer = { key, setting -> unprocessedSettingDatabase.put(key, setting) }
            )
        ).build()

        val offlineFileCountStore = StoreBuilder.from<String, Perishable<Int>, Perishable<Int>, Perishable<Int>>(
            fetcher = Fetcher.of { key -> offlineFileCountApi.get(key, false, valuesTTL) },
            sourceOfTruth = SourceOfTruth.of(
                nonFlowReader = { key -> offlineFileCountDataStore.get(key) },
                writer = { key, metadata -> offlineFileCountDataStore.put(key, metadata) }
            )
        ).build()

        val offlineFilesStatusStore = StoreBuilder.from<String, Perishable<String>, Perishable<String>, Perishable<String>>(
            fetcher = Fetcher.of { key -> offlineFilesStatusApi.get(key, false, valuesTTL) },
            sourceOfTruth = SourceOfTruth.of(
                nonFlowReader = { key -> offlineFilesStatusDataStore.get(key) },
                writer = { key, metadata -> offlineFilesStatusDataStore.put(key, metadata) }
            )
        ).build()

        val processedSettingStore = StoreBuilder.from<String, Setting.Processed, Setting.Processed, Setting.Processed>(
            fetcher = Fetcher.of { key ->
                val request = StoreReadRequest.cached(key, false)
                val unprocessed = unprocessedSettingStore.stream(request).first { it.dataOrNull() != null }.requireData()
                Processor(
                    unprocessed = unprocessed,
                    userId = key,
                    statusStore = offlineFilesStatusStore,
                    countStore = offlineFileCountStore
                )
            },
            sourceOfTruth = SourceOfTruth.of(
                nonFlowReader = { key -> processedSettingDatabase.get(key) },
                writer = { key, setting ->
                    processedSettingDatabase.put(key, setting)
                }
            )
        ).validator(SettingValidator())
            .build()

        val request = StoreReadRequest.fresh(Settings.Tag.ID)

        val stream = processedSettingStore.stream(request)

        assertEmitsExactly(
            stream,
            listOf(
                StoreReadResponse.Loading(origin = StoreReadResponseOrigin.Fetcher),
                StoreReadResponse.Data(Settings.Tag.OfflineFiles.Processed.copy(ttl = valuesTTL), origin = StoreReadResponseOrigin.Fetcher)
            )
        )
    }
}

@Suppress("TestFunctionName")
suspend fun Processor(
    unprocessed: Setting.Unprocessed,
    userId: String,
    statusStore: Store<String, Perishable<String>>,
    countStore: Store<String, Perishable<Int>>
): Setting.Processed {
    val title = process(unprocessed.title, userId, statusStore, countStore)
    val subtitle = process(unprocessed.subtitle, userId, statusStore, countStore)
    val label = process(unprocessed.label, userId, statusStore, countStore)
    val status = process(unprocessed.status, userId, statusStore, countStore)

    val ttl = minOf(title.ttl, subtitle.ttl, label.ttl, status.ttl)

    return Setting.Processed(
        id = unprocessed.id,
        title = title.value,
        subtitle = subtitle.value,
        label = label.value,
        status = status.value,
        ttl = ttl
    )
}

private const val SPACE = " "

private suspend fun process(
    unprocessed: String,
    userId: String,
    statusStore: Store<String, Perishable<String>>,
    countStore: Store<String, Perishable<Int>>
): Perishable<String> {
    var ttl: Long = Long.MAX_VALUE

    val value = unprocessed.split(SPACE).map { word ->

        val request = StoreReadRequest.cached(userId, false)
        when (SettingVariable.lookup(word)) {
            SettingVariable.OfflineFilesStatus -> {
                val status = statusStore.stream(request).first { it.dataOrNull() != null }.requireData()
                ttl = minOf(status.ttl, ttl)
                status.value
            }

            SettingVariable.OfflineFileCount -> {
                val count = countStore.stream(request).first { it.dataOrNull() != null }.requireData()
                ttl = minOf(count.ttl, ttl)
                count.value.toString()
            }

            null -> word
        }
    }.joinToString(SPACE)

    return Perishable(value, ttl)
}

internal class SettingValidator(private val expiration: Long = now()) : Validator<Setting.Processed> {
    override suspend fun isValid(item: Setting.Processed): Boolean = when (item.ttl) {
        null -> true
        else -> item.ttl > expiration
    }
}
