package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.models.Place

private val SettingsBg = Color(0xFF12151A)
private val SettingsPanel = Color(0xFF171A20)
private val SettingsSidebar = Color(0xFF20242B)
private val SettingsTop = Color(0xFF272A31)
private val SettingsInput = Color(0xFF252932)
private val SettingsBorder = Color(0xFF2F343D)
private val SettingsText = Color(0xFFE5E7EC)
private val SettingsMuted = Color(0xFFB6BAC4)
private val SettingsDim = Color(0xFF858A96)
private val SettingsAccent = Color(0xFF293A8E)

private enum class GameSettingsSection(val label: String) {
    BasicInfo("Basic Info"),
    Communication("Communication"),
    Monetization("Monetization"),
    Security("Security"),
    Places("Places"),
    Localization("Localization"),
    Avatar("Avatar"),
    World("World"),
    Other("Other")
}

@Composable
fun GameSettingsDialog(
    place: Place?,
    onDismiss: () -> Unit,
    onSave: (name: String, description: String) -> Unit
) {
    var selectedSection by remember { mutableStateOf(GameSettingsSection.BasicInfo) }
    var name by remember(place?.id) { mutableStateOf(place?.name.orEmpty().take(50)) }
    var description by remember(place?.id) { mutableStateOf(place?.description.orEmpty().take(1000)) }
    val canSave = name.isNotBlank() && name.length <= 50 && description.length <= 1000

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .heightIn(min = 520.dp, max = 700.dp)
                .widthIn(max = 980.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(SettingsBg)
                .border(BorderStroke(1.dp, Color(0xFF30343D)), RoundedCornerShape(2.dp))
        ) {
            SettingsManageBanner()

            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                SettingsSideNav(
                    selected = selectedSection,
                    onSelected = { selectedSection = it }
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(SettingsPanel)
                        .padding(horizontal = 26.dp, vertical = 20.dp)
                ) {
                    when (selectedSection) {
                        GameSettingsSection.BasicInfo -> BasicInfoContent(
                            name = name,
                            onNameChange = { name = it.take(50) },
                            description = description,
                            onDescriptionChange = { description = it.take(1000) }
                        )
                        else -> SettingsPlaceholderContent(selectedSection)
                    }
                }
            }

            SettingsFooter(
                canSave = canSave,
                onCancel = onDismiss,
                onSave = {
                    if (canSave) {
                        onSave(name.trim(), description.trim())
                    }
                }
            )
        }
    }
}

@Composable
private fun SettingsManageBanner() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .background(SettingsTop)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Info, contentDescription = null, tint = Color(0xFF8EB6FF), modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = "Manage permissions and privacy settings on Creator Hub",
            color = SettingsText,
            fontSize = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        OutlinedButton(
            onClick = { },
            border = BorderStroke(1.dp, SettingsBorder),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = SettingsText),
            shape = RoundedCornerShape(3.dp),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
            modifier = Modifier.height(32.dp)
        ) {
            Text("Manage", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun SettingsSideNav(
    selected: GameSettingsSection,
    onSelected: (GameSettingsSection) -> Unit
) {
    Column(
        modifier = Modifier
            .width(194.dp)
            .fillMaxHeight()
            .background(SettingsSidebar)
            .border(BorderStroke(0.5.dp, Color(0xFF101216)))
    ) {
        GameSettingsSection.entries.forEach { section ->
            val isSelected = section == selected
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .background(if (isSelected) SettingsAccent else Color.Transparent)
                    .clickable { onSelected(section) }
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    text = section.label,
                    color = if (isSelected) Color.White else SettingsMuted,
                    fontSize = 16.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = "Manage on Creator Hub",
            color = Color(0xFF74A5FF),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(18.dp)
        )
    }
}

@Composable
private fun BasicInfoContent(
    name: String,
    onNameChange: (String) -> Unit,
    description: String,
    onDescriptionChange: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Text("Basic Info", color = SettingsText, fontSize = 18.sp, fontWeight = FontWeight.Bold)

        Spacer(modifier = Modifier.height(30.dp))
        SettingsLabeledField(label = "Name") {
            Column {
                SettingsTextField(
                    value = name,
                    onValueChange = onNameChange,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().height(34.dp)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text("${name.length}/50", color = SettingsDim, fontSize = 13.sp)
            }
        }

        Spacer(modifier = Modifier.height(28.dp))
        SettingsLabeledField(label = "Description") {
            Column {
                SettingsTextField(
                    value = description,
                    onValueChange = onDescriptionChange,
                    singleLine = false,
                    modifier = Modifier.fillMaxWidth().height(130.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text("${description.length}/1000", color = SettingsDim, fontSize = 13.sp)
            }
        }

        Spacer(modifier = Modifier.height(28.dp))
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(SettingsBorder))

        Spacer(modifier = Modifier.height(28.dp))
        SettingsLabeledField(label = "Content Maturity Label") {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Not Submitted", color = SettingsDim, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedButton(
                    onClick = { },
                    border = BorderStroke(1.dp, SettingsBorder),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = SettingsText),
                    shape = RoundedCornerShape(3.dp),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)
                ) {
                    Text("Fill Out Questionnaire", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(modifier = Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFFFA726), modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "This experience is unplayable. Complete the Maturity & Compliance questionnaire to make it available.",
                        color = SettingsMuted,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsLabeledField(
    label: String,
    content: @Composable () -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            text = label,
            color = SettingsMuted,
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.width(190.dp).padding(top = 5.dp)
        )
        Box(modifier = Modifier.weight(1f)) {
            content()
        }
    }
}

@Composable
private fun SettingsTextField(
    value: String,
    onValueChange: (String) -> Unit,
    singleLine: Boolean,
    modifier: Modifier = Modifier
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        textStyle = TextStyle(color = SettingsText, fontSize = 15.sp, lineHeight = 20.sp),
        modifier = modifier
            .clip(RoundedCornerShape(3.dp))
            .background(SettingsInput)
            .border(BorderStroke(1.dp, Color(0xFF20232B)), RoundedCornerShape(3.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    )
}

@Composable
private fun SettingsPlaceholderContent(section: GameSettingsSection) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(section.label, color = SettingsText, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(28.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFF20242C))
                .border(BorderStroke(1.dp, SettingsBorder), RoundedCornerShape(4.dp))
                .padding(18.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "${section.label} settings are ready for controls.",
                    color = SettingsText,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "This panel matches the Game Settings navigation layout and can be wired to project-level options next.",
                    color = SettingsMuted,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            }
        }
    }
}

@Composable
private fun SettingsFooter(
    canSave: Boolean,
    onCancel: () -> Unit,
    onSave: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .background(SettingsBg)
            .border(BorderStroke(0.5.dp, SettingsBorder))
            .padding(horizontal = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End
    ) {
        OutlinedButton(
            onClick = onCancel,
            border = BorderStroke(1.dp, SettingsBorder),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = SettingsText),
            shape = RoundedCornerShape(3.dp),
            modifier = Modifier.width(126.dp).height(36.dp)
        ) {
            Text("Cancel", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(modifier = Modifier.width(26.dp))
        Button(
            onClick = onSave,
            enabled = canSave,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF2B60D3),
                disabledContainerColor = Color(0xFF22252B),
                disabledContentColor = Color(0xFF575C66)
            ),
            shape = RoundedCornerShape(3.dp),
            modifier = Modifier.width(126.dp).height(36.dp)
        ) {
            Text("Save", fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
    }
}
