package com.nytimes.android.external.store3.base.impl


import com.nytimes.android.external.store3.base.DiskRead
import com.nytimes.android.external.store3.base.DiskWrite
import com.nytimes.android.external.store3.base.Fetcher
import com.nytimes.android.external.store3.base.Parser
import com.nytimes.android.external.store3.base.Persister
import com.nytimes.android.external.store3.base.wrappers.cache
import com.nytimes.android.external.store3.base.wrappers.parser
import com.nytimes.android.external.store3.base.wrappers.persister
import com.nytimes.android.external.store3.util.KeyParser
import com.nytimes.android.external.store3.util.NoKeyParser
import java.util.ArrayList


/**
 * Builder where there parser is used.
 */
class RealStoreBuilder<Raw, Parsed, Key> {
    private val parsers = ArrayList<KeyParser<Any?, Any?, Any?>>()
    private var persister: Persister<Raw, Key>? = null
    private var fetcher: Fetcher<Raw, Key>? = null
    private var memoryPolicy: MemoryPolicy? = null

    private//remove when it is implemented...
    var stalePolicy = StalePolicy.UNSPECIFIED

    fun fetcher(fetcher: Fetcher<Raw, Key>): RealStoreBuilder<Raw, Parsed, Key> = apply {
        this.fetcher = fetcher
    }

    fun fetcher(fetcher: suspend (Key) -> Raw): RealStoreBuilder<Raw, Parsed, Key> = apply {
        this.fetcher = object : Fetcher<Raw, Key> {
            override suspend fun fetch(key: Key): Raw {
                return fetcher(key)
            }
        }
    }

    fun persister(persister: Persister<Raw, Key>): RealStoreBuilder<Raw, Parsed, Key> = apply {
        this.persister = persister
    }

    fun persister(diskRead: DiskRead<Raw, Key>,
                  diskWrite: DiskWrite<Raw, Key>): RealStoreBuilder<Raw, Parsed, Key> = apply {
        persister = object : Persister<Raw, Key> {
            override suspend fun read(key: Key): Raw? =
                    diskRead.read(key)

            override suspend fun write(key: Key, raw: Raw): Boolean =
                    diskWrite.write(key, raw)
        }
    }

    fun parser(parser: Parser<Raw, Parsed>): RealStoreBuilder<Raw, Parsed, Key> = apply {
        this.parsers.clear()
        this.parsers.add(NoKeyParser(parser as Parser<Any?, Any?>))
    }

    fun parser(parser: suspend (Raw) -> Parsed): RealStoreBuilder<Raw, Parsed, Key> =
            parser(NoKeyParser(object : Parser<Raw, Parsed> {
                override suspend fun apply(raw: Raw): Parsed {
                    return parser(raw)
                }
            }))

    fun parser(parser: KeyParser<Key, Raw, Parsed>): RealStoreBuilder<Raw, Parsed, Key> = apply {
        this.parsers.clear()
        this.parsers.add(parser as KeyParser<Any?, Any?, Any?>)
    }

    fun parsers(parsers: List<Parser<*, *>>): RealStoreBuilder<Raw, Parsed, Key> = apply {
        this.parsers.clear()
        this.parsers.addAll(parsers.map { NoKeyParser<Any?, Any?, Any?>(it as Parser<Any?, Any?>) })
    }

    fun memoryPolicy(memoryPolicy: MemoryPolicy): RealStoreBuilder<Raw, Parsed, Key> = apply {
        this.memoryPolicy = memoryPolicy
    }

    //Store will backfill the disk cache anytime a record is stale
    //User will still get the stale record returned to them
    fun refreshOnStale(): RealStoreBuilder<Raw, Parsed, Key> = apply {
        stalePolicy = StalePolicy.REFRESH_ON_STALE
    }

    //Store will try to get network source when disk data is stale
    //if network source throws error or is empty, stale disk data will be returned
    fun networkBeforeStale(): RealStoreBuilder<Raw, Parsed, Key> = apply {
        stalePolicy = StalePolicy.NETWORK_BEFORE_STALE
    }

    fun open(): Store<Parsed, Key> {
        var store = Store.from(fetcher!!)

        persister?.let {
            store = store.persister(it, stalePolicy)
        }

        val multiParser = MultiParser<Key, Raw, Parsed>(parsers)

        return store.parser(multiParser)
                .cache(memoryPolicy)
                .open()
    }

    companion object {

        fun <Raw, Parsed, Key> builder(): RealStoreBuilder<Raw, Parsed, Key> {
            return RealStoreBuilder()
        }
    }
}
