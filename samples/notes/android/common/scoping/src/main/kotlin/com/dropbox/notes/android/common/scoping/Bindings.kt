package com.dropbox.notes.android.common.scoping

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.fragment.app.Fragment
import androidx.lifecycle.AndroidViewModel

inline fun <reified Bindings : Any> Context.bindings() = bindings(Bindings::class.java)

@PublishedApi
internal fun <Bindings : Any> Context.bindings(bindings: Class<Bindings>): Bindings =
    generateSequence(this) { (it as? ContextWrapper)?.baseContext }
        .plus(applicationContext)
        .filterIsInstance<ComponentHolder>()
        .map { it.component }
        .flatMap { if (it is Collection<*>) it else listOf(it) }
        .filterIsInstance(bindings)
        .firstOrNull()
        ?: error("Unable to find ${bindings.name} bindings")


inline fun <reified Bindings : Any> Fragment.bindings() = bindings(Bindings::class.java)

@PublishedApi
internal fun <Bindings : Any> Fragment.bindings(bindings: Class<Bindings>): Bindings =
    generateSequence(this, Fragment::getParentFragment)
        .filterIsInstance<ComponentHolder>()
        .map { it.component }
        .flatMap { if (it is Collection<*>) it else listOf(it) }
        .filterIsInstance(bindings)
        .firstOrNull()
        ?: requireActivity().bindings(bindings)


inline fun <reified Bindings : Any> AndroidViewModel.bindings() =
    ((this as? ComponentHolder)?.component as? Bindings)
        ?: getApplication<Application>().bindings(Bindings::class.java)

