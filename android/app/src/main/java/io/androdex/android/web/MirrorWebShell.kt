package io.androdex.android.web

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Message
import android.util.Log
import android.webkit.ConsoleMessage
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
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
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
import io.androdex.android.ui.shared.RemodexIconButton
import io.androdex.android.ui.shared.RemodexPageHeader
import io.androdex.android.ui.shared.remodexBottomSafeAreaInsets
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
    var activeLoadUrl by remember(pairedOrigin) { mutableStateOf<String?>(state.initialUrl ?: pairedOrigin) }
    var showHomeAssist by remember { mutableStateOf(false) }
    var pendingFileCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
    var webView by remember(pairedOrigin) { mutableStateOf<WebView?>(null) }
    var popupWebView by remember(pairedOrigin) { mutableStateOf<WebView?>(null) }
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

    DisposableEffect(pairedOrigin) {
        onDispose {
            popupWebView?.stopLoading()
            popupWebView?.destroy()
            popupWebView = null
            webView?.stopLoading()
            webView?.destroy()
            webView = null
        }
    }

    BackHandler(enabled = canGoBack) {
        webView?.goBack()
    }

    LaunchedEffect(activeLoadUrl, isLoading, loadError) {
        val targetUrl = activeLoadUrl ?: return@LaunchedEffect
        if (!isLoading || loadError != null) {
            return@LaunchedEffect
        }
        delay(pageLoadTimeoutMs)
        if (isLoading && loadError == null && activeLoadUrl == targetUrl) {
            loadError = "The paired environment took too long to respond. The host may be offline, or the saved session may need a fresh pairing link."
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
                displayLabel = state.displayLabel ?: pairedOrigin,
                onReload = webView?.let { currentWebView -> { currentWebView.reload() } },
                onClearPairing = onClearPairing,
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
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
                                canGoBack = view?.canGoBack() == true
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                activeLoadUrl = url ?: activeLoadUrl
                                isLoading = false
                                canGoBack = view?.canGoBack() == true
                                view?.installAndroidThreadTapBridge()
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
                                loadError = error?.description?.toString()?.takeIf { it.isNotBlank() }
                                    ?: "Unable to load the paired environment."
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
                                val statusCode = errorResponse?.statusCode
                                val reason = errorResponse?.reasonPhrase?.takeIf { it.isNotBlank() }
                                loadError = when {
                                    statusCode != null && reason != null -> {
                                        "The paired environment returned HTTP $statusCode ($reason)."
                                    }
                                    statusCode != null -> "The paired environment returned HTTP $statusCode."
                                    else -> "The paired environment returned an unexpected response."
                                }
                            }
                        }
                        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
                        webView = this
                        onWebViewReady()
                    }
                },
                update = { view ->
                    webView = view
                    val target = state.externalOpenPending ?: state.initialUrl ?: pairedOrigin
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
                            onClick = { webView?.reload() },
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
                        Button(
                            modifier = Modifier.padding(top = 16.dp),
                            onClick = openThreads,
                        ) {
                            Text("Open Threads")
                        }
                        Button(
                            modifier = Modifier.padding(top = 8.dp),
                            onClick = { showHomeAssist = false },
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
private fun MirrorShellTopBar(
    displayLabel: String,
    onReload: (() -> Unit)?,
    onClearPairing: () -> Unit,
) {
    val colors = RemodexTheme.colors
    val geometry = RemodexTheme.geometry
    var overflowMenuExpanded by rememberSaveable { mutableStateOf(false) }

    RemodexPageHeader(
        title = "Androdex",
        subtitle = displayLabel,
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

private fun WebView.openSidebarInPage() {
    evaluateJavascript(
        """
        (function () {
          const getToggleSidebarButtons = () => {
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
              });
            };
          })();

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
              document.querySelectorAll('[data-thread-item="true"] [role="button"][data-testid^="thread-row-"]'),
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
            const rows = Array.from(
              document.querySelectorAll(
                '[data-thread-item="true"] [role="button"][data-testid^="thread-row-"]',
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

            const createButtons = Array.from(document.querySelectorAll("button")).filter((button) => {
              const text = [
                button.innerText || "",
                button.getAttribute("aria-label") || "",
                button.getAttribute("title") || "",
                button.getAttribute("aria-description") || "",
              ].join(" ");
              return /create new thread in/i.test(text);
            });

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
