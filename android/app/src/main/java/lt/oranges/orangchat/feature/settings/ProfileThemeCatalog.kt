package lt.oranges.orangchat.feature.settings

import android.content.Context
import lt.oranges.orangchat.R
import lt.oranges.orangchat.util.AppStrings

/**
 * A profile theme: freeform CSS a user applies to their own profile card. The
 * "Install" action stores the CSS in the user's profileCss; the card renderer
 * sanitizes and scopes it at render time exactly as it does hand-written CSS.
 */
data class ProfileTheme(
    val id: String,
    val name: String,
    val description: String,
    val authors: List<String>,
    val css: String,
)

/**
 * Kotlin mirror of packages/marketplace/src/profile-themes. Keep in lockstep
 * with that catalog: the CSS strings must match the web entries byte-for-byte so
 * a theme installed on either client lights up as "Installed" on the other
 * (the check is an exact string compare against the stored profileCss). The web
 * strings are `.trim()`-ed template literals, so these use the same trimmed body.
 */
val PROFILE_THEMES: List<ProfileTheme> = listOf(
    ProfileTheme(
        id = "neon",
        name = "Neon",
        description = "A dark card with a glowing purple name and a striped banner.",
        authors = listOf("OrangChat"),
        css = """
.oc-pf-body {
  background: #14091f;
  color: #e9d5ff;
}
.oc-pf-name {
  color: #c084fc;
  letter-spacing: 0.02em;
  text-shadow: 0 0 8px rgba(192, 132, 252, 0.6);
}
.oc-pf-username { color: #9a7fc0; }
.oc-pf-banner {
  background: repeating-linear-gradient(45deg, #7c3aed, #7c3aed 10px, #4c1d95 10px, #4c1d95 20px);
}
""".trim(),
    ),
    ProfileTheme(
        id = "parchment",
        name = "Parchment",
        description = "A warm, paper-toned card with a serif name.",
        authors = listOf("OrangChat"),
        css = """
.oc-pf-body {
  background: #f4ecd8;
  color: #3a2f1c;
}
.oc-pf-name {
  color: #6b4f1d;
  font-family: Georgia, "Times New Roman", serif;
}
.oc-pf-username { color: #8a7448; }
.oc-pf-heading { color: #9a6b2f; }
.oc-pf-banner { background: #d9c7a0; }
""".trim(),
    ),
    ProfileTheme(
        id = "terminal",
        name = "Terminal",
        description = "A green-on-black monospace card for the command-line at heart.",
        authors = listOf("OrangChat"),
        css = """
.oc-pf-body {
  background: #000000;
  color: #33ff66;
  font-family: ui-monospace, "SFMono-Regular", Menlo, monospace;
}
.oc-pf-name {
  color: #33ff66;
  font-family: ui-monospace, monospace;
}
.oc-pf-name::before { content: "> "; }
.oc-pf-username { color: #1f9c3f; }
.oc-pf-heading { color: #1f9c3f; }
.oc-pf-banner { background: #0a0a0a; }
""".trim(),
    ),
)

/**
 * The built-in names/descriptions are catalogue strings so the community can
 * translate them; the raw fields above stay English for the search filter.
 */
fun ProfileTheme.displayName(context: Context): String = when (id) {
    "neon" -> AppStrings.get(context, R.string.catalog_neon_00706b5e)
    "parchment" -> AppStrings.get(context, R.string.catalog_parchment_4761c103)
    "terminal" -> AppStrings.get(context, R.string.catalog_terminal_a1f52cdc)
    else -> name
}

fun ProfileTheme.displayDescription(context: Context): String = when (id) {
    "neon" -> AppStrings.get(context, R.string.catalog_a_dark_card_with_a_glowing_purple_1407c4d4)
    "parchment" -> AppStrings.get(context, R.string.catalog_a_warm_paper_toned_card_with_a_3ae147be)
    "terminal" -> AppStrings.get(context, R.string.catalog_a_green_on_black_monospace_card_for_9a798bea)
    else -> description
}
