package org.mobilenativefoundation.paging.core

import org.mobilenativefoundation.paging.core.impl.RealMutablePagingBuffer

inline fun <Id : Comparable<Id>, K : Any, P : Any, D : Any, E : Any, A : Any> mutablePagingBufferOf(maxSize: Int): MutablePagingBuffer<Id, K, P, D> {
    return RealMutablePagingBuffer<Id, K, P, D, E, A>(maxSize)
}