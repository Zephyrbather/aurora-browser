package com.fenglan.aurorabrowser

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.Message
import android.text.InputType
import android.util.TypedValue
import android.view.ViewGroup
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebSettings
import android.webkit.WebSettings.getDefaultUserAgent
import android.webkit.WebStorage
import android.widget.ArrayAdapter
import android.widget.CheckedTextView
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListPopupWindow
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import android.widget.FrameLayout.LayoutParams as FrameLayoutLayoutParams
import android.widget.LinearLayout.LayoutParams as LinearLayoutLayoutParams
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.view.setPadding
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.fenglan.aurorabrowser.browser.AdBlocker
import com.fenglan.aurorabrowser.browser.BrowserDownloads
import com.fenglan.aurorabrowser.browser.BrowserNavigationStateTools
import com.fenglan.aurorabrowser.browser.BrowserPreferences
import com.fenglan.aurorabrowser.browser.BrowserThemePalette
import com.fenglan.aurorabrowser.browser.BrowserThemeTools
import com.fenglan.aurorabrowser.browser.TimeBasedThemePeriod
import com.fenglan.aurorabrowser.browser.MagnetSearchSource
import com.fenglan.aurorabrowser.browser.MagnetSearchSources
import com.fenglan.aurorabrowser.browser.PageTranslator
import com.fenglan.aurorabrowser.browser.PageTextSegment
import com.fenglan.aurorabrowser.browser.ResourceTools
import com.fenglan.aurorabrowser.browser.SearchSuggestions
import com.fenglan.aurorabrowser.browser.SiteIconStore
import com.fenglan.aurorabrowser.browser.TorrentTools
import com.fenglan.aurorabrowser.browser.BrowserViewModel
import com.fenglan.aurorabrowser.data.BrowserStore
import com.fenglan.aurorabrowser.databinding.ActivityMainBinding
import com.fenglan.aurorabrowser.databinding.DialogBrowserMenuBinding
import com.fenglan.aurorabrowser.model.BrowserThemeMode
import com.fenglan.aurorabrowser.model.BrowserUiState
import com.fenglan.aurorabrowser.model.DEFAULT_HOME_URL
import com.fenglan.aurorabrowser.model.FileCategory
import com.fenglan.aurorabrowser.model.HomeSiteItem
import com.fenglan.aurorabrowser.model.HomeStyle
import com.fenglan.aurorabrowser.model.LibraryMode
import com.fenglan.aurorabrowser.model.P2pDownloadEngine
import com.fenglan.aurorabrowser.model.ResourceItem
import com.fenglan.aurorabrowser.model.SearchEngine
import com.fenglan.aurorabrowser.model.SiteSettings
import com.fenglan.aurorabrowser.model.TopSiteItem
import com.fenglan.aurorabrowser.model.TranslationEngine
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.security.SecureRandom
import kotlin.coroutines.resume
import kotlin.math.absoluteValue

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val viewModel: BrowserViewModel by viewModels()
    private val adBlocker by lazy { AdBlocker(this) }
    private val store by lazy { BrowserStore.getInstance(this) }
    private val preferences by lazy { BrowserPreferences(this) }
    private val siteIconStore by lazy { SiteIconStore(this) }
    private lateinit var mobileUserAgent: String
    private val tabs = mutableListOf<BrowserTab>()
    private val bridgeToken: String by lazy { createBridgeToken() }
    private var currentTabId: Long = 0L
    private var nextTabId: Long = 1L
    private var suggestionsPopup: ListPopupWindow? = null
    private var suggestionEntries: List<SuggestionEntry> = emptyList()
    private var suggestionsJob: Job? = null
    @Volatile private var lastActiveVideoUrl: String? = null
    @Volatile private var lastPreferredVideoResourceUrl: String? = null
    @Volatile private var lastPreferredVideoTitle: String? = null
    @Volatile private var currentPageUrlForGuards: String = DEFAULT_HOME_URL
    @Volatile private var lastCommittedPageUrlForGuards: String = DEFAULT_HOME_URL
    @Volatile private var lastAppNavigationAtMillis: Long = 0L
    private var homeKeyboardVisible = false
    private val animFadeIn by lazy { AnimationUtils.loadAnimation(this, R.anim.aurora_fade_in) }
    private val animFadeOut by lazy { AnimationUtils.loadAnimation(this, R.anim.aurora_fade_out) }
    private val animSlideInTop by lazy { AnimationUtils.loadAnimation(this, R.anim.aurora_slide_in_top) }
    private val animSlideOutTop by lazy { AnimationUtils.loadAnimation(this, R.anim.aurora_slide_out_top) }
    @Volatile private var lastTrustedWebNavigationAtMillis: Long = 0L
    @Volatile private var lastExplicitNavigationUrl: String? = null
    @Volatile private var lastExplicitNavigationAtMillis: Long = 0L
    @Volatile private var lastVideoInteractionAtMillis: Long = 0L
    @Volatile private var lastVideoInteractionAtEpochMillis: Long = 0L
    @Volatile private var lastBlockedMainFrameUrl: String? = null
    @Volatile private var lastBlockedMainFrameAtMillis: Long = 0L
    @Volatile private var lastRedirectConfirmAtMillis: Long = 0L
    @Volatile private var pendingBlockedRedirects = 0
    private val currentPageVideoResourceCache = linkedMapOf<String, ResourceItem>()
    private val iconFetchInFlight = linkedSetOf<String>()
    private var mostVisitedSites: List<TopSiteItem> = emptyList()
    private var customHomeSites: List<HomeSiteItem> = emptyList()
    private val defaultVideoPosterBitmap: Bitmap by lazy {
        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
    }
    private var persistTabsJob: Job? = null
    private val imagePickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            saveCustomHomeImage(uri)
        }
    }
    private var finishOnBack: Boolean = false
    private var currentThemePalette: BrowserThemePalette = BrowserThemeTools.paletteFor(BrowserThemeMode.LIGHT)
    private var lastAppliedThemeMode: BrowserThemeMode? = null
    private var lastAppliedTimePeriod: TimeBasedThemePeriod? = null
    private var lastHomeCustomSignature: String? = null
    private var lastHomeTopSignature: String? = null
    private var browserMenuDialog: BottomSheetDialog? = null
    private var browserMenuBinding: DialogBrowserMenuBinding? = null
    private var tabsOverviewDialog: Dialog? = null
    private var translationJob: Job? = null
    private var translationEnabled = false
    private var translatedPageUrl: String? = null
    private var fullscreenVideoView: View? = null
    private var fullscreenVideoContainer: FrameLayout? = null
    private var fullscreenVideoCallback: WebChromeClient.CustomViewCallback? = null
    private var fullscreenOriginalSystemUiVisibility: Int = 0
    private var fullscreenOriginalOrientation: Int = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    private var fullscreenOriginalWindowFlags: Int = 0
    private var fullscreenOriginalCutoutMode: Int = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
    private var webVideoPlaying = false
    @Volatile private var lastActiveVideoWidth: Double = 0.0
    @Volatile private var lastActiveVideoHeight: Double = 0.0
    private val timeThemeHandler = Handler(Looper.getMainLooper())
    private var timeThemeTickActive = false
    @Volatile private var lastCredentialSaveSignature: String? = null
    @Volatile private var lastCredentialSaveAtMillis: Long = 0L
    private var pendingCloudPlayMagnet: String? = null
    private var lastTorrentPromptValue: String? = null
    private var lastTorrentPromptAtMillis: Long = 0L
    private val youTubeVideoFormats = mutableListOf<YouTubeVideoFormat>()
    private val barcodeScanner by lazy {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(
                    Barcode.FORMAT_QR_CODE,
                    Barcode.FORMAT_AZTEC,
                    Barcode.FORMAT_DATA_MATRIX,
                )
                .build(),
        )
    }
    private val qrCameraLauncher = registerForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        if (bitmap == null) {
            return@registerForActivityResult
        }
        decodeQrImage(InputImage.fromBitmap(bitmap, 0))
    }
    private val qrGalleryLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val imageUri = uri ?: return@registerForActivityResult
        val image = runCatching { InputImage.fromFilePath(this, imageUri) }.getOrNull()
        if (image == null) {
            Toast.makeText(this, "图片读取失败", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        decodeQrImage(image)
    }

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            BrowserDownloads.refreshSystemDownloads(this@MainActivity, store)
        }
    }
    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        val clipboard = getSystemService(ClipboardManager::class.java)
        val text = clipboard.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(this)
            ?.toString()
            ?.takeIf { it.isNotBlank() }
            ?: return@OnPrimaryClipChangedListener
        promptTorrentDownload(text, title = null, copied = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mobileUserAgent = getDefaultUserAgent(this)
        ensureInitialTab()

        setupWebView()
        setupSwipeRefresh()
        setupFindBar()
        setupPrimaryActions()
        observeState()
        handleBackNavigation()
        updateSearchEngineLabel()
        refreshCustomHomeSites()
        refreshMostVisitedSites()
        applyBrowserTheme(viewModel.uiState.value)

        finishOnBack = intent.getBooleanExtra(EXTRA_FINISH_ON_BACK, false)
        pendingCloudPlayMagnet = intent.getStringExtra(EXTRA_CLOUD_MAGNET)?.takeIf { it.isNotBlank() }
        val initialInlineHtml = intent.getStringExtra(EXTRA_INLINE_HTML)?.takeIf { it.isNotBlank() }
        val initialUrl = intent.getStringExtra(EXTRA_URL) ?: homeDestinationUrl()
        if (intent.getBooleanExtra(EXTRA_USER_INITIATED, false) || initialUrl != DEFAULT_HOME_URL) {
            noteNavigationIntent()
        }
        if (savedInstanceState == null) {
            if (initialInlineHtml != null) {
                loadInlineHtml(initialUrl, initialInlineHtml)
            } else {
                navigateTo(initialUrl)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        finishOnBack = intent.getBooleanExtra(EXTRA_FINISH_ON_BACK, false) || finishOnBack
        val targetUrl = intent.getStringExtra(EXTRA_URL)
        val inlineHtml = intent.getStringExtra(EXTRA_INLINE_HTML)?.takeIf { it.isNotBlank() }
        pendingCloudPlayMagnet = intent.getStringExtra(EXTRA_CLOUD_MAGNET)?.takeIf { it.isNotBlank() }
        if (intent.getBooleanExtra(EXTRA_USER_INITIATED, false) || !targetUrl.isNullOrBlank()) {
            noteNavigationIntent()
        }
        if (!targetUrl.isNullOrBlank() && inlineHtml != null) {
            loadInlineHtml(targetUrl, inlineHtml)
        } else if (!targetUrl.isNullOrBlank()) {
            navigateTo(targetUrl)
        }
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            downloadReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        getSystemService(ClipboardManager::class.java).addPrimaryClipChangedListener(clipboardListener)
    }

    override fun onStop() {
        runCatching { unregisterReceiver(downloadReceiver) }
        runCatching { getSystemService(ClipboardManager::class.java).removePrimaryClipChangedListener(clipboardListener) }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        syncPreferenceDrivenUi()
        currentWebView().onResume()
        if (viewModel.uiState.value.settings.themeMode == BrowserThemeMode.TIME_BASED) {
            scheduleTimeThemeTick()
        }
    }

    override fun onPause() {
        cancelTimeThemeTick()
        updateWebVideoPlaybackState(false)
        currentWebView().onPause()
        super.onPause()
    }

    override fun onDestroy() {
        hideFullscreenVideo(notifyCallback = true)
        updateWebVideoPlaybackState(false)
        browserMenuDialog?.dismiss()
        tabsOverviewDialog?.dismiss()
        suggestionsJob?.cancel()
        translationJob?.cancel()
        persistTabsJob?.cancel()
        val tabStates = tabs.map { tab ->
            com.fenglan.aurorabrowser.data.BrowserStore.TabState(
                id = tab.id,
                title = tab.title,
                url = tab.webView.url?.takeIf { it != "about:blank" } ?: tab.url,
            )
        }
        store.saveTabs(tabStates, currentTabId)
        (tabs.map { it.webView } + binding.webView).distinct().forEach { webView ->
            webView.stopLoading()
            webView.webChromeClient = null
            webView.webViewClient = WebViewClient()
            webView.setDownloadListener(null)
            webView.removeJavascriptInterface(JS_BRIDGE_NAME)
            webView.destroy()
        }
        runCatching { barcodeScanner.close() }
        super.onDestroy()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setColorSchemeColors(currentThemePalette.accent)
        binding.swipeRefresh.setProgressBackgroundColorSchemeColor(currentThemePalette.surfaceAlt)
        binding.swipeRefresh.setOnChildScrollUpCallback { _, _ ->
            currentWebView().canScrollVertically(-1)
        }
        binding.swipeRefresh.setOnRefreshListener {
            val url = currentWebView().url
            if (!url.isNullOrBlank() && url != DEFAULT_HOME_URL) {
                currentWebView().reload()
            }
            binding.swipeRefresh.isRefreshing = false
        }
    }

    private fun setupWebView(webView: WebView = currentWebView()) {
        webView.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.loadsImagesAutomatically = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.allowFileAccess = false
            settings.allowContentAccess = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.setSupportMultipleWindows(false)
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            isHorizontalScrollBarEnabled = false
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setBackgroundColor(getColor(R.color.aurora_surface))

            addJavascriptInterface(PageBridge(), JS_BRIDGE_NAME)
            setFindListener { activeMatchOrdinal, numberOfMatches, isDoneCounting ->
                if (isDoneCounting && this == currentWebView()) {
                    viewModel.updateFindResult(
                        activeOrdinal = if (numberOfMatches == 0) 0 else activeMatchOrdinal + 1,
                        totalMatches = numberOfMatches,
                    )
                }
            }
            setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
                val sourceTab = tabForWebView(this)
                val pageUrl = sourceTab?.url ?: this.url ?: viewModel.uiState.value.currentUrl
                val message = BrowserDownloads.enqueue(
                    context = this@MainActivity,
                    store = store,
                    url = url,
                    title = sourceTab?.title ?: viewModel.uiState.value.pageTitle,
                    pageUrl = pageUrl,
                    mimeType = mimeType,
                    contentDisposition = contentDisposition,
                    referer = pageUrl,
                    userAgent = userAgent,
                    declaredSize = contentLength.takeIf { it > 0 },
                    isPrivate = preferences.getDefaultPrivacyDownload(),
                )
                Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
            }

            setOnLongClickListener { view ->
                val result = (view as WebView).hitTestResult
                if (result.type == WebView.HitTestResult.SRC_ANCHOR_TYPE ||
                    result.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE ||
                    result.type == WebView.HitTestResult.IMAGE_TYPE
                ) {
                    showLinkContextMenu(view)
                    true
                } else {
                    false
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    if (view == currentWebView()) {
                        viewModel.onProgressChanged(newProgress)
                        viewModel.updateNavigationState(this@apply.canGoBack(), this@apply.canGoForward())
                    }
                }

                override fun onReceivedTitle(view: WebView?, title: String?) {
                    tabForWebView(view)?.title = title?.takeIf(String::isNotBlank) ?: tabForWebView(view)?.title.orEmpty()
                    if (view == currentWebView()) {
                        viewModel.onTitleChanged(title)
                    }
                }

                override fun onReceivedIcon(view: WebView?, icon: Bitmap?) {
                    val pageUrl = view?.url ?: return
                    val bitmap = icon ?: return
                    saveSiteIcon(pageUrl, bitmap)
                }

                override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                    showFullscreenVideo(view, callback, null)
                }

                @Deprecated("Deprecated in Android WebView")
                override fun onShowCustomView(
                    view: View?,
                    requestedOrientation: Int,
                    callback: CustomViewCallback?,
                ) {
                    showFullscreenVideo(view, callback, requestedOrientation)
                }

                override fun onHideCustomView() {
                    hideFullscreenVideo(notifyCallback = false)
                }

                override fun getDefaultVideoPoster(): Bitmap {
                    return defaultVideoPosterBitmap
                }

                override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
                    val targetUrl = view?.hitTestResult?.extra
                    val adBlockOn = effectiveAdBlockEnabled(view?.url ?: viewModel.uiState.value.currentUrl)
                    if (isUserGesture && !targetUrl.isNullOrBlank() &&
                        (!adBlockOn || shouldAllowPopupTarget(targetUrl))) {
                        noteTrustedWebNavigation()
                        openPopupTarget(targetUrl)
                    } else if (adBlockOn) {
                        Snackbar.make(binding.root, "已拦截弹窗", Snackbar.LENGTH_SHORT).show()
                    }
                    return false
                }
            }

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val uri = request?.url ?: return false
                    val currentPageUri = currentGuardPageUri()
                    val gestureAllowed = if (request.hasGesture() && currentPageUri != null) {
                        shouldAllowGestureNavigation(currentPageUri, uri)
                    } else {
                        false
                    }
                    if (request.hasGesture() && gestureAllowed) {
                        noteTrustedWebNavigation()
                    }
                    if (view == currentWebView() && request.isForMainFrame && currentPageUri != null) {
                        applyUserAgentForPage(this@apply, uri.toString())
                        val adBlockOn = effectiveAdBlockEnabled(currentPageUri.toString())
                        val hasGesture = gestureAllowed || hasRecentAppNavigationIntent() || hasRecentTrustedWebNavigation()
                        val shouldBlock = adBlockOn && shouldBlockAutomaticRedirect(
                            currentPage = currentPageUri,
                            targetPage = uri,
                            hasGesture = hasGesture,
                        )
                        if (shouldBlock) {
                            val isAdUrl = adBlocker.isAdLikeUrl(uri, currentPageUri)
                            val isCrossOrigin = adBlocker.isSuspiciousCrossOrigin(
                                currentPageUri.host ?: "", uri.host ?: ""
                            )
                            if (isAdUrl) {
                                val message = if (request.hasGesture()) "已拦截可疑点击跳转" else "已拦截广告跳转"
                                showBlockedNavigationMessage(message, uri.toString())
                                return true
                            }
                            if (isCrossOrigin && !hasGesture) {
                                if (handleRedirectConfirm(uri, currentPageUri)) {
                                    return true
                                }
                                return false
                            }
                            showBlockedNavigationMessage("已阻止网页自动跳转", uri.toString())
                            return true
                        }
                        if (!adBlockOn) {
                            return false
                        }
                    }
                    val scheme = uri.scheme?.lowercase()
                    if (TorrentTools.extractValidMagnet(uri.toString()) != null) {
                        promptTorrentDownload(uri.toString(), title = null, copied = false)
                        return true
                    }
                    if (request.isForMainFrame && request.hasGesture() && TorrentTools.isTorrentUrl(uri.toString())) {
                        promptTorrentDownload(uri.toString(), title = null, copied = false)
                        return true
                    }
                    if (scheme == "http" || scheme == "https") {
                        return false
                    }
                    return runCatching {
                        startActivity(Intent(Intent.ACTION_VIEW, uri))
                        true
                    }.getOrDefault(false)
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    val targetUrl = url ?: DEFAULT_HOME_URL
                    tabForWebView(view)?.let { tab ->
                        if (!tab.frozen || targetUrl != DEFAULT_HOME_URL) {
                            tab.url = targetUrl
                        }
                    }
                    if (view != currentWebView()) {
                        return
                    }
                    applyUserAgentForPage(this@apply, targetUrl)
                    if (effectiveAdBlockEnabled(targetUrl) &&
                        shouldAbortMainFrameNavigation(targetUrl)) {
                        showBlockedNavigationMessage("已阻止网页自动跳转", targetUrl)
                        recoverFromBlockedNavigation(view, targetUrl)
                        return
                    }
                    currentPageUrlForGuards = url ?: DEFAULT_HOME_URL
                    lastActiveVideoUrl = null
                    lastPreferredVideoResourceUrl = null
                    lastPreferredVideoTitle = null
                    lastActiveVideoWidth = 0.0
                    lastActiveVideoHeight = 0.0
                    updateWebVideoPlaybackState(false)
                    translatedPageUrl = null
                    currentPageVideoResourceCache.clear()
                    youTubeVideoFormats.clear()
                    url?.takeIf { it.isNotBlank() && it != DEFAULT_HOME_URL }?.let { pageUrl ->
                        lifecycleScope.launch(Dispatchers.IO) {
                            store.deleteResourcesForPage(pageUrl)
                        }
                    }
                    favicon?.let { saveSiteIcon(targetUrl, it) }
                    viewModel.onPageStarted(url)
                    viewModel.updateNavigationState(this@apply.canGoBack(), this@apply.canGoForward())
                    if (url != DEFAULT_HOME_URL) {
                        view.evaluateJavascript(
                            "(function(){try{if(window.__auroraObserver){window.__auroraObserver.disconnect();window.__auroraObserver=null;}if(window.__auroraTick){clearInterval(window.__auroraTick);window.__auroraTick=null;}window.__auroraSnifferInstalled=false;}catch(e){}})()",
                            null,
                        )
                    }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    val finishedTab = tabForWebView(view)
                    finishedTab?.apply {
                        val finishedUrl = url ?: this.url
                        if (!frozen || finishedUrl != DEFAULT_HOME_URL) {
                            this.url = finishedUrl
                            title = view?.title?.takeIf(String::isNotBlank) ?: title
                        }
                    }
                    if (view != currentWebView()) {
                        return
                    }
                    currentPageUrlForGuards = url ?: currentPageUrlForGuards
                    lastCommittedPageUrlForGuards = url ?: lastCommittedPageUrlForGuards
                    viewModel.onPageFinished(url, view.title)
                    viewModel.updateNavigationState(this@apply.canGoBack(), this@apply.canGoForward())
                    if (url == DEFAULT_HOME_URL && finishedTab?.url == DEFAULT_HOME_URL) {
                        showHomeSurface(refreshData = true)
                        binding.swipeRefresh.isRefreshing = false
                        persistTabs()
                        return
                    }
                    if (!shouldSkipJsInjection(url)) {
                        injectResourceSniffer()
                        syncInjectedPageTheme(BrowserThemeTools.isEffectivelyDark(viewModel.uiState.value.settings.themeMode))
                    }
                    url?.takeIf { it != DEFAULT_HOME_URL }?.let(::fetchBestPageIcon)
                    if ((translationEnabled || effectiveAutoTranslateEnabled(url)) && !url.isNullOrBlank() && url != DEFAULT_HOME_URL) {
                        binding.root.postDelayed({ translateCurrentPage(force = effectiveAutoTranslateEnabled(url)) }, 260L)
                    }
                    if (effectiveAutofillEnabled(url) && !shouldSkipJsInjection(url)) {
                        injectCredentialAssistant()
                        scheduleCredentialAutofill(url)
                    }
                    if (ResourceTools.isYouTubePage(url)) {
                        binding.root.postDelayed({ extractYouTubeFormats() }, 400L)
                    }
                    maybeInjectCloudPlayMagnet(url)
                    if (url != DEFAULT_HOME_URL) {
                        binding.root.postDelayed({ refreshMostVisitedSites() }, 180L)
                    }
                    binding.swipeRefresh.isRefreshing = false
                    persistTabs()
                }

                override fun onPageCommitVisible(view: WebView?, url: String?) {
                    tabForWebView(view)?.let { tab ->
                        val committedUrl = url ?: tab.url
                        if (!tab.frozen || committedUrl != DEFAULT_HOME_URL) {
                            tab.url = committedUrl
                        }
                    }
                    if (view == currentWebView()) {
                        currentPageUrlForGuards = url ?: currentPageUrlForGuards
                        lastCommittedPageUrlForGuards = url ?: lastCommittedPageUrlForGuards
                    }
                }

                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): android.webkit.WebResourceResponse? {
                    val target = request?.url
                    val state = viewModel.uiState.value
                    val pageUrl = tabForWebView(view)?.url ?: state.currentUrl
                    if (effectiveAdBlockEnabled(pageUrl) &&
                        !shouldSkipJsInjection(pageUrl) &&
                        shouldBlockResourceRequest(target?.toString(), request?.isForMainFrame == true)) {
                        if (view == currentWebView()) {
                            viewModel.incrementBlockedCount()
                        }
                        return adBlocker.emptyResponse()
                    }

                    val url = target?.toString() ?: return null
                    val headers = request.requestHeaders
                    val tab = tabForWebView(view)
                    captureResourceCandidate(
                        url = url,
                        title = tab?.title ?: state.pageTitle,
                        pageUrl = tab?.url ?: state.currentUrl,
                        mimeType = null,
                        referer = headers["Referer"] ?: tab?.url ?: state.currentUrl,
                        userAgent = headers["User-Agent"],
                        prioritizeForCurrentVideo = view == currentWebView() && hasRecentVideoInteraction(),
                    )
                    return null
                }
            }
        }

        applyUserAgentForPage(webView, viewModel.uiState.value.currentUrl)
        applyNightModeToWebView(BrowserThemeTools.isEffectivelyDark(viewModel.uiState.value.settings.themeMode))
    }

    private fun setupPrimaryActions() {
        binding.refreshButton.setOnClickListener { currentWebView().reload() }
        binding.homeGoButton.setOnClickListener { loadFromHomeSearch() }
        binding.homeScanButton.setOnClickListener { showQrEntryOptions() }
        binding.homeEngineButton.setOnClickListener { showSearchEnginePicker() }
        binding.bookmarkButton.setOnClickListener { showBookmarkEditorDialog() }
        binding.homeBookmarksButton.setOnClickListener { openLibrary(LibraryMode.BOOKMARKS) }
        binding.homeHistoryButton.setOnClickListener { openLibrary(LibraryMode.HISTORY) }
        binding.homeResourcesButton.setOnClickListener {
            startActivity(MagnetSearchActivity.createIntent(this, binding.homeSearchInput.text?.toString().orEmpty()))
        }
        binding.homeDownloadsButton.setOnClickListener { openLibrary(LibraryMode.DOWNLOADS) }
        binding.homeAddSiteButton.setOnClickListener { showAddHomeSiteDialog() }
        binding.homeIncognitoButton.setOnClickListener {
            requestIncognitoMode(!viewModel.uiState.value.incognito)
        }
        setupMinimalDragHandler()
        binding.backButton.setOnClickListener {
            if (currentWebView().canGoBack()) {
                noteNavigationIntent()
                currentWebView().goBack()
            } else if (tabs.size > 1) {
                closeCurrentTab()
            }
        }
        binding.forwardButton.setOnClickListener {
            if (currentWebView().canGoForward()) {
                noteNavigationIntent()
                currentWebView().goForward()
            }
        }
        binding.homeButton.setOnClickListener { navigateHome(userInitiated = true) }
        binding.tabsButton.setOnClickListener { showTabsDialog() }
        binding.menuButton.setOnClickListener { showBrowserMenu() }

        binding.addressInput.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO || event?.keyCode == KeyEvent.KEYCODE_ENTER) {
                loadFromAddressBar()
                true
            } else {
                false
            }
        }
        binding.homeSearchInput.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO || event?.keyCode == KeyEvent.KEYCODE_ENTER) {
                loadFromHomeSearch()
                true
            } else {
                false
            }
        }
        binding.addressInput.doAfterTextChanged { editable ->
            if (binding.addressInput.hasFocus()) {
                viewModel.setAddressBarText(editable?.toString().orEmpty())
                showRecentSearches(binding.addressInput, editable?.toString().orEmpty())
            }
        }
        binding.addressInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                selectAllAddressInput()
                showRecentSearches(binding.addressInput, binding.addressInput.text?.toString().orEmpty())
            } else {
                dismissRecentSearches()
            }
        }
        binding.addressInput.setOnClickListener {
            selectAllAddressInput()
            showRecentSearches(binding.addressInput, binding.addressInput.text?.toString().orEmpty())
        }
        binding.homeSearchInput.doAfterTextChanged { editable ->
            if (binding.homeSearchInput.hasFocus()) {
                showRecentSearches(binding.homeSearchInput, editable?.toString().orEmpty())
            }
        }
        binding.homeSearchInput.setOnFocusChangeListener { _, hasFocus ->
            handleHomeSearchFocusChange(hasFocus)
        }
        binding.homeSearchInput.setOnClickListener {
            showRecentSearches(binding.homeSearchInput, binding.homeSearchInput.text?.toString().orEmpty())
        }
    }

    private fun setupFindBar() {
        binding.findInput.doAfterTextChanged { editable ->
            val keyword = editable?.toString().orEmpty().trim()
            if (keyword.isBlank()) {
                currentWebView().clearMatches()
                viewModel.clearFindResult()
            } else {
                currentWebView().findAllAsync(keyword)
            }
        }
        binding.findPrevButton.setOnClickListener { currentWebView().findNext(false) }
        binding.findNextButton.setOnClickListener { currentWebView().findNext(true) }
        binding.findCloseButton.setOnClickListener { hideFindBar() }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    renderState(state)
                }
            }
        }
    }

    private fun renderState(state: BrowserUiState) {
        applyBrowserTheme(state)
        if (!binding.addressInput.hasFocus() && binding.addressInput.text?.toString() != state.addressBarText) {
            binding.addressInput.setText(state.addressBarText)
            binding.addressInput.setSelection(binding.addressInput.text?.length ?: 0)
        }

        val showHome = isHomeScreen(state)
        if (binding.homePanel.isVisible != showHome) {
            if (showHome) {
                currentWebView().startAnimation(animFadeOut)
                binding.homePanel.startAnimation(animFadeIn)
            } else {
                binding.homePanel.startAnimation(animFadeOut)
                currentWebView().startAnimation(animFadeIn)
            }
        }
        binding.homePanel.isVisible = showHome
        updateWebViewVisibility(showHome)
        binding.toolbar.isVisible = false
        binding.addressBarContainer.isVisible = !showHome && !isOnCustomStartPage(state)
        if (showHome && binding.findBar.isVisible) {
            hideFindBar()
        }

        binding.toolbar.title = state.pageTitle.takeIf { it.isNotBlank() } ?: getString(R.string.browser_title)
        binding.toolbar.subtitle = buildSubtitle(state)
        binding.bookmarkButton.setImageResource(
            if (state.isBookmarked) R.drawable.ic_bookmark_filled else R.drawable.ic_bookmark_outline,
        )
        binding.bookmarkButton.imageTintList = ColorStateList.valueOf(
            if (state.isBookmarked) currentThemePalette.accent else currentThemePalette.text
        )
        binding.loadingProgress.isVisible = !showHome && state.isLoading
        binding.loadingProgress.progress = state.loadingProgress
        binding.backButton.isEnabled = state.canGoBack
        binding.forwardButton.isEnabled = state.canGoForward
        binding.overlayTip.isVisible = state.incognito
        binding.findStatus.text = "${state.findMatchOrdinal}/${state.findMatchCount}"
        binding.homeIncognitoButton.alpha = if (state.incognito) 1f else 0.55f
        updateTabsCountBadge()
        if (showHome) {
            if (needsHomeSectionData(state)) {
                lifecycleScope.launch {
                    val homes = withContext(Dispatchers.IO) { store.getHomeSites() }
                    val top = withContext(Dispatchers.IO) {
                        if (state.settings.showMostVisitedSites) store.getTopSites(HOME_TOP_SITE_LIMIT)
                        else emptyList()
                    }
                    customHomeSites = homes
                    mostVisitedSites = top
                    refreshHomeSections(viewModel.uiState.value.copy(currentUrl = DEFAULT_HOME_URL))
                }
            }
            renderHomeSectionsIfNeeded(state, force = true)
        } else {
            renderHomeSectionsIfNeeded(state)
        }
        updateCurrentTab(state)
        if (fullscreenVideoView != null) {
            binding.addressBarContainer.isVisible = false
            binding.bottomBarCard.isVisible = false
        }
    }

    private fun needsHomeSectionData(state: BrowserUiState): Boolean {
        return customHomeSites.isEmpty() && (state.settings.showMostVisitedSites && mostVisitedSites.isEmpty())
    }

    private fun buildSubtitle(state: BrowserUiState): String {
        val parts = mutableListOf("Chromium WebView 内核")
        if (state.incognito) parts += "无痕"
        if (effectiveDesktopMode(state.currentUrl)) parts += "桌面UA"
        if (state.settings.themeMode != BrowserThemeMode.LIGHT) parts += state.settings.themeMode.label
        if (translationEnabled && translatedPageUrl == state.currentUrl) parts += "双语翻译"
        if (effectiveAdBlockEnabled(state.currentUrl)) parts += "拦截${state.blockedRequestCount}"
        val siteSettings = siteSettingsForUrl(state.currentUrl)
        if (siteSettings?.desktopMode != null || siteSettings?.adBlockEnabled != null ||
            siteSettings?.autoTranslate == true || siteSettings?.autofillEnabled == false) {
            parts += "站点规则"
        }
        return parts.joinToString(" · ")
    }

    private fun handleBackNavigation() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    when {
                        fullscreenVideoView != null -> hideFullscreenVideo(notifyCallback = true)
                        binding.findBar.isVisible -> hideFindBar()
                        currentWebView().canGoBack() -> {
                            noteNavigationIntent()
                            currentWebView().goBack()
                        }
                        finishOnBack -> finish()
                        tabs.size > 1 -> closeCurrentTab()
                        !isHomeScreen(viewModel.uiState.value) -> navigateHome()
                        else -> finish()
                    }
                }
            },
        )
    }

    private fun showFullscreenVideo(
        view: View?,
        callback: WebChromeClient.CustomViewCallback?,
        requestedOrientation: Int?,
    ) {
        if (view == null) {
            callback?.onCustomViewHidden()
            return
        }
        if (fullscreenVideoView != null) {
            callback?.onCustomViewHidden()
            return
        }
        val decorView = window.decorView as? ViewGroup
        if (decorView == null) {
            callback?.onCustomViewHidden()
            return
        }
        val container = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            fitsSystemWindows = false
        }
        (view.parent as? ViewGroup)?.removeView(view)
        container.addView(
            view,
            FrameLayoutLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER,
            ),
        )
        fullscreenVideoView = view
        fullscreenVideoContainer = container
        fullscreenVideoCallback = callback
        fullscreenOriginalSystemUiVisibility = window.decorView.systemUiVisibility
        fullscreenOriginalOrientation = this.requestedOrientation
        fullscreenOriginalWindowFlags = window.attributes.flags
        fullscreenOriginalCutoutMode = window.attributes.layoutInDisplayCutoutMode
        view.setBackgroundColor(Color.BLACK)
        decorView.addView(
            container,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        this.requestedOrientation = resolveFullscreenVideoOrientation(requestedOrientation)
        applyFullscreenVideoWindowFlags()
        binding.addressBarContainer.isVisible = false
        binding.bottomBarCard.isVisible = false
    }

    private fun hideFullscreenVideo(notifyCallback: Boolean) {
        if (fullscreenVideoView == null && fullscreenVideoContainer == null) return
        val container = fullscreenVideoContainer
        val videoView = fullscreenVideoView
        container?.removeAllViews()
        (container?.parent as? ViewGroup)?.removeView(container)
        if (container == null) {
            (videoView?.parent as? ViewGroup)?.removeView(videoView)
        }
        fullscreenVideoView = null
        fullscreenVideoContainer = null
        val callback = fullscreenVideoCallback
        fullscreenVideoCallback = null
        requestedOrientation = fullscreenOriginalOrientation
        val attrs = window.attributes
        attrs.flags = fullscreenOriginalWindowFlags
        attrs.layoutInDisplayCutoutMode = fullscreenOriginalCutoutMode
        window.attributes = attrs
        window.decorView.systemUiVisibility = fullscreenOriginalSystemUiVisibility
        if (webVideoPlaying) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        val showHome = isHomeScreen(viewModel.uiState.value)
        binding.addressBarContainer.isVisible = !showHome
        binding.bottomBarCard.isVisible = true
        if (notifyCallback) {
            callback?.onCustomViewHidden()
        }
    }

    private fun applyFullscreenVideoWindowFlags() {
        val attrs = window.attributes
        attrs.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        window.attributes = attrs
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
        )
        window.decorView.systemUiVisibility = fullscreenOriginalSystemUiVisibility or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    private fun resolveFullscreenVideoOrientation(requestedOrientation: Int?): Int {
        return when (requestedOrientation) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT,
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,
            ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else -> if (lastActiveVideoHeight > 0.0 && lastActiveVideoHeight > lastActiveVideoWidth * 1.15) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
        }
    }

    private fun updateWebVideoPlaybackState(playing: Boolean) {
        webVideoPlaying = playing
        if (playing || fullscreenVideoView != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun showBrowserMenu() {
        val state = viewModel.uiState.value
        val currentVideoResources = currentPageVideoResources()
        browserMenuDialog?.dismiss()
        val dialog = BottomSheetDialog(this)
        styleDialog(dialog)
        val menuBinding = DialogBrowserMenuBinding.inflate(layoutInflater)
        browserMenuDialog = dialog
        browserMenuBinding = menuBinding
        dialog.setContentView(menuBinding.root)
        applyBrowserMenuTheme(menuBinding)
        val menuActions = buildBrowserMenuActions(state, currentVideoResources)
        menuBinding.menuHintText.text = if (menuActions.size > MENU_ITEMS_PER_PAGE) {
            "左右滑动查看更多"
        } else {
            "点击图标执行操作"
        }
        populateBrowserMenuPages(menuBinding, menuActions, dialog)
        dialog.setOnDismissListener {
            if (browserMenuDialog === dialog) {
                browserMenuDialog = null
                browserMenuBinding = null
            }
        }
        dialog.show()
    }

    private fun refreshOpenBrowserMenuTheme() {
        val dialog = browserMenuDialog ?: return
        val menuBinding = browserMenuBinding ?: return
        if (!dialog.isShowing) return
        applyBottomSheetDialogTheme(dialog)
        applyBrowserMenuTheme(menuBinding)
        val menuActions = buildBrowserMenuActions(viewModel.uiState.value, currentPageVideoResources())
        menuBinding.menuHintText.text = if (menuActions.size > MENU_ITEMS_PER_PAGE) {
            "左右滑动查看更多"
        } else {
            "点击图标执行操作"
        }
        populateBrowserMenuPages(menuBinding, menuActions, dialog)
    }

    private fun applyBrowserMenuTheme(menuBinding: DialogBrowserMenuBinding) {
        menuBinding.root.setBackgroundColor(currentThemePalette.surface)
        menuBinding.menuDragHandle.background = GradientDrawable().apply {
            setColor(currentThemePalette.line)
            cornerRadius = dp(2).toFloat()
        }
        menuBinding.menuTitleText.setTextColor(currentThemePalette.text)
        menuBinding.menuHintText.setTextColor(currentThemePalette.textMuted)
    }

    private fun buildBrowserMenuActions(
        state: BrowserUiState,
        currentVideoResources: List<ResourceItem>,
    ): List<BrowserMenuAction> {
        val actions = mutableListOf<BrowserMenuAction>()
        actions += BrowserMenuAction(
            iconRes = if (state.isBookmarked) R.drawable.ic_bookmark_filled else R.drawable.ic_bookmark_outline,
            label = if (state.isBookmarked) "取消书签" else "加书签",
            checked = state.isBookmarked,
        ) {
            showBookmarkEditorDialog()
        }
        if (!isHomeScreen(state)) {
            actions += BrowserMenuAction(R.drawable.ic_add, "加首页") { showAddHomeSiteDialog() }
            actions += BrowserMenuAction(R.drawable.ic_add_to_home, "发到桌面") { addToHomeScreen() }
        }
        actions += BrowserMenuAction(R.drawable.ic_bookmark_outline, "书签") { openLibrary(LibraryMode.BOOKMARKS) }
        actions += BrowserMenuAction(R.drawable.ic_history, "历史") { openLibrary(LibraryMode.HISTORY) }
        actions += BrowserMenuAction(R.drawable.ic_resources, "资源") { openLibrary(LibraryMode.RESOURCES) }
        actions += BrowserMenuAction(R.drawable.ic_download, "下载") { openLibrary(LibraryMode.DOWNLOADS) }

        if (!isHomeScreen(state) && currentVideoResources.isNotEmpty()) {
            actions += BrowserMenuAction(R.drawable.ic_download, "视频下载") {
                refreshCurrentVideoResources { resources -> showVideoDownloadPicker(resources) }
            }
        }
        if (!isHomeScreen(state)) {
            actions += BrowserMenuAction(R.drawable.ic_html, "存 HTML") { downloadCurrentPageHtml() }
            actions += BrowserMenuAction(R.drawable.ic_html, "阅读") { showReaderMode() }
            actions += BrowserMenuAction(R.drawable.ic_shield, "站点") { showSiteSettingsDialog() }
            actions += BrowserMenuAction(R.drawable.ic_info, "诊断") { showPrivacyDiagnosticsDialog() }
            actions += BrowserMenuAction(R.drawable.ic_find, "页内查找") {
                binding.findBar.isVisible = true
                binding.findBar.startAnimation(animSlideInTop)
                binding.findInput.requestFocus()
            }
            actions += BrowserMenuAction(
                iconRes = R.drawable.ic_translate,
                label = if (translationEnabled && translatedPageUrl == state.currentUrl) "关翻译" else "翻译",
                checked = translationEnabled && translatedPageUrl == state.currentUrl,
            ) {
                togglePageTranslation()
            }
        }
        actions += BrowserMenuAction(R.drawable.ic_settings, "设置") { openSettings() }

        actions += BrowserMenuAction(R.drawable.ic_theme, "主题") { showThemePicker() }
        actions += BrowserMenuAction(
            iconRes = R.drawable.ic_night,
            label = "夜间",
            checked = BrowserThemeTools.isEffectivelyDark(state.settings.themeMode),
        ) {
            val currentlyDark = BrowserThemeTools.isEffectivelyDark(state.settings.themeMode)
            val nextMode = if (currentlyDark) BrowserThemeMode.LIGHT else BrowserThemeMode.DARK
            applyThemeMode(nextMode)
        }
        actions += BrowserMenuAction(
            iconRes = R.drawable.ic_incognito,
            label = "无痕",
            checked = state.incognito,
        ) {
            requestIncognitoMode(!state.incognito)
        }
        actions += BrowserMenuAction(
            iconRes = R.drawable.ic_desktop,
            label = "电脑",
            checked = state.settings.desktopMode,
        ) {
            val enabled = !state.settings.desktopMode
            viewModel.setDesktopMode(enabled)
            applyDesktopMode(enabled, reload = true)
        }
        actions += BrowserMenuAction(
            iconRes = R.drawable.ic_shield,
            label = "拦截",
            checked = state.settings.adBlockEnabled,
        ) {
            val enabled = !state.settings.adBlockEnabled
            viewModel.setAdBlockEnabled(enabled)
            Toast.makeText(this, if (enabled) "广告拦截已开启" else "广告拦截已关闭", Toast.LENGTH_SHORT).show()
        }
        actions += BrowserMenuAction(
            iconRes = R.drawable.ic_tabs,
            label = "常访",
            checked = state.settings.showMostVisitedSites,
        ) {
            val enabled = !state.settings.showMostVisitedSites
            viewModel.setShowMostVisitedSites(enabled)
            if (enabled) {
                refreshMostVisitedSites()
            } else {
                mostVisitedSites = emptyList()
                refreshHomeSections(
                    viewModel.uiState.value.copy(settings = state.settings.copy(showMostVisitedSites = false)),
                )
            }
        }
        return actions
    }

    private fun populateBrowserMenuPages(
        menuBinding: DialogBrowserMenuBinding,
        actions: List<BrowserMenuAction>,
        dialog: BottomSheetDialog,
    ) {
        menuBinding.menuPagesContainer.removeAllViews()
        val pageWidth = (resources.displayMetrics.widthPixels - dp(32)).coerceAtLeast(dp(280))
        actions.chunked(MENU_ITEMS_PER_PAGE).forEachIndexed { pageIndex, pageActions ->
            val pageLayout = LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(pageWidth, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    if (pageIndex > 0) {
                        marginStart = dp(12)
                    }
                }
                orientation = LinearLayout.VERTICAL
            }
            repeat(MENU_ROWS) { row ->
                val rowLayout = LinearLayout(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        if (row > 0) {
                            topMargin = dp(12)
                        }
                    }
                    orientation = LinearLayout.HORIZONTAL
                }
                repeat(MENU_COLUMNS) { column ->
                    val actionIndex = row * MENU_COLUMNS + column
                    val action = pageActions.getOrNull(actionIndex)
                    rowLayout.addView(
                        createBrowserMenuActionView(action, dialog),
                        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                            if (column > 0) {
                                marginStart = dp(8)
                            }
                        },
                    )
                }
                pageLayout.addView(rowLayout)
            }
            menuBinding.menuPagesContainer.addView(pageLayout)
        }
    }

    private fun createBrowserMenuActionView(
        action: BrowserMenuAction?,
        dialog: BottomSheetDialog,
    ): View {
        if (action == null) {
            return Space(this).apply {
                minimumHeight = dp(92)
            }
        }

        val activeColor = tintWithAlpha(currentThemePalette.accent, if (action.checked) 48 else 0)
        val cardBackground = if (action.checked) activeColor else currentThemePalette.surfaceAlt
        val labelColor = if (action.checked) currentThemePalette.accent else currentThemePalette.text
        val iconTint = if (action.checked) currentThemePalette.accent else currentThemePalette.text

        val iconCard = MaterialCardView(this).apply {
            radius = dp(18).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = if (action.checked) currentThemePalette.accent else currentThemePalette.line
            setCardBackgroundColor(cardBackground)
            addView(
                ImageView(context).apply {
                    setImageResource(action.iconRes)
                    imageTintList = ColorStateList.valueOf(iconTint)
                },
                FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER),
            )
        }

        val titleView = TextView(this).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            text = action.label
            setTextColor(labelColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }

        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            minimumHeight = dp(92)
            orientation = LinearLayout.VERTICAL
            addView(iconCard, LinearLayout.LayoutParams(dp(56), dp(56)))
            addView(
                titleView,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(8)
                },
            )
            setOnClickListener {
                dialog.dismiss()
                action.onClick()
            }
        }
    }

    private fun showQrEntryOptions() {
        styleAlertDialog(MaterialAlertDialogBuilder(this))
            .setTitle("二维码跳转")
            .setItems(arrayOf("拍照扫码", "相册识别")) { _, which ->
                when (which) {
                    0 -> qrCameraLauncher.launch(null)
                    1 -> qrGalleryLauncher.launch("image/*")
                }
            }
            .setNegativeButton("取消", null)
            .showThemedAlert()
    }

    private fun showThemePicker() {
        val modes = BrowserThemeMode.entries
        val state = viewModel.uiState.value
        val labels = modes.map {
            if (it == BrowserThemeMode.CUSTOM_IMAGE && preferences.getCustomHomeImagePath() != null) {
                "${it.label} ✓"
            } else it.label
        }.toTypedArray()
        styleAlertDialog(MaterialAlertDialogBuilder(this))
            .setTitle("浏览器主题")
            .setSingleChoiceItems(labels, modes.indexOf(state.settings.themeMode)) { dialog, which ->
                dialog.dismiss()
                val selected = modes[which]
                if (selected == BrowserThemeMode.CUSTOM_IMAGE && preferences.getCustomHomeImagePath() != null) {
                    styleAlertDialog(MaterialAlertDialogBuilder(this))
                        .setTitle("自定义图片")
                        .setItems(arrayOf("更换图片", "使用当前图片")) { _, action ->
                            if (action == 0) imagePickerLauncher.launch("image/*")
                            else applyThemeMode(BrowserThemeMode.CUSTOM_IMAGE)
                        }
                        .setNegativeButton("取消", null)
                        .showThemedAlert()
                } else {
                    applyThemeMode(selected)
                }
            }
            .setNegativeButton("取消", null)
            .showThemedAlert()
    }

    private fun showDownloadLocationDialog() {
        val input = EditText(this).apply {
            setSingleLine()
            hint = "Aurora/Video"
            setText(preferences.getDownloadSubdirectory())
            setSelection(text?.length ?: 0)
        }
        styleAlertDialog(MaterialAlertDialogBuilder(this))
            .setTitle("下载目录")
            .setMessage("文件会保存到系统 Download 目录下的这个子路径")
            .setView(input)
            .setNegativeButton("取消", null)
            .setNeutralButton("恢复默认") { _, _ ->
                preferences.setDownloadSubdirectory("Aurora")
                Toast.makeText(this, "已恢复默认下载目录", Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton("保存") { _, _ ->
                preferences.setDownloadSubdirectory(input.text?.toString().orEmpty())
                Toast.makeText(
                    this,
                    "下载目录已更新为 Download/${preferences.getDownloadSubdirectory()}",
                    Toast.LENGTH_SHORT,
                ).show()
            }
            .showThemedAlert()
    }

    private fun showTranslationSettingsDialog() {
        var selectedEngine = viewModel.uiState.value.settings.translationEngine
        val engineButton = TextView(this).apply {
            setPadding(dp(14), dp(10), dp(14), dp(10))
            applyPillBackground(this, currentThemePalette)
        }
        val hintText = TextView(this).apply {
            setTextColor(currentThemePalette.textMuted)
            textSize = 12f
        }
        val credentialInput = EditText(this).apply {
            setSingleLine()
        }
        val secretInput = EditText(this).apply {
            setSingleLine()
        }
        fun refreshTranslationSettingInputs() {
            engineButton.text = "引擎 · ${selectedEngine.label}"
            when (selectedEngine) {
                TranslationEngine.BAIDU -> {
                    hintText.isVisible = true
                    hintText.text = "百度翻译需要填写 App ID 和密钥"
                    credentialInput.isVisible = true
                    secretInput.isVisible = true
                    credentialInput.hint = "Baidu App ID"
                    secretInput.hint = "Baidu Secret"
                    credentialInput.setText(preferences.getBaiduAppId())
                    secretInput.setText(preferences.getBaiduSecret())
                }

                TranslationEngine.YOUDAO -> {
                    hintText.isVisible = true
                    hintText.text = "有道翻译需要填写 App Key 和密钥"
                    credentialInput.isVisible = true
                    secretInput.isVisible = true
                    credentialInput.hint = "Youdao App Key"
                    secretInput.hint = "Youdao App Secret"
                    credentialInput.setText(preferences.getYoudaoAppKey())
                    secretInput.setText(preferences.getYoudaoAppSecret())
                }

                TranslationEngine.MICROSOFT -> {
                    hintText.isVisible = true
                    hintText.text = "默认优先用微软翻译，失败时自动回退到谷歌翻译"
                    credentialInput.isVisible = false
                    secretInput.isVisible = false
                    credentialInput.setText("")
                    secretInput.setText("")
                }

                TranslationEngine.GOOGLE -> {
                    hintText.isVisible = true
                    hintText.text = "谷歌翻译直接对网页显示双语"
                    credentialInput.isVisible = false
                    secretInput.isVisible = false
                    credentialInput.setText("")
                    secretInput.setText("")
                }
            }
        }
        engineButton.setOnClickListener {
            val engines = TranslationEngine.entries
            styleAlertDialog(MaterialAlertDialogBuilder(this))
                .setTitle("翻译引擎")
                .setSingleChoiceItems(
                    engines.map { it.label }.toTypedArray(),
                    engines.indexOf(selectedEngine),
                ) { dialog, which ->
                    selectedEngine = engines[which]
                    refreshTranslationSettingInputs()
                    dialog.dismiss()
                }
                .setNegativeButton("取消", null)
                .showThemedAlert()
        }
        refreshTranslationSettingInputs()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20))
            addView(engineButton)
            addView(
                hintText,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = dp(10)
                },
            )
            addView(
                credentialInput,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = dp(12)
                },
            )
            addView(
                secretInput,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = dp(12)
                },
            )
        }
        val canTranslateCurrentPage = !isHomeScreen(viewModel.uiState.value)
        styleAlertDialog(MaterialAlertDialogBuilder(this))
            .setTitle("网页翻译")
            .setView(container)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                saveTranslationSettings(selectedEngine, credentialInput, secretInput)
            }
            .apply {
                if (translationEnabled && canTranslateCurrentPage) {
                    setNeutralButton("关闭翻译") { _, _ ->
                        saveTranslationSettings(selectedEngine, credentialInput, secretInput)
                        translationEnabled = false
                        clearPageTranslation()
                    }
                } else if (canTranslateCurrentPage) {
                    setNeutralButton("立即翻译") { _, _ ->
                        saveTranslationSettings(selectedEngine, credentialInput, secretInput)
                        translationEnabled = true
                        translateCurrentPage(force = true)
                    }
                }
            }
            .showThemedAlert()
    }

    private fun saveTranslationSettings(
        engine: TranslationEngine,
        credentialInput: EditText,
        secretInput: EditText,
    ) {
        viewModel.setTranslationEngine(engine)
        when (engine) {
            TranslationEngine.BAIDU -> {
                preferences.setBaiduAppId(credentialInput.text?.toString().orEmpty())
                preferences.setBaiduSecret(secretInput.text?.toString().orEmpty())
            }

            TranslationEngine.YOUDAO -> {
                preferences.setYoudaoAppKey(credentialInput.text?.toString().orEmpty())
                preferences.setYoudaoAppSecret(secretInput.text?.toString().orEmpty())
            }

            else -> Unit
        }
    }

    private fun togglePageTranslation() {
        val currentUrl = viewModel.uiState.value.currentUrl
        if (currentUrl == DEFAULT_HOME_URL) {
            Toast.makeText(this, "首页无需翻译", Toast.LENGTH_SHORT).show()
            return
        }
        if (translationEnabled && translatedPageUrl == currentUrl) {
            translationEnabled = false
            clearPageTranslation()
            Snackbar.make(binding.root, "网页翻译已关闭", Snackbar.LENGTH_SHORT).show()
        } else {
            translationEnabled = true
            translateCurrentPage(force = true)
        }
    }

    private fun translateCurrentPage(force: Boolean = false) {
        val state = viewModel.uiState.value
        val pageUrl = state.currentUrl.takeIf { it.isNotBlank() && it != DEFAULT_HOME_URL } ?: return
        if (!force && translatedPageUrl == pageUrl) {
            return
        }
        if (!ensureTranslationEngineReady(state.settings.translationEngine)) {
            translationEnabled = false
            return
        }
        translationJob?.cancel()
        translationJob = lifecycleScope.launch {
            try {
                val rawResult = evaluateJavascriptAsync(PageTranslator.buildCollectScript())
                val decoded = decodeJavascriptStringResult(rawResult)
                val segments = PageTranslator.parseCollectedSegments(decoded)
                if (segments.isEmpty()) {
                    translatedPageUrl = null
                    if (force) {
                        Snackbar.make(binding.root, "当前页面没有可翻译文本", Snackbar.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                val engine = viewModel.uiState.value.settings.translationEngine
                val batches = segments.chunked(6)
                var appliedCount = 0
                var failedBatches = 0
                val webViewEval: suspend (String) -> String = { script -> withContext(Dispatchers.Main) { evaluateJavascriptAsync(script) } }
                for (batch in batches) {
                    if (pageUrl != viewModel.uiState.value.currentUrl) return@launch
                    try {
                        val batchResult = withContext(Dispatchers.IO) {
                            PageTranslator.translate(engine, batch, preferences, webViewEval)
                        }
                        val newItems = batchResult.items.filter { it.translatedText.isNotBlank() }
                        if (newItems.isNotEmpty()) {
                            currentWebView().evaluateJavascript(PageTranslator.buildApplyScript(newItems), null)
                        }
                        appliedCount += newItems.size
                    } catch (e: Throwable) {
                        failedBatches++
                    }
                }
                if (pageUrl != viewModel.uiState.value.currentUrl) return@launch
                if (appliedCount == 0) {
                    currentWebView().evaluateJavascript(PageTranslator.buildClearScript(), null)
                    translatedPageUrl = null
                    Snackbar.make(binding.root, "翻译失败：所有翻译引擎均不可用", Snackbar.LENGTH_LONG).show()
                    return@launch
                }
                translatedPageUrl = pageUrl
                translationEnabled = true
                val total = segments.size
                val message = if (appliedCount >= total) {
                    "${engine.label}双语翻译已应用（$appliedCount 段）"
                } else {
                    "双语翻译已应用（$appliedCount/$total 段）"
                }
                Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
            } catch (error: Throwable) {
                translatedPageUrl = null
                val detail = error.message?.take(60) ?: "未知错误"
                Snackbar.make(binding.root, "网页翻译失败：$detail", Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun clearPageTranslation() {
        translatedPageUrl = null
        currentWebView().evaluateJavascript(PageTranslator.buildClearScript(), null)
        PageTranslator.clearSessionCache()
    }

    private fun ensureTranslationEngineReady(engine: TranslationEngine): Boolean {
        return when (engine) {
            TranslationEngine.BAIDU -> {
                val ready = preferences.getBaiduAppId().isNotBlank() && preferences.getBaiduSecret().isNotBlank()
                if (!ready) {
                    Toast.makeText(this, "请先填写百度翻译凭据", Toast.LENGTH_SHORT).show()
                }
                ready
            }

            TranslationEngine.YOUDAO -> {
                val ready = preferences.getYoudaoAppKey().isNotBlank() && preferences.getYoudaoAppSecret().isNotBlank()
                if (!ready) {
                    Toast.makeText(this, "请先填写有道翻译凭据", Toast.LENGTH_SHORT).show()
                }
                ready
            }

            else -> true
        }
    }

    private suspend fun evaluateJavascriptAsync(script: String): String {
        return suspendCancellableCoroutine { continuation ->
            currentWebView().evaluateJavascript(script) { result ->
                if (continuation.isActive) {
                    continuation.resume(result ?: "null")
                }
            }
        }
    }

    private fun applyThemeMode(mode: BrowserThemeMode) {
        if (mode == BrowserThemeMode.CUSTOM_IMAGE) {
            val hasImage = preferences.getCustomHomeImagePath()?.let { java.io.File(it).exists() } == true
            if (!hasImage) {
                imagePickerLauncher.launch("image/*")
            }
        }
        viewModel.setThemeMode(mode)
        AppCompatDelegate.setDefaultNightMode(
            if (BrowserThemeTools.isEffectivelyDark(mode)) {
                AppCompatDelegate.MODE_NIGHT_YES
            } else {
                AppCompatDelegate.MODE_NIGHT_NO
            },
        )
        binding.root.post { applyBrowserTheme(viewModel.uiState.value) }
        if (mode == BrowserThemeMode.TIME_BASED) {
            scheduleTimeThemeTick()
        }
    }

    private fun downloadCurrentPageHtml() {
        val state = viewModel.uiState.value
        if (isHomeScreen(state)) {
            Toast.makeText(this, "当前页面没有可保存的 HTML", Toast.LENGTH_SHORT).show()
            return
        }
        val script = "(function(){try{return '<!DOCTYPE html>\\n' + document.documentElement.outerHTML;}catch(e){return '';}})()"
        currentWebView().evaluateJavascript(script) { rawResult ->
            val html = decodeJavascriptStringResult(rawResult)
            if (html.isBlank()) {
                Toast.makeText(this, "网页 HTML 导出失败", Toast.LENGTH_SHORT).show()
                return@evaluateJavascript
            }
            lifecycleScope.launch(Dispatchers.IO) {
                val message = BrowserDownloads.saveHtmlDocument(
                    context = this@MainActivity,
                    store = store,
                    title = state.pageTitle,
                    pageUrl = state.currentUrl,
                    html = html,
                )
                withContext(Dispatchers.Main) {
                    Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showReaderMode() {
        val state = viewModel.uiState.value
        if (isHomeScreen(state)) {
            Toast.makeText(this, "首页没有可阅读正文", Toast.LENGTH_SHORT).show()
            return
        }
        val script = """
            (function() {
              try {
                var root = document.querySelector('article, main, [role="main"], .article, .post, .entry-content, .content') || document.body;
                var title = document.querySelector('h1') && document.querySelector('h1').innerText || document.title || '';
                var nodes = Array.from(root.querySelectorAll('p,h2,h3,li,blockquote,pre'));
                var text = nodes.map(function(node) {
                  return String(node.innerText || node.textContent || '').replace(/\s+/g, ' ').trim();
                }).filter(function(value) { return value.length > 1; }).join('\n\n');
                if (!text) {
                  text = String(root.innerText || '').replace(/\n{3,}/g, '\n\n').trim();
                }
                return JSON.stringify({ title: title, text: text.slice(0, 60000) });
              } catch (e) {
                return '{}';
              }
            })();
        """.trimIndent()
        currentWebView().evaluateJavascript(script) { rawResult ->
            val payload = runCatching { JSONObject(decodeJavascriptStringResult(rawResult)) }.getOrNull()
            val title = payload?.optString("title").orEmpty().ifBlank { state.pageTitle.ifBlank { "阅读模式" } }
            val text = payload?.optString("text").orEmpty()
            if (text.isBlank()) {
                Toast.makeText(this, "未提取到正文内容", Toast.LENGTH_SHORT).show()
                return@evaluateJavascript
            }
            val content = TextView(this).apply {
                this.text = text
                setTextColor(currentThemePalette.text)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setLineSpacing(dp(4).toFloat(), 1.08f)
                setPadding(dp(24), dp(12), dp(24), dp(12))
            }
            val scroll = ScrollView(this).apply {
                addView(content)
            }
            styleAlertDialog(MaterialAlertDialogBuilder(this))
                .setTitle(title.take(80))
                .setView(scroll)
                .setNegativeButton("关闭", null)
                .setPositiveButton("复制正文") { _, _ ->
                    val clipboard = getSystemService(ClipboardManager::class.java)
                    clipboard.setPrimaryClip(ClipData.newPlainText("reader", text))
                    Toast.makeText(this, "正文已复制", Toast.LENGTH_SHORT).show()
                }
                .showThemedAlert()
        }
    }

    private fun showSiteSettingsDialog() {
        val pageUrl = viewModel.uiState.value.currentUrl.takeIf { it.isNotBlank() && it != DEFAULT_HOME_URL }
            ?: return Toast.makeText(this, "当前页面没有站点设置", Toast.LENGTH_SHORT).show()
        val host = hostForUrl(pageUrl)
            ?: return Toast.makeText(this, "无法识别站点域名", Toast.LENGTH_SHORT).show()
        val current = preferences.getSiteSettings(host) ?: SiteSettings(host = host)
        var desktopMode = current.desktopMode
        var adBlockEnabled = current.adBlockEnabled
        var autoTranslate = current.autoTranslate
        var autofillEnabled = current.autofillEnabled
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        lateinit var desktopRow: TextView
        lateinit var adBlockRow: TextView
        lateinit var translateRow: TextView
        lateinit var autofillRow: TextView
        fun triLabel(value: Boolean?, global: Boolean): String {
            return when (value) {
                true -> "开启"
                false -> "关闭"
                null -> "跟随全局（${if (global) "开启" else "关闭"}）"
            }
        }
        fun nextTri(value: Boolean?): Boolean? {
            return when (value) {
                null -> true
                true -> false
                false -> null
            }
        }
        fun updateRows() {
            desktopRow.text = "电脑模式：${triLabel(desktopMode, viewModel.uiState.value.settings.desktopMode)}"
            adBlockRow.text = "广告拦截：${triLabel(adBlockEnabled, viewModel.uiState.value.settings.adBlockEnabled)}"
            translateRow.text = "自动翻译：${if (autoTranslate) "开启" else "关闭"}"
            autofillRow.text = "账号填充：${if (autofillEnabled) "开启" else "关闭"}"
        }
        fun row(onClick: () -> Unit): TextView {
            return TextView(this).apply {
                setTextColor(currentThemePalette.text)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setPadding(0, dp(14), 0, dp(14))
                setOnClickListener { onClick() }
            }
        }
        desktopRow = row {
            desktopMode = nextTri(desktopMode)
            updateRows()
        }
        adBlockRow = row {
            adBlockEnabled = nextTri(adBlockEnabled)
            updateRows()
        }
        translateRow = row {
            autoTranslate = !autoTranslate
            updateRows()
        }
        autofillRow = row {
            autofillEnabled = !autofillEnabled
            updateRows()
        }
        container.addView(desktopRow)
        container.addView(adBlockRow)
        container.addView(translateRow)
        container.addView(autofillRow)
        updateRows()
        styleAlertDialog(MaterialAlertDialogBuilder(this))
            .setTitle(host)
            .setView(container)
            .setNegativeButton("取消", null)
            .setNeutralButton("清除") { _, _ ->
                preferences.clearSiteSettings(host)
                applyUserAgentForPage(currentWebView(), pageUrl)
                currentWebView().reload()
                Snackbar.make(binding.root, "站点设置已清除", Snackbar.LENGTH_SHORT).show()
            }
            .setPositiveButton("保存") { _, _ ->
                preferences.saveSiteSettings(
                    SiteSettings(
                        host = host,
                        desktopMode = desktopMode,
                        adBlockEnabled = adBlockEnabled,
                        autoTranslate = autoTranslate,
                        autofillEnabled = autofillEnabled,
                    ),
                )
                applyUserAgentForPage(currentWebView(), pageUrl)
                currentWebView().reload()
                Snackbar.make(binding.root, "站点设置已保存", Snackbar.LENGTH_SHORT).show()
            }
            .showThemedAlert()
    }

    private fun showPrivacyDiagnosticsDialog() {
        val state = viewModel.uiState.value
        val pageUrl = state.currentUrl.takeIf { it.isNotBlank() && it != DEFAULT_HOME_URL }
            ?: return Toast.makeText(this, "当前页面没有诊断信息", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val captured = withContext(Dispatchers.IO) { store.getResourcesForPage(pageUrl) }
            val host = hostForUrl(pageUrl).orEmpty()
            val siteSettings = preferences.getSiteSettings(host)
            val message = buildString {
                appendLine("页面：${state.pageTitle.ifBlank { "未命名页面" }}")
                appendLine("地址：$pageUrl")
                appendLine("域名：${host.ifBlank { "未知" }}")
                appendLine("拦截请求：${state.blockedRequestCount}")
                appendLine("广告拦截：${if (effectiveAdBlockEnabled(pageUrl)) "开启" else "关闭"}")
                appendLine("电脑模式：${if (effectiveDesktopMode(pageUrl)) "开启" else "关闭"}")
                appendLine("账号填充：${if (effectiveAutofillEnabled(pageUrl)) "开启" else "关闭"}")
                appendLine("自动翻译：${if (siteSettings?.autoTranslate == true) "开启" else "关闭"}")
                appendLine("无痕模式：${if (state.incognito) "开启" else "关闭"}")
                appendLine("Cookie：${if (CookieManager.getInstance().acceptCookie()) "允许" else "关闭"}")
                appendLine("嗅探资源：${captured.size}")
                captured.take(6).forEachIndexed { index, item ->
                    appendLine("${index + 1}. ${item.category.label} ${item.url.take(96)}")
                }
                appendLine("UA：${currentWebView().settings.userAgentString}")
            }
            styleAlertDialog(MaterialAlertDialogBuilder(this@MainActivity))
                .setTitle("隐私与站点诊断")
                .setMessage(message)
                .setNegativeButton("关闭", null)
                .setNeutralButton("清理站点") { _, _ -> clearCurrentSiteData(pageUrl) }
                .setPositiveButton("复制") { _, _ ->
                    val clipboard = getSystemService(ClipboardManager::class.java)
                    clipboard.setPrimaryClip(ClipData.newPlainText("diagnostics", message))
                    Toast.makeText(this@MainActivity, "诊断信息已复制", Toast.LENGTH_SHORT).show()
                }
                .showThemedAlert()
        }
    }

    private fun clearCurrentSiteData(pageUrl: String) {
        originForUrl(pageUrl)?.let { origin ->
            WebStorage.getInstance().deleteOrigin(origin)
        }
        lifecycleScope.launch(Dispatchers.IO) {
            store.deleteResourcesForPage(pageUrl)
            withContext(Dispatchers.Main) {
                currentPageVideoResourceCache.clear()
                Snackbar.make(binding.root, "站点本地存储和本页资源记录已清理", Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    private fun decodeQrImage(image: InputImage) {
        barcodeScanner.process(image)
            .addOnSuccessListener { barcodes ->
                val content = barcodes.firstNotNullOfOrNull { it.rawValue?.trim()?.takeIf(String::isNotBlank) }
                if (content.isNullOrBlank()) {
                    Toast.makeText(this, "未识别到二维码链接", Toast.LENGTH_SHORT).show()
                    return@addOnSuccessListener
                }
                val target = resolveInputToUrl(content)
                navigateTo(target, userInitiated = true)
            }
            .addOnFailureListener {
                Toast.makeText(this, "二维码识别失败", Toast.LENGTH_SHORT).show()
            }
    }

    private fun applyDesktopMode(enabled: Boolean, reload: Boolean) {
        tabs.forEach { tab ->
            val pageUrl = tab.webView.url ?: tab.url
            applyUserAgentForPage(tab.webView, pageUrl, globalDesktop = enabled)
            if (reload && tab.webView.url != null) {
                tab.webView.reload()
            }
        }
    }

    private fun scheduleTimeThemeTick() {
        if (timeThemeTickActive) return
        timeThemeTickActive = true
        val runnable = object : Runnable {
            override fun run() {
                if (!timeThemeTickActive) return
                val state = viewModel.uiState.value
                if (state.settings.themeMode != BrowserThemeMode.TIME_BASED) {
                    timeThemeTickActive = false
                    return
                }
                val period = BrowserThemeTools.timeBasedPeriod()
                applyBrowserTheme(state)
                AppCompatDelegate.setDefaultNightMode(
                    if (period.isDark) AppCompatDelegate.MODE_NIGHT_YES
                    else AppCompatDelegate.MODE_NIGHT_NO
                )
                timeThemeHandler.postDelayed(this, BrowserThemeTools.millisUntilNextPeriod())
            }
        }
        timeThemeHandler.postDelayed(runnable, BrowserThemeTools.millisUntilNextPeriod())
    }

    private fun cancelTimeThemeTick() {
        timeThemeTickActive = false
        timeThemeHandler.removeCallbacksAndMessages(null)
    }

    private fun applyNightModeToWebView(enabled: Boolean) {
        tabs.forEach { applyNightModeToWebView(it.webView, enabled) }
        syncInjectedPageTheme(enabled)
    }

    private fun applyNightModeToWebView(webView: WebView, enabled: Boolean) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.settings, enabled)
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            WebSettingsCompat.setForceDark(
                webView.settings,
                if (enabled) WebSettingsCompat.FORCE_DARK_ON else WebSettingsCompat.FORCE_DARK_OFF,
            )
        }
    }

    private fun applyBrowserTheme(state: BrowserUiState) {
        val settings = state.settings
        val palette = BrowserThemeTools.paletteFor(settings.themeMode)
        currentThemePalette = palette
        com.fenglan.aurorabrowser.browser.ThemeManager.update(palette)
        val currentPeriod = if (settings.themeMode == BrowserThemeMode.TIME_BASED) {
            BrowserThemeTools.timeBasedPeriod()
        } else null
        if (lastAppliedThemeMode == settings.themeMode &&
            (settings.themeMode != BrowserThemeMode.TIME_BASED || lastAppliedTimePeriod == currentPeriod)
        ) {
            return
        }
        lastAppliedThemeMode = settings.themeMode
        lastAppliedTimePeriod = currentPeriod

        window.setBackgroundDrawableResource(android.R.color.transparent)
        binding.root.setBackgroundColor(palette.surface)
        binding.addressBarContainer.setBackgroundColor(palette.surface)
        binding.findBar.setBackgroundColor(palette.surfaceAlt)
        binding.loadingProgress.progressTintList = ColorStateList.valueOf(palette.accent)
        binding.loadingProgress.progressBackgroundTintList = ColorStateList.valueOf(
            if (palette.gradientStart != null) palette.surfaceAlt else palette.line
        )
        binding.overlayTip.setTextColor(palette.textMuted)
        binding.findStatus.setTextColor(palette.textMuted)
        tabs.forEach { it.webView.setBackgroundColor(palette.surface) }

        applyInputLayoutTheme(binding.addressInputLayout, binding.addressInput, palette)
        applyInputLayoutTheme(binding.findInputLayout, binding.findInput, palette)

        if (palette.gradientStart != null && palette.gradientEnd != null) {
            binding.root.background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                if (palette.gradientMid != null) intArrayOf(palette.gradientStart, palette.gradientMid, palette.gradientEnd)
                else intArrayOf(palette.gradientStart, palette.gradientEnd),
            )
            val cardGradient = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                if (palette.gradientMid != null) intArrayOf(
                    palette.gradientStart.let { android.graphics.Color.argb(178, android.graphics.Color.red(it), android.graphics.Color.green(it), android.graphics.Color.blue(it)) },
                    palette.gradientMid.let { android.graphics.Color.argb(127, android.graphics.Color.red(it), android.graphics.Color.green(it), android.graphics.Color.blue(it)) },
                    palette.gradientEnd.let { android.graphics.Color.argb(178, android.graphics.Color.red(it), android.graphics.Color.green(it), android.graphics.Color.blue(it)) }
                ) else intArrayOf(
                    palette.gradientStart.let { android.graphics.Color.argb(178, android.graphics.Color.red(it), android.graphics.Color.green(it), android.graphics.Color.blue(it)) },
                    palette.gradientEnd.let { android.graphics.Color.argb(178, android.graphics.Color.red(it), android.graphics.Color.green(it), android.graphics.Color.blue(it)) }
                ),
            ).apply { cornerRadius = dp(28).toFloat() }
            binding.homeSearchCard.setCardBackgroundColor(Color.TRANSPARENT)
            binding.homeSearchCard.strokeColor = palette.line
            binding.homeSearchCard.strokeWidth = 0
            binding.homeSearchCard.cardElevation = 0f
            val innerSearch = binding.homeSearchCard.getChildAt(0)
            innerSearch?.background = cardGradient

            val barGradient = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                if (palette.gradientMid != null) intArrayOf(
                    palette.gradientStart.let { android.graphics.Color.argb(204, android.graphics.Color.red(it), android.graphics.Color.green(it), android.graphics.Color.blue(it)) },
                    palette.gradientMid.let { android.graphics.Color.argb(153, android.graphics.Color.red(it), android.graphics.Color.green(it), android.graphics.Color.blue(it)) },
                    palette.gradientEnd.let { android.graphics.Color.argb(204, android.graphics.Color.red(it), android.graphics.Color.green(it), android.graphics.Color.blue(it)) }
                ) else intArrayOf(
                    palette.gradientStart.let { android.graphics.Color.argb(204, android.graphics.Color.red(it), android.graphics.Color.green(it), android.graphics.Color.blue(it)) },
                    palette.gradientEnd.let { android.graphics.Color.argb(204, android.graphics.Color.red(it), android.graphics.Color.green(it), android.graphics.Color.blue(it)) }
                ),
            ).apply { cornerRadius = dp(24).toFloat() }
            binding.bottomBarCard.setCardBackgroundColor(Color.TRANSPARENT)
            binding.bottomBarCard.strokeColor = palette.line
            binding.bottomBarCard.strokeWidth = 0
            binding.bottomBarCard.cardElevation = 0f
            binding.bottomBar.background = barGradient

            binding.addressBarContainer.background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                if (palette.gradientMid != null) intArrayOf(palette.gradientStart, palette.gradientMid, palette.gradientEnd)
                else intArrayOf(palette.gradientStart, palette.gradientEnd),
            )
        } else {
            binding.homeSearchCard.setCardBackgroundColor(palette.surfaceAlt)
            binding.homeSearchCard.strokeColor = palette.line
            binding.bottomBarCard.setCardBackgroundColor(palette.surfaceAlt)
            binding.bottomBarCard.strokeColor = palette.line
            binding.bottomBarCard.strokeWidth = dp(1)
            binding.bottomBar.background = null
            binding.addressBarContainer.setBackgroundColor(palette.surface)
        }
        applyPillBackground(binding.homeEngineButton, palette)

        binding.homeSearchInput.setTextColor(palette.text)
        binding.homeSearchInput.setHintTextColor(palette.textMuted)
        binding.homeCustomSitesTitle.setTextColor(palette.text)
        binding.homeTopSitesTitle.setTextColor(palette.text)
        binding.homeCustomSitesTitle.setCompoundDrawablesWithIntrinsicBounds(null, null, null, null)
        binding.homeTopSitesTitle.setCompoundDrawablesWithIntrinsicBounds(null, null, null, null)
        binding.tabsCountText.setBackgroundColor(palette.accent)
        binding.tabsCountText.setTextColor(Color.WHITE)
        if (settings.themeMode == BrowserThemeMode.CUSTOM_IMAGE) {
            applyCustomImageBackground()
        } else if (palette.gradientStart != null && palette.gradientEnd != null) {
            binding.homePanel.background = null
            binding.homeBackgroundScrim.isVisible = false
            binding.homeBackgroundImage.isVisible = false
            binding.homeBackgroundImage.setImageDrawable(null)
        } else {
            binding.homePanel.background = null
            binding.homeBackgroundScrim.setBackgroundColor(palette.homeOverlay)
            binding.homeBackgroundImage.isVisible = false
            binding.homeBackgroundImage.setImageDrawable(null)
            binding.homeBackgroundScrim.isVisible = false
        }

        listOf(
            binding.refreshButton,
            binding.bookmarkButton,
            binding.findPrevButton,
            binding.findNextButton,
            binding.findCloseButton,
            binding.homeGoButton,
            binding.homeScanButton,
            binding.homeBookmarksButton,
            binding.homeHistoryButton,
            binding.homeResourcesButton,
            binding.homeDownloadsButton,
            binding.homeIncognitoButton,
            binding.homeAddSiteButton,
            binding.backButton,
            binding.forwardButton,
            binding.homeButton,
            binding.tabsButton,
            binding.menuButton,
        ).forEach { tintToolbarButton(it, palette.text) }
        applyBottomNavigationTheme(palette)
        refreshOpenBrowserMenuTheme()

        applyNightModeToWebView(BrowserThemeTools.isEffectivelyDark(settings.themeMode))
        invalidateHomeSections()
    }

    private fun applyBottomNavigationTheme(palette: BrowserThemePalette) {
        val isGradient = palette.gradientStart != null && palette.gradientEnd != null
        binding.bottomBarCard.setCardBackgroundColor(if (isGradient) Color.TRANSPARENT else palette.surfaceAlt)
        binding.bottomBarCard.strokeColor = if (isGradient) Color.TRANSPARENT else palette.line
        binding.bottomBarCard.strokeWidth = if (isGradient) 0 else dp(1)
        binding.bottomBarCard.cardElevation = 0f
        binding.bottomBar.background = if (isGradient) {
            GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                if (palette.gradientMid != null) intArrayOf(
                    tintWithAlpha(palette.gradientStart!!, 214),
                    tintWithAlpha(palette.gradientMid, 172),
                    tintWithAlpha(palette.gradientEnd!!, 214),
                ) else intArrayOf(
                    tintWithAlpha(palette.gradientStart!!, 214),
                    tintWithAlpha(palette.gradientEnd!!, 214),
                ),
            ).apply { cornerRadius = dp(24).toFloat() }
        } else {
            null
        }
        val iconTint = ColorStateList(
            arrayOf(
                intArrayOf(-android.R.attr.state_enabled),
                intArrayOf(),
            ),
            intArrayOf(
                tintWithAlpha(palette.textMuted, 132),
                palette.text,
            ),
        )
        val ripple = ColorStateList.valueOf(tintWithAlpha(palette.accent, if (isGradient) 64 else 42))
        listOf(
            binding.backButton,
            binding.forwardButton,
            binding.homeButton,
            binding.tabsButton,
            binding.menuButton,
        ).forEach { button ->
            button.imageTintList = iconTint
            button.background = RippleDrawable(
                ripple,
                ColorDrawable(Color.TRANSPARENT),
                GradientDrawable().apply {
                    cornerRadius = dp(18).toFloat()
                    setColor(Color.WHITE)
                },
            )
        }
        binding.tabsCountText.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(palette.accent)
        }
        binding.tabsCountText.setTextColor(Color.WHITE)
    }

    private fun applyInputLayoutTheme(
        layout: com.google.android.material.textfield.TextInputLayout,
        input: TextView,
        palette: BrowserThemePalette,
    ) {
        layout.boxBackgroundColor = palette.surfaceAlt
        layout.boxStrokeColor = palette.line
        layout.defaultHintTextColor = ColorStateList.valueOf(palette.textMuted)
        input.setTextColor(palette.text)
        input.setHintTextColor(palette.textMuted)
    }

    private fun applyPillBackground(view: TextView, palette: BrowserThemePalette) {
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(16).toFloat()
            setColor(palette.surface)
            setStroke(dp(1), palette.line)
        }
        view.setTextColor(palette.text)
    }

    private fun saveCustomHomeImage(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri) ?: return
            val file = java.io.File(filesDir, "custom_home_bg.jpg")
            file.outputStream().use { out -> inputStream.copyTo(out) }
            inputStream.close()
            preferences.setCustomHomeImagePath(file.absolutePath)
            if (viewModel.uiState.value.settings.themeMode == BrowserThemeMode.CUSTOM_IMAGE) {
                applyCustomImageBackground()
            }
        } catch (_: Exception) {}
    }

    private fun applyCustomImageBackground() {
        val path = preferences.getCustomHomeImagePath()
        if (path != null) {
            val file = java.io.File(path)
            if (file.exists()) {
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                if (bitmap != null) {
                    binding.homeBackgroundImage.setImageBitmap(bitmap)
                    binding.homeBackgroundImage.isVisible = true
                    binding.homeBackgroundImage.scaleType = ImageView.ScaleType.CENTER_CROP
                    binding.homeBackgroundScrim.setBackgroundColor(currentThemePalette.homeOverlay)
                    binding.homeBackgroundScrim.isVisible = true
                    binding.homePanel.background = null
                    return
                }
            }
        }
        binding.homeBackgroundImage.isVisible = false
        binding.homeBackgroundScrim.isVisible = false
    }

    private fun styleDialog(dialog: BottomSheetDialog) {
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.setOnShowListener {
            applyBottomSheetDialogTheme(dialog)
        }
        dialog.behavior.apply {
            isDraggable = true
            state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
            isFitToContents = true
            skipCollapsed = true
        }
    }

    private fun applyBottomSheetDialogTheme(dialog: BottomSheetDialog) {
        val p = currentThemePalette
        val bottomSheet = dialog.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)
        bottomSheet?.background = GradientDrawable().apply {
            setColor(p.surface)
            cornerRadius = dp(20).toFloat()
            setStroke(dp(1), p.line)
        }
        bottomSheet?.let { sheet ->
            val parent = sheet.parent as? android.view.View
            parent?.setBackgroundColor(Color.TRANSPARENT)
            (parent?.parent as? android.view.View)?.setBackgroundColor(Color.TRANSPARENT)
        }
        val coordinator = dialog.findViewById<android.view.View>(com.google.android.material.R.id.coordinator)
        coordinator?.setBackgroundColor(Color.TRANSPARENT)
    }

    private fun styleAlertDialog(builder: MaterialAlertDialogBuilder): MaterialAlertDialogBuilder {
        val p = currentThemePalette
        return builder
            .setBackground(
                GradientDrawable().apply {
                    setColor(p.surface)
                    cornerRadius = dp(16).toFloat()
                    setStroke(dp(1), p.line)
                }
            )
    }

    private fun MaterialAlertDialogBuilder.showThemedAlert(): AlertDialog {
        val dialog = show()
        applyAlertDialogTheme(dialog)
        return dialog
    }

    private fun applyAlertDialogTheme(dialog: AlertDialog) {
        dialog.listView?.apply {
            setBackgroundColor(Color.TRANSPARENT)
            divider = ColorDrawable(Color.TRANSPARENT)
            selector = RippleDrawable(
                ColorStateList.valueOf(tintWithAlpha(currentThemePalette.accent, 36)),
                ColorDrawable(Color.TRANSPARENT),
                null,
            )
        }
        dialog.window?.decorView?.let(::applyAlertDialogViewPalette)
        listOf(
            DialogInterface.BUTTON_POSITIVE,
            DialogInterface.BUTTON_NEGATIVE,
            DialogInterface.BUTTON_NEUTRAL,
        ).forEach { buttonId ->
            dialog.getButton(buttonId)?.apply {
                setTextColor(currentThemePalette.accent)
                background = RippleDrawable(
                    ColorStateList.valueOf(tintWithAlpha(currentThemePalette.accent, 36)),
                    ColorDrawable(Color.TRANSPARENT),
                    null,
                )
            }
        }
    }

    private fun applyAlertDialogViewPalette(view: View) {
        when (view) {
            is CheckedTextView -> {
                view.setTextColor(currentThemePalette.text)
                view.checkMarkTintList = ColorStateList.valueOf(currentThemePalette.accent)
                view.minHeight = view.minHeight.coerceAtLeast(dp(48))
            }

            is CompoundButton -> {
                view.setTextColor(currentThemePalette.text)
                view.buttonTintList = ColorStateList.valueOf(currentThemePalette.accent)
                view.minHeight = view.minHeight.coerceAtLeast(dp(48))
            }

            is EditText -> {
                view.setTextColor(currentThemePalette.text)
                view.setHintTextColor(currentThemePalette.textMuted)
                view.backgroundTintList = ColorStateList.valueOf(currentThemePalette.accent)
                view.minHeight = view.minHeight.coerceAtLeast(dp(48))
            }

            is ImageView -> view.imageTintList = ColorStateList.valueOf(currentThemePalette.text)

            is TextView -> {
                val sp = textSizeSp(view)
                view.setTextColor(if (sp <= 14f) currentThemePalette.textMuted else currentThemePalette.text)
            }
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                applyAlertDialogViewPalette(view.getChildAt(index))
            }
        }
    }

    private fun tintToolbarButton(button: ImageButton, color: Int) {
        button.imageTintList = ColorStateList.valueOf(color)
    }

    private fun syncInjectedPageTheme(enabled: Boolean) {
        val css = if (enabled) {
            """
            html{color-scheme:dark !important;background:#0f1115 !important;}
            body{background:#0f1115 !important;color:#e5e7eb !important;}
            img,video,picture,canvas,svg{background-color:transparent !important;}
            """.trimIndent()
        } else {
            ""
        }
        val script = """
            (function() {
              var id = '__aurora_theme_style__';
              var existing = document.getElementById(id);
              var css = ${JSONObject.quote(css)};
              if (!css) {
                if (existing) existing.remove();
                return;
              }
              if (!existing) {
                existing = document.createElement('style');
                existing.id = id;
                (document.head || document.documentElement).appendChild(existing);
              }
              existing.textContent = css;
            })();
        """.trimIndent()
        currentWebView().evaluateJavascript(script, null)
    }

    private fun injectCredentialAssistant() {
        val script = """
            (function() {
              if (!window.$JS_BRIDGE_NAME) {
                return;
              }
              var __auroraBridgeToken = ${bridgeTokenScriptValue()};
              var callBridge = function(methodName) {
                try {
                  var bridge = window.$JS_BRIDGE_NAME;
                  if (!bridge || !bridge[methodName]) {
                    return null;
                  }
                  var args = Array.prototype.slice.call(arguments, 1);
                  args.unshift(__auroraBridgeToken);
                  return bridge[methodName].apply(bridge, args);
                } catch (e) {
                  return null;
                }
              };
              var visible = function(node) {
                if (!node || !node.getBoundingClientRect) {
                  return false;
                }
                var style = window.getComputedStyle(node);
                if (!style || style.display === 'none' || style.visibility === 'hidden') {
                  return false;
                }
                var rect = node.getBoundingClientRect();
                return rect.width > 0 && rect.height > 0;
              };
              var eventInput = function(node) {
                ['input', 'change'].forEach(function(name) {
                  node.dispatchEvent(new Event(name, { bubbles: true }));
                });
              };
              var usernameInputFor = function(passwordInput) {
                if (!passwordInput) {
                  return null;
                }
                var scope = passwordInput.form || passwordInput.closest('form, section, article, div') || document;
                var candidates = Array.from(scope.querySelectorAll('input'))
                  .filter(function(node) {
                    if (!node || node === passwordInput || !visible(node) || node.disabled || node.readOnly) {
                      return false;
                    }
                    var type = String(node.type || 'text').toLowerCase();
                    return type === 'text' || type === 'email' || type === 'tel' || type === 'url' || type === 'search' || type === 'number';
                  });
                return candidates[0] || null;
              };
              window.__auroraFillCredentials = function(username, password) {
                var passwordInputs = Array.from(document.querySelectorAll('input[type="password"]'))
                  .filter(function(node) { return visible(node) && !node.disabled && !node.readOnly; });
                if (!passwordInputs.length) {
                  return false;
                }
                var passwordInput = passwordInputs[0];
                var userInput = usernameInputFor(passwordInput);
                if (userInput && !String(userInput.value || '').trim()) {
                  userInput.focus();
                  userInput.value = String(username || '');
                  eventInput(userInput);
                }
                passwordInput.focus();
                passwordInput.value = String(password || '');
                eventInput(passwordInput);
                return true;
              };
              if (window.__auroraCredentialAssistInstalled) {
                return;
              }
              window.__auroraCredentialAssistInstalled = true;
              var maybeSave = function(root) {
                var scope = root && root.querySelectorAll ? root : document;
                Array.from(scope.querySelectorAll('input[type="password"]'))
                  .filter(function(node) { return visible(node) && !node.disabled && !node.readOnly; })
                  .forEach(function(passwordInput) {
                    var password = String(passwordInput.value || '').trim();
                    if (!password || password.length < 2) {
                      return;
                    }
                    var userInput = usernameInputFor(passwordInput);
                    var username = String(userInput && userInput.value || '').trim();
                    if (!username) {
                      return;
                    }
                    callBridge('saveCredential', window.location.href, username, password);
                  });
              };
              document.addEventListener('submit', function(event) {
                window.setTimeout(function() {
                  maybeSave(event.target);
                }, 180);
              }, true);
              document.addEventListener('click', function(event) {
                var target = event.target && event.target.closest
                  ? event.target.closest('button,input[type="submit"],input[type="button"],[role="button"]')
                  : null;
                if (!target) {
                  return;
                }
                var label = String(target.innerText || target.value || target.getAttribute('aria-label') || '').toLowerCase();
                if (!label) {
                  return;
                }
                if (!/(login|log in|sign in|signin|continue|submit|登录|注册|继续|确认)/.test(label)) {
                  return;
                }
                window.setTimeout(function() {
                  maybeSave(target.form || document);
                }, 320);
              }, true);
            })();
        """.trimIndent()
        currentWebView().evaluateJavascript(script, null)
    }

    private fun scheduleCredentialAutofill(pageUrl: String?) {
        val targetUrl = pageUrl?.takeIf { it.isNotBlank() && it != DEFAULT_HOME_URL } ?: return
        if (!effectiveAutofillEnabled(targetUrl)) {
            return
        }
        listOf(160L, 720L, 1500L).forEach { delayMs ->
            binding.root.postDelayed({
                autofillSavedCredential(targetUrl)
            }, delayMs)
        }
    }

    private fun autofillSavedCredential(pageUrl: String) {
        val currentUrl = viewModel.uiState.value.currentUrl
        if (currentUrl != pageUrl) {
            return
        }
        if (Uri.parse(pageUrl).scheme?.lowercase() != "https") {
            return
        }
        if (!effectiveAutofillEnabled(pageUrl)) {
            return
        }
        val host = normalizeCredentialHost(pageUrl) ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val credential = store.getCredentialsForHost(host).firstOrNull() ?: return@launch
            withContext(Dispatchers.Main) {
                if (pageUrl != viewModel.uiState.value.currentUrl) {
                    return@withContext
                }
                val script = """
                    (function() {
                      try {
                        return !!(window.__auroraFillCredentials && window.__auroraFillCredentials(
                          ${JSONObject.quote(credential.username)},
                          ${JSONObject.quote(credential.password)}
                        ));
                      } catch (e) {
                        return false;
                      }
                    })();
                """.trimIndent()
                currentWebView().evaluateJavascript(script, null)
            }
        }
    }

    private fun maybeSaveCredential(pageUrl: String?, username: String?, password: String?) {
        val currentPageUrl = viewModel.uiState.value.currentUrl
        if (pageUrl != currentPageUrl || Uri.parse(currentPageUrl).scheme?.lowercase() != "https") {
            return
        }
        if (!effectiveAutofillEnabled(currentPageUrl)) {
            return
        }
        val host = normalizeCredentialHost(pageUrl) ?: return
        val normalizedUsername = username?.trim().orEmpty()
        val normalizedPassword = password?.trim().orEmpty()
        if (normalizedUsername.isBlank() || normalizedPassword.isBlank()) {
            return
        }
        val signature = "$host|$normalizedUsername|$normalizedPassword"
        val now = SystemClock.elapsedRealtime()
        if (signature == lastCredentialSaveSignature && now - lastCredentialSaveAtMillis < 4000L) {
            return
        }
        lastCredentialSaveSignature = signature
        lastCredentialSaveAtMillis = now
        lifecycleScope.launch(Dispatchers.IO) {
            val saved = store.saveCredential(
                host = host,
                pageUrl = pageUrl,
                username = normalizedUsername,
                password = normalizedPassword,
            ) ?: return@launch
            withContext(Dispatchers.Main) {
                if (!isFinishing && !isDestroyed) {
                    Snackbar.make(binding.root, "${saved.host} 的账号密码已保存", Snackbar.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun normalizeCredentialHost(pageUrl: String?): String? {
        return pageUrl
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { Uri.parse(it) }.getOrNull() }
            ?.host
            ?.trim()
            ?.lowercase()
            ?.removePrefix("www.")
            ?.ifBlank { null }
    }

    private fun tintWithAlpha(color: Int, alpha: Int): Int {
        return Color.argb(
            alpha.coerceIn(0, 255),
            Color.red(color),
            Color.green(color),
            Color.blue(color),
        )
    }

    private fun applyIncognitoMode(enabled: Boolean) {
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(!enabled)
        cookieManager.setAcceptThirdPartyCookies(currentWebView(), !enabled)
        cookieManager.removeSessionCookies(null)
        cookieManager.flush()

        currentWebView().clearFormData()
        currentWebView().clearMatches()
        viewModel.clearFindResult()
        if (enabled) {
            currentWebView().clearHistory()
            currentWebView().clearCache(true)
            navigateTo(DEFAULT_HOME_URL)
            Snackbar.make(binding.root, "无痕模式已开启，不记录历史", Snackbar.LENGTH_SHORT).show()
        } else if (viewModel.uiState.value.currentUrl == DEFAULT_HOME_URL) {
            navigateTo(DEFAULT_HOME_URL)
        }
    }

    private fun requestIncognitoMode(enabled: Boolean) {
        if (enabled == viewModel.uiState.value.incognito) {
            return
        }
        val title = if (enabled) "开启无痕模式" else "退出无痕模式"
        val message = if (enabled) {
            "开启后会清理当前 WebView 历史、缓存、表单数据和会话 Cookie，并停止记录浏览历史。是否继续？"
        } else {
            "退出无痕会清理当前无痕会话的表单数据和会话 Cookie。是否继续？"
        }
        styleAlertDialog(MaterialAlertDialogBuilder(this))
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton("取消", null)
            .setPositiveButton(if (enabled) "开启" else "退出") { _, _ ->
                viewModel.setIncognito(enabled)
                applyIncognitoMode(enabled)
            }
            .showThemedAlert()
    }

    private fun hideFindBar() {
        if (!binding.findBar.isVisible) return
        val anim = animSlideOutTop.apply {
            setAnimationListener(object : Animation.AnimationListener {
                override fun onAnimationStart(a: Animation?) {}
                override fun onAnimationRepeat(a: Animation?) {}
                override fun onAnimationEnd(a: Animation?) {
                    binding.findBar.isVisible = false
                    binding.findInput.setText("")
                    currentWebView().clearMatches()
                    viewModel.clearFindResult()
                }
            })
        }
        binding.findBar.startAnimation(anim)
    }

    private fun toggleTranslation() {
        val url = currentWebView().url
        if (url.isNullOrBlank() || url == DEFAULT_HOME_URL) return
        if (translationEnabled && translatedPageUrl == url) {
            translationEnabled = false
            translatedPageUrl = null
            currentWebView().reload()
        } else {
            translationEnabled = true
            translateCurrentPage(force = true)
        }
    }

    private fun navigateTo(rawUrl: String, userInitiated: Boolean = false) {
        val target = rawUrl.trim()
        if (userInitiated) {
            noteNavigationIntent()
        }
        dismissRecentSearches()
        if (BrowserNavigationStateTools.shouldClearSearchInputsForUrl(target)) {
            clearBrowserSearchInputs()
            prepareActiveTabGlobals(DEFAULT_HOME_URL)
            viewModel.showTab(
                DEFAULT_HOME_URL,
                getString(R.string.browser_title),
                currentWebView().canGoBack(),
                currentWebView().canGoForward(),
            )
            tabs.firstOrNull { it.id == currentTabId }?.apply {
                title = getString(R.string.browser_title)
                url = DEFAULT_HOME_URL
            }
            showHomeSurface(refreshData = true)
            applyUserAgentForPage(currentWebView(), DEFAULT_HOME_URL)
            currentWebView().loadUrl(DEFAULT_HOME_URL)
            return
        }
        viewModel.setAddressBarText(target)
        prepareActiveTabGlobals(target)
        applyUserAgentForPage(currentWebView(), target)
        currentWebView().loadUrl(target)
    }

    private fun clearBrowserSearchInputs() {
        binding.homeSearchInput.setText("")
        binding.addressInput.setText("")
        binding.homeSearchInput.clearFocus()
        binding.addressInput.clearFocus()
        viewModel.setAddressBarText("")
        dismissRecentSearches()
    }

    private fun loadInlineHtml(displayUrl: String, html: String, userInitiated: Boolean = false) {
        val target = displayUrl.trim().ifBlank { "about:blank" }
        if (userInitiated) {
            noteNavigationIntent()
        }
        dismissRecentSearches()
        viewModel.setAddressBarText(target)
        applyUserAgentForPage(currentWebView(), target)
        currentWebView().loadDataWithBaseURL(
            "https://webtor.io/",
            html,
            "text/html",
            "UTF-8",
            target,
        )
    }

    private fun loadFromAddressBar() {
        submitInput(binding.addressInput.text?.toString().orEmpty())
    }

    private fun loadFromHomeSearch() {
        submitInput(binding.homeSearchInput.text?.toString().orEmpty())
    }

    private fun submitInput(rawInput: String) {
        val input = rawInput.trim()
        if (input.isBlank()) {
            return
        }
        val target = resolveInputToUrl(input)
        if (shouldSaveSearchHistory(input)) {
            preferences.saveRecentSearch(input)
        }
        binding.homeSearchInput.clearFocus()
        binding.addressInput.clearFocus()
        navigateTo(target, userInitiated = true)
    }

    private fun shouldSaveSearchHistory(input: String): Boolean {
        val normalized = input.trim()
        if (normalized.isBlank() || normalized == DEFAULT_HOME_URL) {
            return false
        }
        return !hasExplicitScheme(normalized) && !looksLikeDirectUrl(normalized)
    }

    private fun resolveInputToUrl(input: String): String {
        val normalized = input.trim()
        if (normalized == "about:blank") {
            return normalized
        }
        resolveHttpInputOrNull(normalized)?.let { return it }
        return preferences.getSearchEngine().buildSearchUrl(
            normalized
                .removePrefix("http://")
                .removePrefix("https://")
                .ifBlank { normalized },
        )
    }

    private fun hasExplicitScheme(input: String): Boolean {
        return SCHEME_REGEX.containsMatchIn(input)
    }

    private fun looksLikeDirectUrl(input: String): Boolean {
        if (input.isBlank() || input.contains(' ')) {
            return false
        }
        val hostPort = input.substringBefore('/').substringBefore('?').substringBefore('#')
        val host = hostPort.substringBefore(':').trim()
        if (host.isBlank()) {
            return false
        }
        if (host.equals("localhost", ignoreCase = true)) {
            return true
        }
        if (host.matches(IPV4_REGEX)) {
            return true
        }
        if (host.startsWith("[") && host.endsWith("]")) {
            return true
        }
        if (!host.contains('.')) {
            return false
        }
        val tld = host.substringAfterLast('.')
        return tld.length >= 2 && (tld.all(Char::isLetter) || tld.startsWith("xn--"))
    }

    private fun resolveHttpInputOrNull(input: String): String? {
        val normalized = input.trim()
        if (normalized.isBlank()) {
            return null
        }
        if (hasExplicitScheme(normalized)) {
            val uri = runCatching { Uri.parse(normalized) }.getOrNull() ?: return null
            return when (uri.scheme?.lowercase()) {
                "http", "https" -> normalized.takeIf { isValidNavigableWebUri(uri) }
                else -> normalized
            }
        }
        if (looksLikeDirectUrl(normalized)) {
            val candidate = "https://$normalized"
            val uri = runCatching { Uri.parse(candidate) }.getOrNull()
            if (uri != null && isValidNavigableWebUri(uri)) {
                return candidate
            }
        }
        return null
    }

    private fun isValidNavigableWebUri(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return false
        }
        val host = uri.host?.trim().orEmpty()
        return looksLikeDirectUrl(host)
    }

    private fun buildUserAgent(desktop: Boolean): String {
        return if (desktop) {
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"
        } else {
            mobileUserAgent
        }
    }

    private fun applyUserAgentForPage(
        webView: WebView,
        pageUrl: String?,
        globalDesktop: Boolean = viewModel.uiState.value.settings.desktopMode,
    ) {
        val desktop = effectiveDesktopMode(pageUrl, globalDesktop)
        webView.settings.userAgentString = buildUserAgent(desktop)
        webView.settings.useWideViewPort = desktop
        webView.settings.loadWithOverviewMode = desktop
    }

    private fun effectiveDesktopMode(
        pageUrl: String? = viewModel.uiState.value.currentUrl,
        globalDesktop: Boolean = viewModel.uiState.value.settings.desktopMode,
    ): Boolean {
        return siteSettingsForUrl(pageUrl)?.desktopMode ?: globalDesktop
    }

    private fun effectiveAdBlockEnabled(pageUrl: String? = currentPageUrlForGuards): Boolean {
        val global = viewModel.uiState.value.settings.adBlockEnabled
        return siteSettingsForUrl(pageUrl)?.adBlockEnabled ?: global
    }

    private fun effectiveAutofillEnabled(pageUrl: String? = viewModel.uiState.value.currentUrl): Boolean {
        return siteSettingsForUrl(pageUrl)?.autofillEnabled ?: true
    }

    private fun effectiveAutoTranslateEnabled(pageUrl: String? = viewModel.uiState.value.currentUrl): Boolean {
        return siteSettingsForUrl(pageUrl)?.autoTranslate == true
    }

    private fun siteSettingsForUrl(pageUrl: String?): SiteSettings? {
        return preferences.getSiteSettings(hostForUrl(pageUrl))
    }

    private fun hostForUrl(pageUrl: String?): String? {
        return pageUrl
            ?.takeIf { it.isNotBlank() && it != DEFAULT_HOME_URL }
            ?.let { runCatching { Uri.parse(it) }.getOrNull() }
            ?.host
            ?.trim()
            ?.lowercase()
            ?.removePrefix("www.")
            ?.ifBlank { null }
    }

    private fun originForUrl(pageUrl: String?): String? {
        val uri = pageUrl?.takeIf { it.isNotBlank() }?.let { runCatching { Uri.parse(it) }.getOrNull() }
            ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host ?: return null
        if (scheme != "http" && scheme != "https") {
            return null
        }
        val port = uri.port.takeIf { it > 0 }?.let { ":$it" }.orEmpty()
        return "$scheme://$host$port"
    }

    private fun createBridgeToken(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun bridgeTokenScriptValue(): String = JSONObject.quote(bridgeToken)

    private fun injectResourceSniffer() {
        val script = """
            (function() {
              if (!window.$JS_BRIDGE_NAME) {
                return;
              }
              var __auroraBridgeToken = ${bridgeTokenScriptValue()};
              var callBridge = function(methodName) {
                try {
                  var bridge = window.$JS_BRIDGE_NAME;
                  if (!bridge || !bridge[methodName]) {
                    return null;
                  }
                  var args = Array.prototype.slice.call(arguments, 1);
                  args.unshift(__auroraBridgeToken);
                  return bridge[methodName].apply(bridge, args);
                } catch (e) {
                  return null;
                }
              };
              var popupSelectors = [
                "[role='dialog']",
                "[aria-modal='true']",
                "[class*='popup']",
                "[class*='modal']",
                "[class*='dialog']",
                "[class*='overlay']",
                "[class*='interstitial']",
                "[id*='popup']",
                "[id*='modal']",
                "[id*='dialog']"
              ].join(',');
              var videoInteractionSelectors = [
                'video',
                '.dplayer',
                '.dplayer-video-wrap',
                '.dplayer-controller',
                '.dplayer-play-icon',
                '.artplayer',
                '.art-video',
                'video-js',
                '.jwplayer',
                '.xgplayer'
              ].join(',');
              var popupKeywords = [
                'ad', 'advert', 'promo', 'sponsor', 'banner', 'interstitial',
                'open app', 'openapp', 'download app', 'downloadapp',
                'app内打开', '立即打开', '立即下载', '继续访问', '跳转'
              ];
              var rememberGesture = function() {
                callBridge('notifyUserGesture');
              };
              var canLoadResource = function(url) {
                if (!url) {
                  return true;
                }
                var blocked = callBridge('shouldBlockResource', String(url || ''));
                return blocked !== true;
              };
              var canAutoNavigate = function(url) {
                return callBridge('shouldAllowNavigation', String(url || '')) === true;
              };
              var normalizeDiscoveredUrl = function(value) {
                if (!value) {
                  return '';
                }
                var normalized = String(value)
                  .trim()
                  .replace(/\\u002f/ig, '/')
                  .replace(/\\\//g, '/')
                  .replace(/&amp;/ig, '&')
                  .replace(/^['"]+|['"]+$/g, '');
                if (!normalized || /^javascript:/i.test(normalized) || /^blob:/i.test(normalized) || /^data:/i.test(normalized)) {
                  return '';
                }
                try {
                  return new URL(normalized, window.location.href).href;
                } catch (e) {
                  return normalized;
                }
              };
              var send = function(url, type) {
                var normalized = normalizeDiscoveredUrl(url);
                if (!normalized || !canLoadResource(normalized)) {
                  return;
                }
                callBridge('captureMedia', String(normalized), String(type || ''));
              };
              var notifyTorrentClipboard = function(text) {
                var value = String(text || '').trim();
                if (!value) return;
                if (/magnet:\?xt=urn:/i.test(value) || /\.torrent(?:[?#]|$)/i.test(value)) {
                  callBridge('onClipboardCandidate', value);
                }
              };
              document.addEventListener('copy', function(event) {
                try {
                  var copied = '';
                  if (event && event.clipboardData) {
                    copied = event.clipboardData.getData('text/plain') || event.clipboardData.getData('text') || '';
                  }
                  if (!copied && window.getSelection) {
                    copied = String(window.getSelection() || '');
                  }
                  notifyTorrentClipboard(copied);
                } catch (e) {}
              }, true);
              if (navigator.clipboard && navigator.clipboard.writeText && !navigator.clipboard.__auroraWrapped) {
                var originalWriteText = navigator.clipboard.writeText.bind(navigator.clipboard);
                navigator.clipboard.writeText = function(text) {
                  notifyTorrentClipboard(text);
                  return originalWriteText(text);
                };
                navigator.clipboard.__auroraWrapped = true;
              }
              var extractMediaFromText = function(text, type) {
                if (!text) {
                  return;
                }
                var source = String(text)
                  .replace(/\\u002f/ig, '/')
                  .replace(/\\\//g, '/')
                  .replace(/&amp;/ig, '&');
                var patterns = [
                  /((?:https?:)?\/\/[^"'<>\\\s]+?\.(?:m3u8|mpd|mp4|webm|mkv|mov|m4v)(?:\?[^"'<>\\\s]*)?)/ig,
                  /((?:\/|\.\.\/|\.\/)[^"'<>\\\s]+?\.(?:m3u8|mpd|mp4|webm|mkv|mov|m4v)(?:\?[^"'<>\\\s]*)?)/ig
                ];
                patterns.forEach(function(pattern) {
                  var match;
                  while ((match = pattern.exec(source)) !== null) {
                    send(match[1], type || 'inline-media');
                  }
                });
              };
              var isVideoInteractionTarget = function(target) {
                if (!target || !target.closest) {
                  return false;
                }
                try {
                  return !!target.closest(videoInteractionSelectors);
                } catch (e) {
                  return false;
                }
              };
              var compactText = function(value) {
                return String(value || '').replace(/\s+/g, ' ').trim();
              };
              var resolveVideoTitle = function(target) {
                var roots = [];
                try {
                  var scoped = target && target.closest
                    ? target.closest('article, section, .player, .video, .play, .module, .box, .detail, .content, .vod-detail, .module-info, .stui-content__detail')
                    : null;
                  if (scoped) {
                    roots.push(scoped);
                  }
                } catch (e) {}
                roots.push(document);
                var selectors = [
                  '[data-title]',
                  '.video-title',
                  '.player-title',
                  '.module-info-heading',
                  '.detail-title',
                  '.vod-title',
                  '.title',
                  '.name',
                  'h1',
                  'h2',
                  'h3'
                ];
                for (var rootIndex = 0; rootIndex < roots.length; rootIndex += 1) {
                  var root = roots[rootIndex];
                  if (!root || !root.querySelector) {
                    continue;
                  }
                  for (var index = 0; index < selectors.length; index += 1) {
                    var node = root.querySelector(selectors[index]);
                    var text = compactText(node && (node.getAttribute && node.getAttribute('data-title') || node.textContent || node.getAttribute && node.getAttribute('title')));
                    if (text && text.length > 1) {
                      return text;
                    }
                  }
                }
                var meta = document.querySelector('meta[property="og:title"], meta[name="title"]');
                var metaTitle = compactText(meta && meta.getAttribute && meta.getAttribute('content'));
                return metaTitle || compactText(document.title || '');
              };
              var readNavigationTarget = function(target) {
                if (!target || !target.closest) {
                  return '';
                }
                try {
                  var node = target.closest('a[href], area[href], [data-url], [data-href]');
                  if (!node) {
                    return '';
                  }
                  return node.href || node.getAttribute('data-url') || node.getAttribute('data-href') || '';
                } catch (e) {
                  return '';
                }
              };
              var notifyExplicitNavigation = function(url) {
                var normalized = normalizeDiscoveredUrl(url);
                if (!normalized) {
                  return;
                }
                callBridge('noteExplicitNavigation', String(normalized));
              };
              var notifyVideoInteractionDetails = function(target) {
                var video = null;
                try {
                  video = (target && target.closest && target.closest('video')) || activeVideo();
                } catch (e) {}
                var url = '';
                var width = 0;
                var height = 0;
                if (video) {
                  url = video.currentSrc || video.src || '';
                  width = Number(video.videoWidth || video.clientWidth || 0);
                  height = Number(video.videoHeight || video.clientHeight || 0);
                }
                callBridge(
                  'onVideoInteraction',
                  String(url || ''),
                  String(resolveVideoTitle(target) || ''),
                  Number(width || 0),
                  Number(height || 0)
                );
              };
              var walkRoots = function(startRoot, visitor) {
                var queue = [startRoot];
                var seen = [];
                while (queue.length) {
                  var root = queue.shift();
                  if (!root || seen.indexOf(root) >= 0) {
                    continue;
                  }
                  seen.push(root);
                  visitor(root);
                  var elements = [];
                  try {
                    elements = Array.from(root.querySelectorAll('*'));
                  } catch (e) {}
                  elements.forEach(function(element) {
                    if (element && element.shadowRoot) {
                      queue.push(element.shadowRoot);
                    }
                    if (element && element.tagName && String(element.tagName).toLowerCase() === 'iframe') {
                      try {
                        if (element.contentDocument) {
                          queue.push(element.contentDocument);
                        }
                      } catch (e) {}
                    }
                  });
                }
              };
              var queryAllAcrossRoots = function(selector) {
                var matches = [];
                walkRoots(document, function(root) {
                  try {
                    matches = matches.concat(Array.from(root.querySelectorAll(selector)));
                  } catch (e) {}
                });
                return matches;
              };
              var mediaUrlFor = function(node) {
                if (!node) {
                  return '';
                }
                return node.currentSrc || node.src || (node.querySelector && node.querySelector('source[src]') && node.querySelector('source[src]').src) || '';
              };
              var reportMediaPlayback = function(node) {
                var playing = !!(node && !node.paused && !node.ended && node.readyState > 1);
                callBridge('onVideoPlaybackState', String(mediaUrlFor(node) || ''), playing);
              };
              var optimizeMediaElement = function(node) {
                if (!node || !node.tagName) {
                  return;
                }
                var tagName = String(node.tagName).toLowerCase();
                if (tagName !== 'video' && tagName !== 'audio') {
                  return;
                }
                if (tagName === 'video') {
                  try {
                    node.setAttribute('playsinline', '');
                    node.setAttribute('webkit-playsinline', '');
                    node.setAttribute('x5-playsinline', '');
                    node.setAttribute('x5-video-player-type', 'h5-page');
                    node.style.maxWidth = node.style.maxWidth || '100%';
                    node.style.backgroundColor = node.style.backgroundColor || '#000';
                  } catch (e) {}
                }
                try {
                  if (!node.getAttribute('preload') || node.getAttribute('preload') === 'none') {
                    node.setAttribute('preload', 'metadata');
                  }
                } catch (e) {}
                if (node.__auroraMediaOptimized) {
                  return;
                }
                node.__auroraMediaOptimized = true;
                ['play', 'playing', 'pause', 'ended', 'loadedmetadata', 'canplay', 'waiting', 'stalled', 'seeked'].forEach(function(name) {
                  node.addEventListener(name, function() {
                    send(mediaUrlFor(node), 'media-' + name);
                    reportMediaPlayback(node);
                    reportActiveVideo();
                  }, true);
                });
              };
              var scanNode = function(node) {
                if (!node || !node.tagName) return;
                var tagName = String(node.tagName).toLowerCase();
                optimizeMediaElement(node);
                var candidates = [
                  node.currentSrc,
                  node.src,
                  node.href,
                  node.data,
                  node.getAttribute && node.getAttribute('src'),
                  node.getAttribute && node.getAttribute('href'),
                  node.getAttribute && node.getAttribute('data-src'),
                  node.getAttribute && node.getAttribute('data-url'),
                  node.getAttribute && node.getAttribute('content'),
                  node.getAttribute && node.getAttribute('data-config'),
                  node.getAttribute && node.getAttribute('data-play'),
                  node.getAttribute && node.getAttribute('data-video'),
                  node.getAttribute && node.getAttribute('data-player'),
                  node.getAttribute && node.getAttribute('data-file'),
                  node.getAttribute && node.getAttribute('value')
                ];
                candidates.forEach(function(value) {
                  if (value) send(value, node.type || node.tagName.toLowerCase());
                });
                if (tagName === 'script') {
                  extractMediaFromText(node.textContent || node.innerHTML || '', node.src ? 'script' : 'inline-script');
                }
              };
              var scan = function() {
                queryAllAcrossRoots('video, audio, source, iframe[src], embed[src], object[data], a[href], link[href], script[src], img[src], meta[content]').forEach(scanNode);
                queryAllAcrossRoots('script:not([src]), [data-config], [data-play], [data-video], [data-player], [data-file], [data-url], [data-src]').forEach(scanNode);
                walkRoots(document, function(root) {
                  try {
                    if (root.__auroraInlineMediaScanned) {
                      return;
                    }
                    root.__auroraInlineMediaScanned = true;
                    var source = '';
                    if (root.documentElement && root.documentElement.innerHTML) {
                      source = root.documentElement.innerHTML;
                    } else if (root.body && root.body.innerHTML) {
                      source = root.body.innerHTML;
                    } else if (root.innerHTML) {
                      source = root.innerHTML;
                    }
                    if (source) {
                      extractMediaFromText(source.slice(0, 350000), 'html-source');
                    }
                  } catch (e) {}
                });
                if (window.performance && window.performance.getEntriesByType) {
                  window.performance.getEntriesByType('resource').forEach(function(entry) {
                    send(entry.name, 'resource');
                  });
                }
              };
              var playerVideoCandidates = function() {
                return queryAllAcrossRoots('video, .dplayer-video-wrap video, .dplayer video, .art-video video, video-js video, .jwplayer video, .xgplayer video')
                  .filter(function(video) {
                    return !!video && video.tagName && String(video.tagName).toLowerCase() === 'video';
                  });
              };
              var videoScore = function(video) {
                if (!video) {
                  return -1;
                }
                var rect = { width: 0, height: 0, top: 0, left: 0, right: 0, bottom: 0 };
                try {
                  rect = video.getBoundingClientRect();
                } catch (e) {}
                var viewport = (video.ownerDocument && video.ownerDocument.defaultView) || window;
                var width = rect.width || video.clientWidth || video.videoWidth || 0;
                var height = rect.height || video.clientHeight || video.videoHeight || 0;
                var hasSource = !!(video.currentSrc || video.src || video.srcObject || (video.querySelector && video.querySelector('source[src]')));
                if (!hasSource) {
                  return -1;
                }
                var visible = width > 40 && height > 40 && rect.bottom > 0 && rect.right > 0 && rect.top < viewport.innerHeight && rect.left < viewport.innerWidth;
                var area = width * height;
                return area +
                  (visible ? 2000000 : 0) +
                  (!video.paused && !video.ended ? 4000000 : 0) +
                  ((video.readyState || 0) >= 2 ? 1000000 : 0) +
                  ((video.currentTime || 0) > 0 ? 300000 : 0) +
                  (video.autoplay ? 100000 : 0) +
                  (video.controls ? 50000 : 0);
              };
              var activeVideo = function() {
                return playerVideoCandidates()
                  .sort(function(left, right) {
                    return videoScore(right) - videoScore(left);
                  })[0] || null;
              };
              var reportActiveVideo = function() {
                var video = activeVideo();
                if (video) {
                  send(video.currentSrc || video.src, 'video');
                  callBridge(
                    'reportActiveVideoState',
                    video.currentSrc || video.src || '',
                    Number(video.videoWidth || video.clientWidth || 0),
                    Number(video.videoHeight || video.clientHeight || 0)
                  );
                  reportMediaPlayback(video);
                } else {
                  callBridge('reportActiveVideoState', '', 0, 0);
                  callBridge('onVideoPlaybackState', '', false);
                }
              };
              var looksLikeBlockingPopup = function(node) {
                if (!node || node.nodeType !== 1 || node.getAttribute('data-aurora-hidden') === '1') {
                  return false;
                }
                var style;
                var rect;
                try {
                  style = window.getComputedStyle(node);
                  rect = node.getBoundingClientRect();
                } catch (e) {
                  return false;
                }
                if (!style || style.display === 'none' || style.visibility === 'hidden') {
                  return false;
                }
                var areaRatio = (rect.width * rect.height) / Math.max(1, window.innerWidth * window.innerHeight);
                var layerScore = ((parseInt(style.zIndex || '0', 10) || 0) >= 100) || style.position === 'fixed' || style.position === 'sticky';
                if (!layerScore || areaRatio < 0.18) {
                  return false;
                }
                var marker = ((node.id || '') + ' ' + (node.className || '') + ' ' + (node.getAttribute('role') || '')).toLowerCase();
                var text = String(node.innerText || '').trim().slice(0, 160).toLowerCase();
                return marker.indexOf('popup') >= 0 ||
                  marker.indexOf('modal') >= 0 ||
                  marker.indexOf('dialog') >= 0 ||
                  marker.indexOf('overlay') >= 0 ||
                  popupKeywords.some(function(keyword) { return marker.indexOf(keyword) >= 0 || text.indexOf(keyword) >= 0; });
              };
              var hidePopup = function(node) {
                if (!looksLikeBlockingPopup(node)) {
                  return;
                }
                node.setAttribute('data-aurora-hidden', '1');
                node.style.setProperty('display', 'none', 'important');
                node.style.setProperty('visibility', 'hidden', 'important');
                node.style.setProperty('pointer-events', 'none', 'important');
                if (document.documentElement) {
                  document.documentElement.style.overflow = 'auto';
                }
                if (document.body) {
                  document.body.style.overflow = 'auto';
                  document.body.style.position = 'static';
                }
              };
              var scrubPopups = function() {
                queryAllAcrossRoots(popupSelectors).forEach(hidePopup);
                queryAllAcrossRoots('iframe[src]').forEach(function(frame) {
                  var src = String(frame.src || '').toLowerCase();
                  if (src.indexOf('doubleclick') >= 0 || src.indexOf('taboola') >= 0 || src.indexOf('outbrain') >= 0 || src.indexOf('ad') >= 0) {
                    hidePopup(frame);
                  }
                });
              };
              if (!window.__auroraSnifferInstalled) {
                window.__auroraSnifferInstalled = true;
                ['click', 'touchstart', 'pointerdown', 'keydown', 'submit'].forEach(function(eventName) {
                  document.addEventListener(eventName, rememberGesture, true);
                });
                ['click', 'touchstart', 'pointerdown'].forEach(function(eventName) {
                  document.addEventListener(eventName, function(event) {
                    if (!event.isTrusted || !isVideoInteractionTarget(event.target)) {
                      return;
                    }
                    notifyVideoInteractionDetails(event.target);
                  }, true);
                });
                document.addEventListener('click', function(event) {
                  if (event.isTrusted) {
                    notifyExplicitNavigation(readNavigationTarget(event.target));
                  }
                  var target = event.target && event.target.closest ? event.target.closest('a[href], area[href]') : null;
                  if (!target) {
                    return;
                  }
                  if (!event.isTrusted && !canAutoNavigate(target.href)) {
                    event.preventDefault();
                    event.stopImmediatePropagation();
                  }
                }, true);
                document.addEventListener('submit', function(event) {
                  if (event.isTrusted) {
                    var trustedForm = event.target;
                    notifyExplicitNavigation(trustedForm && (trustedForm.action || window.location.href));
                    return;
                  }
                  var form = event.target;
                  if (form && !canAutoNavigate(form.action || window.location.href)) {
                    event.preventDefault();
                    event.stopImmediatePropagation();
                  }
                }, true);
                window.__auroraCollectCurrentVideoCandidates = function() {
                  var urls = [];
                  var appendUrl = function(value) {
                    var normalized = normalizeDiscoveredUrl(value);
                    if (!normalized || !canLoadResource(normalized) || urls.indexOf(normalized) >= 0) {
                      return;
                    }
                    urls.push(normalized);
                  };
                  var appendNodeUrls = function(node) {
                    if (!node) {
                      return;
                    }
                    [
                      node.currentSrc,
                      node.src,
                      node.href,
                      node.getAttribute && node.getAttribute('src'),
                      node.getAttribute && node.getAttribute('href'),
                      node.getAttribute && node.getAttribute('data-src'),
                      node.getAttribute && node.getAttribute('data-url'),
                      node.getAttribute && node.getAttribute('data-play'),
                      node.getAttribute && node.getAttribute('data-video'),
                      node.getAttribute && node.getAttribute('data-file')
                    ].forEach(appendUrl);
                  };
                  var video = activeVideo();
                  appendNodeUrls(video);
                  if (video && video.querySelectorAll) {
                    Array.from(video.querySelectorAll('source[src]')).forEach(appendNodeUrls);
                  }
                  var scope = null;
                  try {
                    scope = video && video.closest
                      ? video.closest('article, section, .player, .video, .play, .module, .box, .detail, .content, .vod-detail, .module-info, .stui-player')
                      : null;
                  } catch (e) {}
                  if (scope && scope.querySelectorAll) {
                    Array.from(scope.querySelectorAll('[src], [href], [data-src], [data-url], [data-play], [data-video], [data-file]')).forEach(appendNodeUrls);
                    extractMediaFromText((scope.innerHTML || '').slice(0, 120000), 'current-video');
                  }
                  if (window.performance && window.performance.getEntriesByType) {
                    window.performance.getEntriesByType('resource')
                      .slice(-80)
                      .reverse()
                      .forEach(function(entry) {
                        appendUrl(entry && entry.name);
                      });
                  }
                  return JSON.stringify({
                    title: resolveVideoTitle(video || document.body),
                    urls: urls.slice(0, 12)
                  });
                };
                var rawWindowOpen = window.open;
                if (rawWindowOpen) {
                  window.open = function(url) {
                    if (!canAutoNavigate(url)) {
                      return null;
                    }
                    return rawWindowOpen.apply(this, arguments);
                  };
                }
                var rawElementClick = window.HTMLElement && HTMLElement.prototype && HTMLElement.prototype.click;
                if (rawElementClick) {
                  HTMLElement.prototype.click = function() {
                    if (this && this.matches && this.matches('a[href], area[href]') && !canAutoNavigate(this.href)) {
                      return;
                    }
                    return rawElementClick.apply(this, arguments);
                  };
                }
                var wrapLocationMethod = function(methodName) {
                  try {
                    var locationTarget = window.Location && window.Location.prototype;
                    var original = locationTarget && locationTarget[methodName];
                    if (typeof original !== 'function') {
                      original = window.location && window.location[methodName];
                      locationTarget = window.location;
                    }
                    if (typeof original !== 'function' || !locationTarget) {
                      return;
                    }
                    locationTarget[methodName] = function(url) {
                      if (!canAutoNavigate(url)) {
                        return;
                      }
                      return original.apply(this, arguments);
                    };
                  } catch (e) {}
                };
                wrapLocationMethod('assign');
                wrapLocationMethod('replace');
                var rawFormSubmit = window.HTMLFormElement && HTMLFormElement.prototype && HTMLFormElement.prototype.submit;
                if (rawFormSubmit) {
                  HTMLFormElement.prototype.submit = function() {
                    if (!canAutoNavigate(this.action || window.location.href)) {
                      return;
                    }
                    return rawFormSubmit.apply(this, arguments);
                  };
                }
                var stripMetaRefresh = function(root) {
                  var scope = root && root.querySelectorAll ? root : document;
                  Array.from(scope.querySelectorAll ? scope.querySelectorAll('meta[http-equiv]') : []).forEach(function(meta) {
                    var equiv = String(meta.getAttribute('http-equiv') || '').toLowerCase();
                    if (equiv === 'refresh' && !canAutoNavigate(meta.getAttribute('content') || '')) {
                      meta.remove();
                    }
                  });
                };
                stripMetaRefresh(document);
                var rawFetch = window.fetch;
                if (rawFetch) {
                  window.fetch = function() {
                    var request = arguments[0];
                    var url = typeof request === 'string' ? request : (request && request.url);
                    if (url && !canLoadResource(url)) {
                      return Promise.resolve(new Response('', {
                        status: 204,
                        headers: { 'Content-Type': 'text/plain' }
                      }));
                    }
                    send(url, 'fetch');
                    return rawFetch.apply(this, arguments).then(function(response) {
                      send((response && response.url) || url, (response && response.headers && response.headers.get && response.headers.get('content-type')) || 'fetch');
                      return response;
                    });
                  };
                }
                var rawOpen = XMLHttpRequest.prototype.open;
                XMLHttpRequest.prototype.open = function(method, url) {
                  this.__auroraUrl = url;
                  this.__auroraBlocked = !!url && !canLoadResource(url);
                  if (!this.__auroraBlocked) {
                    send(url, 'xhr');
                  }
                  return rawOpen.apply(this, arguments);
                };
                var rawSend = XMLHttpRequest.prototype.send;
                XMLHttpRequest.prototype.send = function() {
                  if (this.__auroraBlocked) {
                    try {
                      this.abort();
                    } catch (e) {}
                    return;
                  }
                  this.addEventListener('readystatechange', function() {
                    if (this.readyState === 4) {
                      send(this.responseURL || this.__auroraUrl, this.getResponseHeader('Content-Type') || 'xhr');
                    }
                  });
                  return rawSend.apply(this, arguments);
                };
                var rawSetAttribute = Element.prototype.setAttribute;
                Element.prototype.setAttribute = function(name, value) {
                  var normalizedName = String(name || '').toLowerCase();
                  if (normalizedName === 'src' || normalizedName === 'href' || normalizedName === 'data-src' || normalizedName === 'data-url') {
                    send(value, normalizedName);
                  }
                  return rawSetAttribute.apply(this, arguments);
                };
                var rawMediaPlay = window.HTMLMediaElement && HTMLMediaElement.prototype && HTMLMediaElement.prototype.play;
                if (rawMediaPlay) {
                  HTMLMediaElement.prototype.play = function() {
                    var playbackUrl = this.currentSrc || this.src;
                    send(playbackUrl, 'media-play');
                    reportActiveVideo();
                    callBridge('onPlaybackStarted', String(playbackUrl || ''));
                    return rawMediaPlay.apply(this, arguments);
                  };
                }
                var rawMediaLoad = window.HTMLMediaElement && HTMLMediaElement.prototype && HTMLMediaElement.prototype.load;
                if (rawMediaLoad) {
                  HTMLMediaElement.prototype.load = function() {
                    send(this.currentSrc || this.src, 'media-load');
                    return rawMediaLoad.apply(this, arguments);
                  };
                }
                if (window.__auroraObserver) { window.__auroraObserver.disconnect(); }
                if (window.__auroraTick) { clearInterval(window.__auroraTick); }
                window.__auroraObserver = new MutationObserver(function(mutations) {
                  mutations.forEach(function(mutation) {
                    Array.from(mutation.addedNodes || []).forEach(function(node) {
                      scanNode(node);
                      if (node.tagName && String(node.tagName).toLowerCase() === 'meta') {
                        stripMetaRefresh(node.parentNode || document);
                      }
                      if (node.querySelectorAll) {
                        stripMetaRefresh(node);
                        node.querySelectorAll('video, audio, source, iframe[src], embed[src], object[data], a[href], link[href], script[src], img[src]').forEach(scanNode);
                      }
                      hidePopup(node);
                    });
                  });
                });
                window.__auroraObserver.observe(document.documentElement || document.body, { childList: true, subtree: true });
                document.addEventListener('play', reportActiveVideo, true);
                document.addEventListener('pause', reportActiveVideo, true);
                document.addEventListener('loadedmetadata', reportActiveVideo, true);
                window.__auroraTick = setInterval(function() {
                  reportActiveVideo();
                  scrubPopups();
                }, 1500);
              }
              scan();
              scrubPopups();
              reportActiveVideo();
            })();
        """.trimIndent()
        currentWebView().evaluateJavascript(script, null)
    }

    private inner class PageBridge {
        @JavascriptInterface
        fun captureMedia(token: String?, url: String?, type: String?) {
            if (!isValidBridgeToken(token)) return
            val value = url?.takeIf { it.isNotBlank() } ?: return
            val state = viewModel.uiState.value
            captureResourceCandidate(
                url = value,
                title = lastPreferredVideoTitle ?: state.pageTitle,
                pageUrl = state.currentUrl,
                mimeType = type,
                referer = state.currentUrl,
                userAgent = buildUserAgent(effectiveDesktopMode(state.currentUrl)),
                prioritizeForCurrentVideo = hasRecentVideoInteraction(),
            )
        }

        @JavascriptInterface
        fun reportActiveVideo(token: String?, url: String?) {
            if (!isValidBridgeToken(token)) return
            reportActiveVideoState(token, url, 0.0, 0.0)
        }

        @JavascriptInterface
        fun reportActiveVideoState(token: String?, url: String?, width: Double, height: Double) {
            if (!isValidBridgeToken(token)) return
            val value = url?.takeIf { it.isNotBlank() }
            lastActiveVideoUrl = value
            if (value == null) {
                lastActiveVideoWidth = 0.0
                lastActiveVideoHeight = 0.0
            } else {
                lastActiveVideoWidth = width.takeIf { it > 0.0 } ?: 0.0
                lastActiveVideoHeight = height.takeIf { it > 0.0 } ?: 0.0
            }
            if (value != null) {
                val state = viewModel.uiState.value
                captureResourceCandidate(
                    url = value,
                    title = lastPreferredVideoTitle ?: state.pageTitle,
                    pageUrl = state.currentUrl,
                    mimeType = "video/*",
                    referer = state.currentUrl,
                    userAgent = buildUserAgent(effectiveDesktopMode(state.currentUrl)),
                    prioritizeForCurrentVideo = true,
                )
            }
        }

        @JavascriptInterface
        fun onVideoPlaybackState(token: String?, url: String?, playing: Boolean) {
            if (!isValidBridgeToken(token)) return
            updateWebVideoPlaybackState(playing)
            if (playing) {
                noteVideoInteraction(videoUrl = url)
            }
        }

        @JavascriptInterface
        fun shouldAllowNavigation(token: String?, url: String?): Boolean {
            if (!isValidBridgeToken(token)) return true
            if (!effectiveAdBlockEnabled(viewModel.uiState.value.currentUrl)) return true
            return shouldAllowInjectedNavigation(url)
        }

        @JavascriptInterface
        fun shouldBlockResource(token: String?, url: String?): Boolean {
            if (!isValidBridgeToken(token)) return false
            if (!effectiveAdBlockEnabled(viewModel.uiState.value.currentUrl)) return false
            return shouldBlockResourceRequest(url)
        }

        @JavascriptInterface
        fun notifyUserGesture(token: String?) {
            if (!isValidBridgeToken(token)) return
            // Used as a lightweight bridge hook so page scripts can signal interaction timing if needed later.
        }

        @JavascriptInterface
        fun onClipboardCandidate(token: String?, text: String?) {
            if (!isValidBridgeToken(token)) return
            val value = text?.takeIf { it.isNotBlank() } ?: return
            runOnUiThread {
                promptTorrentDownload(value, title = null, copied = true)
            }
        }

        @JavascriptInterface
        fun notifyVideoInteraction(token: String?) {
            if (!isValidBridgeToken(token)) return
            noteVideoInteraction()
        }

        @JavascriptInterface
        fun onVideoInteraction(token: String?, url: String?, title: String?, width: Double, height: Double) {
            if (!isValidBridgeToken(token)) return
            noteVideoInteraction(
                videoUrl = url,
                title = title,
                width = width,
                height = height,
            )
        }

        @JavascriptInterface
        fun noteExplicitNavigation(token: String?, url: String?) {
            if (!isValidBridgeToken(token)) return
            noteExplicitUserNavigation(url)
        }

        @JavascriptInterface
        fun saveCredential(token: String?, pageUrl: String?, username: String?, password: String?) {
            if (!isValidBridgeToken(token)) return
            maybeSaveCredential(pageUrl, username, password)
        }

        @JavascriptInterface
        fun onPlaybackStarted(token: String?, url: String?) {
            if (!isValidBridgeToken(token)) return
            val playbackUrl = url?.takeIf { it.isNotBlank() }
            if (playbackUrl != null) {
                reportActiveVideoState(token, playbackUrl, 0.0, 0.0)
                lastPreferredVideoResourceUrl = playbackUrl
                updateWebVideoPlaybackState(true)
                noteVideoInteraction(videoUrl = playbackUrl)
            }
        }

        private fun isValidBridgeToken(token: String?): Boolean = token == bridgeToken
    }

    private fun showLinkContextMenu(webView: WebView) {
        val hitTest = webView.hitTestResult
        val linkUrl = when (hitTest.type) {
            android.webkit.WebView.HitTestResult.SRC_ANCHOR_TYPE,
            android.webkit.WebView.HitTestResult.ANCHOR_TYPE,
            android.webkit.WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> hitTest.extra
            else -> null
        }
        val imageUrl = when (hitTest.type) {
            android.webkit.WebView.HitTestResult.IMAGE_TYPE,
            android.webkit.WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> hitTest.extra
            else -> null
        }
        if (linkUrl.isNullOrBlank() && imageUrl.isNullOrBlank()) return

        val dialog = BottomSheetDialog(this)
        styleDialog(dialog)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(currentThemePalette.surface)
            setPadding(dp(20), dp(24), dp(20), dp(32))
        }

        val titleText = TextView(this).apply {
            text = if (imageUrl != null) "图片操作" else "链接操作"
            setTextColor(currentThemePalette.text)
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(16))
        }
        container.addView(titleText)

        val urlPreview = TextView(this).apply {
            text = (linkUrl ?: imageUrl).orEmpty().take(80)
            setTextColor(currentThemePalette.textMuted)
            textSize = 12f
            maxLines = 2
            setPadding(0, 0, 0, dp(12))
        }
        container.addView(urlPreview)

        if (!linkUrl.isNullOrBlank()) {
            if (TorrentTools.extractValidMagnet(linkUrl) != null || TorrentTools.isTorrentUrl(linkUrl)) {
                container.addView(createContextMenuItem("下载磁力/种子", R.drawable.ic_download) {
                    dialog.dismiss()
                    promptTorrentDownload(linkUrl, title = null, copied = false)
                })
            }
            container.addView(createContextMenuItem("在新标签页打开", R.drawable.ic_tabs) {
                dialog.dismiss()
                createNewTabWithUrl(linkUrl)
            })
            container.addView(createContextMenuItem("复制链接", R.drawable.ic_open) {
                dialog.dismiss()
                val clipboard = getSystemService(ClipboardManager::class.java)
                clipboard.setPrimaryClip(ClipData.newPlainText("URL", linkUrl))
                Snackbar.make(binding.root, "链接已复制", Snackbar.LENGTH_SHORT).show()
                promptTorrentDownload(linkUrl, title = null, copied = true)
            })
            container.addView(createContextMenuItem("分享链接", R.drawable.ic_menu) {
                dialog.dismiss()
                startActivity(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, linkUrl)
                })
            })
        }

        if (!imageUrl.isNullOrBlank()) {
            if (linkUrl != null) {
                container.addView(dividerView())
            }
            container.addView(createContextMenuItem("查看图片", R.drawable.ic_gallery) {
                dialog.dismiss()
                navigateTo(imageUrl, userInitiated = true)
            })
            container.addView(createContextMenuItem("复制图片链接", R.drawable.ic_open) {
                dialog.dismiss()
                val clipboard = getSystemService(ClipboardManager::class.java)
                clipboard.setPrimaryClip(ClipData.newPlainText("URL", imageUrl))
                Snackbar.make(binding.root, "图片链接已复制", Snackbar.LENGTH_SHORT).show()
            })
            container.addView(createContextMenuItem("分享图片链接", R.drawable.ic_menu) {
                dialog.dismiss()
                startActivity(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, imageUrl)
                })
            })
        }

        container.addView(dividerView().apply { setPadding(0, dp(8), 0, 0) })
        container.addView(createContextMenuItem("取消", null) { dialog.dismiss() })

        dialog.setContentView(container)
        dialog.show()
    }

    private fun createContextMenuItem(label: String, iconRes: Int?, onClick: () -> Unit): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(14), dp(8), dp(14))
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(Color.TRANSPARENT)
            }
            isClickable = true
            isFocusable = true
            if (iconRes != null) {
                addView(ImageView(context).apply {
                    setImageResource(iconRes)
                    imageTintList = ColorStateList.valueOf(currentThemePalette.text)
                    layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(14) }
                })
            }
            addView(TextView(context).apply {
                text = label
                setTextColor(currentThemePalette.text)
                textSize = 15f
            })
            setOnClickListener { onClick() }
        }
    }

    private fun dividerView(): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1),
            )
            setBackgroundColor(currentThemePalette.line)
        }
    }

    private fun createNewTabWithUrl(url: String) {
        if (url.isBlank()) return
        updateCurrentTab(viewModel.uiState.value)
        snapshotCurrentTab()
        currentWebView().onPause()
        val webView = createTabWebView()
        val newTab = BrowserTab(
            id = nextTabId++, title = url, url = url, webView = webView,
        )
        tabs += newTab
        setupWebView(webView)
        currentTabId = newTab.id
        prepareActiveTabGlobals(url)
        webView.loadUrl(url)
        viewModel.showTab(url, newTab.title, webView.canGoBack(), webView.canGoForward())
        webView.onResume()
        updateWebViewVisibility(showHome = false)
        updateTabsCountBadge()
        Snackbar.make(binding.root, "已在新标签页打开", Snackbar.LENGTH_SHORT).show()
    }

    private fun openLibrary(mode: LibraryMode) {
        val pageUrl = when (mode) {
            LibraryMode.RESOURCES -> {
                viewModel.uiState.value.currentUrl.takeIf { it.isNotBlank() && it != DEFAULT_HOME_URL }
            }

            else -> null
        }
        if (mode == LibraryMode.RESOURCES && pageUrl == null) {
            Toast.makeText(this, "当前页面没有可查看的嗅探资源", Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(LibraryActivity.createIntent(this, mode, pageUrl))
    }

    private fun isHomeScreen(state: BrowserUiState): Boolean {
        return state.currentUrl == DEFAULT_HOME_URL
    }

    private fun isOnCustomStartPage(state: BrowserUiState): Boolean {
        val startUrl = preferences.getStartPageUrl()
        return startUrl != DEFAULT_HOME_URL && state.currentUrl == startUrl
    }

    private fun shouldSkipJsInjection(url: String?): Boolean {
        if (url.isNullOrBlank() || url == DEFAULT_HOME_URL) return true
        val host = runCatching { Uri.parse(url).host?.lowercase() }.getOrNull() ?: return false
        return isGoogleRelatedDomain(host)
    }

    private fun ensureInitialTab() {
        if (tabs.isNotEmpty()) {
            return
        }
        val restored = restoreTabs()
        if (restored) {
            return
        }
        val firstTab = BrowserTab(
            id = nextTabId++,
            title = getString(R.string.browser_title),
            url = DEFAULT_HOME_URL,
            webView = binding.webView,
        )
        tabs += firstTab
        currentTabId = firstTab.id
    }

    private fun restoreTabs(): Boolean {
        val (tabStates, activeId) = runBlocking(Dispatchers.IO) { store.loadTabs() }
        if (tabStates.isEmpty()) return false
        currentTabId = activeId
        tabStates.forEachIndexed { index, state ->
            val webView = if (index == 0) binding.webView else createTabWebView()
            val tab = BrowserTab(
                id = state.id,
                title = state.title,
                url = state.url,
                webView = webView,
            )
            tabs += tab
            if (tab.id > nextTabId) nextTabId = tab.id + 1
            setupWebView(webView)
            if (state.id == activeId) {
                loadRestoredTab(tab)
            }
        }
        val activeTab = tabs.firstOrNull { it.id == activeId } ?: tabs.first()
        currentTabId = activeTab.id
        ensureTabLoaded(activeTab)
        val activeUrl = activeTab.webView.url ?: activeTab.url
        prepareActiveTabGlobals(activeUrl)
        viewModel.showTab(activeUrl, activeTab.title, activeTab.webView.canGoBack(), activeTab.webView.canGoForward())
        updateWebViewVisibility(showHome = activeUrl == DEFAULT_HOME_URL)
        if (activeUrl == DEFAULT_HOME_URL) {
            showHomeSurface(refreshData = true)
        }
        updateTabsCountBadge()
        return true
    }

    private fun persistTabs() {
        persistTabsJob?.cancel()
        persistTabsJob = lifecycleScope.launch {
            delay(2000)
            flushPersistTabs()
        }
    }

    private fun flushPersistTabs() {
        val snapshot = tabs.map { tab ->
            com.fenglan.aurorabrowser.data.BrowserStore.TabState(
                id = tab.id,
                title = tab.title,
                url = tab.webView.url?.takeIf { it != "about:blank" } ?: tab.url,
            )
        }
        val activeId = currentTabId
        lifecycleScope.launch(Dispatchers.IO) {
            store.saveTabs(snapshot, activeId)
        }
    }

    private fun currentWebView(): WebView {
        return tabs.firstOrNull { it.id == currentTabId }?.webView ?: binding.webView
    }

    private fun loadRestoredTab(tab: BrowserTab) {
        val target = tab.url.takeIf { it.isNotBlank() && it != "about:blank" } ?: DEFAULT_HOME_URL
        if (tab.webView.url == target) {
            return
        }
        tab.webView.loadUrl(target)
    }

    private fun ensureTabLoaded(tab: BrowserTab) {
        if (tab.webView.url.isNullOrBlank() || tab.webView.url == "about:blank") {
            loadRestoredTab(tab)
        }
    }

    private fun tabForWebView(webView: WebView?): BrowserTab? {
        return tabs.firstOrNull { it.webView == webView }
    }

    private fun updateWebViewVisibility(showHome: Boolean = isHomeScreen(viewModel.uiState.value)) {
        binding.swipeRefresh.isVisible = !showHome
        if (showHome) binding.swipeRefresh.isRefreshing = false
        tabs.forEach { tab ->
            tab.webView.isVisible = !showHome && tab.id == currentTabId && !tab.frozen
        }
        if (!showHome) {
            currentWebView().bringToFront()
        }
    }

    private fun showHomeSurface(refreshData: Boolean = false) {
        val homeState = viewModel.uiState.value.copy(currentUrl = DEFAULT_HOME_URL)
        val onCustomStart = isOnCustomStartPage(viewModel.uiState.value)
        if (!onCustomStart) {
            binding.homePanel.isVisible = true
        }
        binding.addressBarContainer.isVisible = false
        if (binding.findBar.isVisible) {
            hideFindBar()
        }
        updateWebViewVisibility(showHome = !onCustomStart)
        if (refreshData) {
            lifecycleScope.launch {
                val homes = withContext(Dispatchers.IO) { store.getHomeSites() }
                val top = withContext(Dispatchers.IO) {
                    if (homeState.settings.showMostVisitedSites) store.getTopSites(HOME_TOP_SITE_LIMIT)
                    else emptyList()
                }
                customHomeSites = homes
                mostVisitedSites = top
                refreshHomeSections(viewModel.uiState.value.copy(currentUrl = DEFAULT_HOME_URL))
            }
        }
        refreshHomeSections(homeState)
        binding.root.postDelayed({
            val currentState = viewModel.uiState.value
            val stillOnCustom = isOnCustomStartPage(currentState)
            if (!stillOnCustom && isHomeScreen(currentState)) {
                binding.homePanel.isVisible = true
                refreshHomeSections(currentState.copy(currentUrl = DEFAULT_HOME_URL))
            }
        }, 100L)
    }

    private fun createTabWebView(): WebView {
        val parent = binding.webView.parent as ViewGroup
        return WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            isVisible = false
            parent.addView(this, parent.indexOfChild(binding.webView) + 1)
        }
    }

    private fun updateCurrentTab(state: BrowserUiState) {
        tabs.firstOrNull { it.id == currentTabId }?.apply {
            title = state.pageTitle.takeIf { it.isNotBlank() } ?: getString(R.string.browser_title)
            url = state.currentUrl
        }
    }

    private fun showTabsDialog() {
        showTabsOverview()
    }

    private fun showTabsOverview() {
        snapshotCurrentTab()
        tabsOverviewDialog?.dismiss()

        val dialog = Dialog(this)
        tabsOverviewDialog = dialog
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(currentThemePalette.surface)
            setPadding(dp(14), dp(18), dp(14), dp(10))
        }

        val grid = GridLayout(this).apply {
            columnCount = tabOverviewColumnCount()
            useDefaultMargins = false
        }
        val scrollView = ScrollView(this).apply {
            isFillViewport = true
            addView(
                grid,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        root.addView(scrollView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        fun renderOverview() {
            grid.removeAllViews()
            val columns = tabOverviewColumnCount()
            grid.columnCount = columns
            val tileWidth = ((resources.displayMetrics.widthPixels - dp(28) - dp(12) * (columns - 1)) / columns)
                .coerceAtLeast(dp(136))
            val thumbHeight = (tileWidth * 0.66f).toInt().coerceAtLeast(dp(108))
            tabs.forEach { tab ->
                grid.addView(
                    createTabOverviewCard(
                        tab = tab,
                        tileWidth = tileWidth,
                        thumbHeight = thumbHeight,
                        dialog = dialog,
                    ) {
                        closeTab(tab.id)
                        renderOverview()
                    },
                )
            }
        }

        val actionRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        actionRow.addView(
            createTabOverviewActionButton(R.drawable.ic_back, "返回当前页") {
                dialog.dismiss()
            },
            LinearLayout.LayoutParams(0, dp(54), 1f),
        )
        actionRow.addView(
            createTabOverviewActionButton(R.drawable.ic_add, "新建标签页") {
                createNewTab()
                dialog.dismiss()
            },
            LinearLayout.LayoutParams(0, dp(54), 1f),
        )
        actionRow.addView(
            createTabOverviewActionButton(R.drawable.ic_delete, "删除所有标签页") {
                confirmCloseAllTabs(dialog)
            },
            LinearLayout.LayoutParams(0, dp(54), 1f),
        )
        root.addView(actionRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        dialog.setContentView(root)
        dialog.setOnDismissListener {
            if (tabsOverviewDialog === dialog) {
                tabsOverviewDialog = null
            }
        }
        renderOverview()
        dialog.show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(currentThemePalette.surface))
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    private fun createNewTab() {
        if (tabs.size >= MAX_TABS) {
            freezeOldestBackgroundTab()
        }
        updateCurrentTab(viewModel.uiState.value)
        snapshotCurrentTab()
        currentWebView().onPause()
        val webView = createTabWebView()
        val newTab = BrowserTab(
            id = nextTabId++,
            title = "新标签页",
            url = DEFAULT_HOME_URL,
            webView = webView,
        )
        tabs += newTab
        setupWebView(webView)
        currentTabId = newTab.id
        clearBrowserSearchInputs()
        prepareActiveTabGlobals(DEFAULT_HOME_URL)
        viewModel.showTab(DEFAULT_HOME_URL, newTab.title, webView.canGoBack(), webView.canGoForward())
        webView.onResume()
        showHomeSurface(refreshData = true)
        updateTabsCountBadge()
        persistTabs()
    }

    private fun switchToTab(tabId: Long) {
        val target = tabs.firstOrNull { it.id == tabId } ?: return
        if (target.id == currentTabId) {
            return
        }
        updateCurrentTab(viewModel.uiState.value)
        snapshotCurrentTab()
        currentWebView().onPause()
        currentTabId = tabId
        restoreTab(target)
        ensureTabLoaded(target)
        target.webView.onResume()
        val displayState = BrowserNavigationStateTools.resolveTabDisplayState(
            savedUrl = target.url,
            savedTitle = target.title,
            webViewUrl = target.webView.url,
            webViewTitle = target.webView.title,
            frozen = target.frozen,
        )
        val targetUrl = displayState.url
        val targetTitle = displayState.title
        prepareActiveTabGlobals(targetUrl)
        viewModel.showTab(targetUrl, targetTitle, target.webView.canGoBack(), target.webView.canGoForward())
        val showHome = targetUrl == DEFAULT_HOME_URL
        updateWebViewVisibility(showHome = showHome)
        if (showHome) {
            showHomeSurface(refreshData = true)
        }
        syncActiveTabPageHooks(targetUrl)
        updateTabsCountBadge()
        persistTabs()
    }

    private fun freezeTab(tab: BrowserTab) {
        if (tab.frozen || tab.id == currentTabId) return
        tab.webView.url
            ?.takeIf { it.isNotBlank() && it != DEFAULT_HOME_URL }
            ?.let { tab.url = it }
        tab.frozen = true
        tab.webView.stopLoading()
        tab.webView.loadUrl("about:blank")
        tab.webView.isVisible = false
    }

    private fun restoreTab(tab: BrowserTab) {
        if (!tab.frozen) return
        tab.frozen = false
        if (tab.url != DEFAULT_HOME_URL) {
            tab.webView.loadUrl(tab.url)
        }
    }

    private fun freezeOldestBackgroundTab() {
        val backgroundTabs = tabs.filter { it.id != currentTabId && !it.frozen }
        if (backgroundTabs.size <= 1) return
        val oldest = backgroundTabs.firstOrNull() ?: return
        freezeTab(oldest)
    }

    private fun closeCurrentTab() {
        closeTab(currentTabId)
    }

    private fun closeTab(tabId: Long) {
        if (tabs.size <= 1) {
            closeAllTabs()
            return
        }
        val currentIndex = tabs.indexOfFirst { it.id == tabId }.takeIf { it >= 0 } ?: return
        val closing = tabs.removeAt(currentIndex)
        val closingCurrentTab = closing.id == currentTabId
        disposeClosedTab(closing)
        if (!closingCurrentTab) {
            updateTabsCountBadge()
            return
        }
        val nextIndex = (currentIndex - 1).coerceAtLeast(0)
        val target = tabs[nextIndex]
        currentTabId = target.id
        ensureTabLoaded(target)
        val targetUrl = target.webView.url ?: target.url
        prepareActiveTabGlobals(targetUrl)
        viewModel.showTab(targetUrl, target.webView.title ?: target.title, target.webView.canGoBack(), target.webView.canGoForward())
        target.webView.onResume()
        updateWebViewVisibility(showHome = targetUrl == DEFAULT_HOME_URL)
        if (targetUrl == DEFAULT_HOME_URL) {
            showHomeSurface(refreshData = true)
        }
        syncActiveTabPageHooks(targetUrl)
        updateTabsCountBadge()
        persistTabs()
    }

    private fun confirmCloseAllTabs(dialog: Dialog) {
        styleAlertDialog(MaterialAlertDialogBuilder(this))
            .setTitle("关闭所有页面")
            .setMessage("将关闭所有已打开的标签页，并保留一个新的空白标签页。是否继续？")
            .setNegativeButton("取消", null)
            .setPositiveButton("关闭") { _, _ ->
                closeAllTabs()
                dialog.dismiss()
            }
            .showThemedAlert()
    }

    private fun closeAllTabs() {
        val primaryWebView = binding.webView
        tabs.toList().forEach { tab ->
            if (tab.webView != primaryWebView) {
                disposeClosedTab(tab)
            }
        }
        tabs.clear()
        val newTab = BrowserTab(
            id = nextTabId++,
            title = "新标签页",
            url = DEFAULT_HOME_URL,
            webView = primaryWebView,
        )
        tabs += newTab
        currentTabId = newTab.id
        primaryWebView.apply {
            onResume()
            stopLoading()
            clearMatches()
            clearHistory()
            loadUrl(DEFAULT_HOME_URL)
            isVisible = false
        }
        prepareActiveTabGlobals(DEFAULT_HOME_URL)
        clearBrowserSearchInputs()
        viewModel.showTab(DEFAULT_HOME_URL, newTab.title, false, false)
        showHomeSurface(refreshData = true)
        updateTabsCountBadge()
        persistTabs()
    }

    private fun disposeClosedTab(tab: BrowserTab) {
        runCatching { tab.thumbnail?.recycle() }
        tab.thumbnail = null
        tab.webView.onPause()
        tab.webView.stopLoading()
        if (tab.webView == binding.webView) {
            tab.webView.clearMatches()
            tab.webView.isVisible = false
            return
        }
        (tab.webView.parent as? ViewGroup)?.removeView(tab.webView)
        tab.webView.webChromeClient = null
        tab.webView.webViewClient = WebViewClient()
        tab.webView.setDownloadListener(null)
        tab.webView.removeJavascriptInterface(JS_BRIDGE_NAME)
        tab.webView.destroy()
    }

    private fun createTabOverviewCard(
        tab: BrowserTab,
        tileWidth: Int,
        thumbHeight: Int,
        dialog: Dialog,
        onClose: () -> Unit,
    ): View {
        val active = tab.id == currentTabId
        val card = MaterialCardView(this).apply {
            radius = dp(20).toFloat()
            cardElevation = 0f
            strokeWidth = dp(if (active) 2 else 1)
            strokeColor = if (active) currentThemePalette.accent else currentThemePalette.line
            setCardBackgroundColor(currentThemePalette.surfaceAlt)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(10))
        }
        val thumbnailFrame = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(15).toFloat()
                setColor(tintWithAlpha(currentThemePalette.line, 52))
            }
            clipToOutline = false
        }
        val thumbnail = tab.thumbnail ?: if (tab.id == currentTabId) captureActiveTabThumbnail() else null
        if (thumbnail != null) {
            tab.thumbnail = thumbnail
            thumbnailFrame.addView(
                ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setImageBitmap(thumbnail)
                },
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
            )
        } else {
            thumbnailFrame.addView(
                TextView(this).apply {
                    gravity = Gravity.CENTER
                    maxLines = 2
                    text = tab.title.takeIf { it.isNotBlank() } ?: "新标签页"
                    setTextColor(currentThemePalette.textMuted)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                },
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
            )
        }
        thumbnailFrame.addView(
            ImageButton(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(tintWithAlpha(Color.BLACK, 136))
                }
                contentDescription = "关闭标签页"
                imageTintList = ColorStateList.valueOf(Color.WHITE)
                setImageResource(R.drawable.ic_close)
                setPadding(dp(7))
                setOnClickListener { onClose() }
            },
            FrameLayout.LayoutParams(dp(32), dp(32), Gravity.TOP or Gravity.END).apply {
                topMargin = dp(7)
                marginEnd = dp(7)
            },
        )
        content.addView(thumbnailFrame, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, thumbHeight))
        content.addView(
            TextView(this).apply {
                ellipsize = android.text.TextUtils.TruncateAt.END
                maxLines = 1
                text = tab.title.takeIf { it.isNotBlank() } ?: tab.url
                setTextColor(currentThemePalette.text)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.DEFAULT_BOLD
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            },
        )
        content.addView(
            TextView(this).apply {
                ellipsize = android.text.TextUtils.TruncateAt.END
                maxLines = 1
                text = if (tab.url == DEFAULT_HOME_URL) "新标签页" else tab.url
                setTextColor(currentThemePalette.textMuted)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(2)
            },
        )
        card.addView(content)
        card.setOnClickListener {
            dialog.dismiss()
            switchToTab(tab.id)
        }
        return card.apply {
            layoutParams = GridLayout.LayoutParams().apply {
                width = tileWidth
                height = GridLayout.LayoutParams.WRAP_CONTENT
                setMargins(dp(4), dp(6), dp(4), dp(6))
            }
        }
    }

    private fun createTabOverviewActionButton(iconRes: Int, description: String, onClick: () -> Unit): ImageButton {
        return ImageButton(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(currentThemePalette.surfaceAlt)
                setStroke(dp(1), currentThemePalette.line)
            }
            contentDescription = description
            imageTintList = ColorStateList.valueOf(currentThemePalette.text)
            setImageResource(iconRes)
            setPadding(dp(14))
            setOnClickListener { onClick() }
        }
    }

    private fun snapshotCurrentTab() {
        tabs.firstOrNull { it.id == currentTabId }?.thumbnail = captureActiveTabThumbnail()
    }

    private fun captureActiveTabThumbnail(): Bitmap? {
        val state = viewModel.uiState.value
        val source = if (isHomeScreen(state)) binding.homePanel else currentWebView()
        if (source.width <= 0 || source.height <= 0) {
            return null
        }
        val bitmap = Bitmap.createBitmap(TAB_THUMBNAIL_BITMAP_WIDTH, TAB_THUMBNAIL_BITMAP_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(currentThemePalette.surface)
        canvas.scale(
            TAB_THUMBNAIL_BITMAP_WIDTH / source.width.toFloat(),
            TAB_THUMBNAIL_BITMAP_HEIGHT / source.height.toFloat(),
        )
        source.draw(canvas)
        return bitmap
    }

    private fun tabOverviewColumnCount(): Int {
        val widthDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        return if (widthDp >= 680f) 3 else 2
    }

    private fun prepareActiveTabGlobals(url: String) {
        currentPageUrlForGuards = url
        lastCommittedPageUrlForGuards = url
        lastActiveVideoUrl = null
        lastPreferredVideoResourceUrl = null
        lastPreferredVideoTitle = null
        updateWebVideoPlaybackState(false)
        translatedPageUrl = null
        currentPageVideoResourceCache.clear()
        youTubeVideoFormats.clear()
        viewModel.clearFindResult()
    }

    private fun syncActiveTabPageHooks(url: String) {
        if (url == DEFAULT_HOME_URL || shouldSkipJsInjection(url)) {
            return
        }
        currentWebView().post {
            if (viewModel.uiState.value.currentUrl != url) {
                return@post
            }
            injectResourceSniffer()
            syncInjectedPageTheme(BrowserThemeTools.isEffectivelyDark(viewModel.uiState.value.settings.themeMode))
            if (effectiveAutoTranslateEnabled(url)) {
                translateCurrentPage(force = true)
            }
            if (effectiveAutofillEnabled(url)) {
                injectCredentialAssistant()
                scheduleCredentialAutofill(url)
            }
        }
    }

    private fun updateTabsCountBadge() {
        binding.tabsCountText.isVisible = tabs.size > 1
        binding.tabsCountText.text = tabs.size.toString()
    }

    private fun showRecentSearches(anchor: EditText, rawQuery: String) {
        val normalized = rawQuery.trim()
        val recentEntries = preferences.getRecentSearches().filter {
            normalized.isBlank() || it.contains(normalized, ignoreCase = true)
        }.take(MAX_RECENT_SEARCH_SUGGESTIONS).map { SuggestionEntry(it, true) }

        renderSuggestionPopup(anchor, recentEntries)
        suggestionsJob?.cancel()
        if (normalized.isBlank() || looksLikeDirectUrl(normalized) || resolveHttpInputOrNull(normalized) != null) {
            return
        }
        suggestionsJob = lifecycleScope.launch {
            delay(180)
            val suggestions = withContext(Dispatchers.IO) {
                SearchSuggestions.fetch(preferences.getSearchEngine(), normalized)
            }
            if (!anchor.hasFocus() || anchor.text?.toString()?.trim() != normalized) {
                return@launch
            }
            val mergedEntries = recentEntries + suggestions
                .filterNot { suggestion -> recentEntries.any { it.text.equals(suggestion, ignoreCase = true) } }
                .map { SuggestionEntry(it, false) }
            renderSuggestionPopup(anchor, mergedEntries)
        }
    }

    private fun renderSuggestionPopup(anchor: EditText, entries: List<SuggestionEntry>) {
        suggestionEntries = entries.distinctBy { it.text.lowercase() }
        if (suggestionEntries.isEmpty()) {
            dismissRecentSearches()
            return
        }
        dismissRecentSearches(cancelJob = false)
        val palette = currentThemePalette
        val adapter = object : android.widget.BaseAdapter() {
            override fun getCount() = suggestionEntries.size
            override fun getItem(position: Int) = suggestionEntries[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                val view = convertView ?: layoutInflater.inflate(R.layout.item_suggestion, parent, false)
                val entry = suggestionEntries[position]
                val icon = view.findViewById<ImageView>(R.id.suggestionIcon)
                val text = view.findViewById<TextView>(R.id.suggestionText)
                val arrow = view.findViewById<ImageView>(R.id.suggestionArrow)
                icon.setImageResource(if (entry.isHistory) R.drawable.ic_history else R.drawable.ic_search)
                icon.imageTintList = ColorStateList.valueOf(palette.textMuted)
                text.text = entry.text
                text.setTextColor(palette.text)
                arrow.imageTintList = ColorStateList.valueOf(palette.textMuted)
                view.setBackgroundColor(palette.surfaceAlt)
                return view
            }
        }
        suggestionsPopup = ListPopupWindow(this).apply {
            anchorView = anchor
            width = anchor.width
            isModal = false
            setAdapter(adapter)
            setBackgroundDrawable(android.graphics.drawable.GradientDrawable().apply {
                setColor(palette.surfaceAlt)
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), palette.line)
            })
            horizontalOffset = 0
            verticalOffset = dp(4)
            setOnItemClickListener { _, _, position, _ ->
                val selected = suggestionEntries.getOrNull(position)?.text ?: return@setOnItemClickListener
                anchor.setText(selected)
                anchor.setSelection(selected.length)
                dismissRecentSearches()
                submitInput(selected)
            }
            show()
            listView?.setOnItemLongClickListener { _, _, position, _ ->
                val entry = suggestionEntries.getOrNull(position) ?: return@setOnItemLongClickListener true
                if (!entry.isHistory) {
                    return@setOnItemLongClickListener true
                }
                confirmDeleteRecentSearch(anchor, entry.text)
                true
            }
        }
    }

    private fun confirmDeleteRecentSearch(anchor: EditText, query: String) {
        dismissRecentSearches(cancelJob = false)
        styleAlertDialog(MaterialAlertDialogBuilder(this))
            .setTitle(query)
            .setMessage("删除这条搜索历史？")
            .setNegativeButton("取消") { _, _ ->
                if (anchor.hasFocus()) {
                    showRecentSearches(anchor, anchor.text?.toString().orEmpty())
                }
            }
            .setPositiveButton("删除") { _, _ ->
                preferences.deleteRecentSearch(query)
                Toast.makeText(this, "已删除搜索历史", Toast.LENGTH_SHORT).show()
                if (anchor.hasFocus()) {
                    showRecentSearches(anchor, anchor.text?.toString().orEmpty())
                }
            }
            .showThemedAlert()
    }

    private fun dismissRecentSearches(cancelJob: Boolean = true) {
        if (cancelJob) {
            suggestionsJob?.cancel()
        }
        suggestionsPopup?.dismiss()
        suggestionsPopup = null
    }

    private fun showMagnetSearchSheet() {
        dismissRecentSearches()
        val dialog = BottomSheetDialog(this)
        styleDialog(dialog)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(24))
            setBackgroundColor(currentThemePalette.surface)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            TextView(this).apply {
                text = "磁力搜索"
                setTextColor(currentThemePalette.text)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                typeface = Typeface.DEFAULT_BOLD
            },
            LinearLayoutLayoutParams(0, LinearLayoutLayoutParams.WRAP_CONTENT, 1f),
        )
        header.addView(
            TextView(this).apply {
                text = "添加源"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(14), dp(9), dp(14), dp(9))
                applyPillBackground(this, currentThemePalette)
                setOnClickListener {
                    dialog.dismiss()
                    showAddMagnetSourceDialog()
                }
            },
        )
        container.addView(header)

        container.addView(
            TextView(this).apply {
                text = "选择磁力源后使用关键词搜索。请只搜索和访问你有权使用的内容。"
                setTextColor(currentThemePalette.textMuted)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            },
            LinearLayoutLayoutParams(
                LinearLayoutLayoutParams.MATCH_PARENT,
                LinearLayoutLayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) },
        )

        val queryLayout = TextInputLayout(this).apply {
            hint = "关键词"
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_FILLED
        }
        val queryInput = TextInputEditText(this).apply {
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            inputType = InputType.TYPE_CLASS_TEXT
            setText(binding.homeSearchInput.text?.toString().orEmpty())
            setSelection(text?.length ?: 0)
        }
        queryLayout.addView(queryInput)
        applyInputLayoutTheme(queryLayout, queryInput, currentThemePalette)
        container.addView(
            queryLayout,
            LinearLayoutLayoutParams(
                LinearLayoutLayoutParams.MATCH_PARENT,
                LinearLayoutLayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(14) },
        )

        val sourcesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        preferences.getMagnetSources().forEach { source ->
            sourcesContainer.addView(createMagnetSourceRow(source, queryInput, dialog))
        }
        val scrollView = ScrollView(this).apply {
            addView(sourcesContainer)
        }
        container.addView(
            scrollView,
            LinearLayoutLayoutParams(
                LinearLayoutLayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * 0.55f).toInt().coerceAtLeast(dp(280)),
            ).apply { topMargin = dp(12) },
        )
        queryInput.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || event?.keyCode == KeyEvent.KEYCODE_ENTER) {
                openMagnetSearch(preferences.getMagnetSources().first(), queryInput.text?.toString().orEmpty(), dialog)
                true
            } else {
                false
            }
        }
        dialog.setContentView(container)
        dialog.show()
    }

    private fun createMagnetSourceRow(
        source: MagnetSearchSource,
        queryInput: TextView,
        dialog: Dialog,
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(11), dp(10), dp(11))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(14).toFloat()
                setColor(currentThemePalette.surfaceAlt)
                setStroke(dp(1), currentThemePalette.line)
            }
        }
        val textStack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        textStack.addView(
            TextView(this).apply {
                text = if (source.isCustom) "${source.label} · 自定义" else source.label
                setTextColor(currentThemePalette.text)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            },
        )
        textStack.addView(
            TextView(this).apply {
                text = source.template
                setTextColor(currentThemePalette.textMuted)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            },
            LinearLayoutLayoutParams(
                LinearLayoutLayoutParams.MATCH_PARENT,
                LinearLayoutLayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(3) },
        )
        row.addView(textStack, LinearLayoutLayoutParams(0, LinearLayoutLayoutParams.WRAP_CONTENT, 1f))
        if (source.isCustom) {
            row.addView(
                ImageButton(this).apply {
                    setImageResource(R.drawable.ic_delete)
                    background = null
                    contentDescription = "删除磁力源"
                    setPadding(dp(9))
                    imageTintList = ColorStateList.valueOf(currentThemePalette.textMuted)
                    setOnClickListener { confirmDeleteMagnetSource(source, dialog) }
                },
                LinearLayoutLayoutParams(dp(42), dp(42)).apply { marginStart = dp(8) },
            )
        }
        row.setOnClickListener {
            openMagnetSearch(source, queryInput.text?.toString().orEmpty(), dialog)
        }
        return row.apply {
            layoutParams = LinearLayoutLayoutParams(
                LinearLayoutLayoutParams.MATCH_PARENT,
                LinearLayoutLayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }
        }
    }

    private fun openMagnetSearch(source: MagnetSearchSource, rawQuery: String, dialog: Dialog) {
        val query = rawQuery.trim()
        if (query.isBlank()) {
            Toast.makeText(this, "请输入磁力搜索关键词", Toast.LENGTH_SHORT).show()
            return
        }
        preferences.saveRecentSearch(query)
        binding.homeSearchInput.setText(query)
        dialog.dismiss()
        navigateTo(source.buildSearchUrl(query), userInitiated = true)
    }

    private fun showAddMagnetSourceDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(8), dp(2), 0)
        }
        val nameLayout = TextInputLayout(this).apply {
            hint = "源名称"
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_FILLED
        }
        val nameInput = TextInputEditText(this).apply {
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            setText("自定义磁力源")
        }
        nameLayout.addView(nameInput)
        applyInputLayoutTheme(nameLayout, nameInput, currentThemePalette)
        container.addView(nameLayout)

        val templateLayout = TextInputLayout(this).apply {
            hint = "搜索模板"
            helperText = "示例：${MagnetSearchSources.EXAMPLE_TEMPLATE}，也支持 https://example.com/search/%s"
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_FILLED
        }
        val templateInput = TextInputEditText(this).apply {
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(MagnetSearchSources.EXAMPLE_TEMPLATE)
            setSelection(text?.length ?: 0)
        }
        templateLayout.addView(templateInput)
        applyInputLayoutTheme(templateLayout, templateInput, currentThemePalette)
        container.addView(
            templateLayout,
            LinearLayoutLayoutParams(
                LinearLayoutLayoutParams.MATCH_PARENT,
                LinearLayoutLayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) },
        )

        val dialog = styleAlertDialog(MaterialAlertDialogBuilder(this))
            .setTitle("添加磁力源")
            .setMessage("模板必须以 http 或 https 开头，并包含 {query} 或 %s；搜索时会把占位符替换为关键词。")
            .setView(container)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val source = preferences.addCustomMagnetSource(
                    label = nameInput.text?.toString().orEmpty(),
                    template = templateInput.text?.toString().orEmpty(),
                )
                if (source == null) {
                    templateLayout.error = "请检查名称和模板格式"
                    return@setOnClickListener
                }
                Toast.makeText(this, "已添加磁力源：${source.label}", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
                showMagnetSearchSheet()
            }
        }
        dialog.show()
        applyAlertDialogTheme(dialog)
    }

    private fun confirmDeleteMagnetSource(source: MagnetSearchSource, parentDialog: Dialog) {
        styleAlertDialog(MaterialAlertDialogBuilder(this))
            .setTitle("删除磁力源")
            .setMessage("删除自定义源“${source.label}”？")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                preferences.deleteCustomMagnetSource(source.id)
                parentDialog.dismiss()
                showMagnetSearchSheet()
            }
            .showThemedAlert()
    }

    private fun showSearchEnginePicker() {
        val current = preferences.getSearchEngine()
        val items = SearchEngine.entries.map { it.label }.toTypedArray()
        styleAlertDialog(MaterialAlertDialogBuilder(this))
            .setTitle("选择搜索引擎")
            .setSingleChoiceItems(items, SearchEngine.entries.indexOf(current)) { dialog, which ->
                preferences.setSearchEngine(SearchEngine.entries[which])
                updateSearchEngineLabel()
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .showThemedAlert()
    }

    private fun updateSearchEngineLabel() {
        binding.homeEngineButton.text = preferences.getSearchEngine().label
    }

    private fun syncPreferenceDrivenUi() {
        updateSearchEngineLabel()
        val engine = preferences.getTranslationEngine()
        if (viewModel.uiState.value.settings.translationEngine != engine) {
            viewModel.setTranslationEngine(engine)
        }
    }

    private fun maybeInjectCloudPlayMagnet(url: String?, attempt: Int = 0) {
        val magnet = pendingCloudPlayMagnet ?: return
        val host = runCatching { Uri.parse(url).host.orEmpty().lowercase() }.getOrDefault("")
        if (!host.endsWith("webtor.io")) {
            return
        }
        val script = """
            (function() {
              var magnet = ${JSONObject.quote(magnet)};
              var fields = Array.from(document.querySelectorAll('textarea,input')).filter(function(el) {
                if (!el || el.disabled || el.readOnly) return false;
                var tag = String(el.tagName || '').toLowerCase();
                var type = String(el.type || '').toLowerCase();
                var meta = String((el.placeholder || '') + ' ' + (el.name || '') + ' ' + (el.id || '') + ' ' + type).toLowerCase();
                var usableType = tag === 'textarea' || !type || type === 'text' || type === 'search' || type === 'url';
                return usableType && (meta.indexOf('magnet') >= 0 || meta.indexOf('torrent') >= 0 || tag === 'textarea');
              });
              if (!fields.length) {
                fields = Array.from(document.querySelectorAll('textarea,input[type=text],input[type=url],input[type=search],input:not([type])'));
              }
              var field = fields[0];
              if (!field) return false;
              field.focus();
              field.value = magnet;
              ['input', 'change', 'keyup'].forEach(function(name) {
                field.dispatchEvent(new Event(name, { bubbles: true }));
              });
              var buttons = Array.from(document.querySelectorAll('button,input[type=submit],a')).filter(function(el) {
                var rect = el.getBoundingClientRect ? el.getBoundingClientRect() : { width: 1, height: 1 };
                return rect.width > 0 && rect.height > 0;
              });
              var button = buttons.find(function(el) {
                var text = String(el.innerText || el.value || el.title || el.ariaLabel || '').toLowerCase();
                return /stream|play|start|open|add|search|播放|开始|打开|搜索|云播/.test(text);
              });
              if (button) setTimeout(function() { button.click(); }, 160);
              return true;
            })();
        """.trimIndent()
        currentWebView().evaluateJavascript(script) { result ->
            if (result == "true") {
                pendingCloudPlayMagnet = null
                Toast.makeText(this, "已发送磁力链接到云播页", Toast.LENGTH_SHORT).show()
            } else if (attempt < CLOUD_PLAY_INJECT_MAX_ATTEMPTS) {
                binding.root.postDelayed({
                    maybeInjectCloudPlayMagnet(currentWebView().url, attempt + 1)
                }, CLOUD_PLAY_INJECT_RETRY_MS)
            } else {
                pendingCloudPlayMagnet = null
                Toast.makeText(this, "云播页未找到输入框，已打开页面", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun homeDestinationUrl(): String {
        return preferences.getStartPageUrl().ifBlank { DEFAULT_HOME_URL }
    }

    private fun navigateHome(userInitiated: Boolean = true) {
        val destination = homeDestinationUrl()
        navigateTo(destination, userInitiated = userInitiated)
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun addToHomeScreen() {
        val state = viewModel.uiState.value
        val url = state.currentUrl.takeIf { it.isNotBlank() && it != DEFAULT_HOME_URL }
        if (url.isNullOrBlank()) {
            Toast.makeText(this, "当前页面无法发到桌面", Toast.LENGTH_SHORT).show()
            return
        }
        val title = state.pageTitle.takeIf { it.isNotBlank() && it != url } ?: url
        val shortcutManager = getSystemService(ShortcutManager::class.java)
        if (shortcutManager?.isRequestPinShortcutSupported != true) {
            Toast.makeText(this, "当前桌面不支持添加快捷方式", Toast.LENGTH_SHORT).show()
            return
        }
        val shortcut = ShortcutInfo.Builder(this, "aurora_shortcut_${System.currentTimeMillis()}")
            .setShortLabel(title.take(12))
            .setLongLabel(title.take(32))
            .setIcon(android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_browser))
            .setIntent(Intent(this, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = Uri.parse(url)
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_USER_INITIATED, true)
            })
            .build()
        shortcutManager.requestPinShortcut(shortcut, null)
        Snackbar.make(binding.root, R.string.add_to_home_success, Snackbar.LENGTH_SHORT).show()
    }

    private fun selectAllAddressInput() {
        val editable = binding.addressInput.text ?: return
        if (editable.isNotEmpty()) {
            binding.addressInput.post { binding.addressInput.selectAll() }
        }
    }

    private fun showBookmarkEditorDialog() {
        val state = viewModel.uiState.value
        val pageUrl = state.currentUrl.takeIf { it.isNotBlank() && it != DEFAULT_HOME_URL }
        if (pageUrl.isNullOrBlank()) {
            Toast.makeText(this, "当前页面不能加入书签", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val existing = store.getBookmarkByUrl(pageUrl)
            withContext(Dispatchers.Main) {
                var selectedFolderId = existing?.folderId ?: BrowserStore.ROOT_BOOKMARK_FOLDER_ID
                val titleInput = EditText(this@MainActivity).apply {
                    hint = "书签名称"
                    setSingleLine()
                    setText(
                        existing?.title
                            ?: state.pageTitle.takeIf { it.isNotBlank() && it != pageUrl }
                            ?: pageUrl,
                    )
                    setSelection(text?.length ?: 0)
                }
                val folderButton = TextView(this@MainActivity).apply {
                    setPadding(dp(14), dp(10), dp(14), dp(10))
                    applyPillBackground(this, currentThemePalette)
                }
                val createFolderButton = TextView(this@MainActivity).apply {
                    text = "新建文件夹"
                    setPadding(dp(14), dp(10), dp(14), dp(10))
                    applyPillBackground(this, currentThemePalette)
                }
                val updateFolderButton = {
                    folderButton.text = "目录 · ${bookmarkFolderLabel(selectedFolderId)}"
                }
                updateFolderButton()
                folderButton.setOnClickListener {
                    showBookmarkFolderPicker(selectedFolderId) { folderId ->
                        selectedFolderId = folderId
                        updateFolderButton()
                    }
                }
                createFolderButton.setOnClickListener {
                    showBookmarkFolderCreateDialog(selectedFolderId) { folderId ->
                        selectedFolderId = folderId
                        updateFolderButton()
                    }
                }
                val actionsRow = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(
                        folderButton,
                        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                            marginEnd = dp(8)
                        },
                    )
                    addView(
                        createFolderButton,
                        LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT),
                    )
                }
                val container = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(20))
                    addView(titleInput)
                    addView(
                        actionsRow,
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        ).apply {
                            topMargin = dp(12)
                        },
                    )
                }
                styleAlertDialog(MaterialAlertDialogBuilder(this@MainActivity))
                    .setTitle(if (existing != null) "编辑书签" else "添加书签")
                    .setView(container)
                    .setNegativeButton("取消", null)
                    .setPositiveButton(if (existing != null) "保存" else "添加") { _, _ ->
                        val title = titleInput.text?.toString().orEmpty().trim().ifBlank {
                            state.pageTitle.takeIf { it.isNotBlank() } ?: pageUrl
                        }
                        viewModel.saveBookmark(title, pageUrl, selectedFolderId) {
                            runOnUiThread {
                                Snackbar.make(binding.root, "书签已保存", Snackbar.LENGTH_SHORT).show()
                            }
                        }
                    }
                    .apply {
                        if (existing != null) {
                            setNeutralButton("移除") { _, _ ->
                                confirmRemoveBookmark(pageUrl)
                            }
                        }
                    }
                    .showThemedAlert()
            }
        }
    }

    private fun confirmRemoveBookmark(pageUrl: String) {
        styleAlertDialog(MaterialAlertDialogBuilder(this))
            .setTitle("移除书签")
            .setMessage("删除当前页面的书签？")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                viewModel.removeBookmark(pageUrl) {
                    runOnUiThread {
                        Snackbar.make(binding.root, "书签已移除", Snackbar.LENGTH_SHORT).show()
                    }
                }
            }
            .showThemedAlert()
    }

    private fun bookmarkFolderLabel(folderId: Long): String {
        if (folderId == BrowserStore.ROOT_BOOKMARK_FOLDER_ID) {
            return "根目录"
        }
        val path = store.getBookmarkPath(folderId).joinToString(" / ") { it.title }
        return path.ifBlank { "根目录" }
    }

    private fun showBookmarkFolderPicker(initialFolderId: Long, onSelected: (Long) -> Unit) {
        val options = buildBookmarkFolderOptions()
        val labels = options.map { it.label }.toTypedArray()
        val checkedIndex = options.indexOfFirst { it.id == initialFolderId }.takeIf { it >= 0 } ?: 0
        styleAlertDialog(MaterialAlertDialogBuilder(this))
            .setTitle("选择书签目录")
            .setSingleChoiceItems(labels, checkedIndex) { dialog, which ->
                onSelected(options[which].id)
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .showThemedAlert()
    }

    private fun showBookmarkFolderCreateDialog(parentId: Long, onCreated: (Long) -> Unit) {
        val input = EditText(this).apply {
            hint = "文件夹名称"
            setSingleLine()
        }
        styleAlertDialog(MaterialAlertDialogBuilder(this))
            .setTitle("新建书签文件夹")
            .setView(
                LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(20))
                    addView(input)
                },
            )
            .setNegativeButton("取消", null)
            .setPositiveButton("创建") { _, _ ->
                val created = store.createBookmarkFolder(input.text?.toString().orEmpty(), parentId)
                if (created != null) {
                    onCreated(created.id)
                }
            }
            .showThemedAlert()
    }

    private fun buildBookmarkFolderOptions(): List<BookmarkFolderOption> {
        return buildList {
            add(BookmarkFolderOption(BrowserStore.ROOT_BOOKMARK_FOLDER_ID, "根目录"))
            store.getAllBookmarkFolders().forEach { folder ->
                add(
                    BookmarkFolderOption(
                        id = folder.id,
                        label = bookmarkFolderLabel(folder.id),
                    ),
                )
            }
        }
    }

    private fun fetchBestPageIcon(pageUrl: String) {
        val script = """
            (function() {
              var links = Array.from(document.querySelectorAll("link[rel]"))
                .map(function(node) {
                  return {
                    rel: String(node.getAttribute("rel") || "").toLowerCase(),
                    href: node.getAttribute("href") || "",
                    sizes: node.getAttribute("sizes") || ""
                  };
                })
                .filter(function(item) { return !!item.href; })
                .sort(function(left, right) {
                  var score = function(item) {
                    if (item.rel.indexOf("apple-touch-icon") >= 0) return 3000;
                    if (item.rel.indexOf("mask-icon") >= 0) return 2000;
                    if (item.rel.indexOf("shortcut icon") >= 0) return 1500;
                    if (item.rel.indexOf("icon") >= 0) return 1000;
                    return 0;
                  };
                  return score(right) - score(left);
                });
              return links.length ? new URL(links[0].href, window.location.href).href : "";
            })();
        """.trimIndent()
        currentWebView().evaluateJavascript(script) { rawResult ->
            val iconUrl = decodeJavascriptStringResult(rawResult)
            ensureSiteIcon(pageUrl, preferredIconUrl = iconUrl.takeIf { it.isNotBlank() }, force = true)
        }
    }

    private fun saveSiteIcon(pageUrl: String, bitmap: Bitmap) {
        lifecycleScope.launch(Dispatchers.IO) {
            if (siteIconStore.save(pageUrl, bitmap)) {
                withContext(Dispatchers.Main) {
                    refreshHomeSections()
                }
            }
        }
    }

    private fun ensureSiteIcon(pageUrl: String, preferredIconUrl: String? = null, force: Boolean = false) {
        if (!force && siteIconStore.load(pageUrl) != null) {
            return
        }
        synchronized(iconFetchInFlight) {
            if (!iconFetchInFlight.add(pageUrl)) {
                return
            }
        }
        val userAgent = buildUserAgent(effectiveDesktopMode(pageUrl))
        lifecycleScope.launch(Dispatchers.IO) {
            val saved = siteIconStore.fetchAndSave(
                pageUrl = pageUrl,
                iconUrl = preferredIconUrl,
                referer = pageUrl,
                userAgent = userAgent,
            )
            synchronized(iconFetchInFlight) {
                iconFetchInFlight.remove(pageUrl)
            }
            if (saved) {
                withContext(Dispatchers.Main) {
                    refreshHomeSections()
                }
            }
        }
    }

    private fun refreshCustomHomeSites() {
        lifecycleScope.launch {
            customHomeSites = withContext(Dispatchers.IO) {
                store.getHomeSites()
            }
            refreshHomeSections()
        }
    }

    private fun refreshMostVisitedSites() {
        val state = homeAwareRenderState()
        if (!state.settings.showMostVisitedSites) {
            mostVisitedSites = emptyList()
            refreshHomeSections(state)
            return
        }
        lifecycleScope.launch {
            mostVisitedSites = withContext(Dispatchers.IO) {
                store.getTopSites(HOME_TOP_SITE_LIMIT)
            }
            refreshHomeSections()
        }
    }

    private fun refreshHomeSections(state: BrowserUiState = homeAwareRenderState()) {
        invalidateHomeSections()
        renderHomeSectionsIfNeeded(state, force = true)
    }

    private fun homeAwareRenderState(): BrowserUiState {
        val state = viewModel.uiState.value
        return if (binding.homePanel.isVisible) {
            state.copy(currentUrl = DEFAULT_HOME_URL)
        } else {
            state
        }
    }

    private fun renderHomeSectionsIfNeeded(state: BrowserUiState, force: Boolean = false) {
        val homeStyle = preferences.getHomeStyle()
        val customSignature = buildString {
            append(isHomeScreen(state))
            append('|')
            append(homeStyle.key)
            append('|')
            append(state.settings.themeMode.key)
            append('|')
            customHomeSites.forEach { site ->
                append(site.id).append(':').append(site.title).append(':').append(site.url).append(';')
            }
        }
        if (force || customSignature != lastHomeCustomSignature) {
            lastHomeCustomSignature = customSignature
            renderCustomHomeSites(state)
        }

        val topSignature = buildString {
            append(isHomeScreen(state))
            append('|')
            append(homeStyle.key)
            append('|')
            append(state.settings.showMostVisitedSites)
            append('|')
            append(state.settings.themeMode.key)
            append('|')
            mostVisitedSites.forEach { site ->
                append(site.url).append(':').append(site.title).append(':').append(site.visitCount).append(':').append(site.visitedAt).append(';')
            }
        }
        if (force || topSignature != lastHomeTopSignature) {
            lastHomeTopSignature = topSignature
            renderMostVisitedSites(state)
        }

        val isMinimal = homeStyle == HomeStyle.MINIMAL && isHomeScreen(state)
        binding.homeLogo.isVisible = isMinimal
        binding.homeAppName.isVisible = isMinimal
        binding.homeQuickActions.isVisible = !isMinimal
        binding.homeTopSitesSection.isVisible = binding.homeTopSitesSection.isVisible && !isMinimal
        binding.homeCustomSitesSection.isVisible = binding.homeCustomSitesSection.isVisible && !isMinimal
        binding.homeSpacer.isVisible = !isMinimal
        if (isMinimal) {
            binding.homePanel.gravity = Gravity.CENTER
            val hPad = (28 * resources.displayMetrics.density).toInt()
            binding.homePanel.setPadding(hPad, 0, hPad, 0)
            if (!homeKeyboardVisible) {
                val baseShift = (resources.displayMetrics.heightPixels * 0.2).toInt()
                binding.homePanel.translationY = preferences.getHomeVerticalOffset().toFloat() - baseShift
            }
        } else {
            binding.homePanel.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            val hPad = (28 * resources.displayMetrics.density).toInt()
            val vPadTop = (28 * resources.displayMetrics.density).toInt()
            val vPadBottom = (24 * resources.displayMetrics.density).toInt()
            binding.homePanel.setPadding(hPad, vPadTop, hPad, vPadBottom)
            binding.homePanel.translationY = 0f
        }
    }

    private fun invalidateHomeSections() {
        lastHomeCustomSignature = null
        lastHomeTopSignature = null
    }

    private fun setupMinimalDragHandler() {
        var isDragging = false
        var startRawY = 0f
        var startOffset = 0

        binding.homeLogo.setOnLongClickListener {
            if (preferences.getHomeStyle() == HomeStyle.MINIMAL) {
                isDragging = true
                startOffset = preferences.getHomeVerticalOffset()
                it.parent?.requestDisallowInterceptTouchEvent(true)
                true
            } else {
                false
            }
        }

        binding.homePanel.setOnTouchListener { _, event ->
            if (!isDragging) return@setOnTouchListener false
            val baseShift = (resources.displayMetrics.heightPixels * 0.2).toInt()
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startRawY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = (event.rawY - startRawY).toInt()
                    binding.homePanel.translationY = (startOffset + dy - baseShift).toFloat()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val finalOffset = startOffset + (event.rawY - startRawY).toInt()
                    preferences.setHomeVerticalOffset(finalOffset)
                    binding.homePanel.translationY = (finalOffset - baseShift).toFloat()
                    isDragging = false
                    false
                }
                else -> false
            }
        }

    }

    private fun handleHomeSearchFocusChange(hasFocus: Boolean) {
        if (hasFocus) {
            showRecentSearches(binding.homeSearchInput, binding.homeSearchInput.text?.toString().orEmpty())
        } else {
            dismissRecentSearches()
        }
        applyMinimalHomeSearchFocus(hasFocus)
    }

    private fun applyMinimalHomeSearchFocus(hasFocus: Boolean) {
        if (preferences.getHomeStyle() != HomeStyle.MINIMAL) return
        if (!isHomeScreen(viewModel.uiState.value)) return
        val savedOffset = preferences.getHomeVerticalOffset()
        val baseShift = (resources.displayMetrics.heightPixels * 0.2).toInt()
        if (hasFocus) {
            homeKeyboardVisible = true
            binding.homePanel.translationY = (savedOffset - baseShift * 2).toFloat()
        } else {
            homeKeyboardVisible = false
            binding.homePanel.translationY = (savedOffset - baseShift).toFloat()
        }
    }

    private fun renderCustomHomeSites(state: BrowserUiState) {
        val visible = isHomeScreen(state)
        binding.homeCustomSitesSection.isVisible = visible
        if (!visible) {
            return
        }
        val tiles = buildList {
            customHomeSites.forEach { site ->
                add(
                    createSiteTile(
                        title = site.title,
                        url = site.url,
                        badgeHint = displaySiteBadge(site.title, site.url),
                        onClick = { navigateTo(site.url, userInitiated = true) },
                        onLongClick = { confirmDeleteHomeSite(site) },
                    ),
                )
            }
            add(createAddHomeSiteTile())
        }
        renderSiteGrid(binding.homeCustomSitesRow, tiles)
    }

    private fun renderMostVisitedSites(state: BrowserUiState) {
        val homeVisible = isHomeScreen(state)
        val visible = homeVisible && state.settings.showMostVisitedSites && mostVisitedSites.isNotEmpty()
        binding.homeTopSitesSection.isVisible = visible
        if (!homeVisible) {
            return
        }
        if (!visible) {
            binding.homeTopSitesRow.removeAllViews()
            return
        }
        val tiles = mostVisitedSites.map { site ->
            createSiteTile(
                title = site.title,
                url = site.url,
                badgeHint = displaySiteBadge(site.title, site.url),
                onClick = { navigateTo(site.url, userInitiated = true) },
                onLongClick = {
                    Snackbar.make(binding.root, "${site.url} · 访问${site.visitCount}次", Snackbar.LENGTH_SHORT).show()
                    true
                },
            )
        }
        renderHorizontalSiteStrip(binding.homeTopSitesRow, tiles)
    }

    private fun renderSiteGrid(container: LinearLayout, tiles: List<View>) {
        container.removeAllViews()
        if (tiles.isEmpty()) {
            return
        }
        tiles.chunked(HOME_SITE_COLUMNS).forEachIndexed { rowIndex, rowTiles ->
            val row = LinearLayout(this).apply {
                layoutParams = LinearLayoutLayoutParams(
                    LinearLayoutLayoutParams.MATCH_PARENT,
                    LinearLayoutLayoutParams.WRAP_CONTENT,
                ).apply {
                    if (rowIndex > 0) {
                        topMargin = dp(HOME_SITE_ROW_GAP_DP)
                    }
                }
                gravity = Gravity.TOP
                orientation = LinearLayout.HORIZONTAL
            }
            repeat(HOME_SITE_COLUMNS) { columnIndex ->
                val slot = FrameLayout(this).apply {
                    layoutParams = LinearLayoutLayoutParams(
                        0,
                        LinearLayoutLayoutParams.WRAP_CONTENT,
                        1f,
                    ).apply {
                        if (columnIndex > 0) {
                            marginStart = dp(HOME_SITE_COLUMN_GAP_DP)
                        }
                    }
                }
                rowTiles.getOrNull(columnIndex)?.let { tile ->
                    (tile.parent as? ViewGroup)?.removeView(tile)
                    slot.addView(
                        tile,
                        FrameLayoutLayoutParams(
                            FrameLayoutLayoutParams.WRAP_CONTENT,
                            FrameLayoutLayoutParams.WRAP_CONTENT,
                            Gravity.TOP or Gravity.CENTER_HORIZONTAL,
                        ),
                    )
                }
                row.addView(slot)
            }
            container.addView(row)
        }
    }

    private fun renderHorizontalSiteStrip(container: LinearLayout, tiles: List<View>) {
        container.removeAllViews()
        tiles.forEachIndexed { index, tile ->
            (tile.parent as? ViewGroup)?.removeView(tile)
            tile.layoutParams = LinearLayoutLayoutParams(
                dp(HOME_SITE_TILE_WIDTH_DP),
                LinearLayoutLayoutParams.WRAP_CONTENT,
            ).apply {
                if (index > 0) {
                    marginStart = dp(HOME_SITE_COLUMN_GAP_DP)
                }
            }
            container.addView(tile)
        }
    }

    private fun createSiteTile(
        title: String,
        url: String,
        badgeHint: String,
        onClick: () -> Unit,
        onLongClick: (() -> Boolean)? = null,
    ): View {
        val itemWidth = dp(HOME_SITE_TILE_WIDTH_DP)
        val iconSize = dp(HOME_SITE_ICON_SIZE_DP)
        val iconBitmap = siteIconStore.load(url)
        val scheme = runCatching { Uri.parse(url).scheme?.lowercase() }.getOrNull()
        if (iconBitmap == null && (scheme == "http" || scheme == "https")) {
            ensureSiteIcon(url)
        }

        val iconImage = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageBitmap(iconBitmap)
            isVisible = iconBitmap != null
        }

        val fallbackBadge = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            gravity = Gravity.CENTER
            text = badgeHint
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(HOME_SITE_ICON_RADIUS_DP).toFloat()
                setColor(siteAccentColor(url))
            }
            isVisible = iconBitmap == null
        }

        val iconFrame = FrameLayout(this).apply {
            layoutParams = LinearLayoutLayoutParams(iconSize, iconSize)
            addView(iconImage)
            addView(fallbackBadge)
        }

        val iconCard = MaterialCardView(this).apply {
            layoutParams = LinearLayoutLayoutParams(iconSize, iconSize)
            radius = dp(HOME_SITE_ICON_RADIUS_DP).toFloat()
            cardElevation = 0f
            strokeWidth = 0
            setCardBackgroundColor(Color.TRANSPARENT)
            addView(iconFrame)
        }

        val labelView = TextView(this).apply {
            layoutParams = LinearLayoutLayoutParams(LinearLayoutLayoutParams.MATCH_PARENT, LinearLayoutLayoutParams.WRAP_CONTENT)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            text = displaySiteLabel(title, url)
            setTextColor(currentThemePalette.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        }

        return LinearLayout(this).apply {
            layoutParams = LinearLayoutLayoutParams(itemWidth, LinearLayoutLayoutParams.WRAP_CONTENT)
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(iconCard)
            addView(labelView.apply {
                (layoutParams as LinearLayoutLayoutParams).topMargin = dp(6)
            })
            setOnClickListener { onClick() }
            if (onLongClick != null) {
                setOnLongClickListener { onLongClick() }
            }
        }
    }

    private fun createAddHomeSiteTile(): View {
        val tile = createSiteTile(
            title = "添加",
            url = "aurora://add-home-site",
            badgeHint = "+",
            onClick = ::showAddHomeSiteDialog,
        )
        ensureSyntheticAddTileStyle(tile)
        return tile
    }

    private fun ensureSyntheticAddTileStyle(tile: View) {
        val layout = tile as? LinearLayout ?: return
        val card = layout.getChildAt(0) as? MaterialCardView ?: return
        val frame = card.getChildAt(0) as? FrameLayout ?: return
        val image = frame.getChildAt(0) as? ImageView
        val fallback = frame.getChildAt(1) as? TextView
        image?.setImageDrawable(null)
        image?.isVisible = false
        fallback?.apply {
            isVisible = true
            setTextColor(currentThemePalette.text)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(HOME_SITE_ICON_RADIUS_DP).toFloat()
                setColor(currentThemePalette.surface)
                setStroke(dp(1), currentThemePalette.line)
            }
        }
        val label = layout.getChildAt(1) as? TextView
        label?.text = "添加"
    }

    private fun displaySiteBadge(title: String, url: String): String {
        val source = displaySiteLabel(title, url)
        return source.firstOrNull()?.uppercaseChar()?.toString() ?: "•"
    }

    private fun displaySiteLabel(title: String, url: String): String {
        val cleanTitle = title.trim().takeIf { it.isNotBlank() && it != url }
        if (cleanTitle != null) {
            return cleanTitle.take(8)
        }
        val host = runCatching { Uri.parse(url).host }.getOrNull().orEmpty()
            .removePrefix("www.")
        return host.take(10).ifBlank { "站点" }
    }

    private fun siteAccentColor(url: String): Int {
        val index = url.hashCode().absoluteValue % SITE_ACCENT_PALETTE.size
        return SITE_ACCENT_PALETTE[index]
    }

    private fun showAddHomeSiteDialog() {
        val state = viewModel.uiState.value
        val prefillUrl = state.currentUrl.takeIf { it.isNotBlank() && it != DEFAULT_HOME_URL }.orEmpty()
        val prefillTitle = state.pageTitle.takeIf { it.isNotBlank() && state.currentUrl != DEFAULT_HOME_URL }.orEmpty()

        val titleInput = EditText(this).apply {
            hint = "站点名称"
            setText(prefillTitle)
            setSingleLine()
        }
        val urlInput = EditText(this).apply {
            hint = "https://example.com"
            setText(prefillUrl)
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20))
            addView(titleInput)
            addView(
                urlInput,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = dp(12)
                },
            )
        }
        styleAlertDialog(MaterialAlertDialogBuilder(this))
            .setTitle("添加首页站点")
            .setView(container)
            .setNegativeButton("取消", null)
            .setPositiveButton("添加") { _, _ ->
                val normalizedUrl = normalizeHomeSiteInput(urlInput.text?.toString().orEmpty())
                if (normalizedUrl == null) {
                    Toast.makeText(this, "请输入有效网址", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                lifecycleScope.launch(Dispatchers.IO) {
                    store.upsertHomeSite(
                        title = titleInput.text?.toString().orEmpty().trim().ifBlank { displaySiteLabel("", normalizedUrl) },
                        url = normalizedUrl,
                    )
                    ensureSiteIcon(normalizedUrl)
                    withContext(Dispatchers.Main) {
                        refreshCustomHomeSites()
                        Toast.makeText(this@MainActivity, "已添加到首页", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .showThemedAlert()
    }

    private fun normalizeHomeSiteInput(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) {
            return null
        }
        val resolved = when {
            hasExplicitScheme(trimmed) -> trimmed
            looksLikeDirectUrl(trimmed) -> "https://$trimmed"
            else -> return null
        }
        val uri = runCatching { Uri.parse(resolved) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        return resolved.takeIf { scheme == "http" || scheme == "https" }
    }

    private fun confirmDeleteHomeSite(site: HomeSiteItem): Boolean {
        styleAlertDialog(MaterialAlertDialogBuilder(this))
            .setTitle(site.title)
            .setMessage("从首页移除这个站点？")
            .setNegativeButton("取消", null)
            .setPositiveButton("移除") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    store.deleteHomeSite(site.id)
                    withContext(Dispatchers.Main) {
                        refreshCustomHomeSites()
                    }
                }
            }
            .showThemedAlert()
        return true
    }

    private val displayDensity: Float by lazy { resources.displayMetrics.density }

    private fun dp(value: Int): Int {
        return (value * displayDensity + 0.5f).toInt()
    }

    private fun textSizeSp(view: TextView): Float {
        val effectiveScaledDensity = resources.displayMetrics.density * resources.configuration.fontScale
        return view.textSize / effectiveScaledDensity.coerceAtLeast(0.1f)
    }

    private fun showVideoDownloadPicker(resources: List<ResourceItem>) {
        if (resources.isEmpty()) {
            Toast.makeText(this, "未发现可下载的视频", Toast.LENGTH_SHORT).show()
            return
        }
        if (resources.size == 1) {
            enqueueVideoResourceWithQuality(resources.first())
            return
        }
        val items = resources.mapIndexed { index, item ->
            "${index + 1}. ${formatVideoResourceChoice(item)}"
        }.toTypedArray()
        styleAlertDialog(MaterialAlertDialogBuilder(this))
            .setTitle("选择下载视频")
            .setItems(items) { _, which ->
                enqueueVideoResourceWithQuality(resources[which])
            }
            .setNegativeButton("取消", null)
            .showThemedAlert()
    }

    private fun enqueueVideoResourceWithQuality(resource: ResourceItem) {
        if (!resource.isStream && resource.category != FileCategory.STREAM) {
            enqueueVideoResource(resource)
            return
        }
        Snackbar.make(binding.root, "正在解析流媒体清晰度", Snackbar.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val variants = withContext(Dispatchers.IO) {
                runCatching {
                    BrowserDownloads.loadHlsVariants(
                        context = this@MainActivity,
                        url = resource.url,
                        referer = resource.referer ?: resource.pageUrl,
                        userAgent = resource.userAgent ?: buildUserAgent(effectiveDesktopMode(resource.pageUrl)),
                    )
                }.getOrDefault(emptyList())
            }
            if (variants.isEmpty()) {
                enqueueVideoResource(resource)
                return@launch
            }
            val items = variants.mapIndexed { index, option -> "${index + 1}. ${option.label}" }.toTypedArray()
            styleAlertDialog(MaterialAlertDialogBuilder(this@MainActivity))
                .setTitle("选择清晰度")
                .setItems(items) { _, which ->
                    enqueueVideoResource(resourceForHlsVariant(resource, variants[which]))
                }
                .setNegativeButton("取消", null)
                .showThemedAlert()
        }
    }

    private fun enqueueVideoResource(resource: ResourceItem) {
        val message = BrowserDownloads.enqueueFromResource(
            this,
            store,
            resource,
            overrideTitle = preferredVideoTitleFor(resource),
            isPrivate = preferences.getDefaultPrivacyDownload(),
        )
        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
    }

    private fun resourceForHlsVariant(
        source: ResourceItem,
        option: BrowserDownloads.HlsVariantOption,
    ): ResourceItem {
        val baseTitle = preferredVideoTitleFor(source).orEmpty()
        return source.copy(
            id = -SystemClock.elapsedRealtime(),
            title = listOf(baseTitle, option.label).filter { it.isNotBlank() }.joinToString(" "),
            url = option.url,
            mimeType = "application/x-mpegURL",
            category = FileCategory.STREAM,
            isStream = true,
            userAgent = source.userAgent ?: buildUserAgent(effectiveDesktopMode(source.pageUrl)),
        )
    }

    private fun refreshCurrentVideoResources(onReady: (List<ResourceItem>) -> Unit) {
        val fallbackResources = currentPageVideoResources()
        val state = viewModel.uiState.value
        currentWebView().evaluateJavascript(
            "(function(){try{return window.__auroraCollectCurrentVideoCandidates ? window.__auroraCollectCurrentVideoCandidates() : '{}';}catch(e){return '{}';}})()",
        ) { rawResult ->
            val decoded = decodeJavascriptStringResult(rawResult)
            if (decoded.isBlank()) {
                onReady(fallbackResources)
                return@evaluateJavascript
            }
            val freshResources = mutableListOf<ResourceItem>()
            runCatching {
                val payload = JSONObject(decoded)
                val title = payload.optString("title").takeIf { it.isNotBlank() }
                title?.let { lastPreferredVideoTitle = it }
                val urls = payload.optJSONArray("urls")
                for (index in 0 until (urls?.length() ?: 0)) {
                    val candidateUrl = urls?.optString(index).orEmpty()
                    if (candidateUrl.isBlank()) {
                        continue
                    }
                    val candidateMimeType = when {
                        ResourceTools.isStream(candidateUrl, null) -> "application/x-mpegURL"
                        else -> null
                    }
                    if (!ResourceTools.isDownloadableVideo(candidateUrl, candidateMimeType)) {
                        continue
                    }
                    freshResources += ResourceItem(
                        id = -1L - index,
                        title = title ?: state.pageTitle,
                        url = candidateUrl,
                        pageUrl = state.currentUrl,
                        mimeType = candidateMimeType,
                        referer = state.currentUrl,
                        userAgent = buildUserAgent(effectiveDesktopMode(state.currentUrl)),
                        category = ResourceTools.classify(candidateUrl, candidateMimeType),
                        isStream = ResourceTools.isStream(candidateUrl, candidateMimeType),
                        capturedAt = System.currentTimeMillis() + index,
                    )
                    captureResourceCandidate(
                        url = candidateUrl,
                        title = title ?: state.pageTitle,
                        pageUrl = state.currentUrl,
                        mimeType = candidateMimeType,
                        referer = state.currentUrl,
                        userAgent = buildUserAgent(effectiveDesktopMode(state.currentUrl)),
                        prioritizeForCurrentVideo = true,
                    )
                }
            }
            val youTubeResources = youTubeVideoFormats.mapIndexed { index, format ->
                val resolvedUrl = if (format.url.startsWith("http")) format.url
                    else ResourceTools.decodeYouTubeCipher(format.url) ?: format.url
                ResourceItem(
                    id = -2000L - index,
                    title = "${format.quality} ${if (format.hasAudio) "" else "(仅视频)"}".trim(),
                    url = resolvedUrl,
                    pageUrl = state.currentUrl,
                    mimeType = format.mimeType,
                    referer = state.currentUrl,
                    userAgent = buildUserAgent(effectiveDesktopMode(state.currentUrl)),
                    category = FileCategory.VIDEO,
                    isStream = false,
                    capturedAt = System.currentTimeMillis() + 10000 + index,
                )
            }
            val mergedResources = (youTubeResources + freshResources + currentPageVideoResources() + fallbackResources)
                .distinctBy { it.url }
            onReady(mergedResources)
        }
    }

    private fun decodeJavascriptStringResult(result: String?): String {
        val value = result?.trim().orEmpty()
        if (value.isBlank() || value == "null") {
            return ""
        }
        return runCatching { JSONArray("[$value]").getString(0) }.getOrDefault("")
    }

    private fun formatVideoResourceChoice(item: ResourceItem): String {
        val title = preferredVideoTitleFor(item)?.takeIf { it.isNotBlank() && it != viewModel.uiState.value.pageTitle }
            ?: Uri.parse(item.url).lastPathSegment
            ?: item.url
        val kind = when {
            item.isStream || item.category.value == "stream" -> "流媒体"
            else -> item.category.label
        }
        return "$title · $kind"
    }

    private fun preferredVideoTitleFor(item: ResourceItem): String? {
        if (item.url == lastPreferredVideoResourceUrl && !lastPreferredVideoTitle.isNullOrBlank()) {
            return lastPreferredVideoTitle
        }
        return item.title.takeIf { it.isNotBlank() }
            ?: lastPreferredVideoTitle
            ?: viewModel.uiState.value.pageTitle.takeIf { it.isNotBlank() }
    }

    private fun preferredVideoTitleFor(title: String?, url: String): String {
        return title?.takeIf { it.isNotBlank() }
            ?: if (url == lastPreferredVideoResourceUrl) {
                lastPreferredVideoTitle
            } else {
                null
            }
            ?: viewModel.uiState.value.pageTitle.takeIf { it.isNotBlank() }
            ?: Uri.parse(url).lastPathSegment
            ?: url
    }

    private fun resolveOriginHeader(value: String?): String? {
        val uri = value?.takeIf { it.isNotBlank() }?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host ?: return null
        val port = uri.port.takeIf { it > 0 }?.let { ":$it" }.orEmpty()
        return "$scheme://$host$port"
    }

    private fun noteNavigationIntent() {
        lastAppNavigationAtMillis = SystemClock.elapsedRealtime()
    }

    private fun noteTrustedWebNavigation() {
        lastTrustedWebNavigationAtMillis = SystemClock.elapsedRealtime()
    }

    private fun noteExplicitUserNavigation(url: String?) {
        val normalized = url?.takeIf { it.isNotBlank() }?.let(::normalizeNavigableUrl) ?: return
        lastExplicitNavigationUrl = normalized
        lastExplicitNavigationAtMillis = SystemClock.elapsedRealtime()
    }

    private fun noteVideoInteraction(
        videoUrl: String? = null,
        title: String? = null,
        width: Double = 0.0,
        height: Double = 0.0,
    ) {
        lastVideoInteractionAtMillis = SystemClock.elapsedRealtime()
        lastVideoInteractionAtEpochMillis = System.currentTimeMillis()
        if (width > 0.0 && height > 0.0) {
            lastActiveVideoWidth = width
            lastActiveVideoHeight = height
        }
        videoUrl?.takeIf { it.isNotBlank() }?.let {
            lastActiveVideoUrl = it
            val category = ResourceTools.classify(it, null)
            if (category.value == "stream" || category.value == "video") {
                lastPreferredVideoResourceUrl = it
            }
        }
        title?.takeIf { it.isNotBlank() }?.let { lastPreferredVideoTitle = it }
    }

    private fun handleRedirectConfirm(targetUri: Uri, currentPageUri: Uri): Boolean {
        val now = SystemClock.elapsedRealtime()
        val targetHost = targetUri.host ?: return true
        val currentHost = currentPageUri.host ?: "当前网页"

        if (now - lastRedirectConfirmAtMillis < REDIRECT_CONFIRM_DEBOUNCE_MS) {
            pendingBlockedRedirects++
            if (pendingBlockedRedirects >= MAX_SIMULTANEOUS_REDIRECTS) {
                showBlockedNavigationMessage("已拦截 ${pendingBlockedRedirects} 个连续跳转", "")
                pendingBlockedRedirects = 0
                lastRedirectConfirmAtMillis = now
                return true
            }
            return true
        }

        pendingBlockedRedirects = 0
        lastRedirectConfirmAtMillis = now

        binding.root.post {
            if (isFinishing || isDestroyed) return@post
            styleAlertDialog(MaterialAlertDialogBuilder(this@MainActivity))
                .setTitle("检测到跨站跳转")
                .setMessage("当前页面（$currentHost）尝试跳转到：\n\n$targetHost\n\n是否允许此次跳转？")
                .setNegativeButton("拦截") { _, _ ->
                    showBlockedNavigationMessage("已拦截跳转", "")
                }
                .setPositiveButton("允许") { _, _ ->
                    noteTrustedWebNavigation()
                    navigateTo(targetUri.toString(), userInitiated = true)
                }
                .setOnCancelListener {
                    showBlockedNavigationMessage("已拦截跳转", "")
                }
                .showThemedAlert()
        }
        return true
    }

    private fun showBlockedNavigationMessage(message: String, targetUrl: String) {
        val now = SystemClock.elapsedRealtime()
        val isDuplicate = lastBlockedMainFrameUrl == targetUrl &&
            now - lastBlockedMainFrameAtMillis < BLOCKED_NAVIGATION_DEBOUNCE_MS
        lastBlockedMainFrameUrl = targetUrl
        lastBlockedMainFrameAtMillis = now
        if (!isDuplicate) {
            Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun promptTorrentDownload(rawValue: String, title: String?, copied: Boolean) {
        val magnet = TorrentTools.extractValidMagnet(rawValue)
        val torrentUrl = rawValue.trim().takeIf { TorrentTools.isTorrentUrl(it) }
        val target = magnet ?: torrentUrl ?: return
        val now = SystemClock.elapsedRealtime()
        if (target == lastTorrentPromptValue && now - lastTorrentPromptAtMillis < TORRENT_PROMPT_DEBOUNCE_MS) {
            return
        }
        lastTorrentPromptValue = target
        lastTorrentPromptAtMillis = now

        val isMagnet = magnet != null
        val displayTitle = title?.takeIf { it.isNotBlank() }
            ?: magnet?.let(TorrentTools::titleForMagnet)
            ?: target.substringAfterLast('/').substringBefore('?').ifBlank { target }
        val currentEngine = preferences.getP2pDownloadEngine()

        val privacyCheck = android.widget.CheckBox(this).apply {
            text = getString(R.string.download_to_privacy)
            isChecked = preferences.getDefaultPrivacyDownload()
            setPadding(dp(24), dp(8), dp(24), dp(8))
        }

        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(24), 0, dp(24), 0)
            addView(android.widget.TextView(this@MainActivity).apply {
                text = displayTitle.take(180)
                setTextColor(currentThemePalette.textMuted)
                textSize = 14f
                setPadding(0, 0, 0, dp(8))
            })
            addView(privacyCheck)
        }

        styleAlertDialog(MaterialAlertDialogBuilder(this))
            .setTitle(if (isMagnet) "下载磁力链接？" else "下载种子文件？")
            .setView(container)
            .setNegativeButton(if (copied) "仅复制" else "取消", null)
            .setNeutralButton(currentEngine.alternate().actionLabel) { _, _ ->
                handleTorrentDownloadChoice(
                    target = target,
                    magnet = magnet,
                    displayTitle = displayTitle,
                    engine = currentEngine.alternate(),
                    isPrivate = privacyCheck.isChecked,
                )
            }
            .setPositiveButton(currentEngine.actionLabel) { _, _ ->
                handleTorrentDownloadChoice(
                    target = target,
                    magnet = magnet,
                    displayTitle = displayTitle,
                    engine = currentEngine,
                    isPrivate = privacyCheck.isChecked,
                )
            }
            .showThemedAlert()
    }

    private fun handleTorrentDownloadChoice(
        target: String,
        magnet: String?,
        displayTitle: String,
        engine: P2pDownloadEngine,
        isPrivate: Boolean = false,
    ) {
        val pageUrl = viewModel.uiState.value.currentUrl.takeIf { it != DEFAULT_HOME_URL }
        val message = if (magnet != null) {
            BrowserDownloads.enqueueMagnetDownload(
                context = this,
                store = store,
                magnet = magnet,
                title = displayTitle,
                pageUrl = pageUrl,
                engine = engine,
                isPrivate = isPrivate,
            )
        } else if (engine == P2pDownloadEngine.EXTERNAL) {
            if (BrowserDownloads.openP2pWithExternalApp(this, target, "application/x-bittorrent")) {
                "已交给外部 BT 应用"
            } else {
                "未找到可处理此链接的 BT 应用"
            }
        } else {
            BrowserDownloads.enqueue(
                context = this,
                store = store,
                url = target,
                title = displayTitle,
                pageUrl = pageUrl,
                mimeType = "application/x-bittorrent",
                contentDisposition = null,
                referer = pageUrl,
                userAgent = buildUserAgent(effectiveDesktopMode(viewModel.uiState.value.currentUrl)),
                isPrivate = isPrivate,
            )
        }
        if (message.startsWith("未找到")) {
            val clipboard = getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("magnet", target))
        }
        val snackbar = Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG)
        if (engine == P2pDownloadEngine.INTERNAL && !message.startsWith("未找到")) {
            snackbar.setAction("下载列表") { openLibrary(LibraryMode.DOWNLOADS) }
        }
        snackbar.show()
    }

    private fun hasRecentAppNavigationIntent(): Boolean {
        return SystemClock.elapsedRealtime() - lastAppNavigationAtMillis < NAVIGATION_INTENT_WINDOW_MS
    }

    private fun hasRecentTrustedWebNavigation(): Boolean {
        return SystemClock.elapsedRealtime() - lastTrustedWebNavigationAtMillis < TRUSTED_WEB_NAVIGATION_WINDOW_MS
    }

    private fun hasRecentExplicitNavigation(): Boolean {
        return SystemClock.elapsedRealtime() - lastExplicitNavigationAtMillis < EXPLICIT_NAVIGATION_WINDOW_MS
    }

    private fun hasRecentVideoInteraction(): Boolean {
        return SystemClock.elapsedRealtime() - lastVideoInteractionAtMillis < VIDEO_INTERACTION_WINDOW_MS
    }

    private fun matchesExplicitNavigationTarget(targetPage: Uri): Boolean {
        if (!hasRecentExplicitNavigation()) {
            return false
        }
        val expected = lastExplicitNavigationUrl ?: return false
        val expectedUri = runCatching { Uri.parse(expected) }.getOrNull() ?: return false
        return comparableNavigationUrl(expectedUri) == comparableNavigationUrl(targetPage)
    }

    private fun comparableNavigationUrl(uri: Uri): String {
        return uri.buildUpon().fragment(null).build().toString()
    }

    private fun captureResourceCandidate(
        url: String,
        title: String?,
        pageUrl: String?,
        mimeType: String?,
        referer: String?,
        userAgent: String?,
        prioritizeForCurrentVideo: Boolean = false,
    ) {
        if (!ResourceTools.isInteresting(url, mimeType)) {
            return
        }
        viewModel.captureResource(
            url = url,
            title = title,
            pageUrl = pageUrl,
            mimeType = mimeType,
            referer = referer,
            userAgent = userAgent,
        )
        if (ResourceTools.isStream(url, mimeType)) {
            BrowserDownloads.cacheM3u8Playlist(
                context = this@MainActivity,
                url = url,
                referer = referer ?: pageUrl,
                userAgent = userAgent,
            )
        }
        if (ResourceTools.isDownloadableVideo(url, mimeType) &&
            !pageUrl.isNullOrBlank() &&
            pageUrl == viewModel.uiState.value.currentUrl
        ) {
            currentPageVideoResourceCache[url] = ResourceItem(
                id = currentPageVideoResourceCache[url]?.id ?: -(currentPageVideoResourceCache.size + 1).toLong(),
                title = preferredVideoTitleFor(title, url),
                url = url,
                pageUrl = pageUrl,
                mimeType = mimeType,
                referer = referer,
                userAgent = userAgent,
                category = ResourceTools.classify(url, mimeType),
                isStream = ResourceTools.isStream(url, mimeType),
                capturedAt = System.currentTimeMillis(),
            )
        }
        val isPlayableMedia = ResourceTools.isDownloadableVideo(url, mimeType)
        if (isPlayableMedia && (prioritizeForCurrentVideo || hasRecentVideoInteraction() || url == lastActiveVideoUrl)) {
            lastPreferredVideoResourceUrl = url
            title?.takeIf { it.isNotBlank() }?.let { lastPreferredVideoTitle = it }
        }
    }

    private fun recoverFromBlockedNavigation(view: WebView, blockedUrl: String) {
        val fallbackUrl = lastCommittedPageUrlForGuards.takeIf { it.isNotBlank() && it != blockedUrl }
            ?: currentPageUrlForGuards.takeIf { it.isNotBlank() && it != blockedUrl }
            ?: DEFAULT_HOME_URL
        view.post {
            view.stopLoading()
            val history = runCatching { view.copyBackForwardList() }.getOrNull()
            val currentHistoryUrl = history?.currentItem?.url
            when {
                currentHistoryUrl == blockedUrl && view.canGoBack() -> view.goBack()
                currentHistoryUrl == blockedUrl && fallbackUrl != DEFAULT_HOME_URL -> {
                    noteNavigationIntent()
                    view.loadUrl(fallbackUrl)
                }

                currentHistoryUrl == blockedUrl -> navigateTo(DEFAULT_HOME_URL, userInitiated = true)
                fallbackUrl != DEFAULT_HOME_URL -> {
                    currentPageUrlForGuards = fallbackUrl
                    lastCommittedPageUrlForGuards = fallbackUrl
                    viewModel.setAddressBarText(if (fallbackUrl == DEFAULT_HOME_URL) "" else fallbackUrl)
                    viewModel.updateNavigationState(view.canGoBack(), view.canGoForward())
                }
            }
        }
    }

    private fun currentGuardPageUri(): Uri? {
        val currentUrl = currentPageUrlForGuards.takeIf { it.isNotBlank() }
            ?: viewModel.uiState.value.currentUrl.takeIf { it.isNotBlank() }
            ?: return null
        return runCatching { Uri.parse(currentUrl) }.getOrNull()
    }

    private fun shouldBlockResourceRequest(targetUrl: String?, isMainFrame: Boolean = false): Boolean {
        val currentPage = currentGuardPageUri() ?: return false
        if (!effectiveAdBlockEnabled(currentPage.toString())) {
            return false
        }
        val targetUri = targetUrl?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return false
        val scheme = targetUri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return false
        }
        return adBlocker.shouldBlock(targetUri, currentPage, isMainFrame)
    }

    private fun shouldAbortMainFrameNavigation(targetUrl: String): Boolean {
        val targetUri = runCatching { Uri.parse(targetUrl) }.getOrNull() ?: return false
        val scheme = targetUri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return false
        }
        if (!effectiveAdBlockEnabled(targetUrl)) {
            return false
        }
        val currentPage = currentGuardPageUri() ?: return false
        return shouldBlockAutomaticRedirect(
            currentPage = currentPage,
            targetPage = targetUri,
            hasGesture = hasRecentAppNavigationIntent() || hasRecentTrustedWebNavigation() || matchesExplicitNavigationTarget(targetUri),
        )
    }

    private fun openPopupTarget(targetUrl: String) {
        val resolved = normalizeNavigableUrl(targetUrl)
        val uri = runCatching { Uri.parse(resolved) }.getOrNull() ?: return
        when (uri.scheme?.lowercase()) {
            "http", "https", "about" -> navigateTo(resolved, userInitiated = true)
            else -> runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
        }
    }

    private fun shouldAllowPopupTarget(targetUrl: String): Boolean {
        val resolved = normalizeNavigableUrl(targetUrl)
        val uri = runCatching { Uri.parse(resolved) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return false
        }
        val currentPage = currentGuardPageUri() ?: return false
        if (matchesExplicitNavigationTarget(uri)) {
            return !adBlocker.isAdLikeUrl(uri, currentPage)
        }
        if (adBlocker.isAdLikeUrl(uri, currentPage)) {
            return false
        }
        if (hasRecentVideoInteraction() && !isSameDocumentNavigation(currentPage, uri)) {
            return false
        }
        return sameSite(
            currentPage.host?.lowercase().orEmpty(),
            uri.host?.lowercase().orEmpty(),
        )
    }

    private fun normalizeNavigableUrl(targetUrl: String): String {
        val trimmed = targetUrl.trim()
        if (trimmed == DEFAULT_HOME_URL || hasExplicitScheme(trimmed)) {
            return trimmed
        }
        if (trimmed.startsWith("//")) {
            val currentScheme = currentPageUrlForGuards.takeIf { it.isNotBlank() }?.let(Uri::parse)?.scheme ?: "https"
            return "$currentScheme:$trimmed"
        }
        if (trimmed.startsWith("/") || trimmed.startsWith("./") || trimmed.startsWith("../")) {
            val base = currentPageUrlForGuards.takeIf { it.isNotBlank() } ?: return resolveInputToUrl(trimmed)
            return runCatching { URI(base).resolve(trimmed).toString() }.getOrElse { resolveInputToUrl(trimmed) }
        }
        return resolveInputToUrl(trimmed)
    }

    private fun shouldAllowInjectedNavigation(url: String?): Boolean {
        val target = url?.trim().orEmpty()
        if (target.isBlank()) {
            return false
        }
        if (target == "about:blank") {
            return true
        }
        val resolvedTarget = normalizeNavigableUrl(target)
        val targetUri = runCatching { Uri.parse(resolvedTarget) }.getOrNull() ?: return false
        val scheme = targetUri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return false
        }
        val currentPage = currentGuardPageUri() ?: return false
        val currentHost = currentPage.host?.lowercase() ?: return false
        val targetHost = targetUri.host?.lowercase() ?: return false
        if (matchesExplicitNavigationTarget(targetUri)) {
            return !adBlocker.isAdLikeUrl(targetUri, currentPage)
        }
        if (hasRecentAppNavigationIntent()) {
            return true
        }
        val lowerTarget = resolvedTarget.lowercase()
        if (ALLOW_AUTOMATIC_REDIRECT_KEYWORDS.any { lowerTarget.contains(it) }) {
            return true
        }
        if (hasRecentVideoInteraction() && !isSameDocumentNavigation(currentPage, targetUri)) {
            return false
        }
        if (adBlocker.isAdLikeUrl(targetUri, currentPage) || SUSPICIOUS_REDIRECT_KEYWORDS.any { lowerTarget.contains(it) }) {
            return false
        }
        if (isSuspiciousSameSiteNavigation(currentPage, targetUri)) {
            return false
        }
        return !adBlocker.isAdLikeUrl(targetUri, currentPage) && sameSite(currentHost, targetHost)
    }

    private fun shouldBlockAutomaticRedirect(
        currentPage: Uri,
        targetPage: Uri,
        hasGesture: Boolean,
    ): Boolean {
        if (matchesExplicitNavigationTarget(targetPage)) {
            return adBlocker.isAdLikeUrl(targetPage, currentPage)
        }
        if (hasGesture) {
            return false
        }
        val currentHost = currentPage.host?.lowercase() ?: return false
        val targetHost = targetPage.host?.lowercase() ?: return false
        val targetUrl = targetPage.toString().lowercase()
        if (isGoogleRelatedDomain(targetHost) || isGoogleRelatedDomain(currentHost)) {
            return false
        }
        if (ALLOW_AUTOMATIC_REDIRECT_KEYWORDS.any { targetUrl.contains(it) }) {
            return false
        }
        if (adBlocker.isAdLikeUrl(targetPage, currentPage)) {
            return true
        }
        if (SUSPICIOUS_REDIRECT_KEYWORDS.any { targetUrl.contains(it) }) {
            return true
        }
        if (isSuspiciousSameSiteNavigation(currentPage, targetPage)) {
            return true
        }
        if (currentHost == targetHost || sameSite(currentHost, targetHost)) {
            return false
        }
        return true
    }

    private fun shouldAllowGestureNavigation(currentPage: Uri, targetPage: Uri): Boolean {
        if (matchesExplicitNavigationTarget(targetPage)) {
            return !adBlocker.isAdLikeUrl(targetPage, currentPage)
        }
        if (adBlocker.isAdLikeUrl(targetPage, currentPage)) {
            return false
        }
        if (hasRecentVideoInteraction() && !isSameDocumentNavigation(currentPage, targetPage)) {
            return false
        }
        return true
    }

    private fun currentPageVideoResources(): List<ResourceItem> {
        return currentPageVideoResourceCache.values
            .filter { ResourceTools.isDownloadableVideo(it.url, it.mimeType) }
            .distinctBy { it.url }
            .sortedWith(
                compareBy<ResourceItem> {
                    when {
                        it.url == lastPreferredVideoResourceUrl -> -2
                        it.url == lastActiveVideoUrl -> -1
                        it.capturedAt >= lastVideoInteractionAtEpochMillis && hasRecentVideoInteraction() -> 0
                        it.isStream -> 1
                        it.category.value == "stream" -> 2
                        it.category.value == "video" -> 3
                        else -> 4
                    }
                }.thenByDescending { it.capturedAt },
            )
    }

    private fun isSuspiciousSameSiteNavigation(currentPage: Uri, targetPage: Uri): Boolean {
        val currentHost = currentPage.host?.lowercase().orEmpty()
        val targetHost = targetPage.host?.lowercase().orEmpty()
        if (!sameSite(currentHost, targetHost)) {
            return false
        }
        val currentPath = currentPage.path.orEmpty().trimEnd('/')
        val targetPath = targetPage.path.orEmpty().trimEnd('/')
        if (currentPath.isBlank() || targetPath.isBlank() || currentPath == targetPath) {
            return false
        }
        val currentSection = currentPath.trimStart('/').substringBefore('/')
        val targetSection = targetPath.trimStart('/').substringBefore('/')
        if (currentSection != "movie") {
            return false
        }
        return targetSection == "movie" || targetSection == "flim" || targetSection == "sortlist"
    }

    private fun isSameDocumentNavigation(currentPage: Uri, targetPage: Uri): Boolean {
        val currentHost = currentPage.host?.lowercase().orEmpty()
        val targetHost = targetPage.host?.lowercase().orEmpty()
        if (!sameSite(currentHost, targetHost)) {
            return false
        }
        return currentPage.path.orEmpty().trimEnd('/') == targetPage.path.orEmpty().trimEnd('/')
    }

    private fun sameSite(leftHost: String, rightHost: String): Boolean {
        return effectiveSite(leftHost) == effectiveSite(rightHost)
    }

    private fun effectiveSite(host: String): String {
        val parts = host.split('.')
        return if (parts.size >= 2) {
            parts.takeLast(2).joinToString(".")
        } else {
            host
        }
    }

    private data class BrowserTab(
        val id: Long,
        var title: String,
        var url: String,
        val webView: WebView,
        var thumbnail: Bitmap? = null,
        var frozen: Boolean = false,
    )

    private data class BrowserMenuAction(
        val iconRes: Int,
        val label: String,
        val checked: Boolean = false,
        val onClick: () -> Unit,
    )

    private data class BookmarkFolderOption(
        val id: Long,
        val label: String,
    )

    private data class SuggestionEntry(
        val text: String,
        val isHistory: Boolean,
    )

    private data class YouTubeVideoFormat(
        val url: String,
        val mimeType: String,
        val quality: String,
        val bitrate: Int,
        val hasAudio: Boolean,
    )

    private fun extractYouTubeFormats() {
        val script = """
            (function() {
              try {
                var formats = [];
                var response = null;
                if (window.ytInitialPlayerResponse) {
                  response = window.ytInitialPlayerResponse;
                } else if (window.yt && window.yt.player && window.yt.player.getPlayerResponse) {
                  response = window.yt.player.getPlayerResponse();
                } else {
                  var scripts = document.querySelectorAll('script');
                  for (var i = 0; i < scripts.length; i++) {
                    var text = scripts[i].textContent || scripts[i].innerHTML || '';
                    var match = text.match(/(?:var\s+)?ytInitialPlayerResponse\s*=\s*({[\s\S]+?});/);
                    if (match && match[1]) {
                      try { response = JSON.parse(match[1]); } catch(e) {}
                      if (response && response.streamingData) break;
                    }
                    match = text.match(/(?:window\.)?ytInitialPlayerResponse\s*=\s*({[\s\S]+?});/);
                    if (match && match[1]) {
                      try { response = JSON.parse(match[1]); } catch(e) {}
                      if (response && response.streamingData) break;
                    }
                  }
                }
                if (!response || !response.streamingData) return JSON.stringify([]);
                var streamData = response.streamingData;
                var seenUrls = {};
                var addFormat = function(f, type) {
                  if (!f) return;
                  var url = f.url || '';
                  var cipher = f.signatureCipher || f.cipher || '';
                  if (!url && !cipher) return;
                  var key = url || cipher;
                  if (seenUrls[key]) return;
                  seenUrls[key] = true;
                  var mime = f.mimeType || '';
                  if (type === 'adaptive' && mime.indexOf('audio/') === 0) return;
                  formats.push({
                    url: url,
                    cipher: cipher,
                    mimeType: mime,
                    quality: f.qualityLabel || f.quality || (f.height ? f.height + 'p' : ''),
                    bitrate: f.bitrate || 0,
                    fps: f.fps || 0,
                    type: type,
                    hasAudio: type === 'progressive' || (mime.indexOf('video/') >= 0 && f.audioChannels > 0)
                  });
                };
                (streamData.formats || []).forEach(function(f) { addFormat(f, 'progressive'); });
                (streamData.adaptiveFormats || []).forEach(function(f) { addFormat(f, 'adaptive'); });
                formats.sort(function(a, b) {
                  if (a.hasAudio !== b.hasAudio) return a.hasAudio ? -1 : 1;
                  return (b.bitrate || 0) - (a.bitrate || 0);
                });
                return JSON.stringify(formats);
              } catch(e) { return JSON.stringify([]); }
            })();
        """.trimIndent()
        currentWebView().evaluateJavascript(script) { rawResult ->
            val decoded = decodeJavascriptStringResult(rawResult)
            if (decoded.isBlank() || decoded == "[]") {
                binding.root.postDelayed({ extractYouTubeFormats() }, 2000L)
                return@evaluateJavascript
            }
            val list = runCatching { JSONArray(decoded) }.getOrNull() ?: return@evaluateJavascript
            youTubeVideoFormats.clear()
            val state = viewModel.uiState.value
            for (i in 0 until list.length()) {
                val obj = list.optJSONObject(i) ?: continue
                val directUrl = obj.optString("url").takeIf { it.isNotBlank() }
                val cipher = obj.optString("cipher").takeIf { it.isNotBlank() }
                val resolvedUrl = if (!directUrl.isNullOrBlank() && directUrl.startsWith("http")) directUrl
                    else ResourceTools.decodeYouTubeCipher(cipher ?: directUrl.orEmpty()) ?: continue
                val mimeType = obj.optString("mimeType").takeIf { it.isNotBlank() }
                val quality = obj.optString("quality").takeIf { it.isNotBlank() } ?: "Unknown"
                val hasAudio = obj.optBoolean("hasAudio")
                val label = if (hasAudio) "$quality (音视频)" else "$quality (仅视频)"
                youTubeVideoFormats += YouTubeVideoFormat(
                    url = resolvedUrl, mimeType = mimeType ?: "video/mp4",
                    quality = quality, bitrate = obj.optInt("bitrate"), hasAudio = hasAudio,
                )
                lifecycleScope.launch(Dispatchers.IO) {
                    store.upsertResource(
                        title = label, url = resolvedUrl, pageUrl = state.currentUrl,
                        mimeType = mimeType, referer = state.currentUrl,
                        userAgent = buildUserAgent(effectiveDesktopMode(state.currentUrl)),
                        category = FileCategory.VIDEO, isStream = false,
                    )
                }
            }
        }
    }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_USER_INITIATED = "user_initiated"
        const val EXTRA_CLOUD_MAGNET = "cloud_magnet"
        const val EXTRA_INLINE_HTML = "inline_html"
        const val EXTRA_FINISH_ON_BACK = "finish_on_back"
        private const val JS_BRIDGE_NAME = "AuroraBridge"
        private const val CLOUD_PLAY_INJECT_RETRY_MS = 700L
        private const val CLOUD_PLAY_INJECT_MAX_ATTEMPTS = 8
        private const val TORRENT_PROMPT_DEBOUNCE_MS = 1800L
        private const val NAVIGATION_INTENT_WINDOW_MS = 3500L
        private const val TRUSTED_WEB_NAVIGATION_WINDOW_MS = 1200L
        private const val EXPLICIT_NAVIGATION_WINDOW_MS = 1800L
        private const val VIDEO_INTERACTION_WINDOW_MS = 2200L
        private const val HOME_TOP_SITE_LIMIT = 18
        private const val BLOCKED_NAVIGATION_DEBOUNCE_MS = 1500L
        private const val REDIRECT_CONFIRM_DEBOUNCE_MS = 3000L
        private const val MAX_SIMULTANEOUS_REDIRECTS = 3
        private const val MENU_COLUMNS = 4
        private const val MENU_ROWS = 2
        private const val MENU_ITEMS_PER_PAGE = MENU_COLUMNS * MENU_ROWS
        private const val MAX_RECENT_SEARCH_SUGGESTIONS = 8
        private const val MAX_TABS = 20
        private const val HOME_SITE_COLUMNS = 6
        private const val HOME_SITE_TILE_WIDTH_DP = 44
        private const val HOME_SITE_ICON_SIZE_DP = 34
        private const val HOME_SITE_ICON_RADIUS_DP = 12
        private const val HOME_SITE_COLUMN_GAP_DP = 4
        private const val HOME_SITE_ROW_GAP_DP = 12
        private const val TAB_THUMBNAIL_BITMAP_WIDTH = 360
        private const val TAB_THUMBNAIL_BITMAP_HEIGHT = 238
        private val SUSPICIOUS_REDIRECT_KEYWORDS = listOf(
            "doubleclick",
            "googlesyndication",
            "adurl",
            "adclick",
            "advert",
            "redirect",
            "jump",
            "track",
            "trk",
            "utm_",
            "gclid",
            "fbclid",
            "yclid",
            "openapp",
            "downloadapp",
            "landing",
            "campaign",
            "pop",
            "banner",
            "interstitial",
            "affiliate",
            "sponsor",
            "promo",
        )
        private val ALLOW_AUTOMATIC_REDIRECT_KEYWORDS = listOf(
            "oauth",
            "signin",
            "login",
            "auth",
            "callback",
            "passport",
            "sso",
        )

        private val GOOGLE_DOMAINS = setOf(
            "google.com", "google.com.hk", "google.co.jp", "google.co.uk", "google.de",
            "google.fr", "google.it", "google.es", "google.ca", "google.com.au",
            "googleapis.com", "gstatic.com", "googleusercontent.com",
            "youtube.com", "youtu.be", "ytimg.com",
            "googleadservices.com", "googlesyndication.com",
            "googletagmanager.com", "google-analytics.com",
            "recaptcha.net", "gstatic.cn",
        )

        private fun isGoogleRelatedDomain(host: String): Boolean {
            return GOOGLE_DOMAINS.any { host == it || host.endsWith(".$it") }
        }

        private val SCHEME_REGEX = Regex("^[a-zA-Z][a-zA-Z\\d+.-]*:(//)?")
        private val IPV4_REGEX = Regex("""\d{1,3}(\.\d{1,3}){3}""")

        private val SITE_ACCENT_PALETTE = intArrayOf(
            0xFFE85D5D.toInt(), 0xFF2F7CF6.toInt(), 0xFF16A085.toInt(),
            0xFFF39C12.toInt(), 0xFF7F56D9.toInt(), 0xFF3949AB.toInt(),
            0xFF00897B.toInt(), 0xFFD35454.toInt(),
        )
    }
}
