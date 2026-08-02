package com.example.ui.properties

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronRight
import com.example.models.Vector3

// Colors for the properties grid
private val GridDivider = Color(0xFF2A2A2A)
private val LabelColor = Color(0xFF8A8A8A)
private val ValueColor = Color(0xFFCCCCCC)
private val InputBg = Color(0xFF1C1C1C)
private val InputBorder = Color(0xFF333333)
val AccentColor = Color(0xFF00A2FF) // exported for use in StudioScreen
private val TreeLineColor = Color(0xFF9C5BFF) // purple
private val TreeLineAlpha = 0.6f
private val SubLabelColor = Color(0xFF666666)

// ===== Grid row primitives =====

/**
 * A single row in the properties grid: fixed-width label on the left, value on the right,
 * separated by a thin bottom divider. Mimics an Excel/IDE properties table.
 */
@Composable
fun GridRow(
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF0F0F0F))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(26.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Fixed-width label column
            Text(
                text = label,
                color = LabelColor,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .width(112.dp)
                    .padding(start = 10.dp)
            )
            // Value column
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                content()
            }
        }
        // Thin divider
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(GridDivider)
        )
    }
}

/**
 * A collapsible Vector3 row. Collapsed: shows "x, y, z" as inline text (clickable to expand).
 * Expanded: shows X/Y/Z sub-rows with a purple tree guide line drawn via Canvas.
 */
@Composable
fun CollapsibleVector3Row(
    label: String,
    value: Vector3,
    onValueChange: (Vector3) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0F0F0F))
    ) {
        // Collapsed row: label + inline value text + expand chevron
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(26.dp)
                .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Chevron icon
            androidx.compose.material3.Icon(
                imageVector = if (expanded) Lucide.ChevronDown else Lucide.ChevronRight,
                contentDescription = null,
                tint = Color(0xFF555555),
                modifier = Modifier
                    .padding(start = 4.dp)
                    .width(14.dp)
                    .height(14.dp)
            )
            // Label
            Text(
                text = label,
                color = LabelColor,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .width(76.dp)
                    .padding(start = 2.dp)
            )
            // Inline editable value — click to edit "x, y, z" directly; chevron to expand sub-rows
            var inlineText by remember(value) { mutableStateOf("%.1f, %.1f, %.1f".format(value.x, value.y, value.z)) }
            BasicTextField(
                value = inlineText,
                onValueChange = { input ->
                    inlineText = input
                    // Parse "x, y, z" or "x y z" format and commit if valid
                    val parts = input.split(",", " ", "\t").mapNotNull { it.trim().toFloatOrNull() }
                    if (parts.size == 3) {
                        onValueChange(Vector3(parts[0], parts[1], parts[2]))
                    }
                },
                singleLine = true,
                textStyle = TextStyle(
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color.White
                ),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(AccentColor),
                decorationBox = { innerTextField ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(22.dp)
                            .background(InputBg, androidx.compose.foundation.shape.RoundedCornerShape(3.dp))
                            .border(1.dp, InputBorder, androidx.compose.foundation.shape.RoundedCornerShape(3.dp))
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        innerTextField()
                    }
                }
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(GridDivider)
        )

        // Expanded sub-rows with purple tree guide lines
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Column {
                SubVector3Row("X", value.x, TreeLineColor) { v ->
                    onValueChange(value.copy(x = v))
                }
                SubVector3Row("Y", value.y, TreeLineColor) { v ->
                    onValueChange(value.copy(y = v))
                }
                SubVector3Row("Z", value.z, TreeLineColor) { v ->
                    onValueChange(value.copy(z = v))
                }
            }
        }
    }
}

/**
 * A single X/Y/Z sub-row with a purple L-shaped tree guide line drawn on the left.
 */
@Composable
private fun SubVector3Row(
    axis: String,
    value: Float,
    lineColor: Color,
    onValueChange: (Float) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(30.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Purple tree guide line: vertical + horizontal L-shape, drawn via Canvas
        Canvas(
            modifier = Modifier
                .width(90.dp)
                .height(30.dp)
        ) {
            val verticalX = 16.dp.toPx()
            val horizontalEnd = 32.dp.toPx()
            val centerY = size.height / 2f
            // Vertical line (full height)
            drawLine(
                color = lineColor.copy(alpha = TreeLineAlpha),
                start = Offset(verticalX, 0f),
                end = Offset(verticalX, size.height),
                strokeWidth = 1.dp.toPx()
            )
            // Horizontal L-shape to sub-label
            drawLine(
                color = lineColor.copy(alpha = TreeLineAlpha),
                start = Offset(verticalX, centerY),
                end = Offset(horizontalEnd, centerY),
                strokeWidth = 1.dp.toPx()
            )
        }
        // Axis label
        Text(
            text = axis,
            color = SubLabelColor,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(16.dp)
        )
        // Compact input
        CompactNumberInput(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp)
        )
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(GridDivider.copy(alpha = 0.5f))
    )
}

// ===== Compact input controls =====

/**
 * Ultra-compact numeric input: flat border, deep gray bg, 10sp font.
 * Uses a fixed height of 28dp with internal padding so the text is fully visible.
 */
@Composable
fun CompactNumberInput(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var text by remember(value) { mutableStateOf("%.2f".format(value)) }
    BasicTextField(
        value = text,
        onValueChange = { newText ->
            text = newText
            newText.toFloatOrNull()?.let { f -> onValueChange(f) }
        },
        singleLine = true,
        textStyle = TextStyle(
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = Color.White
        ),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(AccentColor),
        decorationBox = { innerTextField ->
            Box(
                modifier = Modifier
                    .height(26.dp)
                    .background(InputBg, androidx.compose.foundation.shape.RoundedCornerShape(3.dp))
                    .border(1.dp, InputBorder, androidx.compose.foundation.shape.RoundedCornerShape(3.dp))
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                innerTextField()
            }
        },
        modifier = modifier
    )
}

/**
 * Ultra-compact text input for string properties.
 */
@Composable
fun CompactTextInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(fontSize = 10.sp, color = Color.White),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(AccentColor),
        decorationBox = { innerTextField ->
            Box(
                modifier = Modifier
                    .height(26.dp)
                    .background(InputBg, androidx.compose.foundation.shape.RoundedCornerShape(3.dp))
                    .border(1.dp, InputBorder, androidx.compose.foundation.shape.RoundedCornerShape(3.dp))
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                innerTextField()
            }
        },
        modifier = modifier
    )
}

/**
 * Compact dropdown selector row in the grid.
 */
@Composable
fun GridDropdownRow(
    label: String,
    selectedValue: String,
    options: List<String>,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    GridRow(label = label) {
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(24.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(InputBg)
                    .border(1.dp, InputBorder, RoundedCornerShape(3.dp))
                    .clickable { expanded = true }
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = selectedValue,
                    color = ValueColor,
                    fontSize = 10.sp,
                    modifier = Modifier.weight(1f)
                )
                androidx.compose.material3.Icon(
                    imageVector = Lucide.ChevronDown,
                    contentDescription = null,
                    tint = Color(0xFF666666),
                    modifier = Modifier.width(12.dp).height(12.dp)
                )
            }
            androidx.compose.material3.DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.background(Color(0xFF222222))
            ) {
                options.forEach { opt ->
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(opt, color = Color.White, fontSize = 11.sp) },
                        onClick = { onSelect(opt); expanded = false }
                    )
                }
            }
        }
    }
}

/**
 * Compact switch row in the grid.
 */
@Composable
fun GridSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    GridRow(label = label) {
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = AccentColor,
                uncheckedTrackColor = Color(0xFF333333),
                uncheckedThumbColor = Color.Gray
            ),
            modifier = Modifier.height(20.dp)
        )
    }
}

/**
 * Compact slider row in the grid (with value display).
 */
@Composable
fun GridSliderRow(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    GridRow(label = label) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "%.2f".format(value),
                color = AccentColor,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.width(32.dp)
            )
            Slider(
                value = value,
                onValueChange = onValueChange,
                valueRange = valueRange,
                colors = SliderDefaults.colors(
                    thumbColor = AccentColor,
                    activeTrackColor = AccentColor,
                    inactiveTrackColor = Color(0xFF333333)
                ),
                modifier = Modifier
                    .weight(1f)
                    .height(20.dp)
            )
        }
    }
}
