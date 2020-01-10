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

package com.dropbox.android.external.store4.impl

import com.dropbox.android.external.cache4.Cache
import com.dropbox.android.external.store4.ResponseOrigin
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.ConflatedBroadcastChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

@UseExperimental(ExperimentalCoroutinesApi::class)
class CacheSourceOfTruth<Key : Any, Output : Any>(
    private val cache: Cache<Key, Output>
) : SourceOfTruth<Key, Output, Output> {
    private val keyTrackers = RefCountedResource<Key, ConflatedBroadcastChannel<Output?>>(
        create = {
            // source of truth is expected to send null if it does not have data.
            ConflatedBroadcastChannel(cache.get(it))
        },
        onRelease = { key, channel ->
            channel.close()
        }
    )

    override fun reader(key: Key): Flow<Output?> {
        return flow {
            keyTrackers.use(key) { channel ->
                emitAll(channel.asFlow())
            }
        }
    }

    override suspend fun write(key: Key, value: Output) {
        keyTrackers.use(key) {
            cache.put(key, value)
            it.send(value)
        }
    }

    override suspend fun delete(key: Key) {
        keyTrackers.use(key) {
            cache.invalidate(key)
            it.send(null)
        }
    }

    override suspend fun getSize() = keyTrackers.size()

    override val defaultOrigin = ResponseOrigin.Cache
}