package io.androdex.android.web

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import io.androdex.android.BuildConfig
import io.androdex.android.MirrorShellUiState
import io.androdex.android.pairing.isAllowedAppUrl
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MirrorWebShell(
    state: MirrorShellUiState,
    onWebViewReady: () -> Unit,
    onTopLevelUrlChanged: (String) -> Unit,
    onExternalOpenHandled: () -> Unit,
    onClearPairing: () -> Unit,
) {
    val context = LocalContext.current
    val pairedOrigin = state.pairedOrigin ?: return
    var isLoading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var pendingFileCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }

    val documentsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        val callback = pendingFileCallback
        pendingFileCallback = null
        pendingCameraUri = null
        callback?.onReceiveValue(uris.takeIf { it.isNotEmpty() }?.toTypedArray())
    }

    val takePictureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { success ->
        val callback = pendingFileCallback
        val cameraUri = pendingCameraUri
        pendingFileCallback = null
        pendingCameraUri = null
        callback?.onReceiveValue(
            if (success && cameraUri != null) {
                arrayOf(cameraUri)
            } else {
                null
            },
        )
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            pendingFileCallback?.onReceiveValue(null)
            pendingFileCallback = null
            pendingCameraUri = null
            return@rememberLauncherForActivityResult
        }

        val nextUri = buildCaptureUri(context) ?: run {
            pendingFileCallback?.onReceiveValue(null)
            pendingFileCallback = null
            pendingCameraUri = null
            return@rememberLauncherForActivityResult
        }
        pendingCameraUri = nextUri
        takePictureLauncher.launch(nextUri)
    }

    val webView = remember(pairedOrigin) {
        WebView(context).apply {
            configureMirrorSettings()
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?,
                ): Boolean {
                    pendingFileCallback?.onReceiveValue(null)
                    pendingFileCallback = filePathCallback
                    val params = fileChooserParams ?: run {
                        pendingFileCallback?.onReceiveValue(null)
                        pendingFileCallback = null
                        return false
                    }

                    val mimeTypes = params.acceptTypes
                        .mapNotNull { type -> type?.trim()?.takeIf { it.isNotEmpty() } }
                        .ifEmpty { listOf("*/*") }
                        .toTypedArray()

                    if (params.isCaptureEnabled && acceptsImages(mimeTypes)) {
                        val hasCameraPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                            ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.CAMERA,
                            ) == PackageManager.PERMISSION_GRANTED
                        if (hasCameraPermission) {
                            val nextUri = buildCaptureUri(context) ?: return false
                            pendingCameraUri = nextUri
                            takePictureLauncher.launch(nextUri)
                        } else {
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                        return true
                    }

                    documentsLauncher.launch(mimeTypes)
                    return true
                }

                override fun onPermissionRequest(request: PermissionRequest?) {
                    request?.deny()
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): Boolean {
                    val url = request?.url?.toString() ?: return false
                    if (!request.isForMainFrame) {
                        return false
                    }
                    if (isAllowedAppUrl(url, pairedOrigin)) {
                        return false
                    }
                    context.openExternalUrl(url)
                    return true
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    isLoading = true
                    loadError = null
                    canGoBack = view?.canGoBack() == true
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    isLoading = false
                    canGoBack = view?.canGoBack() == true
                    url?.let(onTopLevelUrlChanged)
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: android.webkit.WebResourceError?,
                ) {
                    if (request?.isForMainFrame != true) {
                        return
                    }
                    isLoading = false
                    loadError = error?.description?.toString()?.takeIf { it.isNotBlank() }
                        ?: "Unable to load the paired environment."
                }
            }
        }
    }

    DisposableEffect(webView) {
        onDispose {
            webView.stopLoading()
            webView.destroy()
        }
    }

    LaunchedEffect(Unit) {
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        onWebViewReady()
    }

    LaunchedEffect(webView, state.initialUrl, pairedOrigin) {
        val target = state.initialUrl ?: pairedOrigin
        if (webView.url != target) {
            isLoading = true
            webView.loadUrl(target)
        }
    }

    LaunchedEffect(webView, state.externalOpenPending) {
        val target = state.externalOpenPending ?: return@LaunchedEffect
        if (webView.url != target) {
            isLoading = true
            webView.loadUrl(target)
        }
        onExternalOpenHandled()
    }

    BackHandler(enabled = canGoBack) {
        webView.goBack()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { webView },
        )

        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 24.dp),
            )
        }

        if (loadError != null) {
            Surface(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                shadowElevation = 6.dp,
                shape = MaterialTheme.shapes.large,
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "Paired environment unavailable",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = loadError ?: "",
                        modifier = Modifier.padding(top = 8.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(
                        modifier = Modifier.padding(top = 16.dp),
                        onClick = { webView.reload() },
                    ) {
                        Text("Retry")
                    }
                    Button(
                        modifier = Modifier.padding(top = 8.dp),
                        onClick = onClearPairing,
                    ) {
                        Text("Change pairing")
                    }
                }
            }
        }
    }
}

private fun WebView.configureMirrorSettings() {
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.mediaPlaybackRequiresUserGesture = false
    settings.allowFileAccess = false
    settings.allowContentAccess = true
    settings.cacheMode = WebSettings.LOAD_DEFAULT
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        settings.safeBrowsingEnabled = true
    }
}

private fun acceptsImages(mimeTypes: Array<String>): Boolean {
    return mimeTypes.any { type ->
        type.equals("image/*", ignoreCase = true) ||
            type.lowercase(Locale.US).startsWith("image/")
    }
}

private fun buildCaptureUri(context: Context): Uri? {
    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    val capturesDir = context.cacheDir.resolve("captures").apply { mkdirs() }
    val imageFile = runCatching {
        File.createTempFile("androdex_$timestamp", ".jpg", capturesDir)
    }.getOrNull() ?: return null
    return FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        imageFile,
    )
}

private fun Context.openExternalUrl(url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { startActivity(intent) }
        .recoverCatching {
            if (it is ActivityNotFoundException) {
                val fallback = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    `package` = null
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(fallback)
            }
        }
}
