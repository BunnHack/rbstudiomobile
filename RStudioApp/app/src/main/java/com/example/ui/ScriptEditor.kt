package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.viewmodels.StudioViewModel
import kotlin.math.max

@Composable
fun ScriptEditor(
    viewModel: StudioViewModel,
    documentId: String,
    title: String,
    initialSource: String,
    targetPath: String,
    onSaveSource: (String) -> Unit,
    onClose: () -> Unit
) {
    val initialCode = remember(documentId, initialSource, targetPath) {
        initialSource.ifBlank {
            "-- Lua Script Editor\n-- $targetPath\n\nwhile true do\n  wait(1.0)\n  print(\"Tick!\")\nend"
        }
    }
    var code by remember(documentId) { mutableStateOf(initialCode) }
    var dirty by remember(documentId) { mutableStateOf(false) }
    val lineCount = max(1, code.lines().size)

    fun saveScript() {
        onSaveSource(code)
        dirty = false
        viewModel.logSystem("Compiled and saved script to $targetPath.")
    }

    // Auto-save on close: callers route the close action through the document tab
    // strip; the latest source is persisted when the editor leaves the composition.
    androidx.compose.runtime.DisposableEffect(documentId) {
        onDispose {
            if (dirty) {
                onSaveSource(code)
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = EditorChrome
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            CodeEditorPane(
                code = code,
                lineCount = lineCount,
                onCodeChange = {
                    code = it
                    dirty = true
                },
                modifier = Modifier.weight(1f)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(24.dp)
                    .background(StatusBarBackground)
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    color = MutedText,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "Lua  |  $lineCount lines  |  $targetPath",
                    color = MutedText,
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
private fun CodeEditorPane(
    code: String,
    lineCount: Int,
    onCodeChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val verticalScroll = rememberScrollState()
    val horizontalScroll = rememberScrollState()
    val lineNumbers = remember(lineCount) {
        (1..lineCount).joinToString("\n") { it.toString() }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(EditorBackground)
    ) {
        val viewportHeight = maxHeight

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = viewportHeight)
                .verticalScroll(verticalScroll)
        ) {
            Text(
                text = lineNumbers,
                color = LineNumberText,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
                modifier = Modifier
                    .width(43.dp)
                    .background(LineGutterBackground)
                    .padding(top = 8.dp, end = 8.dp)
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = viewportHeight)
                    .horizontalScroll(horizontalScroll)
                    .padding(top = 8.dp, start = 10.dp, end = 20.dp, bottom = 18.dp)
            ) {
                BasicTextField(
                    value = code,
                    onValueChange = onCodeChange,
                    cursorBrush = SolidColor(Color(0xFF4FB2FF)),
                    textStyle = TextStyle(
                        color = CodeText,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        lineHeight = 19.sp
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(min = 920.dp)
                )
            }
        }
    }
}

private val EditorChrome = Color(0xFF1B1B1B)
private val EditorBackground = Color(0xFF1F1F1F)
private val LineGutterBackground = Color(0xFF181818)
private val StatusBarBackground = Color(0xFF202020)
private val LineNumberText = Color(0xFF9D9D9D)
private val MutedText = Color(0xFFA8A8A8)
private val CodeText = Color(0xFFB6F4C4)
