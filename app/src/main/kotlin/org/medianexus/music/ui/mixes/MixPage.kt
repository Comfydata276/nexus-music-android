package org.medianexus.music.ui.mixes

import android.content.res.Resources
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.medianexus.music.R
import org.medianexus.music.library.Mix
import org.medianexus.music.library.MixRules
import org.medianexus.music.library.MixSet
import org.medianexus.music.library.Mixes
import org.medianexus.music.net.Song
import org.medianexus.music.ui.lists.SongListPage
import org.medianexus.music.ui.menu.Entry
import org.medianexus.music.ui.menu.Rule
import org.medianexus.music.ui.menu.Title
import org.medianexus.music.ui.menu.askNewPlaylist
import org.medianexus.music.ui.parts.Field
import org.medianexus.music.ui.parts.HeroData
import org.medianexus.music.ui.parts.Icon
import org.medianexus.music.ui.parts.TextButton
import org.medianexus.music.ui.parts.listFacts
import org.medianexus.music.ui.shell.LocalUi
import org.medianexus.music.ui.shell.PageFeed
import org.medianexus.music.ui.shell.Route
import org.medianexus.music.ui.shell.Sheet
import org.medianexus.music.ui.shell.Ui
import org.medianexus.music.ui.theme.Theme

/** A mix's name and the line under it, in Windows' words. */
object MixText {
    fun words(res: Resources) = Mixes.RuleWords(
        fiveStars = res.getString(R.string.mix_words_five_stars),
        starsOrMore = res.getString(R.string.mix_words_stars_or_more),
        liked = res.getString(R.string.mix_words_liked),
        fromOn = res.getString(R.string.mix_words_from_on),
        upTo = res.getString(R.string.mix_words_up_to),
        years = res.getString(R.string.mix_words_years),
        played = res.getStringArray(R.array.mix_played).toList(),
        addedWhen = res.getString(R.string.mix_words_added),
        added = res.getStringArray(R.array.mix_added).toList(),
        orders = res.getStringArray(R.array.mix_orders).toList(),
        anySong = res.getString(R.string.mix_words_any_song),
    )

    fun title(res: Resources, mix: Mix): String = when (mix.kind) {
        Mix.Kind.RULES -> mix.name
        Mix.Kind.ON_REPEAT -> res.getString(R.string.mix_on_repeat)
        Mix.Kind.TOP -> res.getString(R.string.mix_top)
        Mix.Kind.REDISCOVER -> res.getString(R.string.mix_rediscover)
        Mix.Kind.GENRE -> res.getString(R.string.mix_genre, mix.name)
    }

    fun about(res: Resources, mix: Mix): String = when (mix.kind) {
        Mix.Kind.RULES -> Mixes.summary(mix.rules ?: MixRules(), words(res))
        Mix.Kind.ON_REPEAT -> res.getString(R.string.mix_on_repeat_about)
        Mix.Kind.TOP -> res.getString(R.string.mix_top_about)
        Mix.Kind.REDISCOVER -> res.getString(R.string.mix_rediscover_about)
        Mix.Kind.GENRE -> res.getString(R.string.mix_genre_about)
    }

    /** Offline (`phone`: the songs that play), the mix with only those, each in its place. */
    fun playable(mix: Mix, phone: Set<String>?): Mix = if (phone == null) mix else mix.only { mix.table.id(it) in phone }
}

private val NO_MIXES = MutableStateFlow<MixSet?>(null)

/** The mixes as they stand (null until there is a whole copy of the library). */
@Composable
fun mixesNow(): MixSet? = (LocalUi.current.account?.mixes?.state ?: NO_MIXES).collectAsStateWithLifecycle().value

/** The ids of the songs that play offline, or null online. */
@Composable
fun playableIds(): Set<String>? {
    val phone = org.medianexus.music.ui.downloads.playableOffline()
    return remember(phone) { phone?.mapTo(HashSet()) { it.id } }
}

/**
 * One of the "Made for you" mixes (Windows' `mix` page): made afresh as what it is made from
 * changes, played like any list of songs, and saved as a playlist from its "…" (which, for a
 * mix of one's own, also holds its rules). Offline it lists the songs that can be played.
 */
@Composable
fun MixPage(route: Route.Mix) {
    val ui = LocalUi.current
    val res = LocalResources.current
    val set = mixesNow()
    val inSet = set?.find(route.id)
    // Not among those on Home (a genre no longer played most, a mix with too little to make it
    // from today): worked out by itself, off the main thread.
    val alone by produceState<Mix?>(null, set, route.id, inSet) {
        value = if (set == null || inSet != null) null else withContext(Dispatchers.Default) { Mixes.one(set.from, route.id) }
    }
    val made = inSet ?: alone
    val phone = playableIds()
    val mix = remember(made, phone) { made?.let { MixText.playable(it, phone) } }
    val reading = set == null
    val songs: List<Song>? = when {
        reading -> if (ui.account?.copy?.read?.collectAsStateWithLifecycle()?.value == true) emptyList() else null
        mix == null -> emptyList()
        else -> mix.songs
    }
    val title = made?.let { MixText.title(res, it) } ?: route.title.ifEmpty { stringResource(R.string.mix_default_title) }
    val hero = HeroData(
        stringResource(R.string.made_for_you), title,
        subtitle = made?.let { MixText.about(res, it) }.orEmpty(),
        meta = if (songs.isNullOrEmpty()) "" else listFacts(res, songs),
        cover = mix?.pageCover?.ifEmpty { null } ?: route.cover,
        playlist = true,
    )
    val empty = when {
        reading -> stringResource(R.string.library_still_read)
        made == null -> stringResource(R.string.mix_not_enough)
        made.size > 0 && mix?.size == 0 -> stringResource(R.string.mix_offline_none)
        else -> stringResource(R.string.mix_none_fit)
    }
    // A mix is worked out on the phone: there is nothing to ask the server for again.
    val feed = remember { PageFeed<Unit>() }
    LaunchedEffect(feed.refreshCount) { feed.refreshing = false }
    val rules = made?.rules
    val canMenu = rules != null || !songs.isNullOrEmpty()
    SongListPage(
        key = route.key,
        hero = hero,
        songs = songs,
        context = title,
        feed = feed,
        empty = empty,
        onMore = if (!canMenu) null else ({ page -> ui.mixMenu(title, rules, page.songs) }),
    )
}

/** The menu on a mix's page (Windows' `mix_menu`): it can be kept as a playlist, and one made
 *  to rules of your own can have those rules changed. */
fun Ui.mixMenu(title: String, rules: MixRules?, songs: List<Song>) {
    sheet = Sheet.Drawn { choose ->
        val ui = LocalUi.current
        val res = LocalResources.current
        Title(title)
        Rule()
        if (songs.isNotEmpty()) Entry(R.drawable.ic_list_plus, stringResource(R.string.save_as_playlist)) { choose { ui.askNewPlaylist(songs) } }
        if (rules != null) {
            // As they stand now (the sheet stays up while one is changed elsewhere: it isn't).
            val now = ui.account?.ruleMixes?.state?.collectAsStateWithLifecycle()?.value?.firstOrNull { it.id == rules.id } ?: rules
            if (songs.isNotEmpty()) Rule()
            RuleEntry(stringResource(R.string.mix_rule_genre, now.genre.trim().ifEmpty { stringResource(R.string.mix_any) })) { choose { ui.askRuleText(now, RuleText.GENRE) } }
            val stars = when (now.rating) {
                0 -> stringResource(R.string.mix_any)
                5 -> stringResource(R.string.mix_words_five_stars)
                else -> stringResource(R.string.mix_stars_or_more_short, now.rating)
            }
            RuleEntry(stringResource(R.string.mix_rule_rating, stars)) {
                choose { ui.pickRule(now, res.getString(R.string.mix_rating), res.getStringArray(R.array.mix_ratings).toList(), now.rating) { r, n -> r.copy(rating = n) } }
            }
            RuleEntry(stringResource(R.string.mix_rule_liked, stringResource(if (now.liked) R.string.mix_yes else R.string.mix_no))) {
                choose { ui.changeRules(now.id) { it.copy(liked = !it.liked) } }
            }
            val years = when {
                now.fromYear == 0 && now.toYear == 0 -> stringResource(R.string.mix_any)
                now.toYear == 0 -> stringResource(R.string.mix_years_from, now.fromYear)
                now.fromYear == 0 -> stringResource(R.string.mix_years_up_to, now.toYear)
                else -> stringResource(R.string.mix_years_range, now.fromYear, now.toYear)
            }
            RuleEntry(stringResource(R.string.mix_rule_years, years)) { choose { ui.askRuleText(now, RuleText.YEARS) } }
            val played = res.getStringArray(R.array.mix_played).toList()
            RuleEntry(stringResource(R.string.mix_rule_played, played[now.played.coerceIn(0, played.size - 1)].lowercase(java.util.Locale.ROOT))) {
                choose { ui.pickRule(now, res.getString(R.string.mix_last_played), played, now.played) { r, n -> r.copy(played = n) } }
            }
            val added = res.getStringArray(R.array.mix_added).toList()
            RuleEntry(stringResource(R.string.mix_rule_added, added[now.added.coerceIn(0, added.size - 1)].lowercase(java.util.Locale.ROOT))) {
                choose { ui.pickRule(now, res.getString(R.string.mix_added_title), added, now.added) { r, n -> r.copy(added = n) } }
            }
            val orders = res.getStringArray(R.array.mix_orders).toList()
            RuleEntry(stringResource(R.string.mix_rule_order, orders[now.order.coerceIn(0, orders.size - 1)].lowercase(java.util.Locale.ROOT))) {
                choose { ui.pickRule(now, res.getString(R.string.mix_order), orders, now.order) { r, n -> r.copy(order = n) } }
            }
            val sizes = Mixes.SIZES.map { res.getString(R.string.mix_songs, it) }
            RuleEntry(stringResource(R.string.mix_rule_size, Mixes.SIZES[now.size.coerceIn(0, Mixes.SIZES.size - 1)])) {
                choose { ui.pickRule(now, res.getString(R.string.mix_length), sizes, now.size) { r, n -> r.copy(size = n) } }
            }
            Rule()
            Entry(R.drawable.ic_pencil, stringResource(R.string.mix_rename)) { choose { ui.askRuleText(now, RuleText.NAME) } }
            Entry(R.drawable.ic_trash_2, stringResource(R.string.mix_delete), danger = true) { choose { ui.deleteMix(now) } }
        }
    }
}

/** One of a mix's rules, as Windows' menu lists it ("Genre: rock"): no icon, the words
 *  where an entry's words go. */
@Composable
private fun RuleEntry(text: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = Theme.dimens.pagePad),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.size(20.dp))
        BasicText(text, Modifier.weight(1f), style = Theme.type.body.copy(color = Theme.colors.text, fontSize = Theme.type.rowTitle.fontSize), maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        Icon(R.drawable.ic_chevron_right, size = 18.dp, tint = Theme.colors.muted)
    }
}

/** One of a rule's choices (Windows' list, the one in force ticked). */
private fun Ui.pickRule(rules: MixRules, title: String, names: List<String>, current: Int, set: (MixRules, Int) -> MixRules) {
    sheet = Sheet.Drawn { choose ->
        Title(title)
        Rule()
        for ((index, name) in names.withIndex()) {
            val chosen = index == current
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .clickable(role = Role.RadioButton) { choose { changeRules(rules.id) { set(it, index) } } }
                    .semantics(mergeDescendants = true) { selected = chosen }
                    .padding(horizontal = Theme.dimens.pagePad),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                    if (chosen) Icon(R.drawable.ic_check, size = 20.dp, tint = Theme.colors.accentBright)
                }
                BasicText(name, style = Theme.type.body.copy(color = if (chosen) Theme.colors.text else Theme.colors.muted, fontSize = Theme.type.rowTitle.fontSize))
            }
        }
    }
}

/** Changes a mix's rules: kept on the phone first, then shown (the mix and Home's row follow). */
fun Ui.changeRules(id: Long, change: (MixRules) -> MixRules) {
    val account = account ?: return
    val res = graph.resources
    graph.scope.launch(Dispatchers.Main.immediate) {
        try {
            account.ruleMixes.update(id, change)
        } catch (_: java.io.IOException) {
            toast(res.getString(R.string.mix_not_saved))
        }
    }
}

private fun Ui.deleteMix(rules: MixRules) {
    val account = account ?: return
    val res = graph.resources
    graph.scope.launch(Dispatchers.Main.immediate) {
        try {
            account.ruleMixes.delete(rules.id)
        } catch (_: java.io.IOException) {
            toast(res.getString(R.string.mix_not_saved))
            return@launch
        }
        toast(res.getString(R.string.mix_deleted, rules.name))
        // (Windows goes Home; here its page goes, back to wherever it was opened from.)
        if ((navigator.visible.lastOrNull()?.route as? Route.Mix)?.id == "rule:${java.lang.Long.toUnsignedString(rules.id)}") navigator.back()
    }
}

/** What a typed answer sets. */
private enum class RuleText { NAME, GENRE, YEARS }

/** A rule given in words (Windows' prompts): an empty answer clears the rule, but not the name. */
private fun Ui.askRuleText(rules: MixRules, what: RuleText) {
    val now = when (what) {
        RuleText.NAME -> rules.name
        RuleText.GENRE -> rules.genre
        RuleText.YEARS -> when {
            rules.fromYear == 0 && rules.toYear == 0 -> ""
            rules.toYear == 0 -> "${rules.fromYear}-"
            rules.fromYear == 0 -> "-${rules.toYear}"
            else -> "${rules.fromYear}-${rules.toYear}"
        }
    }
    sheet = Sheet.Drawn { choose ->
        val title = stringResource(when (what) { RuleText.NAME -> R.string.mix_rename_title; RuleText.GENRE -> R.string.mix_genre_title; RuleText.YEARS -> R.string.mix_years_title })
        val detail = when (what) { RuleText.NAME -> ""; RuleText.GENRE -> stringResource(R.string.mix_genre_detail); RuleText.YEARS -> stringResource(R.string.mix_years_detail) }
        AskWords(title, detail, now, label = title, confirm = stringResource(if (what == RuleText.NAME) R.string.save else R.string.mix_set), allowEmpty = what != RuleText.NAME, choose = choose) { text ->
            changeRules(rules.id) { r ->
                when (what) {
                    RuleText.NAME -> if (text.isEmpty()) r else r.copy(name = text)
                    RuleText.GENRE -> r.copy(genre = text)
                    RuleText.YEARS -> Mixes.years(text).let { (from, to) -> r.copy(fromYear = from, toYear = to) }
                }
            }
        }
    }
}

/** "New mix from rules" (Windows' + beside Playlists): its name, then its page, whose "…"
 *  holds its rules. */
fun Ui.askNewMix() {
    sheet = Sheet.Drawn { choose ->
        val ui = LocalUi.current
        val res = LocalResources.current
        AskWords(stringResource(R.string.name_your_mix), stringResource(R.string.name_your_mix_detail), "", label = stringResource(R.string.mix_name), confirm = stringResource(R.string.create_mix), allowEmpty = false, choose = choose) { name ->
            val account = ui.account ?: return@AskWords
            ui.graph.scope.launch(Dispatchers.Main.immediate) {
                val made = try {
                    account.ruleMixes.create(name)
                } catch (_: java.io.IOException) {
                    ui.toast(res.getString(R.string.mix_not_saved))
                    return@launch
                }
                ui.open(Route.Mix("rule:${java.lang.Long.toUnsignedString(made.id)}", made.name))
                ui.toast(res.getString(R.string.mix_set_rules))
            }
        }
    }
}

/** A question answered in words: its title, what it is about, a box with `initial` in it. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun AskWords(title: String, detail: String, initial: String, label: String, confirm: String, allowEmpty: Boolean, choose: (() -> Unit) -> Unit, onConfirm: (String) -> Unit) {
    val box = rememberTextFieldState()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        box.setTextAndPlaceCursorAtEnd(initial)
        focus.requestFocus()
    }
    val go = {
        val text = box.text.toString().trim()
        if (allowEmpty || text.isNotEmpty()) choose { onConfirm(text) }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = Theme.dimens.pagePad, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BasicText(title, Modifier.semantics { heading() }, style = Theme.type.heading.copy(color = Theme.colors.text))
        if (detail.isNotEmpty()) BasicText(detail, style = Theme.type.body.copy(color = Theme.colors.muted))
    }
    Field(box, label, Modifier.fillMaxWidth().padding(horizontal = Theme.dimens.pagePad).focusRequester(focus), onKeyboardAction = { go() })
    androidx.compose.foundation.layout.FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = Theme.dimens.pagePad, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextButton(stringResource(R.string.cancel), onClick = { choose {} })
        TextButton(confirm, primary = true, onClick = go)
    }
}
