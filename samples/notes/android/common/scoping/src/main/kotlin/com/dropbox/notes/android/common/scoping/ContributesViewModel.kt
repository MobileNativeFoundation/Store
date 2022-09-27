package com.dropbox.notes.android.common.scoping

import kotlin.reflect.KClass

@Target(AnnotationTarget.CLASS)
annotation class ContributesViewModel(val scope: KClass<*>)