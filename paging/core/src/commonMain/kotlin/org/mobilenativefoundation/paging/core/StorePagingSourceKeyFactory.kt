package org.mobilenativefoundation.paging.core

fun interface StorePagingSourceKeyFactory<Id : Comparable<Id>, K : Any, P : Any, D : Any> {
    fun createKeyFor(single: PagingData.Single<Id, K, P, D>): PagingKey<K, P>
}