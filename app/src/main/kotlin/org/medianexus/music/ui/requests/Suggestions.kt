package org.medianexus.music.ui.requests

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import org.medianexus.music.R
import org.medianexus.music.ui.parts.CardData
import org.medianexus.music.ui.parts.CardKind
import org.medianexus.music.ui.parts.Shelf
import org.medianexus.music.ui.shell.LocalUi
import org.medianexus.music.ui.shell.Route

/**
 * Home's "Artists you might like" (Windows' `discover_shelf`): artists the library doesn't
 * have who are like the ones played most, each opening their page. Looking for them costs a
 * dozen or so questions, so it is asked only when the row is about to be shown (Home's list
 * draws an item as it comes near the screen), and what is kept is shown meanwhile; nothing
 * at all until there is something to show.
 */
@Composable
fun ArtistsYouMightLike(here: RequestsHere) {
    val ui = LocalUi.current
    val res = LocalResources.current
    val pages = here.pages
    LaunchedEffect(here.key) {
        try {
            pages.suggest()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // (Nothing to show is the same as none to suggest: the row stays away.)
        }
    }
    val suggestions by pages.suggestions.collectAsStateWithLifecycle()
    if (suggestions.isEmpty()) return
    val cards = remember(suggestions, res) {
        suggestions.map { CardData(CardKind.ARTIST, it.id, it.name, if (it.like.isEmpty()) "" else res.getString(R.string.like_named, it.like), it.cover, web = true) }
    }
    Shelf(stringResource(R.string.artists_you_might_like), cards, onOpen = { card -> ui.open(Route.OutsideArtist(card.id, card.title, card.cover)) })
}
