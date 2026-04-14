package io.androdex.android.web

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
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
import io.androdex.android.ui.shared.RemodexButton
import io.androdex.android.ui.shared.RemodexButtonStyle
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

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = remodexBottomSafeAreaInsets(),
        topBar = {
            MirrorShellTopBar(
                displayLabel = state.displayLabel ?: pairedOrigin,
                onOpenThreads = openThreads,
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
                                if (isAllowedAppUrl(url, pairedOrigin)) {
                                    return false
                                }
                                context.openExternalUrl(url)
                                return true
                            }

                            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
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
                            text = "No thread is open yet. Use Threads in the top bar to pick or create one.",
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
    onOpenThreads: () -> Unit,
    onReload: (() -> Unit)?,
    onClearPairing: () -> Unit,
) {
    val colors = RemodexTheme.colors
    val geometry = RemodexTheme.geometry
    var overflowMenuExpanded by rememberSaveable { mutableStateOf(false) }

    RemodexPageHeader(
        title = "Androdex",
        subtitle = displayLabel,
        navigation = {
            RemodexButton(
                onClick = onOpenThreads,
                style = RemodexButtonStyle.Secondary,
                contentPadding = PaddingValues(horizontal = geometry.spacing14, vertical = 0.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Menu,
                    contentDescription = null,
                    tint = colors.textPrimary,
                    modifier = Modifier.size(geometry.iconSize),
                )
                Text(
                    text = "Threads",
                    modifier = Modifier.padding(start = geometry.spacing8),
                )
            }
        },
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
          const match = visibleMatches.sort((left, right) => {
            const topDelta = left.getBoundingClientRect().top - right.getBoundingClientRect().top;
            if (topDelta !== 0) {
              return topDelta;
            }
            return left.getBoundingClientRect().left - right.getBoundingClientRect().left;
          })[0] || matches[0];
          if (match) {
            match.click();
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
        """
        (function () {
          const findToggleSidebarButton = () => {
            const matches = Array.from(document.querySelectorAll("button")).filter((button) => {
              const text = [
                button.innerText || "",
                button.getAttribute("aria-label") || "",
                button.getAttribute("title") || ""
              ].join(" ");
              return /toggle sidebar/i.test(text);
            });
            return matches.find((button) => button.getBoundingClientRect().width > 0) || matches[0] || null;
          };

          const restoreViewportLayout = () => {
            const px = window.innerHeight + "px";
            const nodes = [
              document.documentElement,
              document.body,
              document.getElementById("root"),
              document.querySelector('[data-slot="sidebar-wrapper"]'),
              document.querySelector("main"),
              Array.from(document.querySelectorAll("div")).find((node) => {
                return typeof node.className === "string" &&
                  node.className.includes("flex min-h-0 min-w-0 flex-1 flex-col overflow-x-hidden bg-background");
              }),
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
            }
          };

          const activateThread = (row) => {
            row.focus();
            row.dispatchEvent(new KeyboardEvent("keydown", {
              key: "Enter",
              code: "Enter",
              bubbles: true
            }));
            row.dispatchEvent(new KeyboardEvent("keyup", {
              key: "Enter",
              code: "Enter",
              bubbles: true
            }));

            window.setTimeout(() => {
              const toggle = findToggleSidebarButton();
              if (toggle instanceof HTMLElement) {
                toggle.click();
              }
            }, 180);

            window.setTimeout(() => {
              restoreViewportLayout();
            }, 320);
          };

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
                const now = Date.now();
                const lastActivatedAt = Number(row.dataset.androdexAndroidTapAt || "0");
                if (now - lastActivatedAt < 700) {
                  event.preventDefault();
                  event.stopPropagation();
                  return;
                }

                row.dataset.androdexAndroidTapAt = String(now);
                event.preventDefault();
                event.stopPropagation();
                activateThread(row);
              },
              true,
            );
          });

          return rows.length;
        })();
        """.trimIndent(),
        null,
    )
}

private fun WebView.configureMirrorSettings() {
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
