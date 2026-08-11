package lt.oranges.orangchat.ui.components

import android.content.Context
import lt.oranges.orangchat.R
import lt.oranges.orangchat.notifications.MuteDuration
import lt.oranges.orangchat.util.AppStrings

fun muteDurationItems(context: Context, onPick: (MuteDuration) -> Unit): List<MenuItem> = listOf(
    MenuItem(AppStrings.get(context, R.string.catalog_for_15_minutes_0c40b888)) {
        onPick(MuteDuration.FIFTEEN_MINUTES)
    },
    MenuItem(AppStrings.get(context, R.string.catalog_for_1_hour_32c996ba)) {
        onPick(MuteDuration.ONE_HOUR)
    },
    MenuItem(AppStrings.get(context, R.string.catalog_for_8_hours_3e610b98)) {
        onPick(MuteDuration.EIGHT_HOURS)
    },
    MenuItem(AppStrings.get(context, R.string.catalog_for_24_hours_0a7aea9d)) {
        onPick(MuteDuration.ONE_DAY)
    },
    MenuItem(AppStrings.get(context, R.string.catalog_until_i_turn_it_back_on_d3444633)) {
        onPick(MuteDuration.FOREVER)
    },
)
