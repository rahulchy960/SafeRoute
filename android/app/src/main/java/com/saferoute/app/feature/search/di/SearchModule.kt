// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.search.di

import com.saferoute.app.feature.search.ApiSearchRepository
import com.saferoute.app.feature.search.SearchRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Where searches go. Its own module so that tests replace it with a fake that never calls a
 * server (`FakeSearchModule` in the tests).
 */
@Module
@InstallIn(SingletonComponent::class)
interface SearchModule {
    @Binds
    fun bindSearchRepository(repository: ApiSearchRepository): SearchRepository
}
