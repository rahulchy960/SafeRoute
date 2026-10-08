// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.emergency

import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow

/** Answers "add the tile" from a script; no status bar, no system dialog. */
class FakeTileAdder(var result: TileAddResult = TileAddResult.NotAdded) : TileAdder {
    var requests = 0

    override fun requestAdd(onResult: (TileAddResult) -> Unit) {
        requests++
        onResult(result)
    }
}

/** The two flags in memory. */
class FakeEmergencyShortcutPreferences(
    enabled: Boolean = false,
    offerShown: Boolean = false,
) : EmergencyShortcutPreferences {
    override val notificationEnabled = MutableStateFlow(enabled)
    override val offerShown = MutableStateFlow(offerShown)

    override suspend fun setNotificationEnabled(enabled: Boolean) {
        notificationEnabled.value = enabled
    }

    override suspend fun setOfferShown() {
        offerShown.value = true
    }
}

/** What Android would say about posting notifications, set by the test. */
class FakeNotificationGate(var block: NotificationBlock = NotificationBlock.None) : NotificationGate {
    var checks = 0

    override fun block(): NotificationBlock {
        checks++
        return block
    }
}

/** Records posts and removals; shows nothing. */
class FakeEmergencyNotifier : EmergencyNotifier {
    val events = mutableListOf<String>()
    val shown: Boolean get() = events.lastOrNull() == "post"

    override fun post() {
        events += "post"
    }

    override fun cancel() {
        events += "cancel"
    }
}

/**
 * Replaces [EmergencyShortcutsModule] in every Hilt test. The offer counts as already shown by
 * default, so that tests about other things are not interrupted by it; a test about the offer
 * resets the flag.
 */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [EmergencyShortcutsModule::class])
object FakeEmergencyShortcutsModule {

    @Provides
    @Singleton
    fun provideFakeTileAdder(): FakeTileAdder = FakeTileAdder()

    @Provides
    fun provideTileAdder(fake: FakeTileAdder): TileAdder = fake

    @Provides
    @Singleton
    fun provideFakePreferences(): FakeEmergencyShortcutPreferences =
        FakeEmergencyShortcutPreferences(offerShown = true)

    @Provides
    fun providePreferences(fake: FakeEmergencyShortcutPreferences): EmergencyShortcutPreferences = fake

    @Provides
    @Singleton
    fun provideFakeGate(): FakeNotificationGate = FakeNotificationGate()

    @Provides
    fun provideGate(fake: FakeNotificationGate): NotificationGate = fake

    @Provides
    @Singleton
    fun provideFakeNotifier(): FakeEmergencyNotifier = FakeEmergencyNotifier()

    @Provides
    fun provideNotifier(fake: FakeEmergencyNotifier): EmergencyNotifier = fake
}
