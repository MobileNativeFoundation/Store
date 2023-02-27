package org.mobilenativefoundation.store.store5.stateful_store

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store.store5.ExperimentalStoreApi
import org.mobilenativefoundation.store.store5.Fetcher
import org.mobilenativefoundation.store.store5.SourceOfTruth
import org.mobilenativefoundation.store.store5.StatefulStoreBuilder
import org.mobilenativefoundation.store.store5.StoreReadRequest
import org.mobilenativefoundation.store.store5.StoreReadResponse
import org.mobilenativefoundation.store.store5.StoreReadResponseOrigin
import org.mobilenativefoundation.store.store5.impl.extensions.inHours
import org.mobilenativefoundation.store.store5.impl.extensions.inMinutes
import org.mobilenativefoundation.store.store5.util.assertEmitsExactly
import org.mobilenativefoundation.store.store5.util.fake.CampaignApi
import org.mobilenativefoundation.store.store5.util.fake.CampaignDatabase
import org.mobilenativefoundation.store.store5.util.fake.CampaignValidator
import org.mobilenativefoundation.store.store5.util.fake.campaignProcessor
import org.mobilenativefoundation.store.store5.util.model.Campaign
import org.mobilenativefoundation.store.store5.util.model.CampaignKey
import kotlin.test.BeforeTest
import kotlin.test.Test

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalStoreApi::class)
class StatefulStoreWithSourceOfTruthTests {
    private val testScope = TestScope()
    private lateinit var api: CampaignApi
    private lateinit var database: CampaignDatabase

    @BeforeTest
    fun before() {
        api = CampaignApi()
        database = CampaignDatabase()
    }

    @Test
    fun givenEmptyStatefulStoreWhenFreshThenFetchAndProcess() = testScope.runTest {
        val templateTTL = inHours(1)
        val valuesTTL = inMinutes(5)

        val validator = CampaignValidator()

        val store = StatefulStoreBuilder.from<CampaignKey, Campaign, Campaign, Campaign>(
            fetcher = Fetcher.of { key -> api.get(key, false, templateTTL) },
            sourceOfTruth = SourceOfTruth.of(
                nonFlowReader = { key -> database.get(key) },
                writer = { key, campaign -> database.put(key, campaign) }
            ),
        ).validator(validator).build(
            processor = { campaign ->
                when (campaign) {
                    is Campaign.Processed -> campaign
                    is Campaign.Unprocessed -> campaignProcessor(campaign, valuesTTL)
                }
            }
        )

        val key = CampaignKey("1")
        val readRequest = StoreReadRequest.fresh(key)
        val stream = store.stream(readRequest)

        assertEmitsExactly(
            stream,
            listOf(
                StoreReadResponse.Loading(origin = StoreReadResponseOrigin.Fetcher),
                StoreReadResponse.Data(Campaigns.One.Processed.copy(ttl = valuesTTL), origin = StoreReadResponseOrigin.Fetcher)
            )
        )
    }
}

object Campaigns {

    object One {
        val Unprocessed = Campaign.Unprocessed("1", "Dropbox \${PLAN} for \${PRICE}")
        val Processed = Campaign.Processed("1", "Dropbox Plus for $11.99")
    }
}
