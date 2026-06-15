# Aurora Browser — AI Agent Project Guide

## Project Overview

Aurora Browser is an Android web browser built on the system **Chromium WebView** engine. It provides tabbed browsing, resource sniffing, HLS stream downloading, ad blocking, bookmark/history management, site-level settings, reading/translation tools, credential storage, cache cleanup, and optimized web video playback — all within a Material Design UI.

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Language | **Kotlin** (JVM target 17) |
| Min / Target SDK | 30 (Android 11) / 34 (Android 14) |
| Build System | Gradle (Kotlin DSL), AGP 8.5.2 |
| UI Framework | **Material Design** + **ViewBinding** |
| Architecture | **MVVM** (AndroidX ViewModel + StateFlow) |
| Async | **kotlinx.coroutines** (Dispatchers.IO for DB/network, viewModelScope) |
| Database | **SQLite** via `SQLiteOpenHelper` (no Room) |
| Settings | **SharedPreferences** |
| Background Work | **WorkManager** (CoroutineWorker for HLS downloads) |
| Web Engine | Android System WebView (Chromium) |
| Media | MediaPlayer, MediaMetadataRetriever, MediaExtractor/MediaMuxer |
| P2P | FrostWire jlibtorrent / libtorrent native; optional external BT app handoff |
| Barcode | Google ML Kit (barcode-scanning) |
| Lists | RecyclerView with DiffUtil |

## Directory Structure

```
/aurorabrowser/                          # Project root
├── build.gradle.kts                     # Root Gradle config (plugin versions)
├── settings.gradle.kts                  # Module includes
├── gradle.properties                    # JVM args, AndroidX flag, Kotlin style
├── AGENTS.md                            # This file
├── README.md                            # Project intro (Chinese)
│
└── app/                                 # App module
    ├── build.gradle.kts                 # Dependencies, SDK versions, buildFeatures
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml          # Activities, permissions
        ├── assets/
        │   └── ad_hosts.txt             # Blocked ad/tracker domains (174 entries)
        └── java/com/fenglan/aurorabrowser/
            ├── BrowserApp.kt            # Application class (night mode init)
            ├── MainActivity.kt          # Browser UI: WebView, tabs, address bar, JS bridge
            ├── LibraryActivity.kt       # Downloads / History / Bookmarks / Resources UI
            ├── SettingsActivity.kt      # Preferences UI
            ├── VideoPlayerActivity.kt   # Video playback with local proxy server
            ├── CredentialVaultActivity.kt # Saved login credentials management
            ├── MagnetSearchActivity.kt  # Multi-source magnet search, source menu, history, copy/open/cloud play
            │
            ├── browser/                 # Core browser logic
            │   ├── BrowserTabManager.kt # Tab serialization and restore helpers
            │   ├── BrowserViewModel.kt  # MVVM ViewModel: UI state, URL state, bookmarks
            │   ├── BrowserDownloads.kt  # Download orchestration: queue, HLS, P2P, system DL, MP4 export
            │   ├── HlsPackageTools.kt   # M3U8 parsing, TS/M4S download, segment assembly, MP4 mux
            │   ├── HlsDownloadWorker.kt # WorkManager CoroutineWorker for HLS download
            │   ├── HlsExportWorker.kt   # WorkManager CoroutineWorker for HLS→MP4 conversion
            │   ├── P2pDownloadWorker.kt # WorkManager CoroutineWorker for libtorrent P2P downloads
            │   ├── LocalPlaylistServer.kt # Local HTTP proxy for HLS playback
            │   ├── BrowserPreferences.kt # SharedPreferences wrapper (settings, search history)
            │   ├── SecureTextStore.kt   # Android Keystore AES/GCM encrypted text storage
            │   ├── MagnetSearchClient.kt # HTML/result parser with valid magnet filtering
            │   ├── MagnetSearchSources.kt # Built-in/custom magnet source templates
            │   ├── TorrentTools.kt      # Magnet/torrent URL validation and BTIH helpers
            │   ├── AdBlocker.kt         # Host-based + keyword URL blocking, redirect detection
            │   ├── ResourceTools.kt     # URL classification, file type detection, MIME helpers
            │   ├── BrowserThemeTools.kt # Theme/resource helpers
            │   ├── PageTranslator.kt    # Web page translation via external APIs
            │   ├── SearchSuggestions.kt # Search engine suggestion API calls
            │   └── SiteIconStore.kt     # Favicon/icon storage
            │
            ├── data/
            │   └── BrowserStore.kt      # SQLiteOpenHelper: all CRUD for 6 tables
            │                            # Tables: bookmarks, history, resources, downloads,
            │                            #         home_sites, credentials
            │
            ├── model/
            │   └── BrowserModels.kt     # All data classes & enums
            │                            # FileCategory, SearchEngine, LibraryMode,
            │                            # BookmarkItem, HistoryItem, DownloadItem,
            │                            # ResourceItem, BrowserUiState, etc.
            │
            └── ui/
                └── LibraryAdapter.kt    # RecyclerView adapter with DiffUtil,
                                         # thumbnail extraction, compact density
```

### Key Resource Files

```
res/
├── layout/
│   ├── activity_main.xml               # Browser UI: WebView, toolbar, address bar, buttons
│   ├── activity_library.xml            # List UI: search input, filter chips, RecyclerView
│   ├── activity_settings.xml           # Settings form
│   ├── activity_video_player.xml       # Video player: SurfaceView + controls
│   ├── activity_magnet_search.xml      # Magnet search UI: query, source menu button, result filters
│   ├── dialog_browser_menu.xml         # Browser overflow menu
│   └── item_library_entry.xml          # Single list row: icon, text, thumbnail, action buttons
├── drawable/                           # 35+ Material vector icons (ic_*.xml)
├── anim/                               # Browser UI transition animations
├── menu/
│   ├── browser_menu.xml
│   └── library_menu.xml
├── values/
│   ├── colors.xml, strings.xml, themes.xml
├── xml/
│   └── data_extraction_rules.xml       # Backup/data extraction policy
└── values-night/                       # Night mode color overrides
```

## Code Conventions

### Naming & Style
- **Official Kotlin code style** (`kotlin.code.style=official` in gradle.properties)
- **camelCase** for functions, variables, properties; **PascalCase** for classes, data classes, enums, `object` singletons
- Private helper functions use `camelCase` without underscore prefix
- Companion object constants: `SCREAMING_SNAKE_CASE` (e.g., `KEY_RECORD_ID`, `MAX_RECENT_SEARCHES`)

### Patterns
- **No comments by default.** Add one-line comments only when the WHY is non-obvious (hidden constraint, workaround, subtle invariant).
- **`object` singletons** for stateless utility classes: `BrowserDownloads`, `ResourceTools`, `HlsPackageTools`
- **`data class`** for all models, UI state (immutable `copy()` pattern)
- **Null safety**: heavy use of `?.let {}`, `?.takeIf { it.isNotBlank() }`, `?: return`, `?: error()`
- **Error handling**: `runCatching {}.getOrNull()` / `.getOrDefault()` for operations that may fail silently; `runCatching {}.getOrElse {}` when fallback needed
- **DB operations**: all calls wrapped in `synchronized(this) { }`, use `readableDatabase` for reads, `writableDatabase` for writes
- **ViewBinding**: each activity inflates via `ActivityXxxBinding.inflate(layoutInflater)`, stored as `lateinit var binding`
- **Chinese UI strings** (zh-CN) throughout — string resources, toast messages, dialog text
- **Coroutines**: `Dispatchers.IO` for all DB and network calls; `viewModelScope.launch` in ViewModel; `CoroutineScope(Dispatchers.IO).launch` in adapters/tools
- **Dialogs**: `MaterialAlertDialogBuilder` for all confirmations, with `.setNegativeButton("取消", null)` as cancel. Main browser dialogs styled through `MainActivity.styleAlertDialog(...)` should end with `.showThemedAlert()` unless they need `create()` for custom button handling; in that case call `applyAlertDialogTheme(dialog)` after `dialog.show()`. Settings-page click dialogs must go through `SettingsActivity.themedDialog().showThemed()` so dialog backgrounds, text, inputs, list selection, buttons, and ripple colors follow the active `BrowserThemePalette`.

### File Organization
- One Activity per file (no inner Activity classes)
- Models grouped in `model/BrowserModels.kt` (single file for all data classes + enums)
- Browser logic tools each in their own file under `browser/`
- Single RecyclerView adapter handles 5 different list modes via `LibraryRenderItem` data class

### Documentation Sync Requirement
- After every AI-made code, resource, Gradle, manifest, asset, or behavior change, update the relevant Markdown files before final response.
- At the current project level this means:
  - `README.md`: user-facing feature list, behavior changes, known limits, build/install/verification notes.
  - `AGENTS.md`: architecture, module responsibilities, implementation constraints, agent workflow rules, and recent AI change notes.
  - Any future module-level or docs-level `*.md`: update the closest applicable doc for that directory as well.
- Markdown updates must be concise, factual, and dated when they describe a completed change. Do not document planned work as completed.
- Do not include secrets, private tokens, local credentials, or long device logs in Markdown.
- If a change only affects internal implementation, still record the user-visible behavior or maintenance impact where appropriate.
- Required prompt for future AI agents:

```text
本次更改完成后，请同步整理项目各级 Markdown 文档：更新 README.md 的用户可见功能、行为边界、验证/安装记录；更新 AGENTS.md 的架构、模块职责、约束和 AI 维护规则；如果新增或影响子目录级 md，也要同步更新对应文件。不得把未完成事项写成已完成，不得写入密钥或隐私信息。
```

### Recent AI Change Notes
- 2026-06-15: Fixed stale search text when opening a blank new tab. `MainActivity` now clears both `homeSearchInput` and `addressInput`, drops their focus, resets `BrowserViewModel.addressBarText`, and dismisses recent-search suggestions when creating a blank tab, closing all tabs into a fresh blank tab, or navigating to the built-in home page. Added `BrowserNavigationStateTools` to centralize built-in-home input clearing and tab display state resolution for frozen/restoring/live tabs, with JVM tests in `BrowserNavigationStateToolsTest`. `homeSearchInput` now has one focus handler that combines recent-search suggestions with minimal-home keyboard offset behavior, avoiding listener replacement. Home section forced refreshes now route through `refreshHomeSections()`, and `needsHomeSectionData()` avoids repeated home DB loads when most-visited sites are disabled and lists are empty. README keeps only current verification commands and links to `docs/verification.md` for historical verification notes. Low-risk Kotlin warnings were reduced by removing an unused translation batch index and a redundant non-null Elvis on `WebView.hitTestResult`. Verified with `./gradlew testDebugUnitTest lintDebug assembleDebug`; installed to device `10AFA313MD002MQ` with result `Success`.
- 2026-06-09: Optimized web page translation with inline dual-language display and improved API reliability. `PageTranslator.kt` JS scripts now use `<span>` wrappers instead of `<ruby>/<rt>` — block parents get `display:block` translation below original, inline parents get `margin-left:6px` inline translation. Default `TranslationEngine` changed from `MICROSOFT` to `GOOGLE` in `BrowserModels.kt`. Added session translation cache (`HashMap`) to avoid re-translating identical text. Translation fallback chain: selected engine → Google → MyMemory → graceful skip (no page-level failure). Per-segment retry with 1s backoff for transient errors. `translateCurrentPage()` now applies translations batch-by-batch with partial results and shows translated/total count. `clearPageTranslation()` clears session cache. Verified with `./gradlew testDebugUnitTest lintDebug assembleDebug`.
- 2026-06-09: Fixed Google search "abnormal traffic" detection. Removed `AuroraBrowser/0.1` User-Agent suffix (mobile now uses system default, desktop uses standard Chrome UA without custom identifier). Google domains (`google.com`, `youtube.com`, `googleapis.com`, `recaptcha.net`, etc.) now skip JS injection (`injectResourceSniffer`, `injectCredentialAssistant`, `syncInjectedPageTheme`), ad blocking (both resource requests and navigation interceptor), and popup hiding. Ad blocker (`AdBlocker.kt`) whitelists Google-to-Google subresource requests. Navigation interceptor (`shouldBlockAutomaticRedirect`) allows all Google-related cross-origin redirects. Updated desktop UA Chrome version from 124 to 137. Verified with `./gradlew testDebugUnitTest lintDebug assembleDebug`.
- 2026-06-09: Performance optimizations. `dp()` now caches `displayMetrics.density` via `displayDensity` lazy field instead of recalculating per call (107+ calls). Fade/slide animations cached as `lazy` fields (`animFadeIn`, `animFadeOut`, `animSlideInTop`, `animSlideOutTop`). `refreshSystemDownloads()` now does a single batch `DownloadManager` query via `setFilterById(vararg ids)` instead of N individual queries. Hoisted `Regex` compilations to companion object constants (`SCHEME_REGEX`, `IPV4_REGEX`) and `ResourceTools.UNSAFE_FILENAME_REGEX`. Verified with `./gradlew testDebugUnitTest lintDebug assembleDebug`.
- 2026-06-09: Fixed tab switching and new tab display. `switchToTab()` now uses saved `tab.url` for frozen tabs instead of `webView.url` (which is `"about:blank"` after freeze). `onPageFinished` skips `showHomeSurface` when the tab's saved URL is not `DEFAULT_HOME_URL` (prevents stale `about:blank` callback from frozen tabs). `navigateTo()` now calls `prepareActiveTabGlobals(target)` before `loadUrl` so ViewModel updates immediately. `showHomeSurface` postDelayed only re-renders when `isHomeScreen()` is true, not just when `homePanel.isVisible`. Verified with `./gradlew testDebugUnitTest lintDebug assembleDebug`.
- 2026-06-09: Performance optimizations. `renderState()` home/top site DB reads moved to `Dispatchers.IO` via `lifecycleScope.launch`. `showHomeSurface()` DB reads also async. `restoreTabs()` uses `runBlocking(Dispatchers.IO)` for `store.loadTabs()`. `disposeClosedTab()` now recycles thumbnail Bitmap. `siteAccentColor()` palette hoisted to companion object `SITE_ACCENT_PALETTE`. `dp()` density cached in `LibraryAdapter`, `MagnetSearchAdapter`, `MagnetSearchActivity`, `SettingsActivity`. `BrowserPreferences.normalizeDownloadSubdirectory()` regex hoisted to companion constants. `BrowserApp` enables `WebView.setWebContentsDebuggingEnabled` for debug builds. `LocalPlaylistServer` thread pool bounded to 4. Verified with `./gradlew testDebugUnitTest lintDebug assembleDebug`.
- 2026-06-08: Added privacy space feature for downloads. `BrowserModels.kt` `DownloadItem` now includes `isPrivate` field. `BrowserStore` downloads table gained `is_private` column (migration v8→v9) with `insertDownload`, `getDownloads` (new `privateOnly`/`excludePrivate` filters), `updateDownloadPrivacy`. `BrowserPreferences` stores `defaultPrivacyDownload` boolean. `BrowserDownloads.enqueue()` accepts `isPrivate`; progressive private downloads use new `PrivateDownloadWorker` (direct HTTP to app-private `getExternalFilesDir("privacy")/`); HLS and P2P workers also route to private directories when flagged. `BrowserDownloads` exposes `moveToPrivacy()`/`moveToPublic()` for moving completed files. `SettingsActivity` has "默认下载位置" setting (普通空间/隐私空间). `LibraryActivity` download management dialog shows "移入/移出隐私空间" for completed items, subtitles show "隐私" badge, resource downloads respect global default. `MainActivity` and `MagnetSearchActivity` torrent dialogs include "下载到隐私空间" checkbox. Verified with `./gradlew testDebugUnitTest lintDebug assembleDebug`.
- 2026-06-08: Added home page style setting with default and minimal options. `BrowserModels.kt` now includes a `HomeStyle` enum (`DEFAULT`, `MINIMAL`). `BrowserPreferences` stores the selected style via `getHomeStyle()`/`setHomeStyle()`. `SettingsActivity.showStartPageDialog()` now shows a combined dialog with style radio selection and custom URL input; `renderSettingsSummary()` displays the active style label. `activity_main.xml` quick actions row now has `id=homeQuickActions`. `MainActivity.renderHomeSectionsIfNeeded()` applies the style: minimal mode hides quick actions, top sites, and custom sites, and centers the search card via `Gravity.CENTER`. Custom start page URLs now hide the address bar via `isOnCustomStartPage()`, and `showHomeSurface()` skips forcing the home panel visible when on a custom start page. Verified with `./gradlew testDebugUnitTest lintDebug assembleDebug`.
- 2026-06-07: Moved magnet source management into the top-right dropdown in `MagnetSearchActivity`. `activity_magnet_search.xml` now removes the old horizontal source chip group plus separate select-all/add buttons and exposes a 48dp `sourceMenuButton`. The popup follows `BrowserThemePalette`, shows enabled count, enable-all/disable-all/add actions, source rows with checkbox, source type/domain/recent health, and custom-source delete actions. `BrowserPreferences.getSelectedMagnetSourceIds()` now distinguishes missing legacy selection from an explicitly saved empty JSON array, so "disable all" persists and search prompts the user to enable a source from the top-right menu. Verified with `./gradlew testDebugUnitTest lintDebug assembleDebug`; installed to device `10AFA313MD002MQ` with result `Success`.
- 2026-06-07: Fixed blank web content after the multi-tab visibility change. `activity_main.xml` now wraps the primary `WebView` in `webViewContainer`, so `SwipeRefreshLayout` has exactly one direct child while all tab WebViews live in the inner `FrameLayout`. `MainActivity.updateWebViewVisibility()` brings the active WebView to front, and `setupSwipeRefresh()` is initialized with a current-WebView scroll callback so pull refresh still follows the active tab. Verified with `./gradlew testDebugUnitTest`, `./gradlew lintDebug`, and `./gradlew assembleDebug`; installed to device `10AFA313MD002MQ` with `adb shell pm install -r -g /data/local/tmp/aurora-hotfix.apk`, result `Success`.
- 2026-06-07: Fixed multi-tab page visibility and home-surface recovery. `MainActivity.updateWebViewVisibility()` now makes only the active, non-frozen WebView visible when not on the home screen, so newly opened or switched tabs can display the loaded site. `onPageFinished()` now treats `DEFAULT_HOME_URL`/`about:blank` as a native-home completion path and force-refreshes home sites/top sites before persisting tabs, covering WebView back navigation to home. Frozen background tabs preserve their last real URL before loading `about:blank`, and WebView callbacks no longer overwrite frozen tab URLs/titles with the blank placeholder. Verified with `./gradlew testDebugUnitTest`, `./gradlew lintDebug`, and `./gradlew assembleDebug`; installed to device `10AFA313MD002MQ` with result `Success`.
- 2026-06-05: Unified browser menu and settings-page theme behavior with the active browser palette. `MainActivity` now refreshes an already-open browser main menu after `applyBrowserTheme()`, reapplies bottom-sheet background/stroke from the latest `BrowserThemePalette`, and routes `styleAlertDialog(...)` dialogs through `showThemedAlert()` so list rows, buttons, inputs, check marks, and ripples follow the active theme. `SettingsActivity` applies `BrowserThemeTools.paletteFor(...)` to the settings root, toolbar, cards, text/icons, status/navigation bars, and all click dialogs including download path, cache cleanup, HLS concurrency, P2P engine, start page, search engine, translation engine, translation credentials, and permission info. Verified with `./gradlew testDebugUnitTest lintDebug assembleDebug`; installed to device `10AFA313MD002MQ` with result `Success`.
- 2026-06-05: Fixed the rectangular background visible outside the rounded bottom navigation bar in gradient themes. `MainActivity.applyBrowserTheme()` now applies the active gradient to the root layout and lets the home panel show through that root gradient, so the bottom-bar margin area no longer falls back to the fixed surface color. Verified with `./gradlew testDebugUnitTest`, `./gradlew lintDebug`, and `./gradlew assembleDebug`; installed to device `10AFA313MD002MQ` with result `Success`.
- 2026-06-05: Fixed magnet search restore and gradient bottom bar theming. `MagnetSearchActivity.runSearch()` now saves the query at search start, clears stale last-result snapshots, and persists `latestOutcomes` after each source completes, so reopening the magnet search page shows results from a previous interrupted search once at least one source had finished. `MainActivity.applyBottomNavigationTheme()` now derives the bottom bar card, internal background, icon enabled/disabled tint, ripple color, and tab-count badge from the current `BrowserThemePalette`, and restores non-gradient stroke/background state when switching themes. Verified with `./gradlew testDebugUnitTest`, `./gradlew lintDebug`, and `./gradlew assembleDebug`; installed to device `10AFA313MD002MQ` with result `Success`.
- 2026-06-05: Optimized magnet search speed without changing `MagnetSearchActivity` display/filter rendering. `MagnetSearchClient.parseResults()` now filters candidate links before expensive context slicing, reuses row-level magnet/size metadata when a detail link and copy button appear in the same result row, caches detail-page metadata briefly in memory, narrows empty direct-result WebView fallback to the primary URL and first browser profile, and shortens HTTP/WebView wait windows. Source concurrency remains capped at 3 and 0Magnet remains direct HTTP plus fallback domains only. Verified with `./gradlew testDebugUnitTest`, `./gradlew lintDebug`, and `./gradlew assembleDebug`; installed to device `10AFA313MD002MQ` with result `Success`.
- 2026-06-02: Fixed home-surface sections showing blank when navigating back to home or opening a new tab (second fix). Root cause: `renderState()` called `renderHomeSectionsIfNeeded()` without `force`, so if a `renderState()` call during `onPageStarted` had briefly hidden the sections, the subsequent call from `onPageFinished` would skip re-rendering because the signature matched the already-hidden state. Fix: `renderState()` now always passes `force=true` to `renderHomeSectionsIfNeeded()` when `isHomeScreen()` is true, ensuring home sections are always visible on the home page.
- 2026-06-02: UI accessibility and dark mode fixes. (1) Touch targets: increased button sizes from 34/36/40/42dp to 48dp minimum across activity_main.xml (refreshButton, bookmarkButton, homeAddSiteButton), item_library_entry.xml (primaryIconButton, secondaryIconButton), activity_magnet_search.xml (search/history/selectAll/addSource buttons), item_magnet_result.xml (copy/copyMagnet/cloudPlay/open buttons). (2) Content descriptions: added `contentDescription` to 15+ elements including all 9 settings icons, library item icons/buttons, magnet result action buttons, drag handle, decorative views marked `importantForAccessibility="no"`. (3) Dark mode: replaced hardcoded `#AAFFFFFF` scrim with `@color/aurora_scrim` (light=#AAFFFFFF, night=#AA000000), replaced hardcoded `#000000`/`#88000000` video player backgrounds with color resources, replaced `@android:color/darker_gray` dialog drag handle with `@color/aurora_line`. (4) Search engine button: replaced flat `@color/aurora_surface` background with `aurora_engine_button_bg` drawable (8dp rounded corners). (5) Settings icons: increased from 22dp to 24dp. (6) Strings: moved 30+ hardcoded Chinese strings to `strings.xml` for localization readiness. (7) Added `aurora_engine_button_bg.xml` shape drawable. Verified with `./gradlew testDebugUnitTest`, `./gradlew lintDebug`, and `./gradlew assembleDebug`.
- 2026-06-02: Performance and architecture optimization pass. (1) JS sniffer: merged two `setInterval` calls into one 1500ms consolidated interval, stored `MutationObserver` and interval references on `window` for cleanup, injected cleanup script in `onPageStarted` to disconnect observer and clear interval on navigation. (2) `renderState` dedup: `onProgressChanged` in `BrowserViewModel` now throttles updates (skips if progress changed <5% unless 0/100). (3) `persistTabs` debounce: tab persistence now debounced 2s via coroutine `Job`, actual DB save moved to `Dispatchers.IO`; `onDestroy` saves synchronously. (4) AdBlocker: replaced linear `any { endsWith }` scan with pre-built suffix `HashSet` for O(1) domain matching. (5) `BrowserStore` singleton: added `getInstance(context)` companion method, all 8 callers updated. (6) Bookmarks index: added `idx_bookmarks_url` in `createPerformanceIndexes`. (7) Tab freeze: added `MAX_TABS=20` soft limit, `freezeOldestBackgroundTab()` pauses old background WebViews, `restoreTab()` reloads on switch. (8) `restoreTabs`: set `currentTabId` before loop to fix race with `onPageFinished` callbacks. (9) Removed duplicate `setSupportMultipleWindows(false)` in `setupWebView`. (10) Cached `defaultVideoPosterBitmap` singleton. Verified with `./gradlew testDebugUnitTest`, `./gradlew lintDebug`, and `./gradlew assembleDebug`.
- 2026-06-02: Fixed home-surface sections showing blank when navigating back to home or opening a new tab. Root cause: `showHomeSurface()` rendered sections with the current (possibly empty) in-memory data and then launched async DB loads; a `renderState()` call from `onPageFinished` could re-render with empty data before the async loads completed. Fix: `showHomeSurface()` now loads `customHomeSites` and `mostVisitedSites` synchronously from `BrowserStore` before rendering, and always invalidates and force-renders the sections. Removed the redundant `refreshMostVisitedSites()` call from `onPageFinished` for `about:blank` to avoid overwriting the freshly loaded data. Removed unused `force` parameter from `showHomeSurface()`. Verified with `./gradlew testDebugUnitTest`, `./gradlew lintDebug`, and `./gradlew assembleDebug`.
- 2026-05-20: Updated magnet search result modeling and parsing so `MagnetSearchResult` can carry `sizeText`. `MagnetSearchClient` now extracts sizes from result-row context, RSS/description text, and detail pages with common size labels, and detail-page magnet/size resolution applies to all sources within the existing page limit while keeping 0Magnet off hidden WebView fallback. `MagnetSearchAdapter` displays magnet links first and shows size next to the source/type; the generic copy action only copies parsed magnet or `.torrent` direct links. Verified with `./gradlew testDebugUnitTest`, `./gradlew lintDebug`, and `./gradlew assembleDebug`; installed to device `10AFA313MD002MQ` with result `Success`.
- 2026-05-19: Optimized magnet search scheduling and cache behavior. `MagnetSearchActivity` now ranks selected sources by local health records and runs up to 3 sources concurrently while still rendering each source as soon as it completes. `BrowserPreferences` stores per-source success/failure/verification counts, last duration, and result count, and last-search outcome caching is capped at 30 results per source. `MagnetSearchAdapter` now reuses the Activity theme palette instead of reading preferences on every bind. Verified with `./gradlew testDebugUnitTest`, `./gradlew lintDebug`, and `./gradlew clean assembleDebug`; installed to device `10AFA313MD002MQ` with result `Success`.
- 2026-05-16: Performed first full-code optimization pass. `BrowserStore` now supports SQL-side history filtering, resource/download category filtering, system-download-only reads, and download updates that preserve nullable columns unless explicit clear flags are used. `LibraryActivity` uses those filtered queries, keeps visible history lists for correct filtered click/delete handling, and `LibraryAdapter` computes DiffUtil off the main thread. `MainActivity.restoreTabs()` lazy-loads restored background tabs only when activated. `P2pDownloadWorker` throttles progress DB writes by time/bytes/progress changes. `PageBridge` calls now require a per-activity random token injected into Aurora scripts before native bridge methods accept media, clipboard, navigation, video, or credential events. Verified with `./gradlew testDebugUnitTest`, `./gradlew lintDebug`, and `./gradlew assembleDebug`; installation status is recorded in README verification notes.
- 2026-05-16: Reworked `MagnetSearchActivity` search flow from parallel `awaitAll()` to sequential per-source execution. The UI now updates progress for the active source, renders each source's results immediately after completion, keeps "全部" and individual source views separate, and shows a per-source state card for failures or manual verification. `MagnetSearchClient` now returns `MagnetSearchOutcome.ERROR_REQUIRES_VERIFICATION` for explicit challenge pages; users can open the source page in `MainActivity` to complete verification and retry that source, relying only on shared WebView cookies. Refined the magnet search UI with tighter theme-aware icon buttons, chips, result action buttons, and source-state card. Verified with `./gradlew testDebugUnitTest`, `./gradlew lintDebug`, and `./gradlew assembleDebug`; installed to device `10AFA313MD002MQ` with result `Success`.
- 2026-05-16: Added configurable magnet/torrent download engine selection. Settings now exposes "磁力下载引擎" with default internal jlibtorrent and optional external BT app handoff. `BrowserDownloads` routes external mode through system `ACTION_VIEW` without creating a local download record, while internal mode still uses `P2pDownloadWorker` and the downloads table. Magnet/torrent confirmation dialogs in `MainActivity` and `MagnetSearchActivity` show the configured engine as the primary action and the alternate engine as a one-off neutral action. Verified with `./gradlew testDebugUnitTest`, `./gradlew lintDebug`, and `./gradlew assembleDebug`; installed to device `10AFA313MD002MQ` with result `Success`.
- 2026-05-11: Fixed a home-surface regression where tapping the bottom home button from a normal web page could leave "常访站点"/"我的站点" empty. `MainActivity.navigateTo(DEFAULT_HOME_URL)` now synchronizes the active tab and `BrowserViewModel` to the built-in home URL before loading `about:blank`, and hidden home sections no longer clear their tile containers while waiting for the home state/data refresh. Verified with `./gradlew testDebugUnitTest`, `./gradlew lintDebug`, and `./gradlew assembleDebug`, then installed to device `10AFA313MD002MQ` with result `Success`.
- 2026-05-09: Enhanced magnet-site compatibility and P2P download health. `MagnetSearchClient` now tries mobile and desktop browser profiles for direct HTTP, keeps shared CookieManager cookies, and makes the off-screen WebView fallback accept third-party cookies, scroll once, and wait for DOM settling before extracting HTML. It still must not solve CAPTCHA/human-verification pages; those are only detected and surfaced as verification/failure states. P2P now appends public trackers through `TorrentTools.withPublicTrackers()`, starts libtorrent with DHT/LSD/UPnP/NAT-PMP/uTP/TCP/bootstrap nodes, periodically forces tracker/DHT announces, tries public torrent caches for BTIH metadata, carries Referer/Cookie/User-Agent when fetching `.torrent` URLs, and reports DHT nodes/connections/tracker/speed in download status. Added Android 14 foreground dataSync permissions. Verified with `./gradlew testDebugUnitTest`, `./gradlew lintDebug`, and `./gradlew assembleDebug`, then installed to device `10AFA313MD002MQ` with result `Success`.
- 2026-05-09: Adjusted 0Magnet anti-bot handling after a regression where searches surfaced "反爬验证拦截". `MagnetSearchSources` now prefers `0magnet.co` again and includes legacy path-style fallbacks; `MagnetSearchClient` disables temporary off-screen WebView fallback for 0Magnet search/detail pages, narrows challenge detection to explicit JS/Cloudflare challenge markers, treats blank pages as empty/failure instead of verification, sends same-origin Referer for 0Magnet mirrors, and decodes gzip/deflate response bodies. Added unit tests for 0Magnet short-code parsing and challenge false-positive handling. Verified with `./gradlew testDebugUnitTest`, `./gradlew lintDebug`, and `./gradlew assembleDebug`, then installed to device `10AFA313MD002MQ` with result `Success`.
- 2026-05-08: Added native P2P torrent/magnet downloading with FrostWire jlibtorrent. `BrowserDownloads` now routes valid magnet links and `.torrent` URLs to `P2pDownloadWorker`, which runs libtorrent in WorkManager, writes to app external `Downloads/Aurora/P2P`, and updates download progress/speed/seed/peer status in `BrowserStore`. Download management supports pause/resume/cancel/retry/delete for `downloadType == "p2p"`. Verified with `./gradlew testDebugUnitTest`, `./gradlew lintDebug`, and `./gradlew assembleDebug`, then installed to device `10AFA313MD002MQ` with result `Success`.
- 2026-05-08: Fixed 0Magnet search returning 0 results. Root cause: 0Magnet uses short-code links (`/!kkhz`) instead of standard torrent paths, which `looksLikeResultPage()` didn't recognize. Fix: added `0magnet`-specific path regex detection, and a `resolveDetailMagnets()` step that fetches up to 15 detail pages concurrently to extract actual magnet links. Also added `looksLikeCfChallenge()` to detect Cloudflare/JS challenge pages in HTTP 200 responses.
- 2026-05-08: Added clipboard magnet/torrent auto-detection with download prompt dialog and 1.8s debounce in `MainActivity.clipboardListener`. Also present in `MagnetSearchActivity.maybePromptTorrentDownload()` after copy actions.
- 2026-05-08: Extended magnet search sources from 25 to 33 built-in sources: added ETTV, GloTorrents, Toros, Monova, Zooqle, RARBG, TorrentSeeker, Snowfl. Updated primary domains and fallback URLs for 0Magnet, TPB, 1337x, EZTV, BTDB, BTSOW, MagnetDL, LimeTorrents, TorrentDownloads, TorrentProject, SkyTorrents, and others.
- 2026-05-08: Fixed magnet source failures caused by anti-bot protection. `MagnetSearchClient.search()` now accepts an optional `Context` parameter, uses `CookieManager` for cookie persistence, sends proper browser headers (Accept, Accept-Language, Sec-Fetch-*), and includes a WebView-based fallback (`fetchViaWebView`) that renders the search URL in a temporary off-screen WebView to bypass Cloudflare/JS challenges. The search flow: try direct HTTP → if empty results, try WebView render → parse HTML for magnet/torrent links.
- 2026-05-08: Updated `BrowserPreferences.PREVIOUS_DEFAULT_MAGNET_SOURCE_IDS` to include kickasstorrents, torrentproject, skytorrents, academictorrents so auto-expansion covers users who had the previous 25-source set.
- 2026-05-08: Fixed home surface refresh after new-tab/home navigation so custom home sites, top sites, and the add-site tile are force-rendered and reloaded when returning to the built-in home page.
- 2026-05-08: Improved resource download feedback in `LibraryActivity`: resource downloads now show a Snackbar with a direct action to open the downloads list.
- 2026-05-08: Refined `MagnetSearchActivity` and `MagnetSearchClient`: search/history/select-all/add-source controls are icon buttons, result source chips include every searched source with counts or failure state, invalid magnet links without valid BTIH/BTMH are filtered, non-anchor magnet strings are extracted, default source templates were expanded, and cloud play now opens an inline Webtor SDK page through `MainActivity.EXTRA_INLINE_HTML`. Verified with `./gradlew testDebugUnitTest`, `./gradlew lintDebug`, and `./gradlew assembleDebug`; final install was not completed because ADB no longer listed a connected device.
- 2026-05-08: Extended `MagnetSearchActivity` with successful-source status, horizontal result source filters, full non-deduplicated cross-source result display, magnet search history, last-query restore, copy-magnet, and Webtor cloud-play handoff through `MainActivity.EXTRA_CLOUD_MAGNET`. Verified with `./gradlew testDebugUnitTest`, `./gradlew lintDebug`, and `./gradlew assembleDebug`, then installed to device `10AFA313MD002MQ` with result `Success`.
- 2026-05-08: Reworked magnet search into standalone `MagnetSearchActivity`: selected sources are searched concurrently via `MagnetSearchClient`, results support one-tap copy/open, and direct source-page `keyword error` popups are avoided. Verified with `./gradlew testDebugUnitTest`, `./gradlew lintDebug`, and `./gradlew assembleDebug`, then installed to device `10AFA313MD002MQ` with result `Success`.
- 2026-05-08: Added home magnet search in `MainActivity`, replacing the home resource-sniffing shortcut with a magnet source picker. Built-in/custom source templates live in `MagnetSearchSources` and `BrowserPreferences`; templates must include `{query}` or `%s`. Verified with `./gradlew testDebugUnitTest`, `./gradlew lintDebug`, and `./gradlew assembleDebug`, then installed to device `10AFA313MD002MQ` with result `Success`.
- 2026-05-08: Improved WebView video full-screen in `MainActivity`: video full-screen now uses a black full-window container, temporarily requests a video-aspect orientation so portrait-locked phones can still maximize landscape playback, and restores orientation/window state on exit.
- 2026-05-08: Verified the full-screen video orientation change with `./gradlew testDebugUnitTest`, `./gradlew lintDebug`, and `./gradlew assembleDebug`; installed the debug APK to device `10AFA313MD002MQ` with result `Success`.
- 2026-05-08: Added browser cache cleanup in settings. It clears WebView cache and app temporary cache only; it must not delete history, bookmarks, saved credentials, downloads, or downloaded files.
- 2026-05-08: Optimized web video playback in `MainActivity`: HTML5 full-screen custom view support, back button exits full-screen first, playback keeps screen awake, injected video compatibility attributes, playback state bridge.
- 2026-05-08: Installed debug APK to device `10AFA313MD002MQ` with `adb install -r`; result was `Success`.
- 2026-05-07: Added site settings, privacy diagnostics, reading mode, HLS variant selection, list HTML export, subtitle resource category, credential vault unlock, secure preference storage, and settings UI refinements.

## Build & Run

```bash
# Build debug APK
./gradlew assembleDebug

# Output path
# app/build/outputs/apk/debug/app-debug.apk

# Install to connected device
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Clean build
./gradlew clean assembleDebug

# Run tests (JUnit 4 + Espresso — currently minimal)
./gradlew test
./gradlew connectedAndroidTest
```

## Architecture

### MVVM Flow

```
User Action → Activity → ViewModel → BrowserStore/Preferences
                    ↑                      ↓
              StateFlow<BrowserUiState>  ←  DB update
                    ↓
              Activity observes → recomposes UI
```

- **`BrowserViewModel`** (AndroidViewModel) holds `MutableStateFlow<BrowserUiState>` as single source of truth for browser toolbar state (URL, title, loading progress, bookmark status, settings).
- Activities observe `viewModel.uiState` via `viewModelScope.launch { viewModel.uiState.collect {} }`.
- **`BrowserStore`** is the sole data layer — no repository pattern; activities access it directly via `by lazy { BrowserStore(this) }` for non-browser operations (library, settings).

### Download Pipeline

```
Resource Sniffing (WebView)
  │
  ├─ Progressive downloads → Android DownloadManager
  │
  ├─ HLS streams (.m3u8):
  │    1. M3U8 detected → cacheM3u8Playlist() (immediate HTTP fetch at detection time)
  │    2. User queues download → BrowserDownloads.enqueue()
  │       → estimateHlsSize() from cached M3U8
  │       → insertDownload() into DB
  │       → WorkManager enqueues HlsDownloadWorker
  │    3. HlsDownloadWorker.doWork():
  │       → HlsPackageTools.downloadPackage()
  │       → parse playlist → download TS/M4S segments → assemble
  │       → periodic progress updates to DB via store.updateDownload()
  │    4. Completed → user can "convert to MP4" → HlsExportWorker
  │       → HlsPackageTools.muxToMp4() via MediaExtractor/MediaMuxer
  │
  └─ Magnet/torrent downloads:
      1. User confirms magnet/.torrent download → BrowserDownloads.enqueueMagnetDownload()
         or BrowserDownloads.enqueue()
         → insertDownload() into DB → WorkManager enqueues P2pDownloadWorker
      2. P2pDownloadWorker.doWork():
         → start jlibtorrent SessionManager
         → add magnet or TorrentInfo(.torrent)
         → poll TorrentStatus for progress/speed/seeds/peers
         → update BrowserStore until complete, paused, canceled, or failed
```

### Key Design Decisions

- **No Room database** — plain SQLiteOpenHelper with raw SQL. All queries use `synchronized(this)` for thread safety. Keep large list filtering/search/category constraints in SQL where possible (`getHistory`, `getResourcesForPage`, `getDownloads`) instead of loading whole tables into Activities. `updateDownload*()` preserves nullable columns by default; use explicit `clear*` flags only when the caller intentionally clears a column.
- **WebView-based browsing** — each tab is a WebView managed manually (not TabLayout). Tab state tracked in ViewModel. `SwipeRefreshLayout` must keep a single direct child (`webViewContainer`); add tab WebViews to that inner `FrameLayout`, not directly to `SwipeRefreshLayout`. Only the active, non-frozen tab WebView should be visible when the native home surface is hidden; all WebViews should be hidden while showing the home surface, and the active WebView should be brought to front on switch. Restored background tabs should keep their saved title/URL and lazy-load only when activated, so app startup does not load every saved page at once. Frozen tabs may load `about:blank` to release resources, but their saved `tab.url` must remain the last real page URL and WebView callbacks for that placeholder must not overwrite it. Returning to `DEFAULT_HOME_URL` via WebView back/forward must force-refresh native home sections.
- **Home page style** is stored in `BrowserPreferences` as `HomeStyle` (`DEFAULT` or `MINIMAL`). Default style shows the search card, quick action buttons, top sites, and custom sites. Minimal style hides quick actions, top sites, and custom sites, and centers the search card vertically via `Gravity.CENTER`. The setting lives in the same dialog as the custom start page URL. When a custom start page URL is set (not `about:blank`), the address bar is hidden on that page via `isOnCustomStartPage()`, and `showHomeSurface()` skips forcing the home panel visible. The style is included in the `renderHomeSectionsIfNeeded()` signature cache so switching styles triggers an immediate re-render.
- **M3U8 pre-caching** — HLS playlists are fetched at resource detection time (valid token) and cached to `cacheDir/hls_playlists/`, then reused at download time to avoid token expiration 404s.
- **HLS quality selection** — master playlists can be parsed before enqueue so the user can choose a variant; the selected variant URL is upserted into resources before WorkManager starts.
- **Ad blocking** is a two-layer system: (1) `shouldInterceptRequest` blocks requests to known ad hosts/patterns, (2) `shouldOverrideUrlLoading` intercepts suspicious cross-origin redirects with user confirmation dialog. Must respect `adBlockEnabled` preference.
- **Site settings** can override global desktop mode, ad blocking, auto-translation, and credential autofill per host. Host keys are normalized by removing a leading `www.`.
- **Secure text storage** uses Android Keystore AES/GCM for saved credentials and translation API secrets, with legacy plaintext read compatibility.
- **Magnet search** is a home shortcut that opens `MagnetSearchActivity`. Built-in sources are defined in `browser/MagnetSearchSources.kt`; custom sources, selected source ids, source health records, and magnet search history are persisted as JSON in `BrowserPreferences`. Source enable/disable management belongs in the top-right source dropdown, not in a top-level horizontal source chip list; the dropdown must follow `BrowserThemePalette`, show source type/domain/recent health, support enable all/disable all/add custom source/delete custom source, and preserve an explicitly saved empty source selection. Result source chips remain only for filtering completed/in-progress search results. `MagnetSearchActivity` must rank selected sources by local health, run at most 3 sources concurrently, update progress for active sources, and render each source as soon as it finishes. Start of a new search should save the query and clear stale last-result snapshots; each completed source should persist the current ordered `latestOutcomes` so interrupted searches can be restored on next open. `MagnetSearchClient` extracts magnet/torrent/detail links and `sizeText` from result pages. It should filter anchor candidates before expensive context slicing, derive magnet and size from result-row context first, and fetch detail pages only for missing magnet/size metadata within the existing page cap. Detail-page metadata may use bounded short-lived in-memory caching to avoid repeated network fetches during nearby searches. Direct HTTP fetch uses `CookieManager` for cookie sharing, mobile/desktop browser profiles, full browser headers, same-origin Referer where needed, and gzip/deflate decoding; when non-0Magnet HTTP fetches fail or require JS rendering, it can fall back to a temporary off-screen WebView with third-party cookies, one scroll pass, DOM settling, and HTML extraction via `evaluateJavascript`. If direct HTTP succeeds but yields an empty parse, keep the WebView fallback narrow so one slow source does not block the whole search. Keep 0Magnet on direct HTTP plus fallback domains only unless manually revalidated, because hidden WebView fallback can surface verification pages. Challenge detection must require explicit JS/Cloudflare/human-verification markers, not generic `cloudflare`/`attribution` text; do not implement CAPTCHA solving or credentialed bypass. If `MagnetSearchOutcome.ERROR_REQUIRES_VERIFICATION` is returned, show a per-source manual verification state that opens the source URL in `MainActivity` and lets the user retry after cookies are established. Result filtering is source-based and must not globally de-duplicate cross-source results. Direct magnet links must include a valid BTIH or BTMH exact topic before they are shown or used for cloud play; source result chips should include all searched sources, including zero-result, in-progress, verification, and failed states, so failures are visible. Last-search outcome caching should stay capped per source so SharedPreferences does not grow unbounded.
- **Theme application** should route shared UI chrome through `BrowserThemePalette`. In `MainActivity`, keep bottom navigation styling in `applyBottomNavigationTheme()` so card/background, button tint enabled/disabled states, ripple color, and tab-count badge stay coherent for both solid and gradient themes; when leaving gradient modes, explicitly restore non-gradient stroke/background state. Gradient themes should apply the same gradient to the root layout and let the home panel show through it, preventing fixed-color rectangular bands around rounded chrome.
- **P2P downloads** use FrostWire jlibtorrent native artifacts declared in `app/build.gradle.kts`. Keep P2P work in `P2pDownloadWorker` and keep user-facing state in `BrowserStore.downloads` with `downloadType == "p2p"` and `FileCategory.TORRENT`. P2P files are written under app external downloads (`Downloads/Aurora/P2P`) because libtorrent requires filesystem paths, not `DownloadManager` content URIs. P2P sessions should start with DHT/LSD/UPnP/NAT-PMP/uTP/TCP enabled, bootstrap nodes configured, public trackers appended to magnets and torrent handles, and periodic tracker/DHT announce refreshes. BTIH magnets may try public torrent caches for metadata before falling back to magnet metadata exchange. `.torrent` URL fetches should carry Referer/Cookie/User-Agent when available.
- **P2P engine selection** is stored in `BrowserPreferences` as `P2pDownloadEngine`. `INTERNAL` is the default and routes through `BrowserDownloads.enqueueP2pDownload()` → `P2pDownloadWorker`; `EXTERNAL` uses `TorrentTools.openExternalDownloader()` / system `ACTION_VIEW` and must not insert an Aurora download record because external client progress cannot be synchronized. Magnet/torrent confirmation dialogs should show the configured engine as the primary action and the alternate engine as a neutral action.
- **Clipboard monitoring** for magnet/torrent links is active in `MainActivity` via `ClipboardManager.OnPrimaryClipChangedListener` (registered in `onStart`, removed in `onStop`). Injected page JS also reports copy events through `PageBridge.onClipboardCandidate()`. When a valid magnet or .torrent URL is detected, `promptTorrentDownload()` shows a dialog with 1.8s debounce. `MagnetSearchActivity` also prompts via `maybePromptTorrentDownload()` after copy actions.
- **Cloud play** from magnet search uses `MainActivity.EXTRA_INLINE_HTML` to load an inline Webtor SDK playback page via `WebView.loadDataWithBaseURL()`. Keep the older `EXTRA_CLOUD_MAGNET` injection path only as compatibility fallback for existing callers.
- **Web video playback** uses `WebChromeClient.onShowCustomView()` / `onHideCustomView()` for HTML5 full-screen, a temporary full-screen container with direction/window restoration, and a JS bridge to track media playback state plus current video dimensions for keep-screen-on behavior and full-screen orientation decisions.
- **Injected WebView bridge security**: `MainActivity.PageBridge` methods require the per-activity random bridge token injected into Aurora-owned scripts. Resource sniffing, video state, clipboard magnet reporting, navigation guards, and credential assistant calls must use the local `callBridge()` helper with that token. Do not add new `@JavascriptInterface` methods without token validation and current-page/host checks for sensitive actions.
- **Compact list mode** used in downloads/history (72x48dp thumbnails, smaller text, single-line truncation) to maximize item density.
- **File size estimates** for HLS downloads parsed from cached M3U8 (segment count × bandwidth) and displayed with `~` prefix during download.
- **Cache cleanup** must remain scoped to WebView/app temporary cache. It must not clear `BrowserStore` history/bookmarks/credentials/downloads or user-downloaded files.
