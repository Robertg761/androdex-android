package io.androdex.android

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.webkit.WebView
import androidx.activity.ComponentActivity

class DebugWebViewActivity : ComponentActivity() {
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val webView = WebView(this).apply {
            setBackgroundColor(Color.RED)
            settings.javaScriptEnabled = true
            loadDataWithBaseURL(
                null,
                """
                <html>
                <body style="margin:0;background:#b00020;color:white;display:flex;align-items:center;justify-content:center;height:100vh;font:700 48px sans-serif">
                  DEBUG WEBVIEW
                </body>
                </html>
                """.trimIndent(),
                "text/html",
                "utf-8",
                null,
            )
        }
        setContentView(webView)
    }
}
