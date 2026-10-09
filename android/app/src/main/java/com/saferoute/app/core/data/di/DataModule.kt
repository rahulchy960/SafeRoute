// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.data.di

import android.content.Context
import androidx.room.Room
import com.saferoute.app.core.data.ActiveSosContacts
import com.saferoute.app.core.data.RoomActiveSosContacts
import com.saferoute.app.core.data.RoomSosActionStore
import com.saferoute.app.core.data.RoomSosStore
import com.saferoute.app.core.data.local.ContactDao
import com.saferoute.app.core.data.local.SafeRouteDatabase
import com.saferoute.app.core.data.local.SosDao
import com.saferoute.app.core.emergency.SosActionStore
import com.saferoute.app.core.emergency.SosStore
import com.saferoute.app.core.emergency.UuidV7Generator
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.Clock
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
        Room.databaseBuilder(context, SafeRouteDatabase::class.java, SafeRouteDatabase.FILE_NAME)
            .addMigrations(*SafeRouteDatabase.MIGRATIONS)
            .build()
}

@Module
@InstallIn(SingletonComponent::class)
interface DataModule {

    @Binds
    fun bindActiveSosContacts(contacts: RoomActiveSosContacts): ActiveSosContacts

    @Binds
    fun bindSosStore(store: RoomSosStore): SosStore

    @Binds
    fun bindSosActionStore(store: RoomSosActionStore): SosActionStore

    companion object {
        @Provides
        fun provideContactDao(database: SafeRouteDatabase): ContactDao = database.contactDao()

        @Provides
        fun provideSosDao(database: SafeRouteDatabase): SosDao = database.sosDao()

        @Provides
        fun provideUuidV7Generator(clock: Clock): UuidV7Generator = UuidV7Generator(clock)
    }
}
