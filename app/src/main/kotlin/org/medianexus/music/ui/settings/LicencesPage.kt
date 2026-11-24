package org.medianexus.music.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.medianexus.music.R
import org.medianexus.music.ui.parts.TitleBar
import org.medianexus.music.ui.theme.Theme

/** A licence that comes with the app: what it covers, and the file in the app's assets. */
private class Licence(val name: String, val file: String)

/**
 * The licences of what comes with the app, in full: the two fonts' (SIL Open Font License),
 * the icons' (Lucide, ISC) and the notice for Google's list of known media apps (Apache 2.0).
 * Read from the app's own copies of them.
 */
@Composable
fun LicencesPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val known = stringResource(R.string.licence_known_hosts)
    val licences = listOf(
        Licence("Bricolage Grotesque", "licenses/OFL-BricolageGrotesque.txt"),
        Licence("Hanken Grotesk", "licenses/OFL-HankenGrotesk.txt"),
        Licence("Lucide", "licenses/LICENSE-lucide.txt"),
        Licence(known, "licenses/NOTICE-known-hosts.txt"),
    )
    val texts by produceState(emptyMap<String, String>()) {
        value = withContext(Dispatchers.IO) {
            licences.associate { it.file to runCatching { context.assets.open(it.file).bufferedReader().use { r -> r.readText() } }.getOrDefault("") }
        }
    }
    Column(Modifier.fillMaxSize()) {
        TitleBar(stringResource(R.string.licences), onBack = onBack)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = Theme.dimens.pagePad, end = Theme.dimens.pagePad, bottom = 32.dp)) {
            items(licences, key = { it.file }) { licence ->
                Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BasicText(licence.name, Modifier.semantics { heading() }, style = Theme.type.heading.copy(color = Theme.colors.text))
                    BasicText(texts[licence.file].orEmpty().trim(), style = Theme.type.caption.copy(color = Theme.colors.muted))
                }
            }
        }
    }
}
