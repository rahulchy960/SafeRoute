// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.data.di

import android.content.Context
import androidx.room.Room
import com.saferoute.app.core.data.ActiveSosContacts
import com.saferoute.app.core.data.RoomActiveSosContacts
import com.saferoute.app.core.data.local.ContactDao
import com.saferoute.app.core.data.local.SafeRouteDatabase
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The local database. Its own module so that tests replace it with a database that lives in
 * memory (`TestDatabaseModule`).
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    /**
     * One instance for the whole process (`@Singleton`): Room keeps one connection pool per
     * instance and notifies `Flow` readers only of changes made through the same instance.
     * Building it opens no file; that happens on the first query, off the main thread.
     */
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): SafeRouteDatabase =
        Room.databaseBuilder(context, SafeRouteDatabase::class.java, SafeRouteDatabase.FILE_NAME).build()
}

@Module
@InstallIn(SingletonComponent::class)
interface DataModule {

    @Binds
    fun bindActiveSosContacts(contacts: RoomActiveSosContacts): ActiveSosContacts

    companion object {
        @Provides
        fun provideContactDao(database: SafeRouteDatabase): ContactDao = database.contactDao()
    }
}
