// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.emergency

import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Singleton

/** Answers "add the tile" from a script; no status bar, no system dialog. */
class FakeTileAdder(var result: TileAddResult = TileAddResult.NotAdded) : TileAdder {
    var requests = 0

    override fun requestAdd(onResult: (TileAddResult) -> Unit) {
        requests++
        onResult(result)
    }
}

/** Replaces [EmergencyShortcutsModule] in every Hilt test. */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [EmergencyShortcutsModule::class])
object FakeEmergencyShortcutsModule {

    @Provides
    @Singleton
    fun provideFakeTileAdder(): FakeTileAdder = FakeTileAdder()

    @Provides
    fun provideTileAdder(fake: FakeTileAdder): TileAdder = fake
}
