package lt.oranges.orangchat.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import lt.oranges.orangchat.ui.theme.OrangRadius
import lt.oranges.orangchat.ui.theme.OrangTheme

enum class ButtonVariant { Primary, Secondary, Ghost, Danger }
enum class ButtonSize { Sm, Md, Lg, Icon }

/**
 * Port of components/ui/Button.tsx. Variants and sizes match the cva() config;
 * CSS hover states become pressed states (touch has no hover). Corner = rounded-lg.
 */
@Composable
fun OrangButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: ButtonVariant = ButtonVariant.Primary,
    size: ButtonSize = ButtonSize.Md,
    enabled: Boolean = true,
    loading: Boolean = false,
    content: @Composable () -> Unit,
) {
    val c = OrangTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val active = enabled && !loading

    val bg: Color = when (variant) {
        ButtonVariant.Primary -> if (pressed) c.primaryActive else c.primary
        ButtonVariant.Secondary -> if (pressed) c.surface4 else c.surface3
        ButtonVariant.Ghost -> if (pressed) c.surface3 else Color.Transparent
        ButtonVariant.Danger -> if (pressed) c.danger.copy(alpha = 0.9f) else c.danger
    }
    val fg: Color = when (variant) {
        ButtonVariant.Primary -> c.inkOnPrimary
        ButtonVariant.Secondary -> c.ink
        ButtonVariant.Ghost -> if (pressed) c.ink else c.inkSecondary
        ButtonVariant.Danger -> Color.White
    }
    val fontWeight = when (variant) {
        ButtonVariant.Primary, ButtonVariant.Danger -> FontWeight.SemiBold
        else -> FontWeight.Medium
    }

    val (minHeight, hPad) = when (size) {
        ButtonSize.Sm -> 32.dp to 12.dp
        ButtonSize.Md -> 40.dp to 16.dp
        ButtonSize.Lg -> 44.dp to 20.dp
        ButtonSize.Icon -> 40.dp to 0.dp
    }
    val minWidth = if (size == ButtonSize.Icon) 40.dp else 0.dp
    val shape = RoundedCornerShape(OrangRadius.lg)

    var mod = modifier
        .defaultMinSize(minWidth = minWidth, minHeight = minHeight)
        .clip(shape)
        .background(if (active) bg else bg.copy(alpha = 0.6f))
    if (variant == ButtonVariant.Secondary) {
        mod = mod.border(BorderStroke(1.dp, c.borderStrong), shape)
    }
    mod = mod
        .clickable(
            enabled = active,
            interactionSource = interaction,
            indication = null,
            onClick = onClick,
        )
        .padding(PaddingValues(horizontal = hPad))

    Row(
        modifier = mod,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                color = fg,
                strokeWidth = 2.dp,
            )
        }
        ProvideTextStyle(
            LocalTextStyle.current.copy(color = fg, fontWeight = fontWeight, fontSize = 14.sp),
        ) {
            content()
        }
    }
}

/** Convenience overload for a text-labelled button. */
@Composable
fun OrangButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: ButtonVariant = ButtonVariant.Primary,
    size: ButtonSize = ButtonSize.Md,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    OrangButton(onClick, modifier, variant, size, enabled, loading) {
        Text(text)
    }
}
