// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.di

import javax.inject.Qualifier

/*
 * A coroutine dispatcher decides which thread(s) a piece of work runs on. Classes ask for one
 * through these qualifiers instead of naming Dispatchers.IO directly, so that a test can hand
 * them a dispatcher it controls.
 *
 * Both dispatchers have the same Kotlin type, so Hilt needs a qualifier to tell them apart.
 */

/** For blocking work: disk, database and (later) network. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

/** For CPU-heavy work that must stay off the main (UI) thread. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

/**
 * A coroutine scope that lives as long as the app process.
 *
 * Use it for work that must finish even if the screen that started it goes away. A ViewModel's
 * `viewModelScope` is cancelled the moment its screen leaves the back stack; work that changes
 * which screen is shown must therefore not run in the scope of the screen it replaces (P009d).
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
