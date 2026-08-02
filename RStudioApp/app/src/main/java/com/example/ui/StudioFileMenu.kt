package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val MenuBarBg = Color(0xFF121318)
private val MenuPopupBg = Color(0xFF262832)
private val MenuHoverBg = Color(0xFF333642)
private val MenuDivider = Color(0xFF3A3D47)
private val MenuText = Color(0xFFE3E5EA)
private val MenuMuted = Color(0xFFB5BAC6)

@Composable
fun StudioMenuBar(
    onNew: () -> Unit,
    onOpenFile: () -> Unit,
    onClosePlace: () -> Unit,
    onSave: () -> Unit,
    onPublish: () -> Unit,
    onGameSettings: () -> Unit,
    onGenericAction: (String) -> Unit
) {
    var fileExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(24.dp)
            .background(MenuBarBg)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box {
            TopMenuLabel("File", active = fileExpanded) { fileExpanded = true }
            DropdownMenu(
                expanded = fileExpanded,
                onDismissRequest = { fileExpanded = false },
                modifier = Modifier
                    .background(MenuPopupBg)
                    .width(218.dp)
            ) {
                FileMenuItem("New", "Ctrl+N") {
                    fileExpanded = false
                    onNew()
                }
                FileMenuItem("Open from File", "Ctrl+O") {
                    fileExpanded = false
                    onOpenFile()
                }
                FileMenuItem("Open from Roblox", "Ctrl+Shift+O") {
                    fileExpanded = false
                    onGenericAction("Open from Roblox is not connected to cloud services yet.")
                }
                FileMenuItem("Recent", ">") {
                    fileExpanded = false
                    onGenericAction("Recent places are available on the launcher Home page.")
                }
                FileMenuDivider()
                FileMenuItem("Close Place", "Ctrl+F4") {
                    fileExpanded = false
                    onClosePlace()
                }
                FileMenuDivider()
                FileMenuItem("Import 3D", "Ctrl+M") {
                    fileExpanded = false
                    onOpenFile()
                }
                FileMenuItem("Import Roblox Model", "") {
                    fileExpanded = false
                    onOpenFile()
                }
                FileMenuItem("Export as .obj", "") {
                    fileExpanded = false
                    onGenericAction("Export as .obj is not implemented.")
                }
                FileMenuDivider()
                FileMenuItem("Save to File", "") {
                    fileExpanded = false
                    onSave()
                }
                FileMenuItem("Save to File As", "Ctrl+Shift+S") {
                    fileExpanded = false
                    onSave()
                }
                FileMenuItem("Save to Roblox", "") {
                    fileExpanded = false
                    onSave()
                }
                FileMenuItem("Save to Roblox As", "") {
                    fileExpanded = false
                    onSave()
                }
                FileMenuDivider()
                FileMenuItem("Publish to Roblox", "Alt+P", emphasized = true) {
                    fileExpanded = false
                    onPublish()
                }
                FileMenuItem("Publish to Roblox As", "Alt+Shift+P") {
                    fileExpanded = false
                    onPublish()
                }
                FileMenuDivider()
                FileMenuItem("Game Settings", "") {
                    fileExpanded = false
                    onGameSettings()
                }
                FileMenuItem("Avatar Settings", "") {
                    fileExpanded = false
                    onGenericAction("Avatar settings panel is not implemented.")
                }
                FileMenuItem("Studio Settings", "Alt+S") {
                    fileExpanded = false
                    onGenericAction("Studio settings panel is not implemented.")
                }
                FileMenuItem("Beta Features", "") {
                    fileExpanded = false
                    onGenericAction("Beta Features is not implemented.")
                }
                FileMenuItem("Customize Shortcuts", "") {
                    fileExpanded = false
                    onGenericAction("Customize Shortcuts is not implemented.")
                }
                FileMenuDivider()
                FileMenuItem("Open Auto Saves", "") {
                    fileExpanded = false
                    onGenericAction("Auto saves are stored in the local place database.")
                }
                FileMenuDivider()
                FileMenuItem("About Roblox Studio", "") {
                    fileExpanded = false
                    onGenericAction("RStudioApp local Roblox Studio prototype.")
                }
                FileMenuDivider()
                FileMenuItem("Exit", "") {
                    fileExpanded = false
                    onClosePlace()
                }
            }
        }

        listOf("Edit", "View", "Plugins", "Test", "Window", "Help").forEach { label ->
            TopMenuLabel(label, active = false) {
                onGenericAction("$label menu is not implemented.")
            }
        }
    }
}

@Composable
private fun TopMenuLabel(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(22.dp)
            .background(if (active) MenuPopupBg else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = MenuText,
            fontSize = 12.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium
        )
    }
}

@Composable
private fun FileMenuItem(
    label: String,
    shortcut: String,
    emphasized: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(26.dp)
            .background(if (emphasized) MenuHoverBg else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            color = MenuText,
            fontSize = 12.sp,
            maxLines = 1,
            modifier = Modifier.widthIn(max = 142.dp)
        )
        if (shortcut.isNotEmpty()) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = shortcut,
                color = MenuMuted,
                fontSize = 11.sp,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun FileMenuDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MenuDivider)
    )
}
