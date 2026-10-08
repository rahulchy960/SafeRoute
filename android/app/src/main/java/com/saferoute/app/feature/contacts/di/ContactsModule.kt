// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.contacts.di

import com.saferoute.app.feature.contacts.ApiContactsRepository
import com.saferoute.app.feature.contacts.ContactsRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Where the emergency contacts come from. Its own module so that tests replace it with a fake
 * that never calls a server (`FakeContactsModule` in the tests).
 */
@Module
@InstallIn(SingletonComponent::class)
interface ContactsModule {
    @Binds
    fun bindContactsRepository(repository: ApiContactsRepository): ContactsRepository
}
