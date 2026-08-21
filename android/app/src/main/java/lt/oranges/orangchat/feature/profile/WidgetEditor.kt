package lt.oranges.orangchat.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import lt.oranges.orangchat.R
import lt.oranges.orangchat.data.model.ProfileWidget
import lt.oranges.orangchat.data.model.ProfileWidgetConfigField
import lt.oranges.orangchat.data.model.ProfileWidgetDefinition
import lt.oranges.orangchat.ui.components.ButtonVariant
import lt.oranges.orangchat.ui.components.MenuItem
import lt.oranges.orangchat.ui.components.OrangButton
import lt.oranges.orangchat.ui.components.OrangDropdownMenu
import lt.oranges.orangchat.ui.components.OrangTextField
import lt.oranges.orangchat.ui.components.Text
import lt.oranges.orangchat.ui.theme.OrangRadius
import lt.oranges.orangchat.ui.theme.OrangTheme
import lt.oranges.orangchat.util.AppStrings
import lt.oranges.orangchat.util.fallbackWidgetDefinition
import lt.oranges.orangchat.util.widgetString
import kotlin.random.Random

private const val MAX_WIDGETS = 24

private fun newWidgetId(): String =
    Random.nextLong(0, Long.MAX_VALUE).toString(36).take(8)

private fun JsonElement?.asText(): String =
    (this as? JsonPrimitive)?.let { if (it.isString) it.content else it.content }.orEmpty()

private fun JsonElement?.asBool(): Boolean = (this as? JsonPrimitive)?.content == "true"

private fun JsonElement?.asItems(): List<JsonObject> =
    (this as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()

private fun Map<String, JsonElement>.withValue(key: String, value: JsonElement?): Map<String, JsonElement> =
    if (value == null) this - key else this + (key to value)

@Composable
private fun ConfigInput(
    field: ProfileWidgetConfigField,
    value: JsonElement?,
    onChange: (JsonElement?) -> Unit,
) {
    val context = LocalContext.current
    val c = OrangTheme.colors
    val label = widgetString(context, field.label)

    when (field.kind) {
        "boolean" -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Checkbox(
                checked = value.asBool(),
                onCheckedChange = { onChange(if (it) JsonPrimitive(true) else null) },
            )
            Text(label, color = c.ink, fontSize = 14.sp)
        }

        "select" -> {
            var open by remember { mutableStateOf(false) }
            val current = value.asText()
            val selected = field.options.firstOrNull { it.value == current }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(label, color = c.inkSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Box {
                    Text(
                        text = selected?.let { widgetString(context, it.label) }
                            ?: AppStrings.get(context, R.string.widget_editor_default_option),
                        color = c.ink,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(c.surface1, RoundedCornerShape(OrangRadius.lg))
                            .border(1.dp, c.border, RoundedCornerShape(OrangRadius.lg))
                            .clickable { open = true }
                            .padding(horizontal = 10.dp, vertical = 10.dp),
                    )
                    OrangDropdownMenu(
                        expanded = open,
                        onDismiss = { open = false },
                        items = buildList {
                            add(
                                MenuItem(
                                    label = AppStrings.get(context, R.string.widget_editor_default_option),
                                    onClick = { onChange(null) },
                                ),
                            )
                            field.options.forEach { option ->
                                add(
                                    MenuItem(
                                        label = widgetString(context, option.label),
                                        onClick = { onChange(JsonPrimitive(option.value)) },
                                    ),
                                )
                            }
                        },
                    )
                }
            }
        }

        "list" -> {
            val items = value.asItems()
            val max = field.max ?: 12
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(label, color = c.inkSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                items.forEachIndexed { index, item ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(c.surface2, RoundedCornerShape(OrangRadius.lg))
                            .border(1.dp, c.border, RoundedCornerShape(OrangRadius.lg))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        field.of.forEach { inner ->
                            ConfigInput(
                                field = inner,
                                value = item[inner.key],
                                onChange = { next ->
                                    val updated = items.toMutableList()
                                    updated[index] = JsonObject(item.toMap().withValue(inner.key, next))
                                    onChange(JsonArray(updated))
                                },
                            )
                        }
                        Text(
                            text = AppStrings.get(context, R.string.widget_editor_remove),
                            color = c.inkMuted,
                            fontSize = 12.sp,
                            modifier = Modifier.clickable {
                                val updated = items.filterIndexed { i, _ -> i != index }
                                onChange(if (updated.isEmpty()) null else JsonArray(updated))
                            },
                        )
                    }
                }
                if (items.size < max) {
                    OrangButton(
                        text = AppStrings.get(context, R.string.widget_editor_add_row),
                        onClick = { onChange(JsonArray(items + JsonObject(emptyMap()))) },
                        variant = ButtonVariant.Secondary,
                    )
                }
            }
        }

        else -> OrangTextField(
            value = value.asText(),
            onValueChange = { text ->
                val trimmed = if (field.kind == "string" && field.max != null) text.take(field.max) else text
                onChange(trimmed.ifBlank { null }?.let(::JsonPrimitive))
            },
            label = label,
            placeholder = field.placeholder?.let { widgetString(context, it) }
                ?: "https://".takeIf { field.kind == "url" },
            singleLine = !field.multiline,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun WidgetRow(
    widget: ProfileWidget,
    definition: ProfileWidgetDefinition?,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onPatch: (ProfileWidget) -> Unit,
    onRemove: () -> Unit,
    dragging: Boolean,
    dragOffsetPx: Float,
    onDragStart: (rowHeightPx: Float) -> Unit,
    onDragDelta: (Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    val context = LocalContext.current
    val c = OrangTheme.colors
    val shape = RoundedCornerShape(OrangRadius.xl)
    val config = definition?.config.orEmpty()
    val hidden = widget.hidden

    val latestOnDragStart by rememberUpdatedState(onDragStart)
    val latestOnDragDelta by rememberUpdatedState(onDragDelta)
    val latestOnDragEnd by rememberUpdatedState(onDragEnd)
    var rowHeightPx by remember { mutableStateOf(0f) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { translationY = dragOffsetPx }
            .zIndex(if (dragging) 1f else 0f)
            .onGloballyPositioned { rowHeightPx = it.size.height.toFloat() }
            .background(if (dragging) c.surface2 else c.surface1, shape)
            .border(1.dp, if (dragging) c.primary.copy(alpha = 0.5f) else c.border, shape),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            DragHandleDots(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(OrangRadius.md))
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { latestOnDragStart(rowHeightPx) },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                latestOnDragDelta(dragAmount.y)
                            },
                            onDragEnd = { latestOnDragEnd() },
                            onDragCancel = { latestOnDragEnd() },
                        )
                    }
                    .padding(6.dp),
                contentDescription = AppStrings.get(context, R.string.widget_editor_reorder),
            )
            Text(
                text = definition?.let { widgetString(context, it.label) } ?: widget.type,
                color = if (hidden) c.inkMuted else c.ink,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            RowIcon(
                icon = if (hidden) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                description = AppStrings.get(
                    context,
                    if (hidden) R.string.widget_editor_show else R.string.widget_editor_hide,
                ),
                onClick = { onPatch(widget.copy(hidden = !hidden)) },
            )
            if (config.isNotEmpty()) {
                RowIcon(
                    icon = Icons.Default.Settings,
                    description = AppStrings.get(context, R.string.widget_editor_configure),
                    tint = if (expanded) c.ink else c.inkMuted,
                    onClick = onToggleExpand,
                )
            }
            RowIcon(
                icon = Icons.Default.Delete,
                description = AppStrings.get(context, R.string.widget_editor_remove),
                onClick = onRemove,
            )
        }

        if (expanded && config.isNotEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                definition?.description?.let {
                    Text(widgetString(context, it), color = c.inkMuted, fontSize = 12.sp)
                }
                config.forEach { field ->
                    ConfigInput(
                        field = field,
                        value = widget.config[field.key],
                        onChange = { next ->
                            onPatch(widget.copy(config = widget.config.withValue(field.key, next)))
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun RowIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: Color? = null,
) {
    val c = OrangTheme.colors
    val resolved = (tint ?: c.inkMuted).copy(alpha = if (enabled) 1f else 0.3f)
    Icon(
        imageVector = icon,
        contentDescription = description,
        tint = resolved,
        modifier = Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(OrangRadius.md))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(6.dp),
    )
}

@Composable
private fun DragHandleDots(modifier: Modifier = Modifier, contentDescription: String) {
    val c = OrangTheme.colors
    Column(
        modifier = modifier.semantics { this.contentDescription = contentDescription },
        verticalArrangement = Arrangement.spacedBy(3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        repeat(3) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                repeat(2) {
                    Box(
                        modifier = Modifier
                            .size(3.5.dp)
                            .background(c.inkMuted, CircleShape),
                    )
                }
            }
        }
    }
}

/**
 * Reorder / toggle / configure the blocks of the profile card. The order of
 * this list is the order of the card, so there is no separate position to keep
 * in sync - moving a row is the whole edit.
 */
@Composable
fun WidgetEditor(
    value: List<ProfileWidget>,
    onChange: (List<ProfileWidget>) -> Unit,
    catalog: WidgetCatalogState,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val c = OrangTheme.colors
    var expanded by remember { mutableStateOf<String?>(null) }
    var addOpen by remember { mutableStateOf(false) }

    val move = { from: Int, to: Int ->
        if (to in value.indices && from != to) {
            val next = value.toMutableList()
            next.add(to, next.removeAt(from))
            onChange(next)
        }
    }

    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragOffsetPx by remember { mutableStateOf(0f) }
    var draggedRowHeightPx by remember { mutableStateOf(0f) }

    val addable = catalog.ordered.filter { definition ->
        !definition.singleton || value.none { it.type == definition.type }
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = AppStrings.get(context, R.string.profile_widgets_intro),
            color = c.inkMuted,
            fontSize = 12.sp,
        )

        value.forEachIndexed { index, widget ->
            val id = widget.id ?: "$index"
            key(id) {
                WidgetRow(
                    widget = widget,
                    definition = catalog.definitions[widget.type] ?: fallbackWidgetDefinition(widget.type),
                    expanded = expanded == id,
                    onToggleExpand = { expanded = if (expanded == id) null else id },
                    onPatch = { next ->
                        onChange(value.mapIndexed { i, w -> if (i == index) next else w })
                    },
                    onRemove = { onChange(value.filterIndexed { i, _ -> i != index }) },
                    dragging = draggingId == id,
                    dragOffsetPx = if (draggingId == id) dragOffsetPx else 0f,
                    onDragStart = { rowHeightPx ->
                        draggingId = id
                        dragOffsetPx = 0f
                        draggedRowHeightPx = rowHeightPx
                    },
                    onDragDelta = { deltaY ->
                        dragOffsetPx += deltaY
                        val h = draggedRowHeightPx
                        if (h > 1f) {
                            if (dragOffsetPx > h / 2 && index < value.lastIndex) {
                                move(index, index + 1)
                                dragOffsetPx -= h
                            } else if (dragOffsetPx < -h / 2 && index > 0) {
                                move(index, index - 1)
                                dragOffsetPx += h
                            }
                        }
                    },
                    onDragEnd = {
                        draggingId = null
                        dragOffsetPx = 0f
                    },
                )
            }
        }

        if (value.isEmpty()) {
            Text(
                text = AppStrings.get(context, R.string.widget_editor_empty),
                color = c.inkMuted,
                fontSize = 13.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(c.surface1, RoundedCornerShape(OrangRadius.xl))
                    .border(1.dp, c.border, RoundedCornerShape(OrangRadius.xl))
                    .padding(vertical = 20.dp, horizontal = 12.dp),
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box {
                OrangButton(
                    onClick = { addOpen = true },
                    variant = ButtonVariant.Secondary,
                    enabled = addable.isNotEmpty() && value.size < MAX_WIDGETS,
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(AppStrings.get(context, R.string.widget_editor_add_widget))
                    }
                }
                OrangDropdownMenu(
                    expanded = addOpen,
                    onDismiss = { addOpen = false },
                    items = addable.map { definition ->
                        MenuItem(
                            label = widgetString(context, definition.label),
                            onClick = {
                                val widget = ProfileWidget(id = newWidgetId(), type = definition.type)
                                onChange(value + widget)
                                if (definition.config.isNotEmpty()) expanded = widget.id
                            },
                        )
                    },
                )
            }

            OrangButton(
                text = AppStrings.get(context, R.string.widget_editor_reset),
                onClick = { onChange(catalog.defaultLayout) },
                variant = ButtonVariant.Ghost,
            )

            Text(
                text = AppStrings.get(context, R.string.widget_editor_count)
                    .format(value.size, MAX_WIDGETS),
                color = c.inkMuted,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
