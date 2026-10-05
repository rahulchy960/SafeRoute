// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.session.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.saferoute.app.core.di.IoDispatcher
import com.saferoute.app.core.session.AppLocale
import com.saferoute.app.core.session.DataStoreSessionStore
import com.saferoute.app.core.session.ResourcesAppLocale
import com.saferoute.app.core.session.SessionStore
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

@Module
@InstallIn(SingletonComponent::class)
interface SessionModule {

    @Binds
    fun bindSessionStore(store: DataStoreSessionStore): SessionStore

    @Binds
    fun bindAppLocale(locale: ResourcesAppLocale): AppLocale

    companion object {

        /**
         * One DataStore per file for the whole process: two instances on the same file would
         * corrupt it, hence `@Singleton`. The file is in the app's private storage and, with
         * `allowBackup=false`, never leaves the phone.
         */
        @Provides
        @Singleton
        fun provideSessionDataStore(
            @ApplicationContext context: Context,
            @IoDispatcher io: CoroutineDispatcher,
        ): DataStore<Preferences> = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(io + SupervisorJob()),
            produceFile = { context.preferencesDataStoreFile(DataStoreSessionStore.FILE_NAME) },
        )
    }
}
