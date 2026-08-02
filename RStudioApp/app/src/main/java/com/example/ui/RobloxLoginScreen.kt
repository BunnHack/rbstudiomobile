package com.example.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun RobloxLoginScreen(
    onCookieCaptured: (String) -> Unit,
    onContinueOffline: () -> Unit
) {
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var loading by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf("Sign in to Roblox to enable Toolbox imports and publishing.") }

    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.stopLoading()
            webViewRef?.destroy()
            webViewRef = null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF15171D))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .background(Color(0xFF20232B))
                .border(BorderStroke(1.dp, Color(0xFF343946)))
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Login, contentDescription = null, tint = Color(0xFF00A2FF), modifier = Modifier.size(19.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Roblox Login", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(status, color = Color(0xFFB8BDC8), fontSize = 10.sp, maxLines = 1)
            }
            Box(
                modifier = Modifier
                    .height(28.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color(0xFF2A2D36))
                    .clickable { webViewRef?.reload() }
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Icon(Icons.Default.Refresh, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                    Text("Reload", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .height(28.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color(0xFF343946))
                    .clickable { onContinueOffline() }
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("Continue Offline", color = Color(0xFFE0E3EA), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        webViewRef = this
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.cacheMode = WebSettings.LOAD_DEFAULT
                        settings.userAgentString = settings.userAgentString + " RStudioApp"

                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                loading = newProgress < 100
                                captureRoblosecurityCookie()?.let {
                                    CookieManager.getInstance().flush()
                                    onCookieCaptured(it)
                                }
                            }
                        }
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false

                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                loading = true
                                status = "Loading Roblox sign in..."
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                loading = false
                                status = "Waiting for Roblox login cookie..."
                                captureRoblosecurityCookie()?.let {
                                    CookieManager.getInstance().flush()
                                    onCookieCaptured(it)
                                }
                            }
                        }

                        loadUrl(ROBLOX_LOGIN_URL)
                    }
                }
            )

            if (loading) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xCC111318))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(color = Color(0xFF00A2FF), strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                        Text("Loading", color = Color.White, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

private fun captureRoblosecurityCookie(): String? {
    val manager = CookieManager.getInstance()
    ROBLOX_COOKIE_URLS.forEach { url ->
        extractRoblosecurityCookie(manager.getCookie(url))?.let { return it }
    }
    return null
}

private fun extractRoblosecurityCookie(rawCookie: String?): String? {
    if (rawCookie.isNullOrBlank()) return null
    rawCookie.split(";").forEach { segment ->
        val pair = segment.trim()
        val name = pair.substringBefore("=", missingDelimiterValue = "")
        if (name.equals(".ROBLOSECURITY", ignoreCase = true) || name.equals("ROBLOSECURITY", ignoreCase = true)) {
            return pair.substringAfter("=", missingDelimiterValue = "").takeIf { it.isNotBlank() }
        }
    }
    return null
}

private const val ROBLOX_LOGIN_URL = "https://www.roblox.com/login"
private val ROBLOX_COOKIE_URLS = listOf(
    "https://www.roblox.com",
    "https://roblox.com",
    "https://create.roblox.com",
    "https://apis.roblox.com"
)
