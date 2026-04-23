package com.fenglan.aurorabrowser

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.Message
import android.text.InputType
import android.util.TypedValue
import android.view.ViewGroup
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebSettings
import android.webkit.WebSettings.getDefaultUserAgent
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListPopupWindow
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import android.widget.FrameLayout.LayoutParams as FrameLayoutLayoutParams
import android.widget.LinearLayout.LayoutParams as LinearLayoutLayoutParams
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.isVisible
import androidx.core.view.setPadding
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.card.MaterialCardView
import com.google.android.material.snackbar.Snackbar
import com.fenglan.aurorabrowser.browser.AdBlocker
import com.fenglan.aurorabrowser.browser.BrowserDownloads
import com.fenglan.aurorabrowser.browser.BrowserPreferences
import com.fenglan.aurorabrowser.browser.BrowserThemePalette
import com.fenglan.aurorabrowser.browser.BrowserThemeTools
import com.fenglan.aurorabrowser.browser.PageTranslator
import com.fenglan.aurorabrowser.browser.PageTextSegment
import com.fenglan.aurorabrowser.browser.ResourceTools
import com.fenglan.aurorabrowser.browser.SearchSuggestions
import com.fenglan.aurorabrowser.browser.SiteIconStore
import com.fenglan.aurorabrowser.browser.BrowserViewModel
import com.fenglan.aurorabrowser.data.BrowserStore
import com.fenglan.aurorabrowser.databinding.ActivityMainBinding
import com.fenglan.aurorabrowser.databinding.DialogBrowserMenuBinding
import com.fenglan.aurorabrowser.model.BrowserThemeMode
import com.fenglan.aurorabrowser.model.BrowserUiState
import com.fenglan.aurorabrowser.model.DEFAULT_HOME_URL
import com.fenglan.aurorabrowser.model.HomeSiteItem
import com.fenglan.aurorabrowser.model.LibraryMode
import com.fenglan.aurorabrowser.model.ResourceItem
import com.fenglan.aurorabrowser.model.SearchEngine
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import kotlin.coroutines.resume
import kotlin.math.absoluteValue

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val viewModel: BrowserViewModel by viewModels()
    private val adBlocker by lazy { AdBlocker(this) }
    private val store by lazy { BrowserStore(this) }
    private val preferences by lazy { BrowserPreferences(this) }
    private val siteIconStore by lazy { SiteIconStore(this) }
    private lateinit var mobileUserAgent: String
    private val tabs = mutableListOf<BrowserTab>()
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
    @Volatile private var lastTrustedWebNavigationAtMillis: Long = 0L
    @Volatile private var lastExplicitNavigationUrl: String? = null
    @Volatile private var lastExplicitNavigationAtMillis: Long = 0L
    @Volatile private var lastVideoInteractionAtMillis: Long = 0L
    @Volatile private var lastVideoInteractionAtEpochMillis: Long = 0L
    @Volatile private var lastBlockedMainFrameUrl: String? = null
    @Volatile private var lastBlockedMainFrameAtMillis: Long = 0L
    private val currentPageVideoResourceCache = linkedMapOf<String, ResourceItem>()
    private val iconFetchInFlight = linkedSetOf<String>()
    private var mostVisitedSites: List<TopSiteItem> = emptyList()
    private var customHomeSites: List<HomeSiteItem> = emptyList()
    private var currentThemePalette: BrowserThemePalette = BrowserThemeTools.paletteFor(BrowserThemeMode.LIGHT)
    private var lastAppliedThemeMode: BrowserThemeMode? = null
    private var lastHomeCustomSignature: String? = null
    private var lastHomeTopSignature: String? = null
    private var browserMenuDialog: BottomSheetDialog? = null
    private var translationJob: Job? = null
    private var translationEnabled = false
    private var translatedPageUrl: String? = null
    @Volatile private var lastCredentialSaveSignature: String? = null
    @Volatile private var lastCredentialSaveAtMillis: Long = 0L
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mobileUserAgent = getDefaultUserAgent(this)
        ensureInitialTab()

        setupWebView()
        setupFindBar()
        setupPrimaryActions()
        observeState()
        handleBackNavigation()
        updateSearchEngineLabel()
        refreshCustomHomeSites()
        refreshMostVisitedSites()
        applyBrowserTheme(viewModel.uiState.value)

        val initialUrl = intent.getStringExtra(EXTRA_URL) ?: homeDestinationUrl()
        if (intent.getBooleanExtra(EXTRA_USER_INITIATED, false) || initialUrl != DEFAULT_HOME_URL) {
            noteNavigationIntent()
        }
        if (savedInstanceState == null) {
            navigateTo(initialUrl)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val targetUrl = intent.getStringExtra(EXTRA_URL)
        if (intent.getBooleanExtra(EXTRA_USER_INITIATED, false) || !targetUrl.isNullOrBlank()) {
            noteNavigationIntent()
        }
        if (!targetUrl.isNullOrBlank()) {
            navigateTo(targetUrl)
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(downloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(downloadReceiver, filter)
        }
    }

    override fun onStop() {
        runCatching { unregisterReceiver(downloadReceiver) }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        syncPreferenceDrivenUi()
        binding.webView.onResume()
    }

    override fun onPause() {
        binding.webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        browserMenuDialog?.dismiss()
        suggestionsJob?.cancel()
        translationJob?.cancel()
        binding.webView.apply {
            stopLoading()
            webChromeClient = null
            removeJavascriptInterface(JS_BRIDGE_NAME)
            destroy()
        }
        runCatching { barcodeScanner.close() }
        super.onDestroy()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        binding.webView.apply {
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
            setBackgroundColor(getColor(R.color.aurora_surface))

            addJavascriptInterface(PageBridge(), JS_BRIDGE_NAME)
            setFindListener { activeMatchOrdinal, numberOfMatches, isDoneCounting ->
                if (isDoneCounting) {
                    viewModel.updateFindResult(
                        activeOrdinal = if (numberOfMatches == 0) 0 else activeMatchOrdinal + 1,
                        totalMatches = numberOfMatches,
                    )
                }
            }
            setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
                val message = BrowserDownloads.enqueue(
                    context = this@MainActivity,
                    store = store,
                    url = url,
                    title = viewModel.uiState.value.pageTitle,
                    pageUrl = this.url,
                    mimeType = mimeType,
                    contentDisposition = contentDisposition,
                    referer = this.url,
                    userAgent = userAgent,
                    declaredSize = contentLength.takeIf { it > 0 },
                )
                Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
            }

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    viewModel.onProgressChanged(newProgress)
                    viewModel.updateNavigationState(this@apply.canGoBack(), this@apply.canGoForward())
                }

                override fun onReceivedTitle(view: WebView?, title: String?) {
                    viewModel.onTitleChanged(title)
                }

                override fun onReceivedIcon(view: WebView?, icon: Bitmap?) {
                    val pageUrl = view?.url ?: return
                    val bitmap = icon ?: return
                    saveSiteIcon(pageUrl, bitmap)
                }

                override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
                    val targetUrl = view?.hitTestResult?.extra
                    if (isUserGesture && !targetUrl.isNullOrBlank() && shouldAllowPopupTarget(targetUrl)) {
                        noteTrustedWebNavigation()
                        openPopupTarget(targetUrl)
                    } else {
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
                    if (request.isForMainFrame && currentPageUri != null &&
                        shouldBlockAutomaticRedirect(
                            currentPage = currentPageUri,
                            targetPage = uri,
                            hasGesture = gestureAllowed || hasRecentAppNavigationIntent() || hasRecentTrustedWebNavigation(),
                        )
                    ) {
                        val message = if (request.hasGesture()) "已拦截可疑点击跳转" else "已拦截自动广告跳转"
                        showBlockedNavigationMessage(message, uri.toString())
                        return true
                    }
                    val scheme = uri.scheme?.lowercase()
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
                    if (view != null && shouldAbortMainFrameNavigation(targetUrl)) {
                        showBlockedNavigationMessage("已阻止网页自动跳转", targetUrl)
                        recoverFromBlockedNavigation(view, targetUrl)
                        return
                    }
                    currentPageUrlForGuards = url ?: DEFAULT_HOME_URL
                    lastActiveVideoUrl = null
                    lastPreferredVideoResourceUrl = null
                    lastPreferredVideoTitle = null
                    translatedPageUrl = null
                    currentPageVideoResourceCache.clear()
                    url?.takeIf { it.isNotBlank() && it != DEFAULT_HOME_URL }?.let { pageUrl ->
                        lifecycleScope.launch(Dispatchers.IO) {
                            store.deleteResourcesForPage(pageUrl)
                        }
                    }
                    favicon?.let { saveSiteIcon(targetUrl, it) }
                    viewModel.onPageStarted(url)
                    viewModel.updateNavigationState(this@apply.canGoBack(), this@apply.canGoForward())
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    currentPageUrlForGuards = url ?: currentPageUrlForGuards
                    lastCommittedPageUrlForGuards = url ?: lastCommittedPageUrlForGuards
                    viewModel.onPageFinished(url, view?.title)
                    viewModel.updateNavigationState(this@apply.canGoBack(), this@apply.canGoForward())
                    injectResourceSniffer()
                    syncInjectedPageTheme(viewModel.uiState.value.settings.themeMode.isDark)
                    url?.takeIf { it != DEFAULT_HOME_URL }?.let(::fetchBestPageIcon)
                    if (translationEnabled && !url.isNullOrBlank() && url != DEFAULT_HOME_URL) {
                        binding.root.postDelayed({ translateCurrentPage() }, 260L)
                    }
                    injectCredentialAssistant()
                    scheduleCredentialAutofill(url)
                    binding.root.postDelayed({ refreshMostVisitedSites() }, 180L)
                }

                override fun onPageCommitVisible(view: WebView?, url: String?) {
                    currentPageUrlForGuards = url ?: currentPageUrlForGuards
                    lastCommittedPageUrlForGuards = url ?: lastCommittedPageUrlForGuards
                }

                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): android.webkit.WebResourceResponse? {
                    val target = request?.url
                    val state = viewModel.uiState.value
                    if (state.settings.adBlockEnabled && shouldBlockResourceRequest(target?.toString(), request?.isForMainFrame == true)) {
                        viewModel.incrementBlockedCount()
                        return adBlocker.emptyResponse()
                    }

                    val url = target?.toString() ?: return null
                    val headers = request.requestHeaders
                    captureResourceCandidate(
                        url = url,
                        title = state.pageTitle,
                        pageUrl = state.currentUrl,
                        mimeType = headers["Accept"],
                        referer = headers["Referer"] ?: state.currentUrl,
                        userAgent = headers["User-Agent"],
                        prioritizeForCurrentVideo = hasRecentVideoInteraction(),
                    )
                    return null
                }
            }
        }

        applyDesktopMode(viewModel.uiState.value.settings.desktopMode, reload = false)
        applyNightModeToWebView(viewModel.uiState.value.settings.themeMode.isDark)
    }

    private fun setupPrimaryActions() {
        binding.goButton.setOnClickListener { loadFromAddressBar() }
        binding.homeGoButton.setOnClickListener { loadFromHomeSearch() }
        binding.homeScanButton.setOnClickListener { showQrEntryOptions() }
        binding.homeEngineButton.setOnClickListener { showSearchEnginePicker() }
        binding.bookmarkButton.setOnClickListener { showBookmarkEditorDialog() }
        binding.homeBookmarksButton.setOnClickListener { openLibrary(LibraryMode.BOOKMARKS) }
        binding.homeHistoryButton.setOnClickListener { openLibrary(LibraryMode.HISTORY) }
        binding.homeResourcesButton.setOnClickListener { openLibrary(LibraryMode.RESOURCES) }
        binding.homeDownloadsButton.setOnClickListener { openLibrary(LibraryMode.DOWNLOADS) }
        binding.homeAddSiteButton.setOnClickListener { showAddHomeSiteDialog() }
        binding.homeIncognitoButton.setOnClickListener {
            requestIncognitoMode(!viewModel.uiState.value.incognito)
        }
        binding.backButton.setOnClickListener {
            if (binding.webView.canGoBack()) {
                noteNavigationIntent()
                binding.webView.goBack()
            }
        }
        binding.forwardButton.setOnClickListener {
            if (binding.webView.canGoForward()) {
                noteNavigationIntent()
                binding.webView.goForward()
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
            if (hasFocus) {
                showRecentSearches(binding.homeSearchInput, binding.homeSearchInput.text?.toString().orEmpty())
            } else {
                dismissRecentSearches()
            }
        }
        binding.homeSearchInput.setOnClickListener {
            showRecentSearches(binding.homeSearchInput, binding.homeSearchInput.text?.toString().orEmpty())
        }
    }

    private fun setupFindBar() {
        binding.findInput.doAfterTextChanged { editable ->
            val keyword = editable?.toString().orEmpty().trim()
            if (keyword.isBlank()) {
                binding.webView.clearMatches()
                viewModel.clearFindResult()
            } else {
                binding.webView.findAllAsync(keyword)
            }
        }
        binding.findPrevButton.setOnClickListener { binding.webView.findNext(false) }
        binding.findNextButton.setOnClickListener { binding.webView.findNext(true) }
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
        binding.homePanel.isVisible = showHome
        binding.webView.isVisible = !showHome
        binding.toolbar.isVisible = false
        binding.addressBarContainer.isVisible = !showHome
        if (showHome && binding.findBar.isVisible) {
            hideFindBar()
        }

        binding.toolbar.title = state.pageTitle.takeIf { it.isNotBlank() } ?: getString(R.string.browser_title)
        binding.toolbar.subtitle = buildSubtitle(state)
        binding.bookmarkButton.setImageResource(
            if (state.isBookmarked) R.drawable.ic_bookmark_filled else R.drawable.ic_bookmark_outline,
        )
        binding.loadingProgress.isVisible = !showHome && state.isLoading
        binding.loadingProgress.progress = state.loadingProgress
        binding.backButton.isEnabled = state.canGoBack
        binding.forwardButton.isEnabled = state.canGoForward
        binding.overlayTip.isVisible = state.incognito
        binding.findStatus.text = "${state.findMatchOrdinal}/${state.findMatchCount}"
        binding.homeIncognitoButton.alpha = if (state.incognito) 1f else 0.55f
        updateTabsCountBadge()
        renderHomeSectionsIfNeeded(state)
        updateCurrentTab(state)
    }

    private fun buildSubtitle(state: BrowserUiState): String {
        val parts = mutableListOf("Chromium WebView 内核")
        if (state.incognito) parts += "无痕"
        if (state.settings.desktopMode) parts += "桌面UA"
        if (state.settings.themeMode != BrowserThemeMode.LIGHT) parts += state.settings.themeMode.label
        if (translationEnabled && translatedPageUrl == state.currentUrl) parts += "双语翻译"
        if (state.settings.adBlockEnabled) parts += "拦截${state.blockedRequestCount}"
        return parts.joinToString(" · ")
    }

    private fun handleBackNavigation() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    when {
                        binding.findBar.isVisible -> hideFindBar()
                        binding.webView.canGoBack() -> binding.webView.goBack()
                        !isHomeScreen(viewModel.uiState.value) -> navigateHome()
                        else -> finish()
                    }
                }
            },
        )
    }

    private fun showBrowserMenu() {
        val state = viewModel.uiState.value
        val currentVideoResources = currentPageVideoResources()
        browserMenuDialog?.dismiss()
        val dialog = BottomSheetDialog(this)
        val menuBinding = DialogBrowserMenuBinding.inflate(layoutInflater)
        browserMenuDialog = dialog
        dialog.setContentView(menuBinding.root)
        menuBinding.root.setBackgroundColor(currentThemePalette.surface)
        menuBinding.menuTitleText.setTextColor(currentThemePalette.text)
        menuBinding.menuHintText.setTextColor(currentThemePalette.textMuted)
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
            }
        }
        dialog.show()
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
            actions += BrowserMenuAction(R.drawable.ic_find, "页内查找") {
                binding.findBar.isVisible = true
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
            checked = state.settings.themeMode.isDark,
        ) {
            val nextMode = if (state.settings.themeMode.isDark) BrowserThemeMode.LIGHT else BrowserThemeMode.DARK
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
                invalidateHomeSections()
                renderHomeSectionsIfNeeded(
                    viewModel.uiState.value.copy(settings = state.settings.copy(showMostVisitedSites = false)),
                    force = true,
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
        MaterialAlertDialogBuilder(this)
            .setTitle("二维码跳转")
            .setItems(arrayOf("拍照扫码", "相册识别")) { _, which ->
                when (which) {
                    0 -> qrCameraLauncher.launch(null)
                    1 -> qrGalleryLauncher.launch("image/*")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showThemePicker() {
        val modes = BrowserThemeMode.entries
        val state = viewModel.uiState.value
        val labels = modes.map { it.label }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("浏览器主题")
            .setSingleChoiceItems(labels, modes.indexOf(state.settings.themeMode)) { dialog, which ->
                dialog.dismiss()
                applyThemeMode(modes[which])
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showDownloadLocationDialog() {
        val input = EditText(this).apply {
            setSingleLine()
            hint = "Aurora/Video"
            setText(preferences.getDownloadSubdirectory())
            setSelection(text?.length ?: 0)
        }
        MaterialAlertDialogBuilder(this)
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
            .show()
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
            MaterialAlertDialogBuilder(this)
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
                .show()
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
        MaterialAlertDialogBuilder(this)
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
            .show()
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
                val segments = PageTranslator.parseCollectedSegments(decodeJavascriptStringResult(rawResult))
                if (segments.isEmpty()) {
                    translatedPageUrl = null
                    if (force) {
                        Snackbar.make(binding.root, "当前页面没有可翻译文本", Snackbar.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                val translation = withContext(Dispatchers.IO) {
                    PageTranslator.translate(
                        engine = viewModel.uiState.value.settings.translationEngine,
                        segments = segments,
                        preferences = preferences,
                    )
                }
                if (pageUrl != viewModel.uiState.value.currentUrl) {
                    return@launch
                }
                binding.webView.evaluateJavascript(PageTranslator.buildApplyScript(translation.items), null)
                translatedPageUrl = pageUrl
                translationEnabled = true
                val message = when {
                    translation.usedFallback -> "微软翻译暂不可用，已回退到谷歌翻译"
                    else -> "${translation.engineUsed.label}双语翻译已应用"
                }
                Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
            } catch (error: Throwable) {
                translatedPageUrl = null
                Snackbar.make(binding.root, "网页翻译失败", Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    private fun clearPageTranslation() {
        translatedPageUrl = null
        binding.webView.evaluateJavascript(PageTranslator.buildClearScript(), null)
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
            binding.webView.evaluateJavascript(script) { result ->
                if (continuation.isActive) {
                    continuation.resume(result ?: "null")
                }
            }
        }
    }

    private fun applyThemeMode(mode: BrowserThemeMode) {
        viewModel.setThemeMode(mode)
        AppCompatDelegate.setDefaultNightMode(
            if (mode.isDark) {
                AppCompatDelegate.MODE_NIGHT_YES
            } else {
                AppCompatDelegate.MODE_NIGHT_NO
            },
        )
        binding.root.post { applyBrowserTheme(viewModel.uiState.value) }
    }

    private fun downloadCurrentPageHtml() {
        val state = viewModel.uiState.value
        if (isHomeScreen(state)) {
            Toast.makeText(this, "当前页面没有可保存的 HTML", Toast.LENGTH_SHORT).show()
            return
        }
        val script = "(function(){try{return '<!DOCTYPE html>\\n' + document.documentElement.outerHTML;}catch(e){return '';}})()"
        binding.webView.evaluateJavascript(script) { rawResult ->
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
        binding.webView.settings.userAgentString = buildUserAgent(enabled)
        binding.webView.settings.useWideViewPort = enabled
        binding.webView.settings.loadWithOverviewMode = enabled
        if (reload && binding.webView.url != null) {
            binding.webView.reload()
        }
    }

    private fun applyNightModeToWebView(enabled: Boolean) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(binding.webView.settings, enabled)
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            WebSettingsCompat.setForceDark(
                binding.webView.settings,
                if (enabled) WebSettingsCompat.FORCE_DARK_ON else WebSettingsCompat.FORCE_DARK_OFF,
            )
        }
        syncInjectedPageTheme(enabled)
    }

    private fun applyBrowserTheme(state: BrowserUiState) {
        val settings = state.settings
        val palette = BrowserThemeTools.paletteFor(settings.themeMode)
        currentThemePalette = palette
        if (lastAppliedThemeMode == settings.themeMode) {
            return
        }
        lastAppliedThemeMode = settings.themeMode

        binding.root.setBackgroundColor(palette.surface)
        binding.addressBarContainer.setBackgroundColor(palette.surface)
        binding.findBar.setBackgroundColor(palette.surfaceAlt)
        binding.loadingProgress.progressTintList = ColorStateList.valueOf(palette.accent)
        binding.loadingProgress.progressBackgroundTintList = ColorStateList.valueOf(palette.line)
        binding.overlayTip.setTextColor(palette.textMuted)
        binding.findStatus.setTextColor(palette.textMuted)
        binding.webView.setBackgroundColor(palette.surface)

        applyInputLayoutTheme(binding.addressInputLayout, binding.addressInput, palette)
        applyInputLayoutTheme(binding.findInputLayout, binding.findInput, palette)

        binding.homeSearchCard.setCardBackgroundColor(palette.surfaceAlt)
        binding.homeSearchCard.strokeColor = palette.line
        binding.bottomBarCard.setCardBackgroundColor(palette.surfaceAlt)
        binding.bottomBarCard.strokeColor = palette.line
        applyPillBackground(binding.homeEngineButton, palette)

        binding.homeSearchInput.setTextColor(palette.text)
        binding.homeSearchInput.setHintTextColor(palette.textMuted)
        binding.homeCustomSitesTitle.setTextColor(palette.text)
        binding.homeTopSitesTitle.setTextColor(palette.text)
        binding.tabsCountText.setBackgroundColor(palette.accent)
        binding.tabsCountText.setTextColor(Color.WHITE)
        binding.homeBackgroundScrim.setBackgroundColor(palette.homeOverlay)
        binding.homeBackgroundImage.isVisible = false
        binding.homeBackgroundImage.setImageDrawable(null)
        binding.homeBackgroundScrim.isVisible = false

        listOf(
            binding.goButton,
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

        applyNightModeToWebView(settings.themeMode.isDark)
        invalidateHomeSections()
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
        binding.webView.evaluateJavascript(script, null)
    }

    private fun injectCredentialAssistant() {
        val script = """
            (function() {
              if (!window.$JS_BRIDGE_NAME) {
                return;
              }
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
                    try {
                      window.$JS_BRIDGE_NAME.saveCredential(window.location.href, username, password);
                    } catch (e) {}
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
        binding.webView.evaluateJavascript(script, null)
    }

    private fun scheduleCredentialAutofill(pageUrl: String?) {
        val targetUrl = pageUrl?.takeIf { it.isNotBlank() && it != DEFAULT_HOME_URL } ?: return
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
                binding.webView.evaluateJavascript(script, null)
            }
        }
    }

    private fun maybeSaveCredential(pageUrl: String?, username: String?, password: String?) {
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
        cookieManager.setAcceptThirdPartyCookies(binding.webView, !enabled)
        cookieManager.removeSessionCookies(null)
        cookieManager.flush()

        binding.webView.clearFormData()
        binding.webView.clearMatches()
        viewModel.clearFindResult()
        if (enabled) {
            binding.webView.clearHistory()
            binding.webView.clearCache(true)
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
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton("取消", null)
            .setPositiveButton(if (enabled) "开启" else "退出") { _, _ ->
                viewModel.setIncognito(enabled)
                applyIncognitoMode(enabled)
            }
            .show()
    }

    private fun hideFindBar() {
        binding.findBar.isVisible = false
        binding.findInput.setText("")
        binding.webView.clearMatches()
        viewModel.clearFindResult()
    }

    private fun navigateTo(rawUrl: String, userInitiated: Boolean = false) {
        val target = rawUrl.trim()
        if (userInitiated) {
            noteNavigationIntent()
        }
        dismissRecentSearches()
        if (target == DEFAULT_HOME_URL) {
            binding.homeSearchInput.setText("")
        }
        viewModel.setAddressBarText(if (target == DEFAULT_HOME_URL) "" else target)
        binding.webView.loadUrl(target)
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
        return Regex("^[a-zA-Z][a-zA-Z\\d+.-]*:(//)?").containsMatchIn(input)
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
        if (host.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))) {
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
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36 AuroraBrowser/0.1 Desktop"
        } else {
            "$mobileUserAgent AuroraBrowser/0.1"
        }
    }

    private fun injectResourceSniffer() {
        val script = """
            (function() {
              if (!window.$JS_BRIDGE_NAME) {
                return;
              }
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
                try {
                  window.$JS_BRIDGE_NAME.notifyUserGesture();
                } catch (e) {}
              };
              var canLoadResource = function(url) {
                if (!url) {
                  return true;
                }
                try {
                  return !window.$JS_BRIDGE_NAME.shouldBlockResource(String(url || ''));
                } catch (e) {
                  return true;
                }
              };
              var canAutoNavigate = function(url) {
                try {
                  return !!window.$JS_BRIDGE_NAME.shouldAllowNavigation(String(url || ''));
                } catch (e) {
                  return false;
                }
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
                try {
                  window.$JS_BRIDGE_NAME.captureMedia(String(normalized), String(type || ''));
                } catch (e) {}
              };
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
                try {
                  window.$JS_BRIDGE_NAME.noteExplicitNavigation(String(normalized));
                } catch (e) {}
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
                try {
                  window.$JS_BRIDGE_NAME.onVideoInteraction(
                    String(url || ''),
                    String(resolveVideoTitle(target) || ''),
                    Number(width || 0),
                    Number(height || 0)
                  );
                } catch (e) {}
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
              var scanNode = function(node) {
                if (!node || !node.tagName) return;
                var tagName = String(node.tagName).toLowerCase();
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
                  window.$JS_BRIDGE_NAME.reportActiveVideoState(
                    video.currentSrc || video.src || '',
                    Number(video.videoWidth || video.clientWidth || 0),
                    Number(video.videoHeight || video.clientHeight || 0)
                  );
                } else {
                  window.$JS_BRIDGE_NAME.reportActiveVideoState('', 0, 0);
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
                    try {
                      window.$JS_BRIDGE_NAME.onPlaybackStarted(String(playbackUrl || ''));
                    } catch (e) {}
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
                new MutationObserver(function(mutations) {
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
                }).observe(document.documentElement || document.body, { childList: true, subtree: true });
                document.addEventListener('play', reportActiveVideo, true);
                document.addEventListener('pause', reportActiveVideo, true);
                document.addEventListener('loadedmetadata', reportActiveVideo, true);
                setInterval(reportActiveVideo, 1200);
                setInterval(scrubPopups, 1800);
              }
              scan();
              scrubPopups();
              reportActiveVideo();
            })();
        """.trimIndent()
        binding.webView.evaluateJavascript(script, null)
    }

    private inner class PageBridge {
        @JavascriptInterface
        fun captureMedia(url: String?, type: String?) {
            val value = url?.takeIf { it.isNotBlank() } ?: return
            val state = viewModel.uiState.value
            captureResourceCandidate(
                url = value,
                title = lastPreferredVideoTitle ?: state.pageTitle,
                pageUrl = state.currentUrl,
                mimeType = type,
                referer = state.currentUrl,
                userAgent = buildUserAgent(state.settings.desktopMode),
                prioritizeForCurrentVideo = hasRecentVideoInteraction(),
            )
        }

        @JavascriptInterface
        fun reportActiveVideo(url: String?) {
            reportActiveVideoState(url, 0.0, 0.0)
        }

        @JavascriptInterface
        fun reportActiveVideoState(url: String?, width: Double, height: Double) {
            val value = url?.takeIf { it.isNotBlank() }
            lastActiveVideoUrl = value
            if (value != null) {
                val state = viewModel.uiState.value
                captureResourceCandidate(
                    url = value,
                    title = lastPreferredVideoTitle ?: state.pageTitle,
                    pageUrl = state.currentUrl,
                    mimeType = "video/*",
                    referer = state.currentUrl,
                    userAgent = buildUserAgent(state.settings.desktopMode),
                    prioritizeForCurrentVideo = true,
                )
            }
        }

        @JavascriptInterface
        fun shouldAllowNavigation(url: String?): Boolean {
            return shouldAllowInjectedNavigation(url)
        }

        @JavascriptInterface
        fun shouldBlockResource(url: String?): Boolean {
            return shouldBlockResourceRequest(url)
        }

        @JavascriptInterface
        fun notifyUserGesture() {
            // Used as a lightweight bridge hook so page scripts can signal interaction timing if needed later.
        }

        @JavascriptInterface
        fun notifyVideoInteraction() {
            noteVideoInteraction()
        }

        @JavascriptInterface
        fun onVideoInteraction(url: String?, title: String?, width: Double, height: Double) {
            noteVideoInteraction(
                videoUrl = url,
                title = title,
                width = width,
                height = height,
            )
        }

        @JavascriptInterface
        fun noteExplicitNavigation(url: String?) {
            noteExplicitUserNavigation(url)
        }

        @JavascriptInterface
        fun saveCredential(pageUrl: String?, username: String?, password: String?) {
            maybeSaveCredential(pageUrl, username, password)
        }

        @JavascriptInterface
        fun onPlaybackStarted(url: String?) {
            val playbackUrl = url?.takeIf { it.isNotBlank() }
            if (playbackUrl != null) {
                reportActiveVideoState(playbackUrl, 0.0, 0.0)
                lastPreferredVideoResourceUrl = playbackUrl
                noteVideoInteraction(videoUrl = playbackUrl)
            }
        }
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

    private fun ensureInitialTab() {
        if (tabs.isNotEmpty()) {
            return
        }
        val firstTab = BrowserTab(
            id = nextTabId++,
            title = getString(R.string.browser_title),
            url = DEFAULT_HOME_URL,
        )
        tabs += firstTab
        currentTabId = firstTab.id
    }

    private fun updateCurrentTab(state: BrowserUiState) {
        tabs.firstOrNull { it.id == currentTabId }?.apply {
            title = state.pageTitle.takeIf { it.isNotBlank() } ?: getString(R.string.browser_title)
            url = state.currentUrl
        }
    }

    private fun showTabsDialog() {
        val items = tabs.mapIndexed { index, tab ->
            val marker = if (tab.id == currentTabId) "● " else ""
            val label = tab.title.takeIf { it.isNotBlank() } ?: tab.url
            "$marker${index + 1}. $label"
        }.toTypedArray()

        MaterialAlertDialogBuilder(this)
            .setTitle("标签页 (${tabs.size})")
            .setItems(items) { _, which ->
                switchToTab(tabs[which].id)
            }
            .setPositiveButton("新建") { _, _ ->
                createNewTab()
            }
            .setNeutralButton("关闭当前") { _, _ ->
                closeCurrentTab()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun createNewTab() {
        updateCurrentTab(viewModel.uiState.value)
        val newTab = BrowserTab(
            id = nextTabId++,
            title = "新标签页",
            url = DEFAULT_HOME_URL,
        )
        tabs += newTab
        currentTabId = newTab.id
        navigateTo(DEFAULT_HOME_URL, userInitiated = true)
        updateTabsCountBadge()
    }

    private fun switchToTab(tabId: Long) {
        val target = tabs.firstOrNull { it.id == tabId } ?: return
        updateCurrentTab(viewModel.uiState.value)
        currentTabId = tabId
        navigateTo(target.url, userInitiated = true)
        updateTabsCountBadge()
    }

    private fun closeCurrentTab() {
        if (tabs.size <= 1) {
            navigateHome(userInitiated = true)
            return
        }
        val currentIndex = tabs.indexOfFirst { it.id == currentTabId }.coerceAtLeast(0)
        tabs.removeAt(currentIndex)
        val nextIndex = (currentIndex - 1).coerceAtLeast(0)
        val target = tabs[nextIndex]
        currentTabId = target.id
        navigateTo(target.url, userInitiated = true)
        updateTabsCountBadge()
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
        suggestionsPopup = ListPopupWindow(this).apply {
            anchorView = anchor
            width = anchor.width
            isModal = false
            setAdapter(
                ArrayAdapter(
                    this@MainActivity,
                    android.R.layout.simple_list_item_1,
                    suggestionEntries.map { it.text },
                ),
            )
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
        MaterialAlertDialogBuilder(this)
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
            .show()
    }

    private fun dismissRecentSearches(cancelJob: Boolean = true) {
        if (cancelJob) {
            suggestionsJob?.cancel()
        }
        suggestionsPopup?.dismiss()
        suggestionsPopup = null
    }

    private fun showSearchEnginePicker() {
        val current = preferences.getSearchEngine()
        val items = SearchEngine.entries.map { it.label }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("选择搜索引擎")
            .setSingleChoiceItems(items, SearchEngine.entries.indexOf(current)) { dialog, which ->
                preferences.setSearchEngine(SearchEngine.entries[which])
                updateSearchEngineLabel()
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
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

    private fun homeDestinationUrl(): String {
        return preferences.getStartPageUrl().ifBlank { DEFAULT_HOME_URL }
    }

    private fun navigateHome(userInitiated: Boolean = true) {
        navigateTo(homeDestinationUrl(), userInitiated = userInitiated)
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
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
                MaterialAlertDialogBuilder(this@MainActivity)
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
                    .show()
            }
        }
    }

    private fun confirmRemoveBookmark(pageUrl: String) {
        MaterialAlertDialogBuilder(this)
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
            .show()
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
        MaterialAlertDialogBuilder(this)
            .setTitle("选择书签目录")
            .setSingleChoiceItems(labels, checkedIndex) { dialog, which ->
                onSelected(options[which].id)
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showBookmarkFolderCreateDialog(parentId: Long, onCreated: (Long) -> Unit) {
        val input = EditText(this).apply {
            hint = "文件夹名称"
            setSingleLine()
        }
        MaterialAlertDialogBuilder(this)
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
            .show()
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
        binding.webView.evaluateJavascript(script) { rawResult ->
            val iconUrl = decodeJavascriptStringResult(rawResult)
            ensureSiteIcon(pageUrl, preferredIconUrl = iconUrl.takeIf { it.isNotBlank() }, force = true)
        }
    }

    private fun saveSiteIcon(pageUrl: String, bitmap: Bitmap) {
        lifecycleScope.launch(Dispatchers.IO) {
            if (siteIconStore.save(pageUrl, bitmap)) {
                withContext(Dispatchers.Main) {
                    invalidateHomeSections()
                    renderHomeSectionsIfNeeded(viewModel.uiState.value, force = true)
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
        val userAgent = buildUserAgent(viewModel.uiState.value.settings.desktopMode)
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
                    invalidateHomeSections()
                    renderHomeSectionsIfNeeded(viewModel.uiState.value, force = true)
                }
            }
        }
    }

    private fun refreshCustomHomeSites() {
        lifecycleScope.launch {
            customHomeSites = withContext(Dispatchers.IO) {
                store.getHomeSites()
            }
            invalidateHomeSections()
            renderHomeSectionsIfNeeded(viewModel.uiState.value, force = true)
        }
    }

    private fun refreshMostVisitedSites() {
        val state = viewModel.uiState.value
        if (!state.settings.showMostVisitedSites) {
            mostVisitedSites = emptyList()
            invalidateHomeSections()
            renderHomeSectionsIfNeeded(state, force = true)
            return
        }
        lifecycleScope.launch {
            mostVisitedSites = withContext(Dispatchers.IO) {
                store.getTopSites(HOME_TOP_SITE_LIMIT)
            }
            invalidateHomeSections()
            renderHomeSectionsIfNeeded(viewModel.uiState.value, force = true)
        }
    }

    private fun renderHomeSectionsIfNeeded(state: BrowserUiState, force: Boolean = false) {
        val customSignature = buildString {
            append(isHomeScreen(state))
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
    }

    private fun invalidateHomeSections() {
        lastHomeCustomSignature = null
        lastHomeTopSignature = null
    }

    private fun renderCustomHomeSites(state: BrowserUiState) {
        val visible = isHomeScreen(state)
        binding.homeCustomSitesSection.isVisible = visible
        if (!visible) {
            binding.homeCustomSitesRow.removeAllViews()
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
        val visible = isHomeScreen(state) && state.settings.showMostVisitedSites && mostVisitedSites.isNotEmpty()
        binding.homeTopSitesSection.isVisible = visible
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
            strokeWidth = dp(1)
            strokeColor = currentThemePalette.line
            setCardBackgroundColor(currentThemePalette.surfaceAlt)
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
        val palette = intArrayOf(
            Color.parseColor("#E85D5D"),
            Color.parseColor("#2F7CF6"),
            Color.parseColor("#16A085"),
            Color.parseColor("#F39C12"),
            Color.parseColor("#7F56D9"),
            Color.parseColor("#3949AB"),
            Color.parseColor("#00897B"),
            Color.parseColor("#D35454"),
        )
        val index = url.hashCode().absoluteValue % palette.size
        return palette[index]
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
        MaterialAlertDialogBuilder(this)
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
            .show()
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
        MaterialAlertDialogBuilder(this)
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
            .show()
        return true
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics,
        ).toInt()
    }

    private fun showVideoDownloadPicker(resources: List<ResourceItem>) {
        if (resources.isEmpty()) {
            Toast.makeText(this, "未发现可下载的视频", Toast.LENGTH_SHORT).show()
            return
        }
        if (resources.size == 1) {
            val resource = resources.first()
            val message = BrowserDownloads.enqueueFromResource(
                this,
                store,
                resource,
                overrideTitle = preferredVideoTitleFor(resource),
            )
            Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
            return
        }
        val items = resources.mapIndexed { index, item ->
            "${index + 1}. ${formatVideoResourceChoice(item)}"
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("选择下载视频")
            .setItems(items) { _, which ->
                val resource = resources[which]
                val message = BrowserDownloads.enqueueFromResource(
                    this,
                    store,
                    resource,
                    overrideTitle = preferredVideoTitleFor(resource),
                )
                Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun refreshCurrentVideoResources(onReady: (List<ResourceItem>) -> Unit) {
        val fallbackResources = currentPageVideoResources()
        val state = viewModel.uiState.value
        binding.webView.evaluateJavascript(
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
                        userAgent = buildUserAgent(state.settings.desktopMode),
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
                        userAgent = buildUserAgent(state.settings.desktopMode),
                        prioritizeForCurrentVideo = true,
                    )
                }
            }
            val mergedResources = (freshResources + currentPageVideoResources() + fallbackResources)
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
        videoUrl?.takeIf { it.isNotBlank() }?.let {
            lastActiveVideoUrl = it
            val category = ResourceTools.classify(it, null)
            if (category.value == "stream" || category.value == "video") {
                lastPreferredVideoResourceUrl = it
            }
        }
        title?.takeIf { it.isNotBlank() }?.let { lastPreferredVideoTitle = it }
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

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_USER_INITIATED = "user_initiated"
        private const val JS_BRIDGE_NAME = "AuroraBridge"
        private const val NAVIGATION_INTENT_WINDOW_MS = 3500L
        private const val TRUSTED_WEB_NAVIGATION_WINDOW_MS = 1200L
        private const val EXPLICIT_NAVIGATION_WINDOW_MS = 1800L
        private const val VIDEO_INTERACTION_WINDOW_MS = 2200L
        private const val HOME_TOP_SITE_LIMIT = 18
        private const val BLOCKED_NAVIGATION_DEBOUNCE_MS = 1500L
        private const val MENU_COLUMNS = 4
        private const val MENU_ROWS = 2
        private const val MENU_ITEMS_PER_PAGE = MENU_COLUMNS * MENU_ROWS
        private const val MAX_RECENT_SEARCH_SUGGESTIONS = 8
        private const val HOME_SITE_COLUMNS = 6
        private const val HOME_SITE_TILE_WIDTH_DP = 44
        private const val HOME_SITE_ICON_SIZE_DP = 34
        private const val HOME_SITE_ICON_RADIUS_DP = 12
        private const val HOME_SITE_COLUMN_GAP_DP = 4
        private const val HOME_SITE_ROW_GAP_DP = 12
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
    }
}
