package lt.oranges.orangchat.feature.settings

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import lt.oranges.orangchat.R
import lt.oranges.orangchat.ui.components.Text
import lt.oranges.orangchat.ui.theme.OrangRadius
import lt.oranges.orangchat.ui.theme.OrangTheme
import lt.oranges.orangchat.util.AppLanguage
import lt.oranges.orangchat.util.AppStrings
import lt.oranges.orangchat.util.LocalePreferences
import lt.oranges.orangchat.util.RemoteI18n
import lt.oranges.orangchat.util.labelRes

/**
 * Device-local language picker: one option per row, listed downward rather
 * than as a row of chips, so twelve options stay reachable on any screen.
 *
 * The bundled languages come first (in [AppLanguage] order), then anything the
 * server published that this build never shipped - a language added after the
 * APK was made, fetched by [RemoteI18n] on its launch-and-hourly poll. A
 * language that vanished from the server list but is still cached locally
 * keeps its row so a user isn't silently switched.
 *
 * Picking a language recreates the Activity so [lt.oranges.orangchat.LocalizedActivity]
 * re-attaches the new locale on [android.app.Activity.attachBaseContext] - that
 * is the only point Android reads the override from. Other already-running
 * activities (a bubble, a media preview) keep their old locale until they are
 * themselves relaunched, which is what the notice below the list is for.
 */
@Composable
fun LanguagePage(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val c = OrangTheme.colors
    val context = LocalContext.current
    val systemTag = AppLanguage.SYSTEM.storageValue
    var selected by remember { mutableStateOf(LocalePreferences.getTag(context) ?: systemTag) }

    Column(modifier = modifier.fillMaxSize().background(c.surface2)) {
        SettingsTopBar(AppStrings.get(context, R.string.language_title), onBack)
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            SettingSection(AppStrings.get(context, R.string.language_title)) {
                val options = buildList {
                    AppLanguage.entries.forEach { language ->
                        add(Triple(language.storageValue, AppStrings.get(context, language.labelRes()), ""))
                    }
                    RemoteI18n.languages().forEach { remote ->
                        if (none { it.first == remote.code }) add(Triple(remote.code, remote.endonym, ""))
                    }
                    // A picked language the server no longer publishes still
                    // works from cache; keep its row so it stays visible.
                    if (selected != systemTag && none { it.first == selected }) {
                        add(Triple(selected, LocalePreferences.label(context, selected), ""))
                    }
                }
                SettingsChoiceRow(
                    value = selected,
                    options = options,
                    onSelect = { tag ->
                        selected = tag
                        if (tag == systemTag) LocalePreferences.setSystem(context)
                        else LocalePreferences.set(context, tag)
                        // A server-added language needs its catalogue before the
                        // recreated activity can render it; the refresh is
                        // fire-and-forget so switching bundled languages stays
                        // instant. Until it lands, resources (English for an
                        // unbundled tag) stand in.
                        RemoteI18n.refreshNow()
                        (context as? Activity)?.recreate()
                    },
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(c.surface1, RoundedCornerShape(OrangRadius.lg))
                    .padding(12.dp),
            ) {
                Text(AppStrings.get(context, R.string.language_hint), color = c.inkSecondary, fontSize = 12.sp)
            }
        }
    }
}
