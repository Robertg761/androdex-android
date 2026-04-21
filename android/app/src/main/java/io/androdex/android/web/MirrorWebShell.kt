package io.androdex.android.web

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Message
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import io.androdex.android.ui.shared.RemodexButton
import io.androdex.android.ui.shared.RemodexButtonStyle
import io.androdex.android.ui.shared.RemodexGroupedSurface
import io.androdex.android.ui.shared.RemodexIconButton
import io.androdex.android.ui.shared.RemodexPageHeader
import io.androdex.android.ui.shared.RemodexPill
import io.androdex.android.ui.shared.RemodexPillStyle
import io.androdex.android.ui.shared.remodexBottomSafeAreaInsets
import io.androdex.android.ui.theme.RemodexMonoFontFamily
import io.androdex.android.ui.theme.RemodexTheme
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import org.json.JSONObject

private const val pageLoadTimeoutMs = 12_000L
private const val noActiveThreadMarker = "No active thread"
private const val pickThreadMarker = "Pick a thread to continue"
private const val blankPageProbeDelayMs = 900L

internal enum class MirrorLoadFailureKind {
    Timeout,
    Network,
    Http,
    Ssl,
    RenderProcessGone,
    BrowserErrorPage,
    BlankPage,
}

internal data class MirrorLoadErrorState(
    val kind: MirrorLoadFailureKind,
    val title: String,
    val summary: String,
    val technicalDetails: String? = null,
    val failingUrl: String? = null,
)

internal data class MirrorDocumentSnapshot(
    val title: String,
    val url: String?,
    val bodyText: String,
    val bodyChildCount: Int,
    val imageCount: Int,
    val visibleMediaCount: Int,
)

internal data class MirrorPageFinishedState(
    val activeLoadUrl: String?,
    val isLoading: Boolean,
    val loadError: MirrorLoadErrorState?,
)

internal fun resolveMirrorPageFinishedState(
    currentActiveLoadUrl: String?,
    finishedUrl: String?,
): MirrorPageFinishedState {
    return MirrorPageFinishedState(
        activeLoadUrl = finishedUrl ?: currentActiveLoadUrl,
        isLoading = false,
        loadError = null,
    )
}

internal fun resolveMirrorLoadTarget(
    externalOpenPending: String?,
    activeLoadUrl: String?,
    initialUrl: String?,
    pairedOrigin: String,
): String {
    return externalOpenPending
        ?: activeLoadUrl
        ?: initialUrl
        ?: pairedOrigin
}

@Composable
fun MirrorWebShell(
    state: MirrorShellUiState,
    onWebViewReady: () -> Unit,
    onTopLevelUrlChanged: (String) -> Unit,
    onExternalOpenHandled: () -> Unit,
    onClearPairing: () -> Unit,
) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val pairedOrigin = state.pairedOrigin ?: return
    var isLoading by remember(pairedOrigin) { mutableStateOf(true) }
    var loadError by remember(pairedOrigin) { mutableStateOf<MirrorLoadErrorState?>(null) }
    var canGoBack by remember(pairedOrigin) { mutableStateOf(false) }
    var handlingBackPress by remember(pairedOrigin) { mutableStateOf(false) }
    var activeLoadUrl by remember(pairedOrigin) { mutableStateOf<String?>(state.initialUrl ?: pairedOrigin) }
    var showHomeAssist by remember(pairedOrigin) { mutableStateOf(false) }
    var pendingFileCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
    var webView by remember(pairedOrigin) { mutableStateOf<WebView?>(null) }
    var popupWebView by remember(pairedOrigin) { mutableStateOf<WebView?>(null) }
    var webViewGeneration by remember(pairedOrigin) { mutableIntStateOf(0) }
    val reloadCurrentPage: () -> Unit = {
        val target = resolveMirrorLoadTarget(
            externalOpenPending = state.externalOpenPending,
            activeLoadUrl = activeLoadUrl,
            initialUrl = state.initialUrl,
            pairedOrigin = pairedOrigin,
        )
        showHomeAssist = false
        loadError = null
        isLoading = true
        if (webView == null) {
            activeLoadUrl = target
            webViewGeneration += 1
        } else {
            webView?.reload()
        }
    }
    val openPairedHome: () -> Unit = {
        val target = pairedOrigin
        showHomeAssist = false
        loadError = null
        isLoading = true
        activeLoadUrl = target
        if (webView == null) {
            webViewGeneration += 1
        } else {
            webView?.loadUrl(target)
        }
    }
    val openThreads: () -> Unit = {
        showHomeAssist = false
        webView?.openSidebarInPage()
        webView?.postDelayed(
            {
                webView?.installAndroidThreadTapBridge()
            },
            260L,
        )
        webView?.postDelayed(
            {
                webView?.installAndroidThreadTapBridge()
            },
            900L,
        )
        Unit
    }
    val inspectForHomeAssist: (WebView?) -> Unit = fun(candidateView: WebView?) {
        if (candidateView == null) {
            return
        }
        fun evaluatePageState() {
            candidateView.queryHasActiveThreadFromPage { hasActiveThread ->
                if (hasActiveThread != null) {
                    candidateView.post {
                        if (candidateView.isAttachedToWindow) {
                            showHomeAssist = !hasActiveThread
                        }
                    }
                    return@queryHasActiveThreadFromPage
                }

                candidateView.evaluateJavascript(
                    """
                    (function () {
                      return document.body && document.body.innerText ? document.body.innerText : "";
                    })();
                    """.trimIndent(),
                ) { rawResult ->
                    val bodyText = parseBodyTextFromJsResult(rawResult)
                    val isHomeEmptyState = bodyText.contains(noActiveThreadMarker, ignoreCase = true) ||
                        bodyText.contains(pickThreadMarker, ignoreCase = true)
                    candidateView.post {
                        if (candidateView.isAttachedToWindow) {
                            showHomeAssist = isHomeEmptyState
                        }
                    }
                }
            }
        }
        candidateView.postDelayed(::evaluatePageState, 450L)
        candidateView.postDelayed(::evaluatePageState, 1400L)
    }

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

    DisposableEffect(pairedOrigin, webViewGeneration) {
        onDispose {
            popupWebView?.stopLoading()
            popupWebView?.destroy()
            popupWebView = null
            webView?.stopLoading()
            webView?.destroy()
            webView = null
        }
    }

    BackHandler(enabled = !handlingBackPress) {
        if (showHomeAssist) {
            showHomeAssist = false
            return@BackHandler
        }

        val currentWebView = webView
        if (currentWebView == null) {
            activity?.finish()
            return@BackHandler
        }

        handlingBackPress = true
        currentWebView.requestSidebarCloseFromAndroid { sidebarClosed ->
            if (sidebarClosed) {
                handlingBackPress = false
                return@requestSidebarCloseFromAndroid
            }

            if (canGoBack) {
                currentWebView.goBack()
            } else {
                activity?.finish()
            }
            handlingBackPress = false
        }
    }

    LaunchedEffect(activeLoadUrl, isLoading, loadError) {
        val targetUrl = activeLoadUrl ?: return@LaunchedEffect
        if (!isLoading || loadError != null) {
            return@LaunchedEffect
        }
        delay(pageLoadTimeoutMs)
        if (isLoading && loadError == null && activeLoadUrl == targetUrl) {
            loadError = timeoutLoadError(targetUrl)
            showHomeAssist = false
            isLoading = false
        }
    }

    val forwardPopupNavigation: (String, WebView?) -> Unit = { url, targetView ->
        activeLoadUrl = url
        isLoading = true
        loadError = null
        showHomeAssist = false
        (targetView ?: webView)?.loadUrl(url)
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = remodexBottomSafeAreaInsets(),
        topBar = {
            MirrorShellTopBar(
                onReload = reloadCurrentPage,
                onClearPairing = onClearPairing,
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            key(pairedOrigin, webViewGeneration) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = {
                        WebView(it).apply {
                        configureMirrorSettings()
                        setBackgroundColor(Color.BLACK)
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webChromeClient = object : WebChromeClient() {
                            override fun onCreateWindow(
                                view: WebView?,
                                isDialog: Boolean,
                                isUserGesture: Boolean,
                                resultMsg: Message?,
                            ): Boolean {
                                val openerView = view ?: return false
                                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false

                                popupWebView?.stopLoading()
                                popupWebView?.destroy()

                                lateinit var proxyWebView: WebView
                                proxyWebView = WebView(openerView.context).apply {
                                    configureMirrorSettings()
                                    webViewClient = object : WebViewClient() {
                                        private var forwardedUrl: String? = null

                                        private fun consume(url: String?): Boolean {
                                            val targetUrl = normalizeMirrorNavigationUrl(url) ?: return false
                                            if (targetUrl == forwardedUrl) {
                                                return true
                                            }
                                            return when (classifyMirrorNavigation(targetUrl, pairedOrigin)) {
                                                MirrorNavigationTarget.Ignore -> false
                                                MirrorNavigationTarget.External -> {
                                                    forwardedUrl = targetUrl
                                                    context.openExternalUrl(targetUrl)
                                                    proxyWebView.destroy()
                                                    if (popupWebView === proxyWebView) {
                                                        popupWebView = null
                                                    }
                                                    true
                                                }
                                                MirrorNavigationTarget.InApp -> {
                                                    forwardedUrl = targetUrl
                                                    openerView.post {
                                                        forwardPopupNavigation(targetUrl, openerView)
                                                    }
                                                    proxyWebView.stopLoading()
                                                    proxyWebView.destroy()
                                                    if (popupWebView === proxyWebView) {
                                                        popupWebView = null
                                                    }
                                                    true
                                                }
                                            }
                                        }

                                        override fun shouldOverrideUrlLoading(
                                            view: WebView?,
                                            request: WebResourceRequest?,
                                        ): Boolean {
                                            return consume(request?.url?.toString())
                                        }

                                        override fun onPageStarted(
                                            view: WebView?,
                                            url: String?,
                                            favicon: Bitmap?,
                                        ) {
                                            if (!consume(url)) {
                                                super.onPageStarted(view, url, favicon)
                                            }
                                        }
                                    }
                                }

                                popupWebView = proxyWebView
                                transport.webView = proxyWebView
                                resultMsg.sendToTarget()
                                return true
                            }

                            override fun onCloseWindow(window: WebView?) {
                                if (window === popupWebView) {
                                    popupWebView?.stopLoading()
                                    popupWebView?.destroy()
                                    popupWebView = null
                                }
                            }

                            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                                if (consoleMessage != null) {
                                    Log.d(
                                        "AndrodexWebView",
                                        "${consoleMessage.messageLevel()}: ${consoleMessage.message()} @ ${consoleMessage.sourceId()}:${consoleMessage.lineNumber()}",
                                    )
                                }
                                return super.onConsoleMessage(consoleMessage)
                            }

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
                                return when (classifyMirrorNavigation(url, pairedOrigin)) {
                                    MirrorNavigationTarget.InApp,
                                    MirrorNavigationTarget.Ignore -> false
                                    MirrorNavigationTarget.External -> {
                                        context.openExternalUrl(url)
                                        true
                                    }
                                }
                            }

                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                activeLoadUrl = url ?: activeLoadUrl
                                isLoading = true
                                loadError = null
                                showHomeAssist = false
                                canGoBack = view?.canGoBack() == true
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                val finishedState = resolveMirrorPageFinishedState(
                                    currentActiveLoadUrl = activeLoadUrl,
                                    finishedUrl = url,
                                )
                                activeLoadUrl = finishedState.activeLoadUrl
                                isLoading = finishedState.isLoading
                                loadError = finishedState.loadError
                                canGoBack = view?.canGoBack() == true
                                view?.installAndroidThreadTapBridge()
                                view?.inspectDocumentHealth(
                                    expectedUrl = finishedState.activeLoadUrl,
                                ) { diagnosticError ->
                                    if (diagnosticError != null && activeLoadUrl == finishedState.activeLoadUrl) {
                                        showHomeAssist = false
                                        loadError = diagnosticError
                                    }
                                }
                                inspectForHomeAssist(view)
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
                                showHomeAssist = false
                                loadError = requestLoadError(
                                    requestUrl = request.url?.toString(),
                                    error = error,
                                )
                            }

                            override fun onReceivedHttpError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                errorResponse: android.webkit.WebResourceResponse?,
                            ) {
                                if (request?.isForMainFrame != true) {
                                    return
                                }
                                isLoading = false
                                showHomeAssist = false
                                loadError = httpLoadError(
                                    requestUrl = request.url?.toString(),
                                    statusCode = errorResponse?.statusCode,
                                    reason = errorResponse?.reasonPhrase,
                                )
                            }

                            override fun onReceivedSslError(
                                view: WebView?,
                                handler: SslErrorHandler?,
                                error: SslError?,
                            ) {
                                handler?.cancel()
                                isLoading = false
                                showHomeAssist = false
                                loadError = sslLoadError(
                                    failingUrl = error?.url ?: view?.url ?: activeLoadUrl,
                                    primaryError = error?.primaryError,
                                )
                            }

                            override fun onRenderProcessGone(
                                view: WebView?,
                                detail: RenderProcessGoneDetail?,
                            ): Boolean {
                                view?.destroy()
                                if (webView === view) {
                                    webView = null
                                }
                                popupWebView?.stopLoading()
                                popupWebView?.destroy()
                                popupWebView = null
                                isLoading = false
                                showHomeAssist = false
                                loadError = renderProcessGoneLoadError(
                                    failingUrl = activeLoadUrl ?: view?.url,
                                    didCrash = detail?.didCrash() == true,
                                )
                                return true
                            }
                        }
                        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
                        webView = this
                        onWebViewReady()
                    }
                    },
                    update = { view ->
                        webView = view
                        val target = resolveMirrorLoadTarget(
                            externalOpenPending = state.externalOpenPending,
                            activeLoadUrl = activeLoadUrl,
                            initialUrl = state.initialUrl,
                            pairedOrigin = pairedOrigin,
                        )
                        if (view.url != target) {
                            activeLoadUrl = target
                            isLoading = true
                            loadError = null
                            showHomeAssist = false
                            view.loadUrl(target)
                        }
                        if (state.externalOpenPending != null) {
                            onExternalOpenHandled()
                        }
                    },
                )
            }

            if (isLoading) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 24.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                    tonalElevation = 6.dp,
                    shadowElevation = 6.dp,
                    shape = MaterialTheme.shapes.large,
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                    ) {
                        CircularProgressIndicator()
                        Text(
                            text = "Opening paired environment",
                            modifier = Modifier.padding(top = 14.dp),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = state.displayLabel ?: pairedOrigin,
                            modifier = Modifier.padding(top = 6.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            if (loadError != null) {
                MirrorLoadErrorCard(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                    hostLabel = state.displayLabel ?: pairedOrigin,
                    error = loadError!!,
                    onRetry = reloadCurrentPage,
                    onOpenHome = openPairedHome,
                    onClearPairing = onClearPairing,
                )
            }

            if (!isLoading && loadError == null && showHomeAssist) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                    tonalElevation = 6.dp,
                    shadowElevation = 6.dp,
                    shape = MaterialTheme.shapes.large,
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            text = "Androdex is open",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = "No thread is open yet. Use the thread list below to pick or create one.",
                            modifier = Modifier.padding(top = 8.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        RemodexButton(
                            modifier = Modifier.padding(top = 16.dp),
                            onClick = openThreads,
                        ) {
                            Text("Open Threads")
                        }
                        RemodexButton(
                            modifier = Modifier.padding(top = 8.dp),
                            onClick = { showHomeAssist = false },
                            style = RemodexButtonStyle.Secondary,
                        ) {
                            Text("Dismiss")
                        }
                    }
                }
            }

        }
    }
}

@Composable
private fun MirrorLoadErrorCard(
    modifier: Modifier = Modifier,
    hostLabel: String,
    error: MirrorLoadErrorState,
    onRetry: () -> Unit,
    onOpenHome: () -> Unit,
    onClearPairing: () -> Unit,
) {
    val colors = RemodexTheme.colors
    val geometry = RemodexTheme.geometry

    RemodexGroupedSurface(
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = 560.dp),
        tonalColor = colors.secondarySurface.copy(alpha = 0.96f),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(geometry.spacing10),
        ) {
            RemodexPill(
                label = error.kind.label,
                style = RemodexPillStyle.Error,
            )
            Text(
                text = error.title,
                style = MaterialTheme.typography.titleLarge,
                color = colors.textPrimary,
            )
            Text(
                text = error.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary,
            )
            MirrorDiagnosticBlock(
                label = "Host",
                value = hostLabel,
            )
            error.failingUrl?.let { failingUrl ->
                MirrorDiagnosticBlock(
                    label = "URL",
                    value = failingUrl,
                )
            }
            error.technicalDetails?.let { details ->
                MirrorDiagnosticBlock(
                    label = "Details",
                    value = details,
                )
            }
            RemodexButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = onRetry,
            ) {
                Text("Retry")
            }
            RemodexButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = onOpenHome,
                style = RemodexButtonStyle.Secondary,
            ) {
                Text("Open paired home")
            }
            RemodexButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = onClearPairing,
                style = RemodexButtonStyle.Ghost,
            ) {
                Text("Change pairing")
            }
        }
    }
}

@Composable
private fun MirrorDiagnosticBlock(
    label: String,
    value: String,
) {
    val colors = RemodexTheme.colors

    Surface(
        color = colors.selectedRowFill,
        shape = MaterialTheme.shapes.medium,
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.hairlineDivider),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = RemodexMonoFontFamily),
                color = colors.textPrimary,
            )
        }
    }
}

@Composable
private fun MirrorShellTopBar(
    onReload: (() -> Unit)?,
    onClearPairing: () -> Unit,
) {
    val colors = RemodexTheme.colors
    val geometry = RemodexTheme.geometry
    var overflowMenuExpanded by rememberSaveable { mutableStateOf(false) }

    RemodexPageHeader(
        title = "Androdex",
        actions = {
            RemodexIconButton(
                onClick = { onReload?.invoke() },
                enabled = onReload != null,
                contentDescription = "Refresh",
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    tint = colors.textPrimary,
                    modifier = Modifier.size(geometry.iconSize),
                )
            }
            Box {
                RemodexIconButton(
                    onClick = { overflowMenuExpanded = true },
                    contentDescription = "More",
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = null,
                        tint = colors.textPrimary,
                        modifier = Modifier.size(geometry.iconSize),
                    )
                }
                DropdownMenu(
                    expanded = overflowMenuExpanded,
                    onDismissRequest = { overflowMenuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Change pairing") },
                        onClick = {
                            overflowMenuExpanded = false
                            onClearPairing()
                        },
                    )
                }
            }
        },
    )
}

internal fun parseBodyTextFromJsResult(rawResult: String?): String {
    if (rawResult.isNullOrBlank() || rawResult == "null") {
        return ""
    }

    return runCatching {
        JSONObject("""{"value":$rawResult}""").optString("value")
    }
        .getOrElse { "" }
}

internal fun parseJsBooleanResult(rawResult: String?): Boolean {
    return rawResult?.trim()?.equals("true", ignoreCase = true) ?: false
}

internal fun parseJsOptionalBooleanResult(rawResult: String?): Boolean? {
    return when (rawResult?.trim()?.lowercase(Locale.US)) {
        "true" -> true
        "false" -> false
        else -> null
    }
}

internal fun parseMirrorDocumentSnapshot(rawResult: String?): MirrorDocumentSnapshot? {
    if (rawResult.isNullOrBlank() || rawResult == "null") {
        return null
    }
    return runCatching {
        val json = JSONObject(parseBodyTextFromJsResult(rawResult))
        MirrorDocumentSnapshot(
            title = json.optString("title"),
            url = json.optString("url").trim().takeIf { it.isNotEmpty() },
            bodyText = json.optString("bodyText"),
            bodyChildCount = json.optInt("bodyChildCount"),
            imageCount = json.optInt("imageCount"),
            visibleMediaCount = json.optInt("visibleMediaCount"),
        )
    }.getOrNull()
}

internal fun classifyMirrorDocumentFailure(
    snapshot: MirrorDocumentSnapshot,
    fallbackUrl: String?,
): MirrorLoadErrorState? {
    val normalizedText = snapshot.bodyText
        .replace("\\s+".toRegex(), " ")
        .trim()
        .lowercase(Locale.US)
    val failingUrl = snapshot.url ?: fallbackUrl
    val browserErrorMarkers = listOf(
        "this site can't be reached",
        "webpage not available",
        "aw, snap",
        "net::err_",
        "dns_probe_finished",
        "connection reset",
        "connection refused",
        "timed out",
    )
    if (browserErrorMarkers.any(normalizedText::contains)) {
        return MirrorLoadErrorState(
            kind = MirrorLoadFailureKind.BrowserErrorPage,
            title = "The paired page opened a browser error screen",
            summary = "WebView reached the page, but the document itself is an error page instead of the Androdex UI.",
            technicalDetails = snapshot.bodyText.take(220),
            failingUrl = failingUrl,
        )
    }

    val blankDocument = normalizedText.isEmpty() &&
        snapshot.bodyChildCount <= 2 &&
        snapshot.visibleMediaCount <= 1 &&
        snapshot.imageCount <= 1
    if (blankDocument) {
        return MirrorLoadErrorState(
            kind = MirrorLoadFailureKind.BlankPage,
            title = "The paired page rendered blank",
            summary = "The page finished loading, but WebView did not get meaningful UI content back. This usually means the host page failed during startup or returned an empty shell.",
            technicalDetails = "title=${snapshot.title.ifBlank { "<empty>" }}, children=${snapshot.bodyChildCount}, media=${snapshot.visibleMediaCount}, images=${snapshot.imageCount}",
            failingUrl = failingUrl,
        )
    }

    return null
}

internal fun timeoutLoadError(failingUrl: String?): MirrorLoadErrorState =
    MirrorLoadErrorState(
        kind = MirrorLoadFailureKind.Timeout,
        title = "The paired host took too long to respond",
        summary = "Androdex waited for the page to load but never got a usable response.",
        technicalDetails = "Timed out after ${pageLoadTimeoutMs / 1000}s while loading the paired environment.",
        failingUrl = failingUrl,
    )

internal fun requestLoadError(
    requestUrl: String?,
    error: android.webkit.WebResourceError?,
): MirrorLoadErrorState {
    val description = error?.description?.toString()?.trim().takeUnless { it.isNullOrEmpty() }
    val errorCode = error?.errorCode
    val summary = when (errorCode) {
        WebViewClient.ERROR_HOST_LOOKUP -> "Android could not resolve the paired host address."
        WebViewClient.ERROR_CONNECT -> "Android could not connect to the paired host."
        WebViewClient.ERROR_TIMEOUT -> "The paired host accepted the request but stopped responding."
        WebViewClient.ERROR_TOO_MANY_REQUESTS -> "The paired host or relay is throttling requests right now."
        WebViewClient.ERROR_REDIRECT_LOOP -> "The paired page is stuck in a redirect loop."
        else -> "Android could not load the paired page."
    }
    return MirrorLoadErrorState(
        kind = MirrorLoadFailureKind.Network,
        title = "The paired page could not be loaded",
        summary = summary,
        technicalDetails = buildString {
            if (errorCode != null) {
                append("errorCode=").append(errorCode)
            }
            if (!description.isNullOrBlank()) {
                if (isNotEmpty()) append(", ")
                append(description)
            }
        }.takeIf { it.isNotBlank() },
        failingUrl = requestUrl,
    )
}

internal fun httpLoadError(
    requestUrl: String?,
    statusCode: Int?,
    reason: String?,
): MirrorLoadErrorState {
    val summary = when (statusCode) {
        401, 403 -> "The paired host rejected this session. The saved pairing may no longer be valid."
        404 -> "The paired host could not find the requested page."
        in 500..599 -> "The paired host reported a server-side failure."
        else -> "The paired host returned an unexpected HTTP response."
    }
    val details = buildString {
        if (statusCode != null) {
            append("HTTP ").append(statusCode)
        }
        val cleanedReason = reason?.trim().takeUnless { it.isNullOrEmpty() }
        if (cleanedReason != null) {
            if (isNotEmpty()) append(" ")
            append("(").append(cleanedReason).append(")")
        }
    }.takeIf { it.isNotBlank() }
    return MirrorLoadErrorState(
        kind = MirrorLoadFailureKind.Http,
        title = "The paired host returned an error response",
        summary = summary,
        technicalDetails = details,
        failingUrl = requestUrl,
    )
}

internal fun sslLoadError(
    failingUrl: String?,
    primaryError: Int?,
): MirrorLoadErrorState {
    val detail = when (primaryError) {
        SslError.SSL_DATE_INVALID -> "The certificate date is not valid."
        SslError.SSL_EXPIRED -> "The certificate has expired."
        SslError.SSL_IDMISMATCH -> "The certificate does not match the host name."
        SslError.SSL_INVALID -> "Android reported the certificate as invalid."
        SslError.SSL_NOTYETVALID -> "The certificate is not valid yet."
        SslError.SSL_UNTRUSTED -> "Android does not trust the certificate chain."
        else -> "Android blocked the page because the TLS certificate could not be verified."
    }
    return MirrorLoadErrorState(
        kind = MirrorLoadFailureKind.Ssl,
        title = "The paired host failed TLS validation",
        summary = "Androdex blocked the page because the connection could not be established securely.",
        technicalDetails = detail,
        failingUrl = failingUrl,
    )
}

internal fun renderProcessGoneLoadError(
    failingUrl: String?,
    didCrash: Boolean,
): MirrorLoadErrorState =
    MirrorLoadErrorState(
        kind = MirrorLoadFailureKind.RenderProcessGone,
        title = if (didCrash) "WebView crashed while rendering the page" else "WebView was reclaimed while rendering the page",
        summary = "Android lost the renderer for this page, so the current view can no longer recover in place.",
        technicalDetails = if (didCrash) {
            "The WebView render process crashed and needs a fresh instance."
        } else {
            "Android removed the WebView render process, usually because of memory pressure."
        },
        failingUrl = failingUrl,
    )

private fun WebView.inspectDocumentHealth(
    expectedUrl: String?,
    onResult: (MirrorLoadErrorState?) -> Unit,
) {
    postDelayed(
        {
            if (!isAttachedToWindow) {
                onResult(null)
                return@postDelayed
            }
            val currentUrl = normalizeMirrorNavigationUrl(url)
            if (normalizeMirrorNavigationUrl(expectedUrl) != null && currentUrl != normalizeMirrorNavigationUrl(expectedUrl)) {
                onResult(null)
                return@postDelayed
            }
            evaluateJavascript(
                """
                (function () {
                  const body = document.body;
                  const bodyText = body && body.innerText ? body.innerText.replace(/\s+/g, " ").trim() : "";
                  const visibleMediaCount = Array.from(
                    document.querySelectorAll("img,svg,canvas,video,iframe,embed,object"),
                  ).filter((node) => {
                    if (!(node instanceof Element)) {
                      return false;
                    }
                    const rect = node.getBoundingClientRect();
                    return rect.width > 1 && rect.height > 1;
                  }).length;
                  return JSON.stringify({
                    title: document.title || "",
                    url: window.location && window.location.href ? window.location.href : "",
                    bodyText,
                    bodyChildCount: body ? body.children.length : 0,
                    imageCount: document.images ? document.images.length : 0,
                    visibleMediaCount,
                  });
                })();
                """.trimIndent(),
            ) { rawResult ->
                onResult(
                    parseMirrorDocumentSnapshot(rawResult)?.let { snapshot ->
                        classifyMirrorDocumentFailure(snapshot, currentUrl)
                    },
                )
            }
        },
        blankPageProbeDelayMs,
    )
}

private val MirrorLoadFailureKind.label: String
    get() = when (this) {
        MirrorLoadFailureKind.Timeout -> "Timed Out"
        MirrorLoadFailureKind.Network -> "Network Error"
        MirrorLoadFailureKind.Http -> "HTTP Error"
        MirrorLoadFailureKind.Ssl -> "TLS Error"
        MirrorLoadFailureKind.RenderProcessGone -> "Renderer Lost"
        MirrorLoadFailureKind.BrowserErrorPage -> "Browser Error"
        MirrorLoadFailureKind.BlankPage -> "Blank Page"
    }

private fun WebView.openSidebarInPage() {
    evaluateJavascript(
        """
        (function () {
          try {
            const bridge = window.__androdexAndroidBridge;
            if (bridge && typeof bridge.openSidebar === "function") {
              return bridge.openSidebar() === true;
            }
          } catch (_) {
            return false;
          }
          return false;
        })();
        """.trimIndent(),
    ) { rawResult ->
        if (parseJsBooleanResult(rawResult)) {
            return@evaluateJavascript
        }

        evaluateJavascript(
        """
        (function () {
          const getToggleSidebarButtons = () => {
            const androidMatches = Array.from(
              document.querySelectorAll('[data-androdex-role="sidebar-trigger"]'),
            ).filter((button) => button instanceof HTMLElement);
            if (androidMatches.length > 0) {
              return androidMatches;
            }

            const matches = Array.from(document.querySelectorAll("button")).filter((button) => {
              const text = [
                button.innerText || "",
                button.getAttribute("aria-label") || "",
                button.getAttribute("title") || ""
              ].join(" ");
              return /toggle sidebar/i.test(text);
            });
            const visibleMatches = matches.filter((button) => {
              const rect = button.getBoundingClientRect();
              return rect.width > 0 && rect.height > 0 && rect.bottom > 0 && rect.top >= 0;
            });
            return visibleMatches.sort((left, right) => {
              const topDelta = left.getBoundingClientRect().top - right.getBoundingClientRect().top;
              if (topDelta !== 0) {
                return topDelta;
              }
              return left.getBoundingClientRect().left - right.getBoundingClientRect().left;
            });
          };

          const pressElement = (element) => {
            if (!(element instanceof HTMLElement)) {
              return false;
            }
            ["pointerdown", "mousedown", "pointerup", "mouseup", "click"].forEach((type) => {
              element.dispatchEvent(new MouseEvent(type, {
                bubbles: true,
                cancelable: true,
                view: window,
              }));
            });
            if (typeof element.click === "function") {
              element.click();
            }
            return true;
          };

          const match = getToggleSidebarButtons()[0];
          if (match) {
            pressElement(match);
            return true;
          }
          return false;
        })();
        """.trimIndent(),
            null,
        )
    }
}

private fun WebView.requestSidebarCloseFromAndroid(onResult: (Boolean) -> Unit) {
    evaluateJavascript(
        """
        (function () {
          try {
            const bridge = window.__androdexAndroidBridge;
            if (bridge && typeof bridge.closeSidebar === "function") {
              return bridge.closeSidebar() === true;
            }
            if (typeof window.__androdexAndroidRequestSidebarClose === "function") {
              return window.__androdexAndroidRequestSidebarClose() === true;
            }
          } catch (_) {
            return false;
          }
          return false;
        })();
        """.trimIndent(),
    ) { rawResult ->
        onResult(parseJsBooleanResult(rawResult))
    }
}

private fun WebView.queryHasActiveThreadFromPage(onResult: (Boolean?) -> Unit) {
    evaluateJavascript(
        """
        (function () {
          try {
            const bridge = window.__androdexAndroidBridge;
            if (bridge && typeof bridge.hasActiveThread === "function") {
              return bridge.hasActiveThread() === true;
            }

            const marker = document.querySelector("[data-androdex-active-thread]");
            if (marker instanceof HTMLElement) {
              return marker.dataset.androdexActiveThread === "true";
            }
          } catch (_) {
            return null;
          }
          return null;
        })();
        """.trimIndent(),
    ) { rawResult ->
        onResult(parseJsOptionalBooleanResult(rawResult))
    }
}

private fun WebView.installAndroidThreadTapBridge() {
    evaluateJavascript(
        androidThreadTapBridgeScript(),
        null,
    )
}

internal fun androidThreadTapBridgeScript(): String =
    """
        (function () {
          const nowWithinDebounce = (node, key, thresholdMs) => {
            const now = Date.now();
            const lastValue = Number(node.dataset[key] || "0");
            if (now - lastValue < thresholdMs) {
              return true;
            }
            node.dataset[key] = String(now);
            return false;
          };

          const getToggleSidebarButtons = () => {
            const androidMatches = Array.from(
              document.querySelectorAll('[data-androdex-role="sidebar-trigger"]'),
            ).filter((button) => {
              if (!(button instanceof HTMLElement)) {
                return false;
              }
              const rect = button.getBoundingClientRect();
              return rect.width > 0 && rect.height > 0 && rect.bottom > 0 && rect.right > 0;
            });
            if (androidMatches.length > 0) {
              return androidMatches;
            }

            const matches = Array.from(document.querySelectorAll("button")).filter((button) => {
              const text = [
                button.innerText || "",
                button.getAttribute("aria-label") || "",
                button.getAttribute("title") || ""
              ].join(" ");
              return /toggle sidebar/i.test(text);
            });
            const visibleMatches = matches.filter((button) => {
              const rect = button.getBoundingClientRect();
              return rect.width > 0 &&
                rect.height > 0 &&
                rect.bottom > 0 &&
                rect.top >= 0 &&
                rect.left < window.innerWidth &&
                rect.right > 0;
            });
            return (visibleMatches.length > 0 ? visibleMatches : matches).sort((left, right) => {
              const topDelta = left.getBoundingClientRect().top - right.getBoundingClientRect().top;
              if (topDelta !== 0) {
                return topDelta;
              }
              return left.getBoundingClientRect().left - right.getBoundingClientRect().left;
            });
          };

          const findToggleSidebarButton = () => {
            const buttons = getToggleSidebarButtons();
            return buttons[0] || null;
          };

          const pressElement = (element) => {
            if (!(element instanceof HTMLElement)) {
              return false;
            }

            ["pointerdown", "mousedown", "pointerup", "mouseup", "click"].forEach((type) => {
              element.dispatchEvent(new MouseEvent(type, {
                bubbles: true,
                cancelable: true,
                view: window,
              }));
            });
            if (typeof element.click === "function") {
              element.click();
            }
            return true;
          };

          const getHeaderActionText = (node) => {
            if (!(node instanceof HTMLElement)) {
              return "";
            }
            return [
              node.innerText || "",
              node.getAttribute("aria-label") || "",
              node.getAttribute("title") || "",
              node.getAttribute("aria-description") || "",
            ].join(" ");
          };

          const findViewportContentRoot = () => {
            const explicitRoot = document.querySelector('[data-androdex-role="thread-shell"]');
            if (explicitRoot instanceof HTMLElement) {
              return explicitRoot;
            }
            return Array.from(document.querySelectorAll("div")).find((node) => {
              return typeof node.className === "string" &&
                node.className.includes("flex min-h-0 min-w-0 flex-1 flex-col overflow-x-hidden bg-background");
            }) || null;
          };

          const scoreHeaderCandidate = (node, toggle) => {
            if (!(node instanceof HTMLElement) || !(toggle instanceof HTMLElement) || !node.contains(toggle)) {
              return Number.NEGATIVE_INFINITY;
            }

            const rect = node.getBoundingClientRect();
            if (rect.width <= 0 || rect.height <= 0 || rect.height > Math.max(window.innerHeight * 0.45, 220)) {
              return Number.NEGATIVE_INFINITY;
            }

            let score = 0;
            if (node.tagName === "HEADER") {
              score += 8;
            }
            if (node.querySelector("h1, h2, h3, [role='heading']")) {
              score += 6;
            }

            const buttonTexts = Array.from(node.querySelectorAll("button"))
              .map((button) => getHeaderActionText(button))
              .join(" ");
            if (/git/i.test(buttonTexts)) {
              score += 6;
            }
            if (/share|fork|review|stop|new thread/i.test(buttonTexts)) {
              score += 3;
            }

            const buttonCount = node.querySelectorAll("button").length;
            if (buttonCount >= 2) {
              score += 3;
            }
            if (rect.top <= 32) {
              score += 2;
            }
            if (rect.top >= -8) {
              score += 1;
            }

            return score;
          };

          const findPrimaryThreadHeader = () => {
            const explicitHeader = document.querySelector('[data-androdex-role="thread-header"]');
            if (explicitHeader instanceof HTMLElement) {
              return explicitHeader;
            }

            const toggle = findToggleSidebarButton();
            if (!(toggle instanceof HTMLElement)) {
              return null;
            }

            const candidates = [];
            let current = toggle.parentElement;
            while (current instanceof HTMLElement && candidates.length < 8) {
              candidates.push(current);
              if (current.tagName === "MAIN") {
                break;
              }
              current = current.parentElement;
            }

            const ranked = candidates
              .map((node) => ({ node, score: scoreHeaderCandidate(node, toggle) }))
              .filter((entry) => Number.isFinite(entry.score))
              .sort((left, right) => {
                if (right.score !== left.score) {
                  return right.score - left.score;
                }
                return left.node.getBoundingClientRect().top - right.node.getBoundingClientRect().top;
              });

            return ranked[0]?.node || toggle.closest("header") || toggle.parentElement;
          };

          const pinPrimaryHeader = () => {
            const header = findPrimaryThreadHeader();
            if (!(header instanceof HTMLElement)) {
              return false;
            }

            const backgroundColor = window.getComputedStyle(header).backgroundColor;
            const fallbackBackground = window.getComputedStyle(document.body).backgroundColor;
            if (backgroundColor === "rgba(0, 0, 0, 0)" || backgroundColor === "transparent") {
              header.style.setProperty(
                "background",
                fallbackBackground && fallbackBackground !== "rgba(0, 0, 0, 0)" ? fallbackBackground : "#0b0b0c",
                "important",
              );
            }

            header.style.setProperty("position", "sticky", "important");
            header.style.setProperty("top", "0", "important");
            header.style.setProperty("z-index", "80", "important");
            header.style.setProperty("transform", "translateZ(0)", "important");
            header.style.setProperty("overflow", "visible", "important");
            header.style.setProperty("box-sizing", "border-box", "important");
            header.style.setProperty("align-self", "stretch", "important");
            header.style.setProperty("max-width", "100%", "important");
            header.style.setProperty("backdrop-filter", "blur(18px)", "important");
            header.style.setProperty("-webkit-backdrop-filter", "blur(18px)", "important");

            const scrollRoot = findViewportContentRoot();
            if (scrollRoot instanceof HTMLElement) {
              scrollRoot.style.setProperty(
                "scroll-padding-top",
                Math.max(header.getBoundingClientRect().height, header.offsetHeight, 0) + "px",
                "important",
              );
            }

            let parent = header.parentElement;
            let depth = 0;
            while (parent instanceof HTMLElement && parent !== scrollRoot && depth < 5) {
              parent.style.setProperty("overflow", "visible", "important");
              parent = parent.parentElement;
              depth += 1;
            }

            return true;
          };

          const scheduleLayoutRepair = (() => {
            let frameHandle = 0;
            return () => {
              if (frameHandle !== 0) {
                return;
              }
              frameHandle = window.requestAnimationFrame(() => {
                frameHandle = 0;
                restoreViewportLayout();
                pinPrimaryHeader();
                forceSidebarProjectCreateButtonsVisible();
              });
            };
          })();

          const forceSidebarProjectCreateButtonsVisible = () => {
            const buttons = Array.from(
              document.querySelectorAll('[data-androdex-role="create-thread"]'),
            );

            buttons.forEach((button) => {
              if (!(button instanceof HTMLElement)) {
                return;
              }

              const wrapper = button.parentElement;
              if (wrapper instanceof HTMLElement) {
                wrapper.style.setProperty("opacity", "1", "important");
                wrapper.style.setProperty("pointer-events", "auto", "important");
                wrapper.style.setProperty("visibility", "visible", "important");
              }

              button.style.setProperty("opacity", "1", "important");
              button.style.setProperty("pointer-events", "auto", "important");
              button.style.setProperty("visibility", "visible", "important");
              button.style.setProperty("touch-action", "manipulation", "important");
            });

            return buttons.length;
          };

          const restoreViewportLayout = () => {
            const px = window.innerHeight + "px";
            const nodes = [
              document.documentElement,
              document.body,
              document.getElementById("root"),
              document.querySelector('[data-slot="sidebar-wrapper"]'),
              document.querySelector("main"),
              findViewportContentRoot(),
            ];

            nodes.forEach((node) => {
              if (!(node instanceof HTMLElement)) {
                return;
              }
              node.style.setProperty("height", px, "important");
              node.style.setProperty("min-height", px, "important");
            });

            const body = document.body;
            const main = document.querySelector("main");
            const content = nodes[nodes.length - 1];

            if (body instanceof HTMLElement) {
              body.style.setProperty("overflow", "hidden", "important");
            }
            if (main instanceof HTMLElement) {
              main.style.setProperty("overflow", "hidden", "important");
            }
            if (content instanceof HTMLElement) {
              content.style.setProperty("overflow-y", "auto", "important");
              content.style.setProperty("overscroll-behavior", "contain", "important");
            }
          };

          const findVisibleSidebarRows = () => {
            return Array.from(
              document.querySelectorAll(
                '[data-androdex-role="thread-row"], [data-thread-item="true"] [role="button"][data-testid^="thread-row-"]',
              ),
            ).filter((row) => {
              if (!(row instanceof HTMLElement)) {
                return false;
              }
              const rect = row.getBoundingClientRect();
              return rect.width > 80 &&
                rect.height > 24 &&
                rect.bottom > 0 &&
                rect.top < window.innerHeight &&
                rect.left < window.innerWidth * 0.8 &&
                rect.right > 16;
            });
          };

          const isSidebarOpen = () => {
            return findVisibleSidebarRows().length > 0;
          };

          let pendingSidebarCloseUntil = 0;
          let closeSidebarTimer = 0;

          const dispatchEscape = () => {
            const escapeEvent = () => new KeyboardEvent("keydown", {
              key: "Escape",
              code: "Escape",
              keyCode: 27,
              which: 27,
              bubbles: true,
              cancelable: true,
            });
            [document.activeElement, document.body, document.documentElement].forEach((target) => {
              if (target && typeof target.dispatchEvent === "function") {
                target.dispatchEvent(escapeEvent());
              }
            });
            window.dispatchEvent(escapeEvent());
          };

          const pressOutsideSidebar = () => {
            const probePoints = [
              [window.innerWidth - 24, 72],
              [window.innerWidth - 24, Math.round(window.innerHeight * 0.5)],
              [window.innerWidth - 24, Math.max(72, window.innerHeight - 96)],
            ];

            for (const [x, y] of probePoints) {
              const target = document.elementFromPoint(x, y);
              if (!(target instanceof HTMLElement)) {
                continue;
              }
              if (target.closest('[data-thread-item="true"]')) {
                continue;
              }
              if (pressElement(target)) {
                return true;
              }
            }
            return false;
          };

          const attemptSidebarClose = () => {
            if (!isSidebarOpen()) {
              pendingSidebarCloseUntil = 0;
              scheduleLayoutRepair();
              return;
            }

            const activeElement = document.activeElement;
            if (activeElement instanceof HTMLElement && typeof activeElement.blur === "function") {
              activeElement.blur();
            }

            let pressed = false;
            const toggle = findToggleSidebarButton();
            if (toggle instanceof HTMLElement) {
              pressed = pressElement(toggle) || pressed;
            }
            dispatchEscape();
            pressed = pressOutsideSidebar() || pressed;

            if (Date.now() < pendingSidebarCloseUntil) {
              closeSidebarTimer = window.setTimeout(
                attemptSidebarClose,
                pressed ? 180 : 260,
              );
            } else {
              pendingSidebarCloseUntil = 0;
              scheduleLayoutRepair();
            }
          };

          const requestSidebarClose = (delayMs, holdMs) => {
            pendingSidebarCloseUntil = Date.now() + holdMs;
            if (closeSidebarTimer) {
              window.clearTimeout(closeSidebarTimer);
            }
            closeSidebarTimer = window.setTimeout(
              attemptSidebarClose,
              delayMs,
            );
          };

          window.__androdexAndroidRequestSidebarClose = () => {
            if (!isSidebarOpen()) {
              scheduleLayoutRepair();
              return false;
            }
            requestSidebarClose(0, 1600);
            return true;
          };

          const closeSidebarAfterAction = (delayMs) => {
            requestSidebarClose(delayMs, 2200);

            window.setTimeout(() => {
              if (Date.now() < pendingSidebarCloseUntil && isSidebarOpen()) {
                requestSidebarClose(0, 1600);
              }
            }, delayMs + 500);

            window.setTimeout(() => {
              if (Date.now() < pendingSidebarCloseUntil && isSidebarOpen()) {
                requestSidebarClose(0, 1400);
              }
            }, delayMs + 1100);

            window.setTimeout(() => {
              if (Date.now() < pendingSidebarCloseUntil && isSidebarOpen()) {
                requestSidebarClose(0, 900);
              }
            }, delayMs + 1700);

            window.setTimeout(() => {
              if (Date.now() < pendingSidebarCloseUntil && isSidebarOpen()) {
                requestSidebarClose(0, 500);
              }
            }, delayMs + 2300);

            window.setTimeout(() => {
              scheduleLayoutRepair();
            }, delayMs + 140);
          };

          const bindSidebarInteractions = () => {
            forceSidebarProjectCreateButtonsVisible();

            const rows = Array.from(
              document.querySelectorAll(
                '[data-androdex-role="thread-row"], [data-thread-item="true"] [role="button"][data-testid^="thread-row-"]',
              ),
            );

            rows.forEach((row) => {
              if (!(row instanceof HTMLElement) || row.dataset.androdexAndroidTapBound === "1") {
                return;
              }

              row.dataset.androdexAndroidTapBound = "1";
              row.addEventListener(
                "click",
                (event) => {
                  if (!event.isTrusted) {
                    return;
                  }

                  if (nowWithinDebounce(row, "androdexAndroidTapAt", 700)) {
                    return;
                  }
                  closeSidebarAfterAction(220);
                },
                true,
              );
            });

            const explicitCreateButtons = Array.from(
              document.querySelectorAll('[data-androdex-role="create-thread"]'),
            );
            const createButtons = (
              explicitCreateButtons.length > 0
                ? explicitCreateButtons
                : Array.from(document.querySelectorAll("button")).filter((button) => {
                    const text = [
                      button.innerText || "",
                      button.getAttribute("aria-label") || "",
                      button.getAttribute("title") || "",
                      button.getAttribute("aria-description") || "",
                    ].join(" ");
                    return /create new thread in/i.test(text);
                  })
            );

            createButtons.forEach((button) => {
              if (!(button instanceof HTMLElement) || button.dataset.androdexAndroidCreateBound === "1") {
                return;
              }

              button.dataset.androdexAndroidCreateBound = "1";
              button.addEventListener(
                "click",
                (event) => {
                  if (!event.isTrusted) {
                    return;
                  }

                  if (nowWithinDebounce(button, "androdexAndroidCreateAt", 700)) {
                    return;
                  }
                  closeSidebarAfterAction(260);
                },
                true,
              );
            });

            return rows.length + createButtons.length;
          };

          if (document.body instanceof HTMLElement && document.body.dataset.androdexAndroidLayoutObserved !== "1") {
            const observer = new MutationObserver(() => {
              bindSidebarInteractions();
              scheduleLayoutRepair();
              if (Date.now() < pendingSidebarCloseUntil && isSidebarOpen()) {
                requestSidebarClose(0, Math.max(300, pendingSidebarCloseUntil - Date.now()));
              }
            });
            observer.observe(document.body, {
              childList: true,
              subtree: true,
            });
            document.body.dataset.androdexAndroidLayoutObserved = "1";
          }

          if (window.__androdexAndroidLayoutResizeBound !== true) {
            window.addEventListener("resize", scheduleLayoutRepair, { passive: true });
            window.addEventListener("orientationchange", scheduleLayoutRepair, { passive: true });
            window.__androdexAndroidLayoutResizeBound = true;
          }

          restoreViewportLayout();
          pinPrimaryHeader();
          const boundCount = bindSidebarInteractions();

          return boundCount;
        })();
        """.trimIndent()

private fun WebView.configureMirrorSettings() {
    val defaultUserAgent = settings.userAgentString.orEmpty()
    if (!defaultUserAgent.contains("AndrodexAndroidWebView")) {
        settings.userAgentString = "$defaultUserAgent AndrodexAndroidWebView/1.0".trim()
    }
    settings.javaScriptEnabled = true
    settings.javaScriptCanOpenWindowsAutomatically = true
    settings.domStorageEnabled = true
    settings.mediaPlaybackRequiresUserGesture = false
    settings.allowFileAccess = false
    settings.allowContentAccess = true
    settings.cacheMode = WebSettings.LOAD_DEFAULT
    settings.setSupportMultipleWindows(true)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        settings.safeBrowsingEnabled = true
    }
}

internal enum class MirrorNavigationTarget {
    InApp,
    External,
    Ignore,
}

internal fun classifyMirrorNavigation(
    url: String?,
    pairedOrigin: String,
): MirrorNavigationTarget {
    val normalizedUrl = normalizeMirrorNavigationUrl(url) ?: return MirrorNavigationTarget.Ignore
    return if (isAllowedAppUrl(normalizedUrl, pairedOrigin)) {
        MirrorNavigationTarget.InApp
    } else {
        MirrorNavigationTarget.External
    }
}

internal fun normalizeMirrorNavigationUrl(url: String?): String? {
    val normalizedUrl = url?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (normalizedUrl == "about:blank") {
        return null
    }
    return normalizedUrl
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
