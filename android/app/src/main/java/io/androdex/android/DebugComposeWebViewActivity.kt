package io.androdex.android

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.os.Build
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

class DebugComposeWebViewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = applicationContext.getSharedPreferences("androdex_web_shell", MODE_PRIVATE)
        val pairedUrl = prefs.getString("last_opened_url", null)
            ?: prefs.getString("bootstrap_pairing_url", null)
            ?: prefs.getString("paired_origin", null)

        setContent {
            DebugComposeWebView(pairedUrl = pairedUrl)
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun DebugComposeWebView(pairedUrl: String?) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                val defaultUserAgent = settings.userAgentString.orEmpty()
                if (!defaultUserAgent.contains("AndrodexAndroidWebView")) {
                    settings.userAgentString = "$defaultUserAgent AndrodexAndroidWebView/1.0".trim()
                }
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.allowFileAccess = false
                settings.allowContentAccess = true
                settings.cacheMode = WebSettings.LOAD_DEFAULT
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    settings.safeBrowsingEnabled = true
                }
                setBackgroundColor(Color.BLACK)
                if (pairedUrl.isNullOrBlank()) {
                    loadDataWithBaseURL(
                        null,
                        """
                        <html>
                        <body style="margin:0;background:#0b57d0;color:white;display:flex;align-items:center;justify-content:center;height:100vh;font:700 48px sans-serif">
                          COMPOSE WEBVIEW
                        </body>
                        </html>
                        """.trimIndent(),
                        "text/html",
                        "utf-8",
                        null,
                    )
                } else {
                    loadUrl(pairedUrl)
                }
            }
        },
    )
}
