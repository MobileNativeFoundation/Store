package com.dropbox.notes.android.lib.result

sealed class Result<out S : Any, out F : Throwable> {
    open operator fun component1(): S? = null
    open operator fun component2(): F? = null

    class Success<S : Any>(private val value: S) : Result<S, Nothing>() {
        override fun component1(): S = value
    }

    class Failure<F : Throwable>(private val throwable: F) : Result<Nothing, F>() {
        override fun component2(): F = throwable
    }

    object Loading : Result<Nothing, Nothing>()

    companion object {
        fun <F : Throwable> asFailure(throwable: F) = Failure(throwable)
        fun <S : Any> asSuccess(value: S) = Success(value)
        fun asLoading() = Loading

        inline fun <S : Any, reified F : Throwable> from(transform: () -> S): Result<S, F> = doTry(onTry = {
            asSuccess(transform())
        }, onCatch = {
            when (it) {
                is F -> asFailure(it)
                else -> throw it
            }
        })
    }
}