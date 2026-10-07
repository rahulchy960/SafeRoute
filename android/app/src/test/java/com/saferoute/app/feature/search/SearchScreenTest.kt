// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.search

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasText
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.saferoute.app.R
import com.saferoute.app.core.designsystem.theme.SafeRouteTheme
import com.saferoute.app.testing.assertMinTouchTarget
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The search screen alone: every state, the text field's rules and accessibility. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w360dp-h640dp")
class SearchScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun string(id: Int): String = context.getString(id)
    private fun plural(id: Int, count: Int): String = context.resources.getQuantityString(id, count, count)

    private var query by mutableStateOf("")
    private var state: SearchUiState by mutableStateOf(SearchUiState.Idle)
    private var searches = 0
    private var backPresses = 0
    private val chosen = mutableListOf<FoundPlace>()

    @Composable
    private fun Screen(fontScale: Float = 1f) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
            SafeRouteTheme {
                SearchScreen(
                    query = query,
                    state = state,
                    onQueryChange = { query = it },
                    onSearch = { searches++ },
                    onPlaceClick = { chosen += it },
                    onBack = { backPresses++ },
                )
            }
        }
    }

    private fun field() = compose.onNodeWithTag(SearchTags.Field)
    private val polite = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)

    @Test
    fun `opens with the field focused and a hint, and back works`() {
        compose.setContent { Screen() }

        field().assertIsDisplayed().assertIsFocused()
        compose.onNodeWithText(string(R.string.search_empty_title)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.search_empty_body)).assertIsDisplayed()
        // Nothing to clear yet.
        compose.onNodeWithContentDescription(string(R.string.search_clear)).assertDoesNotExist()

        compose.onNodeWithContentDescription(string(R.string.navigate_back))
            .assertMinTouchTarget()
            .performClick()
        assertEquals(1, backPresses)
    }

    @Test
    fun `typing reports the text, Bengali included, and the clear button empties it`() {
        compose.setContent { Screen() }

        field().performTextInput("হাওড়া স্টেশন")
        assertEquals("হাওড়া স্টেশন", query)
        field().assertTextContains("হাওড়া স্টেশন")

        compose.onNodeWithContentDescription(string(R.string.search_clear))
            .assertMinTouchTarget()
            .performClick()
        assertEquals("", query)
    }

    @Test
    fun `the field takes at most 100 code points`() {
        compose.setContent { Screen() }
        field().performTextInput("a".repeat(130))
        assertEquals(100, query.codePointLength())
    }

    @Test
    fun `the keyboard's Search key asks for a search`() {
        compose.setContent { Screen() }
        field().performTextInput("station")
        field().performImeAction()
        assertEquals(1, searches)
    }

    @Test
    fun `results show name and label, announce their count, and report a tap`() {
        state = SearchUiState.Results(listOf(FAKE_STATION, FAKE_MARKET), FAKE_ATTRIBUTION)
        compose.setContent { Screen() }

        compose.onNodeWithText(plural(R.plurals.search_result_count, 2)).assertIsDisplayed().assert(polite)
        compose.onNodeWithText(FAKE_STATION.label, useUnmergedTree = true).assertIsDisplayed()
        // One stop per result for TalkBack: the row is the button and carries both texts.
        val row = compose.onNode(hasText(FAKE_STATION.name) and hasText(FAKE_STATION.label))
        row.assertHasClickAction().assertMinTouchTarget().performClick()
        assertEquals(listOf(FAKE_STATION), chosen)

        compose.onNodeWithText(FAKE_ATTRIBUTION).assertIsDisplayed()
    }

    @Test
    fun `one result uses the singular, and no attribution shows no credit line`() {
        state = SearchUiState.Results(listOf(FAKE_STATION), attribution = null)
        compose.setContent { Screen() }

        compose.onNodeWithText(plural(R.plurals.search_result_count, 1)).assertIsDisplayed()
        compose.onNodeWithText(FAKE_ATTRIBUTION).assertDoesNotExist()
    }

    @Test
    fun `every other state has a calm message that is read out`() {
        compose.setContent { Screen() }
        val messages = listOf(
            SearchUiState.TooShort to string(R.string.search_too_short),
            SearchUiState.Empty to string(R.string.search_no_results_title),
            SearchUiState.Error(SearchError.NoConnection) to string(R.string.search_error_offline),
            SearchUiState.Error(SearchError.RateLimited(null)) to string(R.string.search_error_rate_limited),
            SearchUiState.Error(SearchError.RateLimited(17)) to
                plural(R.plurals.search_error_rate_limited_seconds, 17),
            SearchUiState.Error(SearchError.Unavailable) to string(R.string.search_error_unavailable),
            SearchUiState.Error(SearchError.Unexpected) to string(R.string.search_error_unexpected),
        )
        messages.forEach { (next, text) ->
            state = next
            compose.onNodeWithText(text).assertIsDisplayed().assert(polite)
            // No message ever suggests signing in again or blames the user.
            compose.onNodeWithText(string(R.string.search_empty_title)).assertDoesNotExist()
        }
        // "No results" says what to try next.
        state = SearchUiState.Empty
        compose.onNodeWithText(string(R.string.search_no_results_body)).assertIsDisplayed()
    }

    @Test
    fun `an error offers Try again, and loading is described for screen readers`() {
        state = SearchUiState.Error(SearchError.NoConnection)
        compose.setContent { Screen() }

        compose.onNodeWithText(string(R.string.search_retry)).assertMinTouchTarget().performClick()
        assertEquals(1, searches)

        state = SearchUiState.Loading
        compose.onNodeWithContentDescription(string(R.string.search_loading)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.search_retry)).assertDoesNotExist()
    }

    @Test
    fun `at 200 percent font size the last result and the credit can still be reached`() {
        val many = (1..6).map { FAKE_STATION.copy(id = "fake-$it", name = "Main Station $it") }
        state = SearchUiState.Results(many, FAKE_ATTRIBUTION)
        compose.setContent { Screen(fontScale = 2f) }

        compose.onNodeWithTag(SearchTags.Results).performScrollToNode(hasText("Main Station 6"))
        compose.onNodeWithText("Main Station 6").assertIsDisplayed()
        compose.onNodeWithTag(SearchTags.Results).performScrollToNode(hasText(FAKE_ATTRIBUTION))
        compose.onNodeWithText(FAKE_ATTRIBUTION).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "bn-w360dp-h640dp")
    fun `the messages are in Bengali when the app is`() {
        state = SearchUiState.Empty
        compose.setContent { Screen() }
        compose.onNodeWithText("কোনো জায়গা পাওয়া যায়নি").assertIsDisplayed()
    }
}
