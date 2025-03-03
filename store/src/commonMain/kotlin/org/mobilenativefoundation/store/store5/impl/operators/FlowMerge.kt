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
package org.mobilenativefoundation.store.store5.impl.operators

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

/** Merge implementation tells downstream what the source is and also uses a rendezvous channel */
internal fun <T, R> Flow<T>.merge(other: Flow<R>): Flow<Either<T, R>> {
  return channelFlow<Either<T, R>> {
      launch { this@merge.collect { send(Either.Left(it)) } }
      launch { other.collect { send(Either.Right(it)) } }
    }
    .buffer(Channel.RENDEZVOUS)
}

internal sealed class Either<T, R> {
  data class Left<T, R>(val value: T) : Either<T, R>()

  data class Right<T, R>(val value: R) : Either<T, R>()
}
